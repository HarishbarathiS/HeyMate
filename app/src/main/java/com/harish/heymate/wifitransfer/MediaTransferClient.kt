package com.harish.heymate.wifitransfer

import android.net.Network
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * HTTP client for the glasses' Wi-Fi media server.
 *
 * REVERSE-ENGINEERED protocol (from the vendor app, confirmed on the wire — see docs/PROTOCOL.md §3).
 * Once the Wi-Fi Direct link is up, the glasses run a plain HTTP file server on port 80:
 *
 *   manifest : GET http://<ip>:80/files/media.config   → newline-separated filenames
 *   file     : GET http://<ip>:80/files/<name>          → full-resolution bytes
 *
 * Non-obvious requirements, each of which cost real debugging time:
 *   - A recognizable `User-Agent: okhttp/…` is REQUIRED — the server 400s requests without it.
 *   - NO auth. The X-Signature/X-Timestamp HMAC signing in the vendor app is for its CLOUD API only.
 *   - The server closes the socket after every response, so OkHttp connection reuse fails with
 *     "unexpected end of stream" — pooling is disabled and we send Connection: close.
 *   - Right after recording, /files/media.config HANGS while the device finalizes the video (the
 *     vendor app's loader waits on exactly this) — the list request retries over a long window.
 *   - With mobile data also active, sockets escape onto cellular unless bound to the Wi-Fi Direct
 *     [Network]; cleartext HTTP also needs network_security_config.xml.
 */
class MediaTransferClient(
    private val deviceIp: String,
    /** The Wi-Fi Direct network to bind sockets to, or null to use the process default. */
    boundNetwork: Network? = null,
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        // Short read timeout so a HUNG manifest request (the server hangs generating media.config
        // while a just-recorded video is still finalizing) fails fast and we can retry, rather than
        // blocking 30s per attempt. File downloads use their own longer client below.
        .readTimeout(8, TimeUnit.SECONDS)
        // The server closes the socket after each response (Connection: close), so OkHttp's reuse
        // fails with "unexpected end of stream". Force a fresh connection every request.
        .connectionPool(ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
        .retryOnConnectionFailure(true)
        .apply { boundNetwork?.socketFactory?.let { socketFactory(it) } }
        .build()

    /** Separate client for large file downloads, where a long read timeout is correct. */
    private val downloadHttp: OkHttpClient = http.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch and parse the media file list. Confirmed on the wire: the list is served at
     * `/files/media.config` (plain text, one `<timestamp>.jpg` filename per line, no sizes), and
     * each file downloads from `/files/<name>`.
     */
    suspend fun listMedia(onWaiting: (Int) -> Unit = {}): Result<List<MediaFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val text = getWithRetry(filesUrl(MANIFEST_NAME), onWaiting).use { response ->
                // No manifest (404) means the glasses simply have no media — an empty list, not an
                // error. Any other non-success is a real failure.
                if (response.code == 404) return@withContext Result.success(emptyList())
                if (!response.isSuccessful) error("manifest HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
            Log.i(TAG, "manifest raw =\n$text")
            val parsed = ManifestParser.parse(text, FILES_DIR)
            Log.i(TAG, "manifest parsed ${parsed.size}: ${parsed.map { "${it.name}(${it.kind})" }}")
            parsed
        }.onFailure { Log.w(TAG, "listMedia failed: ${it.message}") }
    }

    /**
     * Download one file to [destDir], reporting progress as a 0f..1f fraction. Writes to a
     * temporary file first and renames on success, so a cancelled or failed download never leaves a
     * half-written file in place.
     *
     * @return the saved [File] on success.
     */
    suspend fun download(
        file: MediaFile,
        destDir: File,
        onProgress: (Float) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            destDir.mkdirs()
            val out = File(destDir, file.name)
            val tmp = File(destDir, "${file.name}.part")
            tmp.delete()

            val expected = get(filesUrl(file.name), forDownload = true).use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code} for ${file.name}")
                val body = response.body ?: error("empty body for ${file.name}")
                val total = body.contentLength().takeIf { it > 0 } ?: file.sizeBytes ?: -1L

                var copied = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive() // honor cancellation between chunks
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                // If the server closes early, the read loop ends normally with a short file — guard
                // against silently publishing a truncated photo.
                if (total > 0 && copied < total) {
                    error("truncated ${file.name}: got $copied of $total bytes")
                }
                total
            }

            out.delete()
            // renameTo() fails intermittently on scoped external storage; fall back to copy+delete.
            if (!tmp.renameTo(out)) {
                runCatching { tmp.copyTo(out, overwrite = true); tmp.delete() }
                    .getOrElse { error("could not finalize ${file.name}: ${it.message}") }
            }
            if (!out.exists() || out.length() == 0L) error("finalize produced no file for ${file.name}")
            onProgress(1f)
            Log.i(TAG, "downloaded ${file.name} (${if (expected > 0) "$expected bytes" else "size unknown"})")
            out
        }.onFailure {
            // Never swallow cancellation — that would turn a user Cancel into a per-file "failure"
            // and let the import loop keep running. Clean up the partial file for real errors only.
            if (it is kotlinx.coroutines.CancellationException) {
                File(destDir, "${file.name}.part").delete()
                throw it
            }
            Log.w(TAG, "download ${file.name} failed: ${it.message}")
            File(destDir, "${file.name}.part").delete()
        }
    }

    /**
     * Issue a GET with the exact headers the glasses' server expects. Captured from the vendor app
     * on the wire, the server rejects requests missing these (a bare request gets 400); it needs a
     * standard okhttp-style [User-Agent] and keep-alive. No auth/signature is required — the signing
     * seen in the vendor app applies only to its cloud API, not this local server.
     */
    private fun get(url: String, forDownload: Boolean = false): okhttp3.Response {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            // The server closes after each response; ask for close so OkHttp won't try to reuse the
            // socket (which fails with "unexpected end of stream" on the next request).
            .header("Connection", "close")
            // Deliberately NOT setting Accept-Encoding: setting it manually disables OkHttp's
            // transparent gzip decompression, which would write compressed bytes to a .jpg (silent
            // corruption) if the server ever gzips. Media is already compressed, so identity is best.
            .build()
        return (if (forDownload) downloadHttp else http).newCall(request).execute()
    }

    /**
     * GET with retries. The glasses' embedded server is flaky right after the Wi-Fi Direct link comes
     * up — the first request or two can fail with "unexpected end of stream" before it settles. Retry
     * a few times with a short backoff; raw requests to the same server succeed, so this is transient.
     */
    private suspend fun getWithRetry(url: String, onWaiting: (Int) -> Unit = {}): okhttp3.Response {
        var last: Exception? = null
        var last404: okhttp3.Response? = null
        repeat(LIST_RETRIES) { attempt ->
            try {
                val r = get(url)
                if (r.isSuccessful) return r
                // 404 can be transient (a just-recorded video still finalizing) OR mean the device is
                // genuinely empty. Retry it only a few times; if it persists, return it so the caller
                // can treat it as "no media" rather than an error.
                if (r.code == 404) {
                    if (attempt >= EMPTY_404_RETRIES) return r
                    last404?.close(); last404 = r
                } else if (r.code in 400..499) {
                    return r // other 4xx is a real answer
                } else {
                    r.close(); last = java.io.IOException("HTTP ${r.code}")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
                Log.i(TAG, "GET $url attempt ${attempt + 1} failed: ${e.message}")
            }
            onWaiting(attempt + 1)
            kotlinx.coroutines.delay(RETRY_BACKOFF_MS)
        }
        // A persistent 404 means "no media" — hand it back for the caller to treat as empty.
        last404?.let { return it }
        throw last ?: java.io.IOException("request failed")
    }

    /** Absolute URL for a name served under the device's `/files/` root (manifest and each file). */
    private fun filesUrl(name: String): String = "http://$deviceIp:$PORT$FILES_DIR$name"

    companion object {
        private const val TAG = "MediaTransferClient"
        private const val PORT = 80
        /** Matches what the vendor app sends; the server requires a recognizable UA (else 400). */
        private const val USER_AGENT = "okhttp/4.9.2"
        /**
         * The server hangs generating media.config while a just-recorded video finalizes (the OEM
         * app shows a loader for this). Each attempt is bounded by the 8s read timeout, so retry
         * generously — ~12 × (up to 8s + 2s) ≈ up to ~2 min of patience for a long video.
         */
        private const val LIST_RETRIES = 12
        private const val RETRY_BACKOFF_MS = 2_000L
        /** Retry a 404 this many times (video finalizing) before treating it as "no media". */
        private const val EMPTY_404_RETRIES = 3
        /** The server's file root — both the manifest and each media file live here. */
        const val FILES_DIR = "/files/"
        /** The media file list, confirmed on the wire (one filename per line, no sizes). */
        const val MANIFEST_NAME = "media.config"
    }
}
