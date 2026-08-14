package com.framecut.app.drive

import com.framecut.app.auth.AuthManager
import com.framecut.app.net.Http
import com.framecut.app.net.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

const val DRIVE_API = "https://www.googleapis.com/drive/v3"
const val DRIVE_UPLOAD = "https://www.googleapis.com/upload/drive/v3"

/** Escapes a value for embedding in a Drive `q` string. Mirrors browser.ts. */
fun escapeDriveQuery(value: String): String =
    value.replace("\\", "\\\\").replace("'", "\\'")

/**
 * Drive v3 client. Every call goes through [withToken], which retries exactly
 * once with a force-refreshed access token when Drive answers 401 — that is the
 * "token expired mid-operation" path.
 */
class DriveApi(private val auth: AuthManager) {

    /**
     * Runs [block] with a valid access token. On 401 the token is refreshed and
     * [block] is retried once. [block] must be idempotent (all callers are:
     * GETs, or POSTs that are re-driven from a known offset).
     */
    suspend fun <T> withToken(block: suspend (String) -> T): T {
        val token = auth.accessToken()
        return try {
            block(token)
        } catch (e: HttpException) {
            if (e.code != 401) throw e
            block(auth.refreshedToken(token))
        }
    }

    private suspend fun getJson(url: String): JSONObject = withToken { token ->
        withContext(Dispatchers.IO) {
            val conn = Http.open(url, "GET", mapOf("Authorization" to "Bearer $token"))
            try {
                JSONObject(Http.requireOk(conn))
            } finally {
                conn.disconnect()
            }
        }
    }

    private suspend fun postJson(url: String, body: String): JSONObject = withToken { token ->
        withContext(Dispatchers.IO) {
            val conn = Http.open(url, "POST", mapOf("Authorization" to "Bearer $token"))
            try {
                Http.writeBody(conn, body.toByteArray(), "application/json; charset=UTF-8")
                JSONObject(Http.requireOk(conn))
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Signed-in user's email, via the Drive about endpoint (mirrors auth.ts). */
    suspend fun fetchUserEmail(): String = runCatching {
        getJson("$DRIVE_API/about?fields=user(emailAddress)")
            .optJSONObject("user")?.optString("emailAddress").orEmpty()
    }.getOrDefault("")

    /**
     * Lists a folder, following nextPageToken to the end. [onPage] receives each
     * page as it arrives so a big folder paints progressively.
     *
     * The virtual "Shared with me" root has no folder id, so it uses Drive's
     * `sharedWithMe` query, which is only valid in the `user` corpus.
     */
    suspend fun listFolder(
        folderId: String,
        onPage: suspend (List<DriveItem>) -> Unit = {},
    ): List<DriveItem> {
        val all = mutableListOf<DriveItem>()
        val shared = folderId == SHARED_WITH_ME.id
        val escapedFolderId = escapeDriveQuery(folderId)
        val seenPageTokens = mutableSetOf<String>()
        var pageToken: String? = null
        do {
            if (!seenPageTokens.add(pageToken ?: "")) {
                throw IllegalStateException("Drive repeated a page token while listing this folder")
            }
            val params = linkedMapOf(
                "q" to if (shared) {
                    "sharedWithMe and trashed=false"
                } else {
                    "'$escapedFolderId' in parents and trashed=false"
                },
                "fields" to "nextPageToken, files(id,name,mimeType,size,modifiedTime)",
                "orderBy" to "folder,name",
                "pageSize" to "1000",
                "supportsAllDrives" to "true",
                "includeItemsFromAllDrives" to "true",
                "corpora" to if (shared) "user" else "allDrives",
            )
            pageToken?.let { params["pageToken"] = it }
            val json = getJson("$DRIVE_API/files?${Http.formEncode(params)}")
            val page = json.optJSONArray("files")?.let { arr ->
                List(arr.length()) { driveItemFrom(arr.getJSONObject(it)) }
            } ?: emptyList()
            all += page
            onPage(page)
            pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return all
    }

    /**
     * Drive-wide search for folders and videos matching [text]. Single request
     * capped at 1000 results; the caller sorts client-side. Mirrors browser.ts.
     */
    suspend fun searchDrive(text: String): List<DriveItem> {
        val escaped = escapeDriveQuery(text)
        val params = linkedMapOf(
            "q" to "name contains '$escaped' and trashed=false and " +
                "(mimeType='$FOLDER_MIME' or mimeType contains 'video/')",
            "fields" to "files(id,name,mimeType,size,modifiedTime)",
            "pageSize" to "1000",
            "supportsAllDrives" to "true",
            "includeItemsFromAllDrives" to "true",
            "corpora" to "allDrives",
        )
        val json = getJson("$DRIVE_API/files?${Http.formEncode(params)}")
        return json.optJSONArray("files")?.let { arr ->
            List(arr.length()) { driveItemFrom(arr.getJSONObject(it)) }
        } ?: emptyList()
    }

    suspend fun createFolder(name: String, parentId: String): Crumb {
        val body = JSONObject().apply {
            put("name", name)
            put("mimeType", FOLDER_MIME)
            put("parents", org.json.JSONArray(listOf(parentId)))
        }.toString()
        val json = postJson("$DRIVE_API/files?fields=id,name&supportsAllDrives=true", body)
        return Crumb(json.optString("id"), json.optString("name", name))
    }

    /** Access tokens for the streaming helpers (Downloader / Uploader). */
    suspend fun token(): String = auth.accessToken()

    suspend fun tokenAfterRejecting(rejected: String): String = auth.refreshedToken(rejected)
}
