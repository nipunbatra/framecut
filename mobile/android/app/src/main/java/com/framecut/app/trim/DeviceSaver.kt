package com.framecut.app.trim

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Writes the trimmed file into the device's Movies/FrameCut collection.
 *
 * API 29+ goes through MediaStore (no permission needed); API 26-28 writes the
 * public directory directly and asks the media scanner to index it, which needs
 * WRITE_EXTERNAL_STORAGE.
 */
object DeviceSaver {

    private const val SUBDIRECTORY = "FrameCut"

    /** True when the running OS still requires a storage permission for this. */
    val needsLegacyPermission: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    suspend fun save(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String,
    ): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, source, displayName, mimeType)
        } else {
            saveLegacy(source, displayName, context)
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String,
    ): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/$SUBDIRECTORY",
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Could not create an entry in the Movies collection")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out, 256 * 1024) }
            } ?: throw IOException("Could not open the destination for writing")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
            null,
            null,
        )
        return "${Environment.DIRECTORY_MOVIES}/$SUBDIRECTORY/$displayName"
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(source: File, displayName: String, context: Context): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            SUBDIRECTORY,
        )
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Could not create ${dir.path}")
        val target = File(dir, displayName)
        source.inputStream().use { input ->
            target.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
        }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        return target.absolutePath
    }
}
