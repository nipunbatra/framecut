package com.framecut.app.drive

/**
 * Port of the web app's metadata.ts. Drive caps appProperties at 30 entries and
 * 124 bytes for key + value combined, so values are truncated on a UTF-8
 * boundary and overflow is reported rather than silently dropped.
 *
 * Fixed provenance fields win on key collisions; the full user metadata still
 * goes into the Drive description.
 */
object AppProperties {

    private const val MAX_PRIVATE_PROPERTIES = 30
    private const val MAX_PROPERTY_BYTES = 124

    data class Result(
        val properties: Map<String, String>,
        val omitted: Int,
        val truncated: Int,
    )

    fun build(fixed: Map<String, String>, user: Map<String, String>): Result {
        val properties = LinkedHashMap<String, String>()
        var omitted = 0
        var truncated = 0

        for ((key, value) in fixed) {
            if (properties.size >= MAX_PRIVATE_PROPERTIES) break
            val remaining = MAX_PROPERTY_BYTES - utf8Length(key)
            if (remaining <= 0) continue
            properties[key] = truncateUtf8(value, remaining)
        }

        for ((key, value) in user) {
            if (properties.containsKey(key)) {
                omitted++
                continue
            }
            if (properties.size >= MAX_PRIVATE_PROPERTIES) {
                omitted++
                continue
            }
            val remaining = MAX_PROPERTY_BYTES - utf8Length(key)
            if (remaining <= 0) {
                omitted++
                continue
            }
            val safeValue = truncateUtf8(value, remaining)
            if (safeValue != value) truncated++
            properties[key] = safeValue
        }

        return Result(properties, omitted, truncated)
    }

    private fun utf8Length(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    /** Truncates on whole code points so a surrogate pair is never split. */
    private fun truncateUtf8(value: String, maxBytes: Int): String {
        if (maxBytes <= 0) return ""
        val out = StringBuilder()
        var used = 0
        var i = 0
        while (i < value.length) {
            val codePoint = value.codePointAt(i)
            val charCount = Character.charCount(codePoint)
            val piece = value.substring(i, i + charCount)
            val bytes = utf8Length(piece)
            if (used + bytes > maxBytes) break
            out.append(piece)
            used += bytes
            i += charCount
        }
        return out.toString()
    }
}
