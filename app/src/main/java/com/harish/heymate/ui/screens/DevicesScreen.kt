package com.harish.heymate.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.harish.heymate.ble.FoundDevice
import com.harish.heymate.ble.GlassesBle
import com.harish.heymate.ble.GlassesState
import com.harish.heymate.data.Prefs
import com.harish.heymate.ui.theme.Surface
import com.harish.heymate.ui.theme.TextSecondary
import com.harish.heymate.ui.theme.White
import kotlinx.coroutines.launch

@Composable
fun DevicesScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()

    val state by GlassesBle.state.collectAsState()
    val results by GlassesBle.scanResults.collectAsState()
    val scanning by GlassesBle.scanning.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Text("Devices", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(24.dp))

        // Current device
        val s = state
        if (s is GlassesState.Connected || s is GlassesState.Connecting) {
            val (mac, name) = when (s) {
                is GlassesState.Connected -> s.mac to s.name
                is GlassesState.Connecting -> s.mac to s.name
                else -> "" to null
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Surface)
                    .padding(20.dp),
            ) {
                Text(name ?: mac, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (s is GlassesState.Connected) "Connected" else "Connecting…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        GlassesBle.disconnect()
                        scope.launch { prefs.clearBoundDevice() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Forget device") }
            }
            Spacer(Modifier.height(24.dp))
        }

        // Scan
        OutlinedButton(
            onClick = { if (scanning) GlassesBle.stopScan() else GlassesBle.startScan() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (scanning) {
                CircularProgressIndicator(Modifier.size(14.dp), color = White, strokeWidth = 2.dp)
                Spacer(Modifier.size(10.dp))
            }
            Text(if (scanning) "Scanning… tap to stop" else "Scan for glasses")
        }

        Spacer(Modifier.height(16.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results, key = { it.mac }) { device ->
                DeviceRow(device) {
                    GlassesBle.connect(device.mac, device.name)
                    scope.launch { prefs.saveBoundDevice(device.mac, device.name) }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: FoundDevice, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(device.name, style = MaterialTheme.typography.titleMedium)
            Text(device.mac, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Text("${device.rssi} dBm", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}
