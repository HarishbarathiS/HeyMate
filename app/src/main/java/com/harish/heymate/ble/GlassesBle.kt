package com.harish.heymate.ble

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.harish.heymate.core.EventFeed
import com.oudmon.ble.base.bluetooth.BleAction
import com.oudmon.ble.base.bluetooth.BleBaseControl
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.DeviceManager
import com.oudmon.ble.base.bluetooth.QCBluetoothCallbackCloneReceiver
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanRecord
import com.oudmon.ble.base.scan.ScanWrapperCallback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Connection state of the glasses' BLE control channel. */
sealed class GlassesState {
    data object Disconnected : GlassesState()
    data class Connecting(val mac: String, val name: String?) : GlassesState()
    data class Connected(val mac: String, val name: String?) : GlassesState()
}

/** A device found during scanning. */
data class FoundDevice(val mac: String, val name: String, val rssi: Int)

/**
 * BLE connection layer for the glasses, extracted from the vendor app's
 * MyApplication/DeviceBindActivity/MyBluetoothReceiver trio and rebuilt UI-free:
 *
 *  - [init] wires the vendor SDK (BleOperateManager + receivers) — call once from Application.
 *  - [startScan]/[stopScan] populate [scanResults].
 *  - [connect]/[disconnect] manage the control channel; [state] is the single source of truth.
 *  - Auto-reconnects to the bound device with a simple backoff loop while enabled.
 */
object GlassesBle {

    private const val TAG = "GlassesBle"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _state = MutableStateFlow<GlassesState>(GlassesState.Disconnected)
    val state: StateFlow<GlassesState> = _state.asStateFlow()

    private val _scanResults = MutableStateFlow<List<FoundDevice>>(emptyList())
    val scanResults: StateFlow<List<FoundDevice>> = _scanResults.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private lateinit var app: Application
    private var targetMac: String? = null
    private var targetName: String? = null
    private var autoReconnectEnabled = true
    private var reconnectJob: Job? = null
    private var scanStopJob: Job? = null

    fun init(application: Application) {
        app = application

        // Vendor SDK bootstrap — order matters (mirrors the official companion app).
        LargeDataHandler.getInstance()
        BleOperateManager.getInstance(application)
        BleOperateManager.getInstance().setApplication(application)
        BleOperateManager.getInstance().init()
        BleBaseControl.getInstance(application).setmContext(application)

        // SDK-internal events (connect status, service discovery) arrive via LocalBroadcast.
        LocalBroadcastManager.getInstance(application)
            .registerReceiver(SdkEventReceiver(), BleAction.getIntentFilter())

        // System Bluetooth on/off + ACL events.
        val sysReceiver = SystemBtReceiver()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            application.registerReceiver(sysReceiver, BleAction.getDeviceIntentFilter(), Context.RECEIVER_EXPORTED)
        } else {
            application.registerReceiver(sysReceiver, BleAction.getDeviceIntentFilter())
        }

        Log.i(TAG, "BLE stack initialised")
    }

    // ---------------------------------------------------------------- scanning

    fun startScan(durationMs: Long = 15_000L) {
        if (_scanning.value) return
        _scanResults.value = emptyList()
        _scanning.value = true
        BleScannerHelper.getInstance().reSetCallback()
        BleScannerHelper.getInstance().scanDevice(app, null, scanCallback)
        scanStopJob?.cancel()
        scanStopJob = scope.launch {
            delay(durationMs)
            stopScan()
        }
    }

    fun stopScan() {
        scanStopJob?.cancel()
        scanStopJob = null
        if (!_scanning.value) return
        _scanning.value = false
        runCatching { BleScannerHelper.getInstance().stopScan(app) }
    }

    private val scanCallback = object : ScanWrapperCallback {
        override fun onStart() {}
        override fun onStop() {}

        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            val mac = device?.address ?: return
            val name = try { device.name } catch (_: SecurityException) { null }
            upsert(mac, name, rssi)
        }

        override fun onParsedData(device: BluetoothDevice?, scanRecord: ScanRecord?) {
            val mac = device?.address ?: return
            val name = try { scanRecord?.deviceName ?: device.name } catch (_: SecurityException) { scanRecord?.deviceName }
            upsert(mac, name, rssi = _scanResults.value.firstOrNull { it.mac.equals(mac, true) }?.rssi ?: 0)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "Scan failed: $errorCode")
            _scanning.value = false
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {}
    }

    private fun upsert(mac: String, name: String?, rssi: Int) {
        val clean = name?.trim()?.takeIf { it.isNotEmpty() } ?: return // unnamed devices are noise
        val current = _scanResults.value
        val idx = current.indexOfFirst { it.mac.equals(mac, ignoreCase = true) }
        _scanResults.value = if (idx >= 0) {
            current.toMutableList().also { it[idx] = FoundDevice(mac, clean, rssi) }
        } else {
            current + FoundDevice(mac, clean, rssi)
        }
    }

    // ---------------------------------------------------------------- connection

    fun connect(mac: String, name: String?) {
        stopScan()
        targetMac = mac
        targetName = name
        _state.value = GlassesState.Connecting(mac, name)
        Log.i(TAG, "connectDirectly($mac)")
        BleOperateManager.getInstance().connectDirectly(mac)
    }

    fun disconnect() {
        targetMac = null
        targetName = null
        reconnectJob?.cancel()
        runCatching { BleOperateManager.getInstance().unBindDevice() }
        _state.value = GlassesState.Disconnected
        GlassesInfo.clear()
        EventFeed.status("Disconnected from glasses")
    }

    fun setAutoReconnect(enabled: Boolean) {
        autoReconnectEnabled = enabled
        if (!enabled) reconnectJob?.cancel()
    }

    /** Restore the bound device on app start (call after init, with the persisted MAC). */
    fun restoreBoundDevice(mac: String, name: String?) {
        targetMac = mac
        targetName = name
        if (BleOperateManager.getInstance().isConnected) {
            _state.value = GlassesState.Connected(mac, name)
        } else {
            connect(mac, name)
        }
    }

    private fun scheduleReconnect() {
        val mac = targetMac ?: return
        if (!autoReconnectEnabled) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var attempt = 0
            while (state.value !is GlassesState.Connected && targetMac != null) {
                val backoff = (5_000L * (attempt + 1)).coerceAtMost(30_000L)
                delay(backoff)
                if (state.value is GlassesState.Connected || targetMac == null) break
                attempt++
                Log.i(TAG, "Auto-reconnect attempt $attempt to $mac")
                _state.value = GlassesState.Connecting(mac, targetName)
                runCatching { BleOperateManager.getInstance().connectDirectly(mac) }
            }
        }
    }

    // ---------------------------------------------------------------- receivers

    /** SDK connection lifecycle events (mirrors the vendor app's MyBluetoothReceiver). */
    private class SdkEventReceiver : QCBluetoothCallbackCloneReceiver() {
        override fun connectStatue(device: BluetoothDevice?, connected: Boolean) {
            if (device != null && connected) {
                val name = try { device.name } catch (_: SecurityException) { null }
                if (name != null) DeviceManager.getInstance().deviceName = name
                // Not "ready" yet — wait for onServiceDiscovered.
            } else {
                Log.i(TAG, "Control channel disconnected")
                if (_state.value !is GlassesState.Disconnected) {
                    _state.value = GlassesState.Disconnected
                    EventFeed.status("Glasses disconnected")
                }
                GlassesInfo.clear()
                scheduleReconnect()
            }
        }

        override fun onServiceDiscovered() {
            // Required handshake before any commands work.
            LargeDataHandler.getInstance().initEnable()
            BleOperateManager.getInstance().isReady = true
            val mac = targetMac ?: DeviceManager.getInstance().deviceAddress ?: "?"
            val name = targetName ?: DeviceManager.getInstance().deviceName
            _state.value = GlassesState.Connected(mac, name)
            reconnectJob?.cancel()
            Log.i(TAG, "Glasses ready ($mac)")
            EventFeed.status("Connected to ${name ?: mac}")
            GlassesInfo.refreshAll()
        }

        override fun onCharacteristicChange(address: String?, uuid: String?, data: ByteArray?) {}
        override fun onCharacteristicRead(uuid: String?, data: ByteArray?) {}
    }

    /** System Bluetooth adapter events. */
    private class SystemBtReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_OFF -> {
                            BleOperateManager.getInstance().setBluetoothTurnOff(false)
                            runCatching { BleOperateManager.getInstance().disconnect() }
                            _state.value = GlassesState.Disconnected
                            GlassesInfo.clear()
                        }
                        BluetoothAdapter.STATE_ON -> {
                            BleOperateManager.getInstance().setBluetoothTurnOff(true)
                            scheduleReconnect()
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    // Glasses connected as classic BT audio → opportunistically bring up BLE control.
                    val device: BluetoothDevice? =
                        @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val saved = targetMac
                    if (device != null && saved != null && saved.equals(device.address, ignoreCase = true) &&
                        state.value !is GlassesState.Connected
                    ) {
                        connect(saved, targetName)
                    }
                }
            }
        }
    }
}
