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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.harish.heymate.ble.GlassesBle
import com.harish.heymate.ble.GlassesInfo
import com.harish.heymate.ble.GlassesState
import com.harish.heymate.ble.VolumeLevel
import com.harish.heymate.ui.theme.Black
import com.harish.heymate.ui.theme.Danger
import com.harish.heymate.ui.theme.Outline
import com.harish.heymate.ui.theme.Success
import com.harish.heymate.ui.theme.Surface
import com.harish.heymate.ui.theme.SurfaceHigh
import com.harish.heymate.ui.theme.TextSecondary
import com.harish.heymate.ui.theme.White

/**
 * Everything the glasses report about themselves, plus the controls they accept.
 *
 * Only features the device advertises via [GlassesInfo.support] are shown as controls — the vendor
 * SDK is shared with their smartwatches, so most of its surface (heart rate, sleep, watch faces)
 * does not exist on this hardware and is intentionally absent.
 */
@Composable
fun GlassesScreen() {
    val state by GlassesBle.state.collectAsState()
    val battery by GlassesInfo.battery.collectAsState()
    val info by GlassesInfo.deviceInfo.collectAsState()
    val media by GlassesInfo.media.collectAsState()
    val support by GlassesInfo.support.collectAsState()
    val volume by GlassesInfo.volume.collectAsState()
    val classicBt by GlassesInfo.classicBt.collectAsState()
    val worn by GlassesInfo.worn.collectAsState()
    val refreshing by GlassesInfo.refreshing.collectAsState()

    val connected = state is GlassesState.Connected

    // Pull fresh telemetry whenever this screen is shown while connected.
    LaunchedEffect(connected) {
        if (connected) GlassesInfo.refreshAll()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Glasses", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            if (refreshing) {
                CircularProgressIndicator(Modifier.size(18.dp), color = White, strokeWidth = 2.dp)
            }
        }
        Spacer(Modifier.height(24.dp))

        if (!connected) {
            EmptyHint("Connect your glasses to see their status")
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        // ---- Status: connection, battery, wear ----
        StatusCard(
            deviceName = (state as? GlassesState.Connected)?.let { it.name ?: it.mac } ?: "",
            batteryPercent = battery?.percent,
            charging = battery?.charging == true,
            worn = worn,
            wearSupported = support?.wearCheck == true,
        )

        // ---- Storage ----
        Spacer(Modifier.height(28.dp))
        SectionLabel("ON-DEVICE STORAGE")
        Spacer(Modifier.height(12.dp))
        val counts = media
        if (counts != null) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatTile("Photos", counts.images.toString(), Modifier.weight(1f))
                StatTile("Videos", counts.videos.toString(), Modifier.weight(1f))
                StatTile("Recordings", counts.recordings.toString(), Modifier.weight(1f))
            }
        } else {
            EmptyHint("Reading storage…")
        }

        // ---- Volume ----
        val vol = volume
        if (support?.volumeControl == true && vol != null) {
            Spacer(Modifier.height(28.dp))
            SectionLabel("VOLUME")
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Surface)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                VolumeSlider("Music", vol.music) { GlassesInfo.setVolume(GlassesInfo.VolumeStream.MUSIC, it) }
                Spacer(Modifier.height(12.dp))
                VolumeSlider("System", vol.system) { GlassesInfo.setVolume(GlassesInfo.VolumeStream.SYSTEM, it) }
                Spacer(Modifier.height(12.dp))
                VolumeSlider("Call", vol.call) { GlassesInfo.setVolume(GlassesInfo.VolumeStream.CALL, it) }
            }
        }

        // ---- Controls ----
        Spacer(Modifier.height(28.dp))
        SectionLabel("CONTROLS")
        Spacer(Modifier.height(12.dp))
        ControlsCard(wearSupported = support?.wearCheck == true, wearCheckOn = worn != null)

        // ---- Device details ----
        Spacer(Modifier.height(28.dp))
        SectionLabel("DEVICE")
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Surface)
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            InfoRow("Firmware", info?.firmware)
            InfoRow("Hardware", info?.hardware)
            InfoRow("Wi-Fi firmware", info?.wifiFirmware)
            InfoRow("Wi-Fi hardware", info?.wifiHardware)
            InfoRow("Audio device", classicBt?.name)
            InfoRow("Audio address", classicBt?.address)
            InfoRow("Model", support?.model?.toString())
            InfoRow("Translation", support?.let { if (it.translation) "Supported" else "Not supported" })
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = { GlassesInfo.refreshAll() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Refresh") }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatusCard(
    deviceName: String,
    batteryPercent: Int?,
    charging: Boolean,
    worn: Boolean?,
    wearSupported: Boolean,
) {
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
                    .background(Success),
            )
            Spacer(Modifier.size(12.dp))
            Text(deviceName, style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(20.dp))

        if (batteryPercent != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Battery", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Spacer(Modifier.weight(1f))
                Text(
                    if (charging) "$batteryPercent%  ⚡ Charging" else "$batteryPercent%",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (batteryPercent <= LOW_BATTERY_PERCENT && !charging) Danger else White,
                )
            }
            Spacer(Modifier.height(10.dp))
            BatteryBar(percent = batteryPercent, charging = charging)
        } else {
            Text("Reading battery…", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        }

        if (wearSupported) {
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Being worn", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                Spacer(Modifier.weight(1f))
                Text(
                    when (worn) {
                        true -> "Yes"
                        false -> "No"
                        null -> "Unknown"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/** Simple horizontal fill bar; green while charging, white normally, red when low. */
@Composable
private fun BatteryBar(percent: Int, charging: Boolean) {
    val fraction = (percent.coerceIn(0, 100)) / 100f
    val fill = when {
        charging -> Success
        percent <= LOW_BATTERY_PERCENT -> Danger
        else -> White
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(SurfaceHigh),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(CircleShape)
                .background(fill),
        )
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Surface)
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ControlsCard(wearSupported: Boolean, wearCheckOn: Boolean) {
    // The speaker and wake-word commands are write-only — the device offers no way to read their
    // current state — so these reflect the last value we sent, not necessarily the device's.
    var speakerOn by remember { mutableStateOf(true) }
    var aiWake by remember { mutableStateOf(false) }
    // Wear detection does report back: once it answers, `worn` is non-null and the toggle is on.
    var wearCheck by remember(wearCheckOn) { mutableStateOf(wearCheckOn) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Surface),
    ) {
        ToggleRowLocal("Speaker", "Play sound through the glasses", speakerOn) {
            speakerOn = it
            GlassesInfo.setSpeakerOn(it)
        }
        ToggleRowLocal("AI wake word", "Let the glasses listen for their wake word", aiWake) {
            aiWake = it
            GlassesInfo.setAiWake(it)
        }
        if (wearSupported) {
            ToggleRowLocal("Wear detection", "Detect when the glasses are on your face", wearCheck) {
                wearCheck = it
                GlassesInfo.setWearCheck(it)
            }
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
            OutlinedButton(
                onClick = { GlassesInfo.syncTime() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sync time to glasses") }
        }
    }
}

@Composable
private fun VolumeSlider(label: String, level: VolumeLevel, onChange: (Int) -> Unit) {
    // Slide freely, commit once on release — every change is a BLE write.
    var draft by remember(level.current) { mutableStateOf(level.current.toFloat()) }
    Column(Modifier.fillMaxWidth()) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            Spacer(Modifier.weight(1f))
            Text(draft.toInt().toString(), style = MaterialTheme.typography.bodyMedium)
        }
        // A device reporting min == max has nothing to adjust; a zero-width range crashes Slider.
        if (level.max > level.min) {
            Slider(
                value = draft,
                onValueChange = { draft = it },
                onValueChangeFinished = { onChange(draft.toInt()) },
                valueRange = level.min.toFloat()..level.max.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = White,
                    activeTrackColor = White,
                    inactiveTrackColor = SurfaceHigh,
                ),
            )
        }
    }
}

@Composable
private fun ToggleRowLocal(
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

/** Label/value row; shows a dash until the device reports the value. */
@Composable
private fun InfoRow(label: String, value: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Spacer(Modifier.weight(1f))
        Text(value?.takeIf { it.isNotBlank() } ?: "—", style = MaterialTheme.typography.bodyMedium)
    }
}

private const val LOW_BATTERY_PERCENT = 20
