package com.harish.heymate.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.harish.heymate.ui.theme.Black
import com.harish.heymate.ui.theme.Danger
import com.harish.heymate.ui.theme.Success
import com.harish.heymate.ui.theme.TextSecondary
import com.harish.heymate.ui.theme.White
import com.harish.heymate.wifitransfer.MediaFile
import com.harish.heymate.wifitransfer.WifiImportCoordinator
import kotlinx.coroutines.launch
import java.io.File

/** Progress of saving the fetched preview to the phone's Gallery. */
private enum class SaveState { IDLE, SAVING, SAVED, FAILED }

/**
 * Full-screen preview of a single photo fetched from the glasses, with a Download-to-Gallery
 * action. Fetches on entry, shows the image edge-to-edge on black, and lets the user save it
 * without leaving the screen.
 */
@Composable
fun PreviewScreen(file: MediaFile, onBack: () -> Unit) {
    BackHandler { onBack() }

    // If we already have the file locally, show it instantly with no "downloading" state — only fetch
    // over Wi-Fi when it isn't there yet.
    val alreadyLocal = remember(file.name) { WifiImportCoordinator.localFileFor(file.name) }

    // Re-fetch by bumping this key; produceState re-runs when it changes.
    var attempt by remember { mutableStateOf(0) }

    val preview by produceState<Result<File>?>(
        initialValue = alreadyLocal?.let { Result.success(it) },
        key1 = file.name,
        key2 = attempt,
    ) {
        if (alreadyLocal != null && attempt == 0) {
            value = Result.success(alreadyLocal)   // instant, no network
            return@produceState
        }
        value = null
        value = WifiImportCoordinator.fetchPreview(file)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black),
        contentAlignment = Alignment.Center,
    ) {
        when (val result = preview) {
            null -> LoadingState(isVideo = file.kind == com.harish.heymate.wifitransfer.MediaKind.VIDEO)
            else -> result.fold(
                onSuccess = { localFile ->
                    LoadedState(file = file, localFile = localFile, onBack = onBack)
                },
                onFailure = { error ->
                    ErrorState(
                        message = error.message ?: "Could not load the image.",
                        onRetry = { attempt++ },
                        onBack = onBack,
                    )
                },
            )
        }
    }
}

@Composable
private fun LoadingState(isVideo: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(color = White)
        Text(
            text = if (isVideo) "Downloading video…" else "Loading full image…",
            color = TextSecondary,
            fontSize = 14.sp,
        )
    }
}

/**
 * In-app video player: an ExoPlayer bound to the composition lifecycle, shown via a media3
 * [androidx.media3.ui.PlayerView] with default playback controls. Auto-plays and releases the
 * player when the composable leaves.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ExoVideoPlayer(file: File, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val player = remember(file.absolutePath) {
        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    androidx.compose.runtime.DisposableEffect(player) {
        onDispose { player.release() }
    }
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { ctx ->
            androidx.media3.ui.PlayerView(ctx).apply {
                this.player = player
                useController = true
                setShowBuffering(androidx.media3.ui.PlayerView.SHOW_BUFFERING_ALWAYS)
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
    )
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = message,
            color = Danger,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("Retry")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("← Back")
        }
    }
}

@Composable
private fun LoadedState(file: MediaFile, localFile: File, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var saveState by remember(file.name) { mutableStateOf(SaveState.IDLE) }

    val isVideo = file.kind == com.harish.heymate.wifitransfer.MediaKind.VIDEO
    val context = androidx.compose.ui.platform.LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        if (isVideo) {
            // Play the video in-app with ExoPlayer — no external player dialog.
            ExoVideoPlayer(
                file = localFile,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Black),
            )
        } else {
            AsyncImage(
                model = localFile,
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Black),
            )
        }

        // Top: back button floating over the image.
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp),
        ) {
            Text("← Back")
        }

        // Bottom: scrimmed info + download.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = file.name,
                color = White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = file.sizeLabel,
                color = TextSecondary,
                fontSize = 13.sp,
            )

            DownloadButton(
                saveState = saveState,
                onDownload = {
                    saveState = SaveState.SAVING
                    scope.launch {
                        val ok = WifiImportCoordinator.saveToGallery(localFile, file.name)
                        saveState = if (ok) SaveState.SAVED else SaveState.FAILED
                        // On success, briefly show the tick then return to the gallery automatically.
                        if (ok) {
                            kotlinx.coroutines.delay(700)
                            onBack()
                        }
                    }
                },
            )

            // Delete HeyMate's own copy (the glasses' original and any Google Photos copy stay).
            OutlinedButton(
                onClick = {
                    scope.launch {
                        WifiImportCoordinator.deleteLocal(file.name)
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Remove from app", color = Danger)
            }
        }
    }
}

@Composable
private fun DownloadButton(saveState: SaveState, onDownload: () -> Unit) {
    val (label, color, enabled) = when (saveState) {
        SaveState.IDLE -> Triple("Save to Photos", White, true)
        SaveState.SAVING -> Triple("Saving…", TextSecondary, false)
        SaveState.SAVED -> Triple("✓ Saved to Photos", Success, false)
        SaveState.FAILED -> Triple("Save failed — tap to retry", Danger, true)
    }

    OutlinedButton(
        onClick = onDownload,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 14.dp),
    ) {
        if (saveState == SaveState.SAVING) {
            CircularProgressIndicator(
                color = TextSecondary,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(16.dp)
                    .padding(end = 0.dp),
            )
        }
        Text(
            text = label,
            color = color,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = if (saveState == SaveState.SAVING) 10.dp else 0.dp),
        )
    }
}
