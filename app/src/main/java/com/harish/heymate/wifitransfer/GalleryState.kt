package com.harish.heymate.wifitransfer

import android.content.Context
import com.harish.heymate.ble.GlassesInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * How much media on the glasses is not yet on the phone, for the "new content available" prompt.
 *
 * Reliability note: the glasses' BLE media *count* proved unreliable (often stale or unanswered), so
 * "new" is derived from the authoritative source instead — the last real Wi-Fi scan's file list vs.
 * what's actually downloaded. After a scan (which auto-downloads), this correctly drops to 0.
 *
 * As a soft hint BEFORE any scan, if the BLE count reports more items than we've downloaded we show
 * that difference — but the scanned-list truth always takes over once a scan has run.
 */
object GalleryState {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Number of items known to be on the glasses but not yet downloaded to the phone. */
    private val _newCount = MutableStateFlow(0)
    val newCount: StateFlow<Int> = _newCount.asStateFlow()

    fun init(context: Context) {
        scope.launch {
            combine(
                WifiImportCoordinator.items,       // the real scanned file list (authoritative)
                WifiImportCoordinator.localGallery, // what we actually have on disk
                GlassesInfo.media,                  // BLE count — only a pre-scan hint
            ) { scanned, local, media ->
                if (scanned.isNotEmpty()) {
                    // Authoritative: scanned files whose bytes we don't yet have locally.
                    val localNames = local.map { it.name }.toSet()
                    scanned.count { it.localPath == null && it.file.name !in localNames }
                } else {
                    // No scan yet — best-effort hint from the (flaky) BLE count vs. downloaded total.
                    val onDevice = (media?.images ?: 0) + (media?.videos ?: 0) + (media?.recordings ?: 0)
                    (onDevice - local.size).coerceAtLeast(0)
                }
            }.collect { _newCount.value = it }
        }
    }
}
