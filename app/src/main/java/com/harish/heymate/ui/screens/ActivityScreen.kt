package com.harish.heymate.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.harish.heymate.core.AppEvent
import com.harish.heymate.core.EventFeed
import com.harish.heymate.ui.theme.Danger
import com.harish.heymate.ui.theme.Surface
import com.harish.heymate.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@Composable
fun ActivityScreen() {
    val events by EventFeed.events.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text("Activity", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))

        if (events.isEmpty()) {
            EmptyHint("Events from your glasses will appear here")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(events, key = { it.id }) { event -> EventRow(event) }
            }
        }
    }
}

@Composable
private fun EventRow(event: AppEvent) {
    val (label, body, color) = when (event) {
        is AppEvent.Transcript -> Triple("You said", event.text, MaterialTheme.colorScheme.onBackground)
        is AppEvent.AgentReply -> Triple("Agent", event.text, MaterialTheme.colorScheme.onBackground)
        is AppEvent.PhotoCaptured -> Triple("Photo", event.path.substringAfterLast('/'), MaterialTheme.colorScheme.onBackground)
        is AppEvent.VoiceStart -> Triple("Voice", "Listening started", TextSecondary)
        is AppEvent.VoiceEnd -> Triple("Voice", "Listening ended", TextSecondary)
        is AppEvent.Status -> Triple("Status", event.text, TextSecondary)
        is AppEvent.Error -> Triple("Error", event.text, Danger)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = color)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            timeFormat.format(Date(event.time)),
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
    }
}
