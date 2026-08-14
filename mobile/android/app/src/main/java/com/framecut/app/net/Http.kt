package com.framecut.app.net

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Non-2xx response from an HTTP call, carrying the body for error reporting. */
class HttpException(val code: Int, val body: String) :
    IOException("HTTP $code${if (body.isBlank()) "" else ": ${body.take(400)}"}")

/**
 * Thin java.net wrapper. Deliberately not a third-party client: the platform
 * HttpURLConnection (OkHttp under the hood on Android) covers ranged GETs and
 * resumable PUTs without adding a dependency.
 */
object Http {

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 60_000

    /**
     * @param followRedirects must be false for resumable-upload PUTs, whose 308
     *   responses are protocol signals rather than redirects. Drive's media
     *   download can legitimately redirect, so everything else follows.
     */
    fun open(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true,
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = CONNECT_TIMEOUT_MS
        readTimeout = READ_TIMEOUT_MS
        instanceFollowRedirects = followRedirects
        useCaches = false
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }

    /** Reads the response (or error) stream fully as UTF-8 text. */
    fun readText(conn: HttpURLConnection): String {
        val stream: InputStream? = try {
            conn.inputStream
        } catch (_: IOException) {
            conn.errorStream
        }
        return stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
    }

    fun readErrorText(conn: HttpURLConnection): String =
        conn.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

    /** GET/POST that expects 2xx; throws [HttpException] otherwise. */
    fun requireOk(conn: HttpURLConnection): String {
        val code = conn.responseCode
        if (code !in 200..299) throw HttpException(code, readErrorText(conn))
        return readText(conn)
    }

    fun writeBody(conn: HttpURLConnection, body: ByteArray, contentType: String) {
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", contentType)
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }
    }

    fun formEncode(params: Map<String, String>): String = params.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }
}
