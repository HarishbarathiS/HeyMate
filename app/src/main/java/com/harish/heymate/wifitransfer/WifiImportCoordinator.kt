package com.harish.heymate.wifitransfer

import android.content.Context
import android.util.Log
import com.harish.heymate.core.EventFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** A media file already downloaded to the phone, for the persistent local gallery. */
data class LocalMedia(val name: String, val path: String) {
    val kind: MediaKind get() = MediaFile(name, null, "").kind
}

/** Where one file is in the import pipeline. */
enum class ImportStatus { PENDING, DOWNLOADING, DONE, FAILED }

/** A file plus its selection state and download progress, for display. */
data class ImportItem(
    val file: MediaFile,
    val selected: Boolean = false,
    val status: ImportStatus = ImportStatus.PENDING,
    /** 0f..1f while downloading. */
    val progress: Float = 0f,
    /** Absolute path of the downloaded file once imported, for previewing; null until done. */
    val localPath: String? = null,
)

/** Top-level phase of the Wi-Fi import flow, driving what the UI shows. */
sealed class ImportPhase {
    data object Idle : ImportPhase()
    data class Connecting(val detail: String) : ImportPhase()
    data object Listing : ImportPhase()
    /** Files are listed and awaiting the user's selection. */
    data object Ready : ImportPhase()
    data class Importing(val done: Int, val total: Int) : ImportPhase()
    data class Finished(val imported: Int, val failed: Int) : ImportPhase()
    data class Error(val message: String) : ImportPhase()
}

/**
 * Orchestrates the whole Wi-Fi import: connect over Wi-Fi Direct, list the glasses' media, let the
 * user pick a subset, and download only those. UI-facing state lives in [phase] and [items]; the
 * UI never touches [WifiDirectConnection] or [MediaTransferClient] directly.
 *
 * Selective import is the point: the file list (with sizes) is fetched before any download, so the
 * user imports only what fits their storage. [selectedBytes] backs a free-space check in the UI.
 */
object WifiImportCoordinator {

    private const val TAG = "WifiImport"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var appContext: Context
    private var connection: WifiDirectConnection? = null
    private var client: MediaTransferClient? = null
    private var importJob: Job? = null

    private val _phase = MutableStateFlow<ImportPhase>(ImportPhase.Idle)
    val phase: StateFlow<ImportPhase> = _phase.asStateFlow()

    private val _items = MutableStateFlow<List<ImportItem>>(emptyList())
    val items: StateFlow<List<ImportItem>> = _items.asStateFlow()

    /** Total bytes of the currently selected files (known sizes only). */
    val selectedBytes: Long
        get() = _items.value.filter { it.selected }.sumOf { it.file.sizeBytes ?: 0L }

    /** Files already downloaded to the phone, shown in the Gallery without needing the glasses. */
    private val _localGallery = MutableStateFlow<List<LocalMedia>>(emptyList())
    val localGallery: StateFlow<List<LocalMedia>> = _localGallery.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        refreshLocalGallery()
    }

    /** Rebuild the local gallery list from the download directory. */
    fun refreshLocalGallery() {
        scope.launch {
            _localGallery.value = kotlinx.coroutines.withContext(Dispatchers.IO) {
                (importDir().listFiles { f -> f.isFile && f.extension.lowercase() in MEDIA_EXTS }
                    ?.sortedByDescending { it.lastModified() }
                    ?.map { LocalMedia(it.name, it.absolutePath) }
                    ?: emptyList())
            }
        }
    }

    /** Directory imported files are saved to (app-scoped external DCIM). */
    fun importDir(): File =
        appContext.getExternalFilesDir("DCIM") ?: File(appContext.filesDir, "DCIM")

    /**
     * Fetch the full-size file for previewing. Downloads it into the app's PERMANENT gallery dir (not
     * volatile cache), so once the user has seen a photo it survives app restart and shows in the
     * grid — matching the expectation that "I opened it, so keep it". If the file is already there,
     * returns it instantly. Reconnects first if the Wi-Fi Direct group has since torn down.
     *
     * Note: this keeps a copy in the app; pushing it to the phone's shared Gallery (Google Photos)
     * is a separate step via [saveToGallery].
     */
    /** The already-downloaded local file for [name], or null if we don't have it yet. Synchronous. */
    fun localFileFor(name: String): File? = importDir().resolve(name).takeIf { it.exists() }

    suspend fun fetchPreview(file: MediaFile): Result<File> {
        localFileFor(file.name)?.let { return Result.success(it) }

        // First attempt with the existing client (if any); if it fails, the link likely dropped —
        // reconnect once and retry.
        val result = client?.download(file, importDir())?.let { first ->
            if (first.isSuccess) first else {
                Log.i(TAG, "preview fetch failed, reconnecting: ${first.exceptionOrNull()?.message}")
                null
            }
        } ?: run {
            if (!ensureConnected()) return Result.failure(IllegalStateException("Could not connect to the glasses"))
            val c = client ?: return Result.failure(IllegalStateException("Not connected"))
            c.download(file, importDir())
        }
        // A previewed file is kept locally and shows in the grid immediately: update BOTH the scanned
        // item's localPath (so its tile renders the image instead of a placeholder) and the local list.
        result.getOrNull()?.let { saved ->
            mark(file.name) { it.copy(status = ImportStatus.DONE, localPath = saved.absolutePath) }
            refreshLocalGallery()
        }
        return result
    }

    /**
     * Ensure a live Wi-Fi Direct connection + [client], reconnecting if needed. Returns true on
     * success. Used by preview/download when the group may have torn down since listing. Preserves
     * the current [_phase] (usually [ImportPhase.Ready]) so the gallery grid isn't disrupted.
     */
    private suspend fun ensureConnected(): Boolean {
        val savedPhase = _phase.value
        val conn = WifiDirectConnection(appContext).also {
            connection?.disconnect()
            connection = it
        }
        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { awaitConnection(conn) }
        _phase.value = savedPhase // undo the transient Connecting/Error phase transitions
        if (connected == null) {
            conn.disconnect()
            return false
        }
        client = MediaTransferClient(connected.ip, connected.network)
        return true
    }

    /**
     * Start the flow: connect over Wi-Fi Direct, then list the media. Ends in [ImportPhase.Ready]
     * with [items] populated, or [ImportPhase.Error].
     */
    fun startDiscovery() {
        // Block only while a scan is actively in flight; allow a fresh scan from Ready/Finished so the
        // refresh button can re-check the glasses at any time.
        if (_phase.value is ImportPhase.Connecting || _phase.value is ImportPhase.Listing) {
            Log.i(TAG, "startDiscovery ignored; already in ${_phase.value}")
            return
        }
        reset()
        val conn = WifiDirectConnection(appContext).also { connection = it }
        _phase.value = ImportPhase.Connecting("Searching for glasses…")

        scope.launch {
            val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { awaitConnection(conn) }
            if (connected == null) {
                fail("Could not connect to the glasses over Wi-Fi Direct")
                conn.disconnect()
                return@launch
            }
            client = MediaTransferClient(connected.ip, connected.network)
            listMedia()
        }
    }

    /** Collect connection state until Connected or Failed. */
    private suspend fun awaitConnection(conn: WifiDirectConnection): WifiDirectConnection.State.Connected? {
        conn.connect()
        var result: WifiDirectConnection.State.Connected? = null
        conn.state.collectUntil { s ->
            when (s) {
                is WifiDirectConnection.State.Discovering -> {
                    _phase.value = ImportPhase.Connecting("Searching for glasses…"); false
                }
                is WifiDirectConnection.State.PeerFound -> {
                    _phase.value = ImportPhase.Connecting("Found ${s.deviceName}"); false
                }
                is WifiDirectConnection.State.Connecting -> {
                    _phase.value = ImportPhase.Connecting("Connecting…"); false
                }
                is WifiDirectConnection.State.Connected -> { result = s; true }
                is WifiDirectConnection.State.Failed -> { fail(s.reason); true }
                else -> false
            }
        }
        return result
    }

    private suspend fun listMedia() {
        _phase.value = ImportPhase.Listing
        val c = client ?: return fail("No transfer client")
        c.listMedia(onWaiting = { attempt ->
            // The glasses hang serving the list while a just-recorded video is still finalizing.
            // Surface a "still processing" message rather than looking stuck.
            if (attempt >= 2) _phase.value = ImportPhase.Connecting("Processing video on glasses… ($attempt)")
        }).fold(
            onSuccess = { files ->
                // Mark items already on disk as downloaded so their tiles show the image immediately.
                _items.value = files.map {
                    val local = localFileFor(it.name)
                    ImportItem(it, status = if (local != null) ImportStatus.DONE else ImportStatus.PENDING,
                        localPath = local?.absolutePath)
                }
                _phase.value = ImportPhase.Ready
                EventFeed.status(
                    if (files.isEmpty()) "No new media on the glasses"
                    else "Found ${files.size} file(s) on the glasses",
                )
                // Auto-download everything not already local, so the grid fills with real images.
                if (files.isNotEmpty()) autoDownloadAll()
            },
            onFailure = { fail("Could not read the file list: ${it.message}") },
        )
    }

    /**
     * Download every listed file we don't already have, sequentially in the background, so the grid
     * shows real images without the user tapping each. Each tile updates as its file lands. Runs
     * within the current connection; failures per-file are non-fatal.
     */
    private fun autoDownloadAll() {
        importJob?.cancel()
        importJob = scope.launch {
            val c = client ?: return@launch
            val pending = _items.value.filter { it.localPath == null }
            for (item in pending) {
                if (localFileFor(item.file.name) != null) continue
                mark(item.file.name) { it.copy(status = ImportStatus.DOWNLOADING, progress = 0f) }
                val result = c.download(item.file, importDir()) { p ->
                    mark(item.file.name) { it.copy(progress = p) }
                }
                result.getOrNull()?.let { saved ->
                    mark(item.file.name) { it.copy(status = ImportStatus.DONE, progress = 1f, localPath = saved.absolutePath) }
                } ?: mark(item.file.name) { it.copy(status = ImportStatus.FAILED) }
            }
            refreshLocalGallery()
        }
    }

    // ---------------------------------------------------------------- selection

    fun toggle(name: String) = _items.update(name) { it.copy(selected = !it.selected) }

    fun setSelected(name: String, selected: Boolean) = _items.update(name) { it.copy(selected = selected) }

    fun selectAll(selected: Boolean) {
        _items.value = _items.value.map { it.copy(selected = selected) }
    }

    // ---------------------------------------------------------------- delete / import

    /**
     * Remove HeyMate's own copy of [mediaName]: the file in the download dir and any preview cache,
     * and un-record it as imported. The original on the glasses and any copy already in the phone's
     * Gallery (Google Photos) are left untouched. Refreshes the local gallery afterwards.
     */
    suspend fun deleteLocal(mediaName: String) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            importDir().resolve(mediaName).delete()
        }
        com.harish.heymate.data.Prefs(appContext).removeImportedFile(mediaName)
        // Drop it from the scanned list's "downloaded" marker too.
        mark(mediaName) { it.copy(status = ImportStatus.PENDING, localPath = null) }
        refreshLocalGallery()
    }

    /**
     * Save an already-fetched preview [file] (for [mediaName]) to the phone's Gallery and record it
     * as imported. Used by the preview screen's Download button — no re-fetch needed.
     */
    suspend fun saveToGallery(file: File, mediaName: String): Boolean {
        // Keep a permanent copy in the app's own gallery dir (the preview may be in volatile cache),
        // so the photo shows in the Gallery grid offline forever.
        val kept = importDir().resolve(mediaName)
        if (file.absolutePath != kept.absolutePath) {
            runCatching { importDir().mkdirs(); file.copyTo(kept, overwrite = true) }
        }
        val ok = MediaStoreSaver.saveToGallery(appContext, kept)
        if (ok) {
            com.harish.heymate.data.Prefs(appContext).addImportedFiles(listOf(mediaName))
            mark(mediaName) { it.copy(status = ImportStatus.DONE, localPath = kept.absolutePath) }
            refreshLocalGallery()
        }
        return ok
    }

    /** Download every selected file, sequentially, updating per-item progress. */
    fun importSelected() {
        val selected = _items.value.filter { it.selected }
        if (selected.isEmpty()) return
        val c = client ?: return fail("Not connected")

        importJob?.cancel()
        importJob = scope.launch {
            val dir = importDir()
            var done = 0
            var failed = 0
            _phase.value = ImportPhase.Importing(0, selected.size)

            for (item in selected) {
                mark(item.file.name) { it.copy(status = ImportStatus.DOWNLOADING, progress = 0f) }
                // download() rethrows CancellationException, so a user Cancel unwinds this loop
                // (and this coroutine) instead of being recorded as a failed file.
                val result = c.download(item.file, dir) { p ->
                    mark(item.file.name) { it.copy(progress = p) }
                }
                if (result.isSuccess) {
                    done++
                    val saved = result.getOrNull()
                    // Publish to the phone's Gallery so it shows in Google Photos / gallery apps,
                    // and record it as imported so it no longer counts as "new".
                    saved?.let {
                        MediaStoreSaver.saveToGallery(appContext, it)
                        com.harish.heymate.data.Prefs(appContext).addImportedFiles(listOf(item.file.name))
                    }
                    mark(item.file.name) {
                        it.copy(status = ImportStatus.DONE, progress = 1f, localPath = saved?.absolutePath)
                    }
                } else {
                    failed++
                    mark(item.file.name) { it.copy(status = ImportStatus.FAILED) }
                }
                _phase.value = ImportPhase.Importing(done + failed, selected.size)
            }

            // Release the Wi-Fi Direct group now that downloads are done — leaving it up holds the
            // glasses in transfer mode and can block the next import. Downloaded files are kept.
            connection?.disconnect()
            connection = null
            client = null
            _phase.value = ImportPhase.Finished(done, failed)
            EventFeed.status("Imported $done file(s)" + if (failed > 0) ", $failed failed" else "")
            refreshLocalGallery()
        }
    }

    fun cancel() {
        importJob?.cancel()
        connection?.disconnect()
        connection = null
        client = null
        _phase.value = ImportPhase.Idle
    }

    private fun reset() {
        importJob?.cancel()
        // Release any previous P2P group/receiver before starting a new discovery, so a cancelled
        // or timed-out prior attempt can't leak its Wi-Fi Direct connection.
        connection?.disconnect()
        connection = null
        client = null
        // Keep _items so the grid doesn't flash empty on re-scan; listMedia() replaces it on success.
    }

    private fun fail(message: String) {
        Log.w(TAG, "import failed: $message")
        EventFeed.error(message)
        _phase.value = ImportPhase.Error(message)
    }

    private fun mark(name: String, transform: (ImportItem) -> ImportItem) = _items.update(name, transform)

    private fun MutableStateFlow<List<ImportItem>>.update(name: String, transform: (ImportItem) -> ImportItem) {
        value = value.map { if (it.file.name == name) transform(it) else it }
    }

    private const val CONNECT_TIMEOUT_MS = 45_000L
    private val MEDIA_EXTS = setOf("jpg", "jpeg", "png", "heic", "webp", "mp4", "mov", "avi")
}

/**
 * Collect the flow, invoking [onEach] for every emission (for its side effects), and stop as soon
 * as [onEach] returns true — that emission is the terminal one and its side effect still runs.
 */
private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.collectUntil(onEach: (T) -> Boolean) {
    try {
        collect { if (onEach(it)) throw StopCollect }
    } catch (_: StopCollect) {
    }
}

private object StopCollect : Throwable() {
    override fun fillInStackTrace(): Throwable = this // control-flow signal, no stack needed
    private fun readResolve(): Any = StopCollect
}
