package com.framecut.app.drive

import java.text.Collator
import org.json.JSONObject

const val FOLDER_MIME = "application/vnd.google-apps.folder"

data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modifiedTime: String,
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME
    val isVideo: Boolean get() = mimeType.startsWith("video/")
}

/** One step in the browser breadcrumb trail. */
data class Crumb(val id: String, val name: String)

/** Root of My Drive. */
val MY_DRIVE = Crumb("root", "My Drive")

/**
 * Virtual root for files shared with the user. Not a real Drive folder — it is
 * listed with the `sharedWithMe` query and nothing can be saved into it.
 */
val SHARED_WITH_ME = Crumb("shared-with-me", "Shared with me")

fun Crumb.isSharedRoot(): Boolean = id == SHARED_WITH_ME.id

enum class SortKey(val label: String) { NAME("Name"), MODIFIED("Modified"), SIZE("Size") }

fun driveItemFrom(json: JSONObject): DriveItem = DriveItem(
    id = json.optString("id"),
    name = json.optString("name"),
    mimeType = json.optString("mimeType"),
    size = json.optString("size").toLongOrNull() ?: 0L,
    modifiedTime = json.optString("modifiedTime"),
)

/**
 * Folders first, then the chosen key; ties fall back to name order so the result
 * is stable. [ascending] flips the primary key only. Mirrors browser.ts.
 */
fun sortItems(items: List<DriveItem>, key: SortKey, ascending: Boolean): List<DriveItem> {
    val dir = if (ascending) 1 else -1
    return items.sortedWith { a, b ->
        if (a.isFolder != b.isFolder) return@sortedWith if (a.isFolder) -1 else 1
        val nameCmp = compareNamesNaturally(a.name, b.name)
        if (key == SortKey.NAME) return@sortedWith dir * nameCmp
        val d = when (key) {
            SortKey.SIZE -> a.size.compareTo(b.size)
            else -> a.modifiedTime.compareTo(b.modifiedTime)
        }
        if (d != 0) dir * d else nameCmp
    }
}

// PRIMARY strength ignores case and accents, matching sensitivity: 'base'.
// Only ever used from the main dispatcher, where Collator's lack of thread
// safety does not matter.
private val collator: Collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

/**
 * Mirrors the web app's `localeCompare(b, undefined, { numeric: true,
 * sensitivity: 'base' })`: digit runs compare as numbers, so "Lecture 2" sorts
 * before "Lecture 10" rather than after it.
 */
fun compareNamesNaturally(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        if (a[i].isDigit() && b[j].isDigit()) {
            val aStart = i
            val bStart = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            val aNum = a.substring(aStart, i).trimStart('0')
            val bNum = b.substring(bStart, j).trimStart('0')
            if (aNum.length != bNum.length) return aNum.length - bNum.length
            val byDigits = aNum.compareTo(bNum)
            if (byDigits != 0) return byDigits
        } else {
            val aStart = i
            val bStart = j
            while (i < a.length && !a[i].isDigit()) i++
            while (j < b.length && !b[j].isDigit()) j++
            val byText = collator.compare(a.substring(aStart, i), b.substring(bStart, j))
            if (byText != 0) return byText
        }
    }
    return (a.length - i) - (b.length - j)
}

/** Case-insensitive substring filter on item names. Blank text keeps all. */
fun filterItems(items: List<DriveItem>, text: String): List<DriveItem> {
    val needle = text.trim().lowercase()
    if (needle.isEmpty()) return items
    return items.filter { it.name.lowercase().contains(needle) }
}

data class UploadMeta(
    val name: String,
    val mimeType: String,
    val description: String? = null,
    val parents: List<String>? = null,
    val appProperties: Map<String, String> = emptyMap(),
) {
    fun toJson(): String = JSONObject().apply {
        put("name", name)
        put("mimeType", mimeType)
        if (!description.isNullOrBlank()) put("description", description)
        parents?.takeIf { it.isNotEmpty() }?.let { put("parents", org.json.JSONArray(it)) }
        if (appProperties.isNotEmpty()) {
            val props = JSONObject()
            appProperties.forEach { (k, v) -> props.put(k, v) }
            put("appProperties", props)
        }
    }.toString()
}

data class UploadResult(val id: String, val name: String, val webViewLink: String)
