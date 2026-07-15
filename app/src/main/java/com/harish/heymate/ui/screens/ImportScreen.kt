package com.harish.heymate.ui.screens

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harish.heymate.wifitransfer.ImportPhase
import com.harish.heymate.wifitransfer.ImportStatus
import com.harish.heymate.wifitransfer.MediaFile
import com.harish.heymate.wifitransfer.MediaKind
import com.harish.heymate.wifitransfer.WifiImportCoordinator
import com.harish.heymate.ui.theme.Danger
import com.harish.heymate.ui.theme.Outline
import com.harish.heymate.ui.theme.Success
import com.harish.heymate.ui.theme.Surface
import com.harish.heymate.ui.theme.SurfaceHigh
import com.harish.heymate.ui.theme.TextSecondary
import com.harish.heymate.ui.theme.White

/**
 * Wi-Fi media import: connect to the glasses over Wi-Fi Direct, show every photo/video/recording
 * with its size, let the user tick exactly the ones to import, and download only those.
 *
 * Selection-first by design — the full list (with sizes and a running total vs. free space) is
 * shown before anything downloads, so a full device doesn't force an all-or-nothing import.
 */
@Composable
fun ImportScreen() {
    val phase by WifiImportCoordinator.phase.collectAsState()
    val items by WifiImportCoordinator.items.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text("Import media", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(20.dp))

        when (val p = phase) {
            is ImportPhase.Idle -> IdleState()
            is ImportPhase.Connecting -> BusyState(p.detail)
            is ImportPhase.Listing -> BusyState("Reading file list…")
            is ImportPhase.Error -> ErrorState(p.message)
            is ImportPhase.Ready,
            is ImportPhase.Importing,
            is ImportPhase.Finished -> FileList(phase, items)
        }
    }
}

@Composable
private fun IdleState() {
    Column {
        EmptyHint(
            "Import photos, videos and recordings from your glasses over Wi-Fi. " +
                "Make sure the glasses are on and nearby.",
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { WifiImportCoordinator.startDiscovery() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Connect over Wi-Fi") }
    }
}

@Composable
private fun BusyState(detail: String) {
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
private fun ErrorState(message: String) {
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

@Composable
private fun ColumnScope.FileList(phase: ImportPhase, items: List<com.harish.heymate.wifitransfer.ImportItem>) {
    val importing = phase is ImportPhase.Importing
    val selectedCount = items.count { it.selected }
    val selectedBytes = WifiImportCoordinator.selectedBytes
    // StatFs is a blocking syscall — compute once off the main thread, not on every progress tick.
    val freeBytes by produceState(initialValue = Long.MAX_VALUE) {
        value = withContext(Dispatchers.IO) { freeInternalBytes() }
    }
    val fits = selectedBytes <= freeBytes

    // Select-all / none + free-space summary.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${items.size} files • ${MediaFile.formatBytes(freeBytes)} free",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.weight(1f),
        )
        if (!importing) {
            val allSelected = items.isNotEmpty() && items.all { it.selected }
            TextButton(onClick = { WifiImportCoordinator.selectAll(!allSelected) }) {
                Text(if (allSelected) "Clear" else "Select all")
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    LazyColumn(
        Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.file.name }) { item ->
            FileRow(item, enabled = !importing) { WifiImportCoordinator.toggle(item.file.name) }
        }
    }

    Spacer(Modifier.height(12.dp))
    ImportFooter(phase, selectedCount, selectedBytes, fits)
}

@Composable
private fun FileRow(
    item: com.harish.heymate.wifitransfer.ImportItem,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = item.selected,
                onCheckedChange = { if (enabled) onToggle() },
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = White,
                    checkmarkColor = androidx.compose.ui.graphics.Color.Black,
                    uncheckedColor = Outline,
                ),
            )
            Spacer(Modifier.size(8.dp))
            // Once imported, preview the actual photo; otherwise a small placeholder tile.
            if (item.localPath != null && item.file.kind == MediaKind.PHOTO) {
                coil.compose.AsyncImage(
                    model = item.localPath,
                    contentDescription = item.file.name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.size(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    item.file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${item.file.kind.name.lowercase().replaceFirstChar { it.uppercase() }} • ${item.file.sizeLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
            }
            StatusBadge(item.status)
        }
        if (item.status == ImportStatus.DOWNLOADING) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier.fillMaxWidth(),
                color = White,
                trackColor = SurfaceHigh,
            )
        }
    }
}

@Composable
private fun StatusBadge(status: ImportStatus) {
    val (text, color) = when (status) {
        ImportStatus.PENDING -> return
        ImportStatus.DOWNLOADING -> "…" to TextSecondary
        ImportStatus.DONE -> "✓" to Success
        ImportStatus.FAILED -> "!" to Danger
    }
    Text(text, style = MaterialTheme.typography.titleMedium, color = color)
}

@Composable
private fun ImportFooter(phase: ImportPhase, selectedCount: Int, selectedBytes: Long, fits: Boolean) {
    when (phase) {
        is ImportPhase.Importing -> {
            Text(
                "Importing ${phase.done} of ${phase.total}…",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { WifiImportCoordinator.cancel() }, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
        is ImportPhase.Finished -> {
            Text(
                "Imported ${phase.imported} file(s)" + if (phase.failed > 0) " • ${phase.failed} failed" else "",
                style = MaterialTheme.typography.bodyMedium,
                color = if (phase.failed > 0) Danger else Success,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { WifiImportCoordinator.cancel() }, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }
        else -> {
            if (!fits && selectedCount > 0) {
                Text(
                    "Selection is larger than free space",
                    style = MaterialTheme.typography.bodySmall,
                    color = Danger,
                )
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(
                onClick = { WifiImportCoordinator.importSelected() },
                enabled = selectedCount > 0 && fits,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (selectedCount == 0) "Select files to import"
                    else "Import $selectedCount (${MediaFile.formatBytes(selectedBytes)})",
                )
            }
        }
    }
}

/** Free bytes on internal storage, for the fits-in-storage guard. */
private fun freeInternalBytes(): Long = runCatching {
    val stat = StatFs(Environment.getDataDirectory().path)
    stat.availableBlocksLong * stat.blockSizeLong
}.getOrDefault(Long.MAX_VALUE)
