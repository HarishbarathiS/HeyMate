package com.fersaiyan.glassescapture

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pulls a photo from the glasses over BLE (the fast, near-real-time thumbnail path — no Wi-Fi
 * mode switch). Extracted from the vendor app's photo flow.
 *
 * Two modes:
 *  - [captureNow]: tell the glasses to take a fresh photo, then download it.
 *  - [downloadLatest]: just download the most recent picture already on the glasses (used when
 *    the user pressed the photo button on the glasses themselves).
 */
class GlassesPhotoCapture(private val appContext: Context) {

    /**
     * Ask the glasses to take a photo, then pull it over BLE.
     * @return the saved JPEG file, or null on failure/timeout.
     */
    suspend fun captureNow(): File? {
        if (!BleOperateManager.getInstance().isConnected) {
            Log.w(TAG, "captureNow: glasses not connected")
            return null
        }
        // Put glasses in camera mode and take the shot.
        runCatching {
            LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x06, 0x02, 0x02)) { _, _ -> }
        }
        delay(250)
        runCatching {
            LargeDataHandler.getInstance().glassesControl(byteArrayOf(0x02, 0x01, 0x01)) { _, _ -> }
        }
        // Give the glasses time to actually capture + store the frame before pulling it.
        delay(2500)
        return pullThumbnail(maxAttempts = 3, interAttemptDelayMs = 1_000L)
    }

    /**
     * Download the most recent photo already captured on the glasses (no new shutter).
     * Use this when the user pressed the physical photo button on the glasses.
     */
    /**
     * Pull the photo the glasses just took (via the physical button) — no second shutter.
     * @param onProgress optional: called before each poll attempt with (attempt, maxAttempts) so
     *        callers can show "waiting for photo…", since the device can take a while to write it.
     */
    suspend fun downloadLatest(onProgress: ((Int, Int) -> Unit)? = null): File? {
        if (!BleOperateManager.getInstance().isConnected) {
            Log.w(TAG, "downloadLatest: glasses not connected")
            return null
        }
        // When the photo button is pressed on the glasses, the device keeps writing/encoding the
        // just-taken photo to flash for a while — the thumbnail simply isn't available yet and the
        // pull returns 0 bytes. So poll patiently: keep re-requesting until data shows up.
        return pullThumbnail(
            maxAttempts = LATEST_MAX_ATTEMPTS,
            interAttemptDelayMs = LATEST_RETRY_DELAY_MS,
            onProgress = onProgress,
        )
    }

    private suspend fun pullThumbnail(
        maxAttempts: Int = 2,
        interAttemptDelayMs: Long = 0L,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): File? {
        // Each attempt writes to its OWN file so a retry can never append onto a half-written
        // JPEG from a previous attempt (that produced the "could not decode bitmap" corruption).
        repeat(maxAttempts) { attempt ->
            onProgress?.invoke(attempt + 1, maxAttempts)
            val file = pullThumbnailOnce(attempt)
            if (file != null) return file
            val more = attempt < maxAttempts - 1
            Log.w(TAG, "Thumbnail attempt ${attempt + 1}/$maxAttempts got no data; ${if (more) "retrying" else "giving up"}")
            if (more && interAttemptDelayMs > 0) delay(interAttemptDelayMs)
        }
        return null
    }

    private suspend fun pullThumbnailOnce(attempt: Int): File? {
        val outDir = appContext.getExternalFilesDir("DCIM") ?: appContext.filesDir
        val file = File(outDir, "Glasses_Photo_${System.currentTimeMillis()}_$attempt.jpg")
        runCatching {
            file.parentFile?.mkdirs()
            if (file.exists()) file.delete()
        }

        val completed = AtomicBoolean(false)
        val done = CompletableDeferred<Boolean>()
        // Single output stream for the whole transfer; appended to as chunks arrive.
        val out = runCatching { FileOutputStream(file, false) }.getOrNull()
        if (out == null) {
            Log.e(TAG, "Could not open output file ${file.absolutePath}")
            return null
        }

        val thumbCallback: (Int, Boolean, ByteArray?) -> Unit = { _, isComplete, data ->
            if (!completed.get() && data != null && data.isNotEmpty()) {
                runCatching { out.write(data) }
                    .onFailure { Log.e(TAG, "Failed writing chunk: ${it.message}", it) }
            }
            if (isComplete && completed.compareAndSet(false, true)) {
                runCatching { out.flush(); out.close() }
                if (!done.isCompleted) done.complete(true)
            }
        }

        LargeDataHandler.getInstance().getPictureThumbnails(thumbCallback)

        val finished = withTimeoutOrNull(TRANSFER_TIMEOUT_MS) { done.await() } == true
        if (!finished) {
            // Timed out — close and discard.
            completed.set(true)
            runCatching { out.flush(); out.close() }
            Log.w(TAG, "Transfer timed out after ${TRANSFER_TIMEOUT_MS}ms (got ${file.length()} bytes)")
            runCatching { file.delete() }
            return null
        }

        // Validate it's a real, decodable image before handing it back.
        if (!isDecodableJpeg(file)) {
            Log.w(TAG, "Transfer complete but bytes are not a decodable image (${file.length()} bytes)")
            runCatching { file.delete() }
            return null
        }
        Log.i(TAG, "Photo pulled OK: ${file.absolutePath} (${file.length()} bytes)")
        return file
    }

    /** True only if [file] holds a non-trivial, decodable bitmap. */
    private fun isDecodableJpeg(file: File): Boolean {
        if (!file.exists() || file.length() < MIN_IMAGE_BYTES) return false
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return opts.outWidth > 0 && opts.outHeight > 0
    }

    companion object {
        private const val TAG = "GlassesPhotoCapture"
        // Per-attempt wait for the thumbnail chunks to arrive once we've requested them.
        private const val TRANSFER_TIMEOUT_MS = 6_000L
        private const val MIN_IMAGE_BYTES = 1024L
        // downloadLatest() (glasses-button path): the photo can take tens of seconds to become
        // available, so poll for up to ~ maxAttempts * (timeout + delay) ≈ 60s.
        private const val LATEST_MAX_ATTEMPTS = 8
        private const val LATEST_RETRY_DELAY_MS = 1_500L
    }
}
