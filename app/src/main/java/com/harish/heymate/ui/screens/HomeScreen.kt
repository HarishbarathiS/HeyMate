package com.harish.heymate.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.harish.heymate.ui.components.MarkdownText
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.harish.heymate.ble.GlassesBle
import com.harish.heymate.ble.GlassesState
import com.harish.heymate.core.CaptureCoordinator
import com.harish.heymate.ui.theme.Danger
import com.harish.heymate.ui.theme.Success
import com.harish.heymate.ui.theme.Surface
import com.harish.heymate.ui.theme.TextSecondary
import com.harish.heymate.ui.theme.White

@Composable
fun HomeScreen(onGoToDevices: () -> Unit) {
    val state by GlassesBle.state.collectAsState()
    val battery by com.harish.heymate.ble.GlassesInfo.battery.collectAsState()
    val lastPhoto by CaptureCoordinator.lastPhotoPath.collectAsState()
    val pendingPhoto by CaptureCoordinator.pendingPhotoPath.collectAsState()
    val lastTranscript by CaptureCoordinator.lastTranscript.collectAsState()
    val lastReply by CaptureCoordinator.lastReply.collectAsState()
    val liveStatus by CaptureCoordinator.liveStatus.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text("HeyMate", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))

        // ---- Connection ----
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Surface)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when (state) {
                                is GlassesState.Connected -> Success
                                is GlassesState.Connecting -> TextSecondary
                                else -> Danger
                            }
                        ),
                )
                Spacer(Modifier.size(12.dp))
                Column {
                    Text(
                        when (val s = state) {
                            is GlassesState.Connected -> s.name ?: s.mac
                            is GlassesState.Connecting -> "Connecting…"
                            else -> "Not connected"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        when (state) {
                            is GlassesState.Connected -> "Glasses ready"
                            is GlassesState.Connecting -> "Bringing up control channel"
                            else -> "Connect your glasses to start"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                    )
                }
            }

            // Battery — shown once the glasses report it.
            if (state is GlassesState.Connected && battery != null) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Battery", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                    Spacer(Modifier.weight(1f))
                    Text(
                        battery!!.let { if (it.charging) "${it.percent}%  ⚡" else "${it.percent}%" },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (battery!!.percent <= 20 && battery!!.charging.not()) Danger else White,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = {
                    if (state is GlassesState.Connected) GlassesBle.disconnect() else onGoToDevices()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state is GlassesState.Connected) "Disconnect" else "Connect")
            }
        }

        // ---- Live status ----
        if (liveStatus != null) {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Surface)
                    .padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), color = White, strokeWidth = 2.dp)
                Text(liveStatus ?: "", style = MaterialTheme.typography.bodyLarge)
            }
        }

        // ---- Stop speaking (shown while a reply is being read aloud) ----
        val speaking by CaptureCoordinator.speaking.collectAsState()
        if (speaking) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = { CaptureCoordinator.stopSpeaking() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Stop speaking") }
        }

        // ---- Last photo ----
        Spacer(Modifier.height(28.dp))
        if (lastPhoto != null) {
            AsyncImage(
                model = lastPhoto,
                contentDescription = "Last captured photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(16.dp)),
            )
        } else {
            EmptyHint("Press the photo button on your glasses")
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { CaptureCoordinator.capturePhotoForMessage() },
            enabled = state is GlassesState.Connected,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Capture now") }

        // ---- Photo armed for the next voice query ----
        if (pendingPhoto != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Photo ready — ask your question by voice and it'll be sent with this photo.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }

        // ---- Last conversation ----
        Spacer(Modifier.height(28.dp))
        if (lastTranscript != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Surface)
                    .padding(20.dp),
            ) {
                Text("You", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Spacer(Modifier.height(4.dp))
                Text(lastTranscript ?: "", style = MaterialTheme.typography.bodyLarge)
                if (lastReply != null) {
                    Spacer(Modifier.height(16.dp))
                    Text("Agent", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    Spacer(Modifier.height(4.dp))
                    ProvideTextStyle(MaterialTheme.typography.bodyLarge) {
                        MarkdownText(lastReply ?: "")
                    }
                }
            }
        } else {
            EmptyHint("Hold the voice button on your glasses and speak")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = TextSecondary,
    )
}

@Composable
fun EmptyHint(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
    }
}
