package com.harish.heymate.wifitransfer

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Publishes an imported media file into the phone's shared Gallery (MediaStore) so it appears in
 * Google Photos and other gallery apps. Files land under `DCIM/HeyMate/`.
 *
 * On Android 10+ this uses scoped storage via MediaStore (no storage permission needed). On older
 * versions it copies into public DCIM and notifies the media scanner.
 */
object MediaStoreSaver {

    private const val TAG = "MediaStoreSaver"
    private const val ALBUM = "HeyMate"

    /** Copy [file] into the Gallery. Returns true on success. Safe to call off the main thread. */
    suspend fun saveToGallery(context: Context, file: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveViaMediaStore(context, file)
            else saveLegacy(context, file)
        }.onFailure { Log.w(TAG, "saveToGallery(${file.name}) failed: ${it.message}") }
            .getOrDefault(false)
    }

    private fun saveViaMediaStore(context: Context, file: File): Boolean {
        val mime = mimeOf(file.name)
        val isVideo = mime.startsWith("video/")
        val collection = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        // Save under Pictures/, NOT DCIM/. On MIUI (Xiaomi), the Gallery's cloud-sync aggressively
        // trashes new albums it creates under DCIM/ — our DCIM/HeyMate photos were auto-trashed and
        // never appeared. Pictures/ is a standard media location that gallery apps scan without that.
        val videoRoot = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_DCIM
        val relativeDir = if (isVideo) "$videoRoot/$ALBUM" else "${Environment.DIRECTORY_PICTURES}/$ALBUM"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return false
        // From here the row exists with IS_PENDING=1; any failure must delete it or it leaks as an
        // invisible orphaned entry that accumulates on every failed import.
        return try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: error("could not open output stream for ${file.name}")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Log.i(TAG, "saved ${file.name} to Gallery ($relativeDir)")
            true
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, file: File): Boolean {
        val dcim = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), ALBUM)
        dcim.mkdirs()
        val dest = File(dcim, file.name)
        file.inputStream().use { input -> dest.outputStream().use { input.copyTo(it) } }
        android.media.MediaScannerConnection.scanFile(
            context, arrayOf(dest.absolutePath), arrayOf(mimeOf(file.name)), null,
        )
        return true
    }

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "heic" -> "image/heic"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        else -> "application/octet-stream"
    }
}
