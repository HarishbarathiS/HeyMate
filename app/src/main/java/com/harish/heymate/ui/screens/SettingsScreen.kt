package com.harish.heymate.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.harish.heymate.ble.GlassesBle
import com.harish.heymate.core.CaptureCoordinator
import com.harish.heymate.data.Prefs
import com.harish.heymate.ui.theme.Black
import com.harish.heymate.ui.theme.Outline
import com.harish.heymate.ui.theme.Surface
import com.harish.heymate.ui.theme.TextSecondary
import com.harish.heymate.ui.theme.White
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onGoToHardware: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()

    val savedApiKey by prefs.geminiApiKey.collectAsState(initial = "")
    val speakReplies by prefs.speakReplies.collectAsState(initial = true)
    val autoReconnect by prefs.autoReconnect.collectAsState(initial = true)
    val sendPhotos by prefs.sendPhotosToAgent.collectAsState(initial = false)
    val notifyReplies by prefs.notifyReplies.collectAsState(initial = true)
    val savedVoiceName by prefs.voiceName.collectAsState(initial = "")
    val savedVoiceLang by prefs.voiceLanguage.collectAsState(initial = "")

    var apiKeyDraft by remember { mutableStateOf("") }
    var apiKeyLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(savedApiKey) {
        if (!apiKeyLoaded && savedApiKey.isNotBlank()) {
            apiKeyDraft = savedApiKey
            apiKeyLoaded = true
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))

        SectionLabel("GEMINI AGENT")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = apiKeyDraft,
            onValueChange = { apiKeyDraft = it },
            label = { Text("API key") },
            placeholder = { Text("AIza…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = White,
                unfocusedBorderColor = Outline,
                focusedLabelColor = White,
                unfocusedLabelColor = TextSecondary,
                cursorColor = White,
            ),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { scope.launch { prefs.setGeminiApiKey(apiKeyDraft) } },
            enabled = apiKeyDraft.trim() != savedApiKey,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save API key") }

        Spacer(Modifier.height(28.dp))
        SectionLabel("BEHAVIOR")
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Surface),
        ) {
            ToggleRow("Speak replies aloud", "Agent answers are spoken via the glasses", speakReplies) {
                scope.launch { prefs.setSpeakReplies(it) }
            }
            ToggleRow("Notify replies", "Show agent answers as notifications", notifyReplies) {
                scope.launch { prefs.setNotifyReplies(it) }
            }
            ToggleRow("Attach last photo", "Send the most recent photo with voice queries", sendPhotos) {
                scope.launch { prefs.setSendPhotosToAgent(it) }
            }
            ToggleRow("Auto-reconnect", "Keep the glasses connected automatically", autoReconnect) {
                scope.launch {
                    prefs.setAutoReconnect(it)
                    GlassesBle.setAutoReconnect(it)
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        SectionLabel("VOICE")
        Spacer(Modifier.height(12.dp))
        VoicePicker(
            savedLanguage = savedVoiceLang,
            savedVoiceName = savedVoiceName,
            languages = { CaptureCoordinator.availableVoiceLanguages() },
            voices = { lang -> CaptureCoordinator.availableVoices(lang) },
            onLanguageSelected = { tag ->
                scope.launch {
                    prefs.setVoiceLanguage(tag)
                    // Language changed → clear voice so it isn't mismatched.
                    prefs.setVoiceName("")
                    CaptureCoordinator.applyVoiceSettings()
                }
            },
            onVoiceSelected = { name ->
                scope.launch {
                    prefs.setVoiceName(name)
                    CaptureCoordinator.applyVoiceSettings()
                }
            },
            onPreview = { CaptureCoordinator.previewVoice() },
        )

        Spacer(Modifier.height(28.dp))
        SectionLabel("GLASSES")
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onGoToHardware, modifier = Modifier.fillMaxWidth()) {
            Text("Glasses hardware & controls")
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "HeyMate 0.1.0",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Black,
                checkedTrackColor = White,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = Surface,
                uncheckedBorderColor = Outline,
            ),
        )
    }
}

@Composable
private fun VoicePicker(
    savedLanguage: String,
    savedVoiceName: String,
    languages: () -> List<String>,
    voices: (String?) -> List<Pair<String, String>>,
    onLanguageSelected: (String) -> Unit,
    onVoiceSelected: (String) -> Unit,
    onPreview: () -> Unit,
) {
    // TTS voices may not be enumerable until the engine finishes init; retry a few times.
    var langOptions by remember { mutableStateOf(emptyList<String>()) }
    var voiceOptions by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    LaunchedEffect(savedLanguage) {
        repeat(10) {
            val langs = languages()
            val vs = voices(savedLanguage.ifBlank { null })
            if (langs.isNotEmpty()) langOptions = langs
            if (vs.isNotEmpty()) voiceOptions = vs
            if (langs.isNotEmpty() && vs.isNotEmpty()) return@LaunchedEffect
            kotlinx.coroutines.delay(400)
        }
    }

    val voiceLabel = voiceOptions.firstOrNull { it.first == savedVoiceName }?.let {
        "${voiceShortName(it.first)}  (${it.second})"
    } ?: "Default voice"
    val langLabel = savedLanguage.ifBlank { "System default" }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        DropdownField(
            label = "Language / accent",
            current = langLabel,
            options = buildList {
                add("System default" to "")
                langOptions.forEach { add(it to it) }
            },
            onSelected = onLanguageSelected,
        )
        Spacer(Modifier.height(16.dp))
        DropdownField(
            label = "Voice",
            current = voiceLabel,
            options = buildList {
                add("Default voice" to "")
                voiceOptions.forEach { (name, label) -> add("${voiceShortName(name)}  ($label)" to name) }
            },
            onSelected = onVoiceSelected,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onPreview, modifier = Modifier.fillMaxWidth()) {
            Text("Preview voice")
        }
    }
}

@Composable
private fun DropdownField(
    label: String,
    current: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(current, modifier = Modifier.weight(1f))
            Text("▾")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (display, value) ->
                DropdownMenuItem(
                    text = { Text(display) },
                    onClick = {
                        expanded = false
                        onSelected(value)
                    },
                )
            }
        }
    }
}

/** Trim the verbose engine voice name to something readable (last path-ish segment). */
private fun voiceShortName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('#').ifBlank { name }
