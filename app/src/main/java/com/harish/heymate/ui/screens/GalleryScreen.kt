package com.harish.heymate.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.harish.heymate.ble.GlassesBle
import com.harish.heymate.ble.GlassesState
import com.harish.heymate.wifitransfer.GalleryState
import com.harish.heymate.wifitransfer.ImportItem
import com.harish.heymate.wifitransfer.ImportPhase
import com.harish.heymate.wifitransfer.ImportStatus
import com.harish.heymate.wifitransfer.MediaFile
import com.harish.heymate.wifitransfer.MediaKind
import com.harish.heymate.wifitransfer.WifiImportCoordinator
import com.harish.heymate.ui.theme.Black
import com.harish.heymate.ui.theme.Danger
import com.harish.heymate.ui.theme.Success
import com.harish.heymate.ui.theme.Surface
import com.harish.heymate.ui.theme.SurfaceHigh
import com.harish.heymate.ui.theme.TextSecondary
import com.harish.heymate.ui.theme.White

/**
 * The Gallery: media on the glasses. Shows a "new content available" bar (driven by the BLE media
 * count, no Wi-Fi needed), connects over Wi-Fi on demand to list files, displays them as a grid, and
 * opens each into a fullscreen preview where it can be downloaded to the phone's Gallery.
 *
 * @param onOpenFile navigate to the fullscreen preview for [MediaFile].
 */
@Composable
fun GalleryScreen(onOpenFile: (MediaFile) -> Unit) {
    val connected = GlassesBle.state.collectAsState().value is GlassesState.Connected
    val phase by WifiImportCoordinator.phase.collectAsState()
    val scanned by WifiImportCoordinator.items.collectAsState()
    val local by WifiImportCoordinator.localGallery.collectAsState()
    val newCount by GalleryState.newCount.collectAsState()

    // Refresh whenever the Gallery is shown. LaunchedEffect(Unit) runs each time this composable
    // enters composition — which the NavHost triggers on every tab switch back to Gallery — so the
    // local list and the glasses media-count re-check don't wait for an app restart.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        WifiImportCoordinator.refreshLocalGallery()
    }
    androidx.compose.runtime.LaunchedEffect(connected) {
        if (connected) com.harish.heymate.ble.GlassesInfo.readMediaCounts()
    }

    // The grid: everything scanned from the glasses this session, plus already-downloaded files not
    // in that scan (so downloaded photos always show, even with no connection). Deduped by name.
    val scannedNames = scanned.map { it.file.name }.toSet()
    val localOnly = local.filter { it.name !in scannedNames }
    val hasAny = scanned.isNotEmpty() || localOnly.isNotEmpty()

    val scanning = phase is ImportPhase.Connecting || phase is ImportPhase.Listing

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        // Header with an always-available "load from glasses" action so a scan can be triggered
        // regardless of whether the BLE count is known or the grid already has photos.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Gallery", style = MaterialTheme.typography.headlineLarge)
                val shown = scanned.size + localOnly.size
                val subtitle = buildString {
                    append("$shown item${if (shown == 1) "" else "s"}")
                    if (newCount > 0) append(" · $newCount to download")
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            // Refresh: the authoritative re-check. Re-reads the on-disk list AND kicks a real Wi-Fi
            // scan of the glasses (which lists actual files) — the BLE count is too flaky to trust.
            IconButton(
                onClick = {
                    WifiImportCoordinator.refreshLocalGallery()
                    if (connected && !scanning) WifiImportCoordinator.startDiscovery()
                },
                enabled = connected && !scanning,
            ) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "Refresh",
                    tint = if (connected && !scanning) White else TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        // "New content" bar — the glasses hold more than we've downloaded.
        if (newCount > 0 && !scanning) {
            NewContentBar(count = newCount, enabled = connected) {
                WifiImportCoordinator.startDiscovery()
            }
            Spacer(Modifier.height(16.dp))
        }

        when (val p = phase) {
            is ImportPhase.Connecting -> Busy(p.detail)
            is ImportPhase.Listing -> Busy("Reading gallery…")
            is ImportPhase.Error -> {
                // Show the error but still render whatever we already have downloaded.
                ErrorBox(p.message)
                if (hasAny) { Spacer(Modifier.height(16.dp)); MediaGrid(scanned, localOnly, onOpenFile) }
            }
            else -> {
                if (hasAny) MediaGrid(scanned, localOnly, onOpenFile)
                else EmptyGallery(connected)
            }
        }
    }
}

@Composable
private fun NewContentBar(count: Int, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50))
                .background(Success),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "$count new item${if (count == 1) "" else "s"} available",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (enabled) "Tap to load them from your glasses" else "Connect your glasses to load",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
        }
        Text("→", style = MaterialTheme.typography.titleLarge, color = TextSecondary)
    }
}

@Composable
private fun MediaGrid(
    scanned: List<ImportItem>,
    localOnly: List<com.harish.heymate.wifitransfer.LocalMedia>,
    onOpenFile: (MediaFile) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Files seen this session on the glasses (may or may not be downloaded yet).
        items(scanned, key = { "s:" + it.file.name }) { item ->
            Tile(
                name = item.file.name,
                kind = item.file.kind,
                localPath = item.localPath,
                downloading = item.status == ImportStatus.DOWNLOADING,
                onClick = { onOpenFile(item.file) },
            )
        }
        // Already-downloaded files not in this session's scan — always available offline.
        items(localOnly, key = { "l:" + it.name }) { m ->
            Tile(
                name = m.name,
                kind = m.kind,
                localPath = m.path,
                downloading = false,
                onClick = { onOpenFile(MediaFile(m.name, null, "")) },
            )
        }
    }
}

@Composable
private fun Tile(
    name: String,
    kind: MediaKind,
    localPath: String?,
    downloading: Boolean,
    onClick: () -> Unit,
) {
    // Fall back to resolving the file by name: a just-downloaded item may not have localPath wired
    // through yet, but if it's on disk we should still show its image, never a placeholder. Not
    // remembered — a cheap file check that must re-evaluate once the download lands.
    val resolvedPath = localPath
        ?: com.harish.heymate.wifitransfer.WifiImportCoordinator.localFileFor(name)?.absolutePath
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceHigh)
            .clickable(onClick = onClick),
    ) {
        // Photos and videos both render a frame via Coil (the video-frame decoder handles .mp4).
        // Audio/other keep a glyph placeholder.
        if (resolvedPath != null && (kind == MediaKind.PHOTO || kind == MediaKind.VIDEO)) {
            AsyncImage(
                model = resolvedPath,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (downloading) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = White, strokeWidth = 2.dp)
                } else {
                    Text(
                        when (kind) {
                            MediaKind.VIDEO -> "▶"
                            MediaKind.AUDIO -> "♪"
                            else -> "▣"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextSecondary,
                    )
                }
            }
        }
        // A small play badge over a video's thumbnail so it's distinguishable from a photo.
        if (kind == MediaKind.VIDEO && resolvedPath != null) {
            Text(
                "▶",
                style = MaterialTheme.typography.titleMedium,
                color = White,
                modifier = Modifier
                    .align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun EmptyGallery(connected: Boolean) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Surface)
                .padding(vertical = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (connected) "Load your glasses' photos and videos over Wi-Fi"
                else "Connect your glasses to see their photos",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { WifiImportCoordinator.startDiscovery() },
            enabled = connected,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Load from glasses") }
    }
}

@Composable
private fun Busy(detail: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), color = White, strokeWidth = 2.dp)
        Text(detail, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ErrorBox(message: String) {
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Surface)
                .padding(20.dp),
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Danger)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { WifiImportCoordinator.startDiscovery() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Try again") }
    }
}
