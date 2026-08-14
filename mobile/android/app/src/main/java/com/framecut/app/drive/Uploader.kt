package com.framecut.app.drive

import com.framecut.app.net.Http
import com.framecut.app.net.HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * Resumable upload, ported from drive.ts: 8 MB chunks (a multiple of 256 KiB,
 * as the API requires), 308 responses carry the authoritative next offset, and
 * 5xx / network failures are retried with backoff after re-querying the server
 * for how much it actually kept.
 *
 * The file is read from disk chunk by chunk — never loaded whole.
 */
class Uploader(private val api: DriveApi) {

    private companion object {
        const val CHUNK = 8 * 1024 * 1024
        const val MAX_ATTEMPTS = 5
    }

    suspend fun upload(
        file: File,
        meta: UploadMeta,
        onProgress: (Long, Long) -> Unit,
    ): UploadResult = withContext(Dispatchers.IO) {
        val total = file.length()
        val sessionUri = startSession(meta, total)

        var offset = 0L
        var attempt = 0
        val buffer = ByteArray(CHUNK)

        RandomAccessFile(file, "r").use { raf ->
            while (offset < total) {
                coroutineContext.ensureActive()
                val length = min(CHUNK.toLong(), total - offset).toInt()
                raf.seek(offset)
                raf.readFully(buffer, 0, length)

                val conn = Http.open(
                    sessionUri,
                    "PUT",
                    mapOf("Content-Range" to "bytes $offset-${offset + length - 1}/$total"),
                    followRedirects = false,
                )
                val code = try {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(length)
                    conn.outputStream.use { it.write(buffer, 0, length) }
                    conn.responseCode
                } catch (e: CancellationException) {
                    conn.disconnect()
                    throw e
                } catch (e: IOException) {
                    conn.disconnect()
                    if (++attempt > MAX_ATTEMPTS) throw e
                    delay(1000L * attempt)
                    val status = queryStatus(sessionUri, total)
                    status.result?.let {
                        onProgress(total, total)
                        return@withContext it
                    }
                    offset = status.offset
                    continue
                }

                try {
                    when {
                        code == 308 -> {
                            // The Range header is authoritative. Its absence means
                            // the server kept nothing, so the bytes we just sent
                            // must never be assumed to have landed.
                            val next = acknowledgedOffset(conn.getHeaderField("Range")) ?: 0L
                            if (next > offset) {
                                offset = next
                                attempt = 0
                                onProgress(offset, total)
                            } else {
                                // No forward progress: bound the retries so a server
                                // repeating the same offset cannot spin forever.
                                if (++attempt > MAX_ATTEMPTS) {
                                    throw IOException("Upload stalled at $offset of $total bytes")
                                }
                                offset = next
                                delay(1000L * attempt)
                            }
                        }

                        code in 200..299 -> {
                            onProgress(total, total)
                            return@withContext parseResult(Http.readText(conn))
                        }

                        code >= 500 && attempt < MAX_ATTEMPTS -> {
                            attempt++
                            delay(1000L * attempt)
                            val status = queryStatus(sessionUri, total)
                            status.result?.let {
                                onProgress(total, total)
                                return@withContext it
                            }
                            offset = status.offset
                        }

                        else -> throw HttpException(code, Http.readErrorText(conn))
                    }
                } finally {
                    conn.disconnect()
                }
            }
        }
        throw IOException("Upload ended unexpectedly")
    }

    /** Opens the resumable session and returns its one-shot session URI. */
    private suspend fun startSession(meta: UploadMeta, total: Long): String {
        return api.withToken { token ->
            withContext(Dispatchers.IO) {
                val conn = Http.open(
                    "$DRIVE_UPLOAD/files?uploadType=resumable&supportsAllDrives=true" +
                        "&fields=id,name,webViewLink",
                    "POST",
                    mapOf(
                        "Authorization" to "Bearer $token",
                        "X-Upload-Content-Type" to meta.mimeType,
                        "X-Upload-Content-Length" to total.toString(),
                    ),
                )
                try {
                    Http.writeBody(conn, meta.toJson().toByteArray(), "application/json; charset=UTF-8")
                    val code = conn.responseCode
                    if (code !in 200..299) throw HttpException(code, Http.readErrorText(conn))
                    conn.getHeaderField("Location")
                        ?: throw IOException("Upload init returned no session URI")
                } finally {
                    conn.disconnect()
                }
            }
        }
    }

    private data class Status(val offset: Long, val result: UploadResult?)

    /** Asks the server how many bytes it committed (`Content-Range: bytes * /total`). */
    private suspend fun queryStatus(sessionUri: String, total: Long): Status =
        withContext(Dispatchers.IO) {
            val conn = Http.open(
                sessionUri,
                "PUT",
                mapOf("Content-Range" to "bytes */$total"),
                followRedirects = false,
            )
            try {
                val code = conn.responseCode
                when {
                    code == 308 -> Status(acknowledgedOffset(conn.getHeaderField("Range")) ?: 0L, null)

                    code in 200..299 -> Status(total, parseResult(Http.readText(conn)))
                    else -> throw HttpException(code, Http.readErrorText(conn))
                }
            } finally {
                conn.disconnect()
            }
        }

    /** "Range: bytes=0-8388607" -> 8388608, the offset the next chunk starts at. */
    private fun acknowledgedOffset(header: String?): Long? =
        header?.substringAfterLast('-')?.trim()?.toLongOrNull()?.takeIf { it >= 0 }?.plus(1)

    private fun parseResult(body: String): UploadResult {
        val json = JSONObject(body)
        return UploadResult(
            id = json.optString("id"),
            name = json.optString("name"),
            webViewLink = json.optString(
                "webViewLink",
                "https://drive.google.com/file/d/${json.optString("id")}/view",
            ),
        )
    }
}
