package com.framecut.app.trim

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.BufferOverflowException
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/** What the source file actually contains, probed before the player is ready. */
data class VideoInfo(
    val durationMs: Long,
    val rotationDegrees: Int,
    val width: Int,
    val height: Int,
)

data class TrimResult(
    val file: File,
    val mimeType: String,
    /** Where the cut really landed: the nearest sync frame at or before the request. */
    val actualStartUs: Long,
    val actualEndUs: Long,
)

/**
 * Lossless trim: MediaExtractor reads compressed samples and MediaMuxer writes
 * them straight back out. No MediaCodec, no re-encode — a 30-minute source is
 * remuxed in seconds and the picture is bit-identical to the original.
 *
 * The consequence, surfaced in the UI, is that the start of the cut snaps back
 * to the nearest keyframe: there is no way to start mid-GOP without decoding.
 */
object Trimmer {

    private const val MIN_BUFFER = 1 shl 20 // 1 MB floor when the format omits max-input-size
    /** Only a guard against a nonsense max-input-size; never trims a real one. */
    private const val BUFFER_CEILING = 64 shl 20

    fun probe(source: File): VideoInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(source.absolutePath)
            fun key(k: Int) = retriever.extractMetadata(k)?.toLongOrNull() ?: 0L
            VideoInfo(
                durationMs = key(MediaMetadataRetriever.METADATA_KEY_DURATION),
                rotationDegrees = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt(),
                width = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt(),
                height = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt(),
            )
        } catch (_: Exception) {
            VideoInfo(0, 0, 0, 0)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** MP4 unless the source is WebM, which the muxer supports natively. */
    fun outputExtension(sourceName: String): String =
        if (sourceName.substringAfterLast('.', "").lowercase() == "webm") "webm" else "mp4"

    fun outputMimeType(sourceName: String): String =
        if (outputExtension(sourceName) == "webm") "video/webm" else "video/mp4"

    suspend fun trim(
        source: File,
        sourceName: String,
        dest: File,
        startUs: Long,
        endUs: Long,
        onProgress: (Float) -> Unit,
    ): TrimResult = withContext(Dispatchers.IO) {
        require(endUs > startUs) { "Selection is empty" }
        dest.parentFile?.mkdirs()
        dest.delete()

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var failed = true
        try {
            extractor.setDataSource(source.absolutePath)

            val containerFormat = if (outputExtension(sourceName) == "webm") {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            } else {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }
            muxer = MediaMuxer(dest.absolutePath, containerFormat)

            val trackMap = HashMap<Int, Int>()
            var bufferSize = MIN_BUFFER
            var rotation = 0
            var rotationKnown = false

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                extractor.selectTrack(i)
                trackMap[i] = muxer.addTrack(format)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufferSize = max(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                    rotationKnown = true
                }
            }
            if (trackMap.isEmpty()) throw IOException("No audio or video tracks in this file")

            // Some muxers only record rotation in the container metadata, where
            // the extractor's track format does not surface it.
            if (!rotationKnown) rotation = probe(source).rotationDegrees
            if (rotation != 0) muxer.setOrientationHint(rotation)

            muxer.start()
            muxerStarted = true

            // Rebasing PTS onto the first sample the extractor happens to return
            // would push an earlier-starting track to a negative offset. Probing
            // each track's own post-seek position gives the true common origin,
            // so audio keeps its lead instead of being clamped onto zero.
            val originUs = earliestSampleTime(extractor, trackMap.keys.toList(), startUs)
                ?: throw IOException("Nothing to write \u2014 the selection contains no samples")

            // SEEK_TO_PREVIOUS_SYNC: start at the keyframe at or before the cut so
            // the copied stream is decodable from its first sample.
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            var buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()
            val finishedTracks = HashSet<Int>()
            var wroteAnything = false
            var lastWrittenUs = originUs
            var samplesSinceProgress = 0
            val span = (endUs - startUs).coerceAtLeast(1)

            while (true) {
                coroutineContext.ensureActive()
                val sourceTrack = extractor.sampleTrackIndex
                if (sourceTrack < 0) break
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0) break

                val muxerTrack = trackMap[sourceTrack]
                if (muxerTrack == null || sourceTrack in finishedTracks) {
                    if (!extractor.advance()) break
                    continue
                }
                if (sampleTime > endUs) {
                    // This track is past the out point; keep draining the others.
                    finishedTracks += sourceTrack
                    if (finishedTracks.size == trackMap.size) break
                    if (!extractor.advance()) break
                    continue
                }

                // A format may understate (or omit) max-input-size; grow rather
                // than fail on the first oversized keyframe.
                var size = readSample(extractor, buffer)
                while (size == SAMPLE_TOO_LARGE) {
                    if (buffer.capacity() >= BUFFER_CEILING) {
                        throw IOException("A single frame exceeds ${BUFFER_CEILING / (1 shl 20)} MB")
                    }
                    buffer = ByteBuffer.allocate(min(buffer.capacity() * 2, BUFFER_CEILING))
                    size = readSample(extractor, buffer)
                }
                if (size < 0) break

                info.offset = 0
                info.size = size
                info.presentationTimeUs = (sampleTime - originUs).coerceAtLeast(0)
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                muxer.writeSampleData(muxerTrack, buffer, info)
                wroteAnything = true
                lastWrittenUs = sampleTime

                if (++samplesSinceProgress >= 64) {
                    samplesSinceProgress = 0
                    onProgress(((sampleTime - startUs).toFloat() / span).coerceIn(0f, 1f))
                }
                if (!extractor.advance()) break
            }

            if (!wroteAnything) {
                throw IOException("Nothing to write \u2014 the selection contains no samples")
            }
            // stop() is what finalizes the container. Doing it here rather than in
            // the finally block means a failure to write the index surfaces as an
            // error instead of a silently truncated file.
            muxer.stop()
            muxerStarted = false
            onProgress(1f)
            failed = false
            TrimResult(
                file = dest,
                mimeType = outputMimeType(sourceName),
                actualStartUs = originUs,
                actualEndUs = lastWrittenUs,
            )
        } finally {
            // Only reached with muxerStarted set when the copy failed part-way,
            // where a stop() throw is expected and irrelevant.
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            // Release first: the muxer still owns the file descriptor.
            if (failed) dest.delete()
        }
    }

    private const val SAMPLE_TOO_LARGE = -2

    /** [MediaExtractor.readSampleData] but reporting "too small" instead of throwing. */
    private fun readSample(extractor: MediaExtractor, buffer: ByteBuffer): Int = try {
        extractor.readSampleData(buffer, 0)
    } catch (_: IllegalArgumentException) {
        SAMPLE_TOO_LARGE
    } catch (_: BufferOverflowException) {
        SAMPLE_TOO_LARGE
    }

    /**
     * The earliest presentation time any selected track lands on after seeking to
     * [startUs]. Each track is probed alone because the extractor exposes only the
     * next sample across all selected tracks, not a per-track peek.
     */
    private fun earliestSampleTime(
        extractor: MediaExtractor,
        tracks: List<Int>,
        startUs: Long,
    ): Long? {
        var earliest: Long? = null
        for (track in tracks) {
            tracks.forEach { if (it != track) extractor.unselectTrack(it) }
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val time = extractor.sampleTime
            if (time >= 0) earliest = min(earliest ?: time, time)
            tracks.forEach { if (it != track) extractor.selectTrack(it) }
        }
        return earliest
    }

    /** "HH:MM:SS.mmm" — same shape the web app stores in appProperties. */
    fun toTimestamp(seconds: Double): String {
        val h = (seconds / 3600).toInt()
        val m = ((seconds % 3600) / 60).toInt()
        val s = seconds % 60
        return String.format(java.util.Locale.US, "%02d:%02d:%06.3f", h, m, s)
    }
}
