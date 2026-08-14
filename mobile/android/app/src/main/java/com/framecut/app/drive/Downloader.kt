package com.framecut.app.drive

import com.framecut.app.auth.AuthRequiredException
import com.framecut.app.net.Http
import com.framecut.app.net.HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Streams a Drive file to disk. Nothing is ever held in memory beyond a 64 KB
 * read buffer per connection, so 2 GB lecture recordings are fine.
 *
 * Speed comes from parallel ranged GETs: Drive throttles a single connection
 * hard, and 5 concurrent 12 MB ranges typically multiply throughput. If the
 * server does not honour ranges we fall back to one plain stream.
 */
class Downloader(private val api: DriveApi) {

    private companion object {
        const val CHUNK_BYTES = 12L * 1024 * 1024
        const val CONCURRENCY = 5
        const val BUFFER = 64 * 1024
        /** Progress is reported at most this often, to keep the UI thread calm. */
        const val PROGRESS_INTERVAL_BYTES = 1L * 1024 * 1024
    }

    /**
     * @param expectedSize size Drive reported; 0 means unknown (single stream only).
     * @param onProgress called with (received, total) from a background thread.
     */
    suspend fun download(
        fileId: String,
        expectedSize: Long,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        dest.parentFile?.mkdirs()
        if (expectedSize > CHUNK_BYTES) {
            try {
                downloadParallel(fileId, expectedSize, dest, onProgress)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthRequiredException) {
                throw e
            } catch (e: HttpException) {
                // 4xx is a verdict on the request itself; one stream would fail
                // identically. Anything else is worth a single-stream attempt,
                // matching the web app's blanket fallback.
                if (e.code in 400..499) throw e
            } catch (_: IOException) {
                // Ranges unsupported, or the ranged transfer gave up. Fall through.
            }
        }
        downloadSingleStream(fileId, expectedSize, dest, onProgress)
    }

    private class RangesUnsupported : IOException("ranges-unsupported")

    private suspend fun downloadParallel(
        fileId: String,
        total: Long,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val chunkCount = ((total + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
        val nextChunk = AtomicInteger(0)
        val received = AtomicLong(0)
        val lastReported = AtomicLong(0)

        RandomAccessFile(dest, "rw").use { raf ->
            raf.setLength(total) // pre-allocate so workers can write anywhere
            val channel = raf.channel
            coroutineScope {
                repeat(min(CONCURRENCY, chunkCount)) {
                    launch(Dispatchers.IO) {
                        var index = nextChunk.getAndIncrement()
                        while (index < chunkCount) {
                            coroutineContext.ensureActive()
                            val start = index * CHUNK_BYTES
                            val end = min(start + CHUNK_BYTES, total) - 1
                            fetchRange(fileId, start, end, channel, received) { got ->
                                // CAS so two workers cannot publish out of order, and
                                // so a retry's rollback simply pauses the bar rather
                                // than making it jump backwards.
                                val seen = lastReported.get()
                                if ((got - seen >= PROGRESS_INTERVAL_BYTES || got == total) &&
                                    lastReported.compareAndSet(seen, got)
                                ) {
                                    onProgress(got, total)
                                }
                            }
                            index = nextChunk.getAndIncrement()
                        }
                    }
                }
            }
        }
        if (dest.length() != total) throw IOException("Download incomplete (${dest.length()} of $total bytes)")
        onProgress(total, total)
    }

    /** One ranged GET written straight into the file at its absolute offset. */
    private suspend fun fetchRange(
        fileId: String,
        start: Long,
        end: Long,
        channel: java.nio.channels.FileChannel,
        received: AtomicLong,
        report: (Long) -> Unit,
    ) {
        var token = api.token()
        var refreshed = false
        var attempt = 0
        while (true) {
            var writtenThisAttempt = 0L
            val conn = openMedia(fileId, start, end, token)
            try {
                val code = conn.responseCode
                if (code == 401 && !refreshed) {
                    refreshed = true
                    token = api.tokenAfterRejecting(token)
                    continue
                }
                if (code != HttpURLConnection.HTTP_PARTIAL) {
                    if (code in 200..299) throw RangesUnsupported()
                    throw HttpException(code, Http.readErrorText(conn))
                }
                var position = start
                val buffer = ByteArray(BUFFER)
                conn.inputStream.use { input ->
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n <= 0) break
                        // Positional writes are safe from multiple threads, but a
                        // single call may write fewer bytes than asked, which would
                        // otherwise leave a hole in a file that still measures full.
                        val slice = ByteBuffer.wrap(buffer, 0, n)
                        while (slice.hasRemaining()) {
                            val written = channel.write(slice, position)
                            if (written <= 0) throw IOException("Storage accepted no bytes")
                            position += written
                            writtenThisAttempt += written
                            report(received.addAndGet(written.toLong()))
                        }
                    }
                }
                if (position != end + 1) throw IOException("incomplete-range")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: RangesUnsupported) {
                throw e
            } catch (e: IOException) {
                // Transient network hiccup: rewind this chunk's contribution to
                // the progress total and retry it from the start of the range.
                received.addAndGet(-writtenThisAttempt)
                if (++attempt > 3) throw e
                kotlinx.coroutines.delay(500L * attempt)
            } finally {
                conn.disconnect()
            }
        }
    }

    private suspend fun downloadSingleStream(
        fileId: String,
        expectedSize: Long,
        dest: File,
        onProgress: (Long, Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var token = api.token()
        var refreshed = false
        while (true) {
            val conn = openMedia(fileId, null, null, token)
            try {
                val code = conn.responseCode
                if (code == 401 && !refreshed) {
                    refreshed = true
                    token = api.tokenAfterRejecting(token)
                    continue
                }
                if (code !in 200..299) throw HttpException(code, Http.readErrorText(conn))
                val declared = conn.getHeaderField("Content-Length")?.toLongOrNull() ?: expectedSize
                var received = 0L
                var lastReported = 0L
                val buffer = ByteArray(BUFFER)
                conn.inputStream.use { input ->
                    dest.outputStream().buffered(BUFFER).use { out ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n <= 0) break
                            out.write(buffer, 0, n)
                            received += n
                            if (received - lastReported >= PROGRESS_INTERVAL_BYTES) {
                                lastReported = received
                                onProgress(received, declared)
                            }
                        }
                    }
                }
                if (declared > 0 && received != declared) {
                    throw IOException("Download incomplete (received $received of $declared bytes)")
                }
                onProgress(received, if (declared > 0) declared else received)
                return@withContext
            } finally {
                conn.disconnect()
            }
        }
    }

    private suspend fun openMedia(
        fileId: String,
        start: Long?,
        end: Long?,
        token: String,
    ): HttpURLConnection = withContext(Dispatchers.IO) {
        val headers = buildMap {
            put("Authorization", "Bearer $token")
            // Ranged bodies must not be transparently gzipped or the byte
            // offsets written into the pre-allocated file would be wrong.
            put("Accept-Encoding", "identity")
            if (start != null && end != null) put("Range", "bytes=$start-$end")
        }
        Http.open("$DRIVE_API/files/$fileId?alt=media&supportsAllDrives=true", "GET", headers)
    }
}
