package com.harish.heymate.ble

import android.util.Log
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.communication.bigData.resp.GlassModelControlResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Battery level + charging state, as reported by the glasses. */
data class BatteryInfo(val percent: Int, val charging: Boolean)

/** Firmware/hardware revisions for both the main SoC and the Wi-Fi module. */
data class DeviceInfo(
    val firmware: String,
    val hardware: String,
    val wifiFirmware: String,
    val wifiHardware: String,
)

/** How many photos / videos / voice recordings are stored on the glasses. */
data class MediaCounts(val images: Int, val videos: Int, val recordings: Int)

/**
 * Which optional features this particular glasses model supports. The vendor SDK is shared across
 * their watches and glasses, so most of its API is irrelevant here — this response is the device
 * telling us what it can actually do. Everything else should be treated as unsupported.
 */
data class FeatureSupport(
    val model: Int,
    val translation: Boolean,
    val wearCheck: Boolean,
    val volumeControl: Boolean,
)

/** Volume for one audio stream: current level within [min, max]. */
data class VolumeLevel(val current: Int, val min: Int, val max: Int)

/** Volumes for the three independent streams the glasses expose. */
data class VolumeInfo(
    val music: VolumeLevel,
    val system: VolumeLevel,
    val call: VolumeLevel,
    /** Which stream the hardware buttons currently adjust. */
    val activeType: Int,
)

/** Classic-Bluetooth (A2DP audio) identity of the glasses, distinct from the BLE control channel. */
data class ClassicBtInfo(val name: String, val address: String)

/**
 * Telemetry and control surface for the glasses, layered on top of [GlassesBle]'s connection.
 *
 * Everything here maps to a call the vendor SDK genuinely exposes for glasses (verified against
 * `glasses_sdk_20250723_v01.aar`). Health/fitness APIs in that SDK belong to the vendor's watches
 * and are deliberately not surfaced.
 *
 * All reads are fire-and-forget over BLE: we ask, the device answers on a callback, we push the
 * result into a [StateFlow]. Nothing blocks. Fields stay null until the device first answers.
 */
object GlassesInfo {

    private const val TAG = "GlassesInfo"
    private const val BATTERY_CB_KEY = "HeyMate"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Bounds how long the refresh spinner shows; cancelled if a newer refresh supersedes it. */
    private var refreshSpinnerJob: kotlinx.coroutines.Job? = null

    private val _battery = MutableStateFlow<BatteryInfo?>(null)
    val battery: StateFlow<BatteryInfo?> = _battery.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfo?>(null)
    val deviceInfo: StateFlow<DeviceInfo?> = _deviceInfo.asStateFlow()

    private val _media = MutableStateFlow<MediaCounts?>(null)
    val media: StateFlow<MediaCounts?> = _media.asStateFlow()

    private val _support = MutableStateFlow<FeatureSupport?>(null)
    val support: StateFlow<FeatureSupport?> = _support.asStateFlow()

    private val _volume = MutableStateFlow<VolumeInfo?>(null)
    val volume: StateFlow<VolumeInfo?> = _volume.asStateFlow()

    private val _classicBt = MutableStateFlow<ClassicBtInfo?>(null)
    val classicBt: StateFlow<ClassicBtInfo?> = _classicBt.asStateFlow()

    /** True while the glasses are being worn (only meaningful if [FeatureSupport.wearCheck]). */
    private val _worn = MutableStateFlow<Boolean?>(null)
    val worn: StateFlow<Boolean?> = _worn.asStateFlow()

    /**
     * IP address the glasses expose for Wi-Fi (P2P) media transfer, once negotiated. Null until
     * the device reports one. Full-res photo/video pull will use this instead of BLE thumbnails.
     */
    private val _p2pIp = MutableStateFlow<String?>(null)
    val p2pIp: StateFlow<String?> = _p2pIp.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private fun connected(): Boolean = runCatching {
        BleOperateManager.getInstance().isConnected
    }.getOrDefault(false)

    /**
     * Ask the glasses for everything we display. Safe to call repeatedly; each answer arrives
     * asynchronously on its own callback. No-op when disconnected.
     */
    fun refreshAll() {
        if (!connected()) {
            Log.i(TAG, "refreshAll: not connected")
            return
        }
        _refreshing.value = true
        readBattery()
        readDeviceInfo()
        readSupport()
        readMediaCounts()
        readVolume()
        readClassicBt()
        // The device answers on its own schedule; clear the spinner after a bounded window rather
        // than tracking six independent callbacks. Both the connect handler and the Glasses screen
        // call refreshAll(), so cancel any in-flight timer or the first would hide the second's.
        refreshSpinnerJob?.cancel()
        refreshSpinnerJob = scope.launch {
            kotlinx.coroutines.delay(REFRESH_SPINNER_MS)
            _refreshing.value = false
        }
    }

    /** Clear all cached telemetry — call on disconnect so the UI never shows stale device data. */
    fun clear() {
        // Unregister first: a late callback would otherwise resurrect the values we clear below.
        runCatching { LargeDataHandler.getInstance().removeBatteryCallBack(BATTERY_CB_KEY) }
        refreshSpinnerJob?.cancel()
        refreshSpinnerJob = null
        _battery.value = null
        _deviceInfo.value = null
        _media.value = null
        _support.value = null
        _volume.value = null
        _classicBt.value = null
        _worn.value = null
        _p2pIp.value = null
        _refreshing.value = false
    }

    // ------------------------------------------------------------------ reads

    fun readBattery() = guarded("battery") {
        LargeDataHandler.getInstance().addBatteryCallBack(BATTERY_CB_KEY) { _, rsp ->
            _battery.value = BatteryInfo(rsp.battery, rsp.isCharging)
        }
        LargeDataHandler.getInstance().syncBattery()
    }

    fun readDeviceInfo() = guarded("deviceInfo") {
        LargeDataHandler.getInstance().syncDeviceInfo { _, rsp ->
            _deviceInfo.value = DeviceInfo(
                firmware = rsp.firmwareVersion.orEmpty(),
                hardware = rsp.hardwareVersion.orEmpty(),
                wifiFirmware = rsp.wifiFirmwareVersion.orEmpty(),
                wifiHardware = rsp.wifiHardwareVersion.orEmpty(),
            )
        }
    }

    fun readSupport() = guarded("support") {
        LargeDataHandler.getInstance().wearFunctionSupport { _, rsp ->
            _support.value = FeatureSupport(
                model = rsp.glassesModel,
                translation = rsp.isTranslationSupport,
                wearCheck = rsp.isWearCheckSupport,
                volumeControl = rsp.isVolumeControl,
            )
        }
    }

    fun readVolume() = guarded("volume") {
        LargeDataHandler.getInstance().getVolumeControl { _, rsp ->
            _volume.value = VolumeInfo(
                music = VolumeLevel(rsp.currVolumeMusic, rsp.minVolumeMusic, rsp.maxVolumeMusic),
                system = VolumeLevel(rsp.currVolumeSystem, rsp.minVolumeSystem, rsp.maxVolumeSystem),
                call = VolumeLevel(rsp.currVolumeCall, rsp.minVolumeCall, rsp.maxVolumeCall),
                activeType = rsp.currVolumeType,
            )
        }
    }

    fun readClassicBt() = guarded("classicBt") {
        LargeDataHandler.getInstance().syncClassicBluetooth { _, rsp ->
            _classicBt.value = ClassicBtInfo(rsp.btName.orEmpty(), rsp.btAddress.orEmpty())
        }
    }

    /**
     * Query how many photos/videos/recordings are on the device. The reply arrives as a
     * GlassModelControlResponse with dataType==[DATA_TYPE_MEDIA_COUNTS]; other dataTypes on this
     * same channel carry work-state and are ignored here.
     */
    fun readMediaCounts() = guarded("mediaCounts") {
        LargeDataHandler.getInstance().glassesControl(CMD_QUERY_MEDIA_COUNTS, ::onControlResponse)
    }

    /**
     * The glasses multiplex several unrelated payloads onto the one control channel, tagged by
     * `dataType`. Each response carries only the fields for its own tag; reading any other getter
     * yields a stale zero. Values verified against the SDK's response parser.
     */
    private fun onControlResponse(@Suppress("UNUSED_PARAMETER") code: Int, rsp: GlassModelControlResponse) {
        when (rsp.dataType) {
            DATA_TYPE_MEDIA_COUNTS ->
                _media.value = MediaCounts(rsp.imageCount, rsp.videoCount, rsp.recordCount)
            DATA_TYPE_P2P_IP ->
                rsp.p2pIp?.takeIf { it.isNotBlank() }?.let { _p2pIp.value = it }
        }
    }

    /** Enable/disable wear detection; the answer streams into [worn]. */
    fun setWearCheck(enabled: Boolean) = guarded("wearCheck") {
        LargeDataHandler.getInstance().wearCheck(enabled, enabled) { _, rsp ->
            _worn.value = rsp.isOpen
        }
    }

    // ------------------------------------------------------------------ controls

    /** Mute/unmute the glasses' speaker. */
    fun setSpeakerOn(on: Boolean) = guarded("speakSoundSwitch") {
        LargeDataHandler.getInstance().speakSoundSwitch(on)
    }

    /** Turn the on-device AI wake word on/off. */
    fun setAiWake(enabled: Boolean) = guarded("aiVoiceWake") {
        LargeDataHandler.getInstance().aiVoiceWake(enabled, enabled) { _, _ -> }
    }

    /** Push the phone's clock to the glasses. */
    fun syncTime() = guarded("syncTime") {
        LargeDataHandler.getInstance().syncTime { _, _ -> }
    }

    /**
     * Set one stream's volume. The SDK takes all ten values at once (type + min/max/current for
     * music, system and call), so we merge [stream]'s new level into the last known [volume].
     */
    fun setVolume(stream: VolumeStream, value: Int) {
        val v = _volume.value ?: run {
            Log.w(TAG, "setVolume before volume was read; ignoring")
            return
        }
        val music = if (stream == VolumeStream.MUSIC) v.music.copy(current = value) else v.music
        val system = if (stream == VolumeStream.SYSTEM) v.system.copy(current = value) else v.system
        val call = if (stream == VolumeStream.CALL) v.call.copy(current = value) else v.call

        guarded("setVolumeControl") {
            LargeDataHandler.getInstance().setVolumeControl(
                stream.wireValue,
                music.min, music.max, music.current,
                system.min, system.max, system.current,
                call.min, call.max, call.current,
            )
        }
        // Reflect immediately; the device does not echo the new value back.
        _volume.value = v.copy(music = music, system = system, call = call)
    }

    /**
     * Tell the glasses which IP to stream media to, kicking off Wi-Fi (P2P) transfer setup.
     * Groundwork for full-res photo/video pull.
     *
     * Two separate channels are involved: [LargeDataHandler.writeIpToSoc] pushes our address on the
     * SoC/OTA action, while the glasses report *their* address as a dataType==3 payload on the
     * control action. Asking for the latter is a distinct command, issued here after the write.
     */
    fun beginWifiTransfer(phoneIp: String) = guarded("writeIpToSoc") {
        LargeDataHandler.getInstance().writeIpToSoc(phoneIp) { _, _ -> }
        LargeDataHandler.getInstance().glassesControl(CMD_QUERY_P2P_IP, ::onControlResponse)
    }

    /** Run [block] only when connected, swallowing SDK throws so one bad read can't crash the UI. */
    private inline fun guarded(what: String, block: () -> Unit) {
        if (!connected()) return
        runCatching(block).onFailure { Log.w(TAG, "$what failed: ${it.message}") }
    }

    /** This phone's IPv4 address on its current network, or null if it isn't on one. */
    fun phoneIpv4(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    enum class VolumeStream(val wireValue: Int) {
        MUSIC(0),
        SYSTEM(1),
        CALL(2),
    }

    private const val REFRESH_SPINNER_MS = 1_500L

    /**
     * `glassesControl` payloads are `[0x02, subCommand, ...]`. The SDK frames them behind a
     * six-byte header and the device echoes the sub-command back as the response's `dataType`,
     * so the two sets of constants below intentionally line up.
     */
    private val CMD_QUERY_P2P_IP = byteArrayOf(0x02, 0x03)
    private val CMD_QUERY_MEDIA_COUNTS = byteArrayOf(0x02, 0x04)

    /**
     * `GlassModelControlResponse.dataType` tags, read off the SDK's own parser. Only the two we
     * consume are named; 1 = work-state, 2 = video info, 6 = audio-record duration.
     */
    private const val DATA_TYPE_P2P_IP = 3
    private const val DATA_TYPE_MEDIA_COUNTS = 4
}
