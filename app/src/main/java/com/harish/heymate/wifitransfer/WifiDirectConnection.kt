package com.harish.heymate.wifitransfer

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Establishes and tears down a Wi-Fi Direct (P2P) link to the glasses so media can be pulled over
 * HTTP. The glasses act as the group owner; once connected we expose the group owner's IP and the
 * bound [Network] (needed so download sockets don't escape onto mobile data).
 *
 * REVERSE-ENGINEERED — none of this Wi-Fi transfer exists in the vendor BLE SDK; it was recovered
 * from the vendor app and confirmed on the wire. See docs/PROTOCOL.md §3.
 * The connect sequence:
 *   1. BLE trigger glassesControl([0x02,0x01,0x04,0x01]) → glasses start their Wi-Fi Direct group
 *      (reply dataType=1, workType=4; errorCode!=0 means "still processing", not a failure).
 *   2. WifiP2pManager discovery + connect; glasses take a 192.168.49.x DHCP lease.
 *   3. If the phone becomes group owner, resolve the glasses' client IP by probing port 80 across
 *      the DHCP range — /proc/net/arp is unreadable to sandboxed apps on modern Android.
 *
 * This is the on-device-dependent half — it drives the Android [WifiP2pManager] framework and must
 * be exercised against real glasses. State is surfaced via [state] for the UI.
 *
 * Requires ACCESS_FINE_LOCATION (or NEARBY_WIFI_DEVICES on Android 13+) to discover peers.
 */
class WifiDirectConnection(private val appContext: Context) {

    sealed class State {
        data object Idle : State()
        data object Discovering : State()
        data class PeerFound(val deviceName: String) : State()
        data object Connecting : State()
        /** Connected: [ip] is the glasses' address; [network] binds sockets to this link. */
        data class Connected(val ip: String, val network: Network?) : State()
        data class Failed(val reason: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val manager: WifiP2pManager? =
        appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** True once we've begun resolving the connection, so repeat broadcasts don't restart it. */
    private var resolving = false

    /** Names that identify a glasses peer during discovery (prefix match, case-insensitive). */
    private val glassesNamePrefixes = listOf("glass", "qglass", "heycyan", "aw_eis", "e03", "cyan")

    /**
     * Begin discovery and connect to the first glasses-like peer found. Progress and the final
     * result arrive on [state]. Call [disconnect] when finished to release the P2P group.
     */
    @SuppressLint("MissingPermission") // callers gate on location/nearby-wifi permission
    fun connect() {
        val mgr = manager
        if (mgr == null) {
            _state.value = State.Failed("Wi-Fi Direct not supported on this device")
            return
        }
        if (channel != null) {
            Log.i(TAG, "connect() ignored; already active in ${_state.value}")
            return
        }
        channel = mgr.initialize(appContext, appContext.mainLooper, null)
        registerReceiver()

        // Tell the glasses over BLE to enter Wi-Fi transfer mode (start their P2P group), THEN — after
        // a short beat for the group to come up — discover. Discovering too early finds nothing. The
        // command + expected reply (dataType==1, glassWorkType==4) were captured from the vendor app.
        _state.value = State.Discovering
        triggerTransferMode()
        scope.launch {
            delay(TRANSFER_MODE_SETTLE_MS)
            startDiscovery()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        val mgr = manager ?: return
        val ch = channel ?: return
        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.i(TAG, "peer discovery started") }
            override fun onFailure(reason: Int) {
                _state.value = State.Failed("Discovery failed (${reasonName(reason)})")
            }
        })
    }

    /**
     * Send the BLE command that puts the glasses into Wi-Fi transfer mode. Payload
     * `[0x02, 0x01, 0x04, 0x01]` mirrors the vendor app's `importAlbum()`; the device replies with
     * `dataType==1, glassWorkType==4` and brings up its Wi-Fi Direct group.
     */
    private fun triggerTransferMode() {
        runCatching {
            com.oudmon.ble.base.communication.LargeDataHandler.getInstance()
                .glassesControl(byteArrayOf(0x02, 0x01, 0x04, 0x01)) { _, rsp ->
                    Log.i(TAG, "transfer-mode reply: dataType=${rsp.dataType} workType=${rsp.glassWorkType} err=${rsp.errorCode}")
                }
        }.onFailure { Log.w(TAG, "triggerTransferMode failed: ${it.message}") }
    }

    /** Tear down the P2P group and unregister. Safe to call repeatedly. */
    fun disconnect() {
        // Cancel any in-flight discovery/IP-probe coroutines so they can't write state on a dead link.
        scope.coroutineContext.cancelChildren()
        val mgr = manager
        val ch = channel
        if (mgr != null && ch != null) {
            runCatching {
                mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {}
                    override fun onFailure(reason: Int) {}
                })
            }
        }
        unregisterReceiver()
        channel = null
        resolving = false
        _state.value = State.Idle
    }

    // ---------------------------------------------------------------- internals

    @SuppressLint("MissingPermission")
    private fun onPeersAvailable(devices: Collection<WifiP2pDevice>) {
        if (_state.value is State.Connecting || _state.value is State.Connected) return
        val glasses = devices.firstOrNull { it.deviceName.isGlasses() }
        if (glasses == null) {
            Log.i(TAG, "peers found but none look like glasses: ${devices.map { it.deviceName }}")
            return
        }
        _state.value = State.PeerFound(glasses.deviceName)
        // Force the GLASSES to be the group owner (intent 0 = we refuse the role). Then their media
        // server is reachable at the fixed group-owner address 192.168.49.1. When the phone becomes
        // GO instead, the glasses stay a passive L2 peer with no routable IP (observed on this model).
        val config = WifiP2pConfig().apply {
            deviceAddress = glasses.deviceAddress
            groupOwnerIntent = 0
        }

        val mgr = manager ?: return
        val ch = channel ?: return
        _state.value = State.Connecting
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { Log.i(TAG, "connect request sent to ${glasses.deviceName}") }
            override fun onFailure(reason: Int) {
                _state.value = State.Failed("Connect failed (${reasonName(reason)})")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun onConnectionInfo(info: WifiP2pInfo) {
        if (!info.groupFormed) return
        // WIFI_P2P_CONNECTION_CHANGED can fire repeatedly; once we've started resolving the link,
        // ignore further firings so we don't launch overlapping IP-probe sweeps or re-transition.
        if (resolving || _state.value is State.Connected) return
        resolving = true
        val goIp = info.groupOwnerAddress?.hostAddress
        Log.i(TAG, "connected: groupOwner=$goIp isGroupOwner=${info.isGroupOwner}")

        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) return

        mgr.requestGroupInfo(ch) { group ->
            val iface = group?.`interface`
            val network = boundP2pNetwork(iface)
            if (!info.isGroupOwner) {
                // Glasses are the group owner — their address is groupOwnerAddress, available now.
                if (goIp.isNullOrBlank()) {
                    _state.value = State.Failed("Connected but no group owner address")
                } else {
                    Log.i(TAG, "glasses server ip = $goIp (glasses are GO)")
                    _state.value = State.Connected(goIp, network)
                }
            } else {
                // WE are the group owner — the glasses are a client whose IP appears once they send
                // traffic. Poll ARP (nudging the subnet) until it shows up.
                scope.launch { resolveClientIp(iface, network) }
            }
        }
    }

    /**
     * Find the glasses' client IP when the phone is the P2P group owner. `/proc/net/arp` is usually
     * unreadable to sandboxed apps on modern Android, so instead we probe the Wi-Fi Direct DHCP range
     * directly: the glasses run their HTTP server on port 80, so a successful TCP connect identifies
     * them. Android hands out .49.x leases; we try the address the vendor app used first, then sweep.
     */
    private suspend fun resolveClientIp(iface: String?, network: Network?) {
        // ARP first (cheap when it works), then a direct port-80 probe of the DHCP range.
        repeat(CLIENT_IP_ATTEMPTS) { attempt ->
            val ip = glassesClientIp(iface) ?: probePort80(network)
            if (!ip.isNullOrBlank()) {
                Log.i(TAG, "glasses server ip = $ip (client, attempt ${attempt + 1})")
                _state.value = State.Connected(ip, network)
                return
            }
            delay(CLIENT_IP_POLL_MS)
        }
        _state.value = State.Failed("Connected, but the glasses never came online on the link")
    }

    /**
     * Probe the Wi-Fi Direct DHCP range for a host answering on port 80 (the glasses' server), bound
     * to the P2P [network]. Tries the vendor app's observed address (.43) first, then the rest of the
     * small lease range concurrently. Returns the first responder's IP.
     */
    private suspend fun probePort80(network: Network?): String? = withContext(Dispatchers.IO) {
        val hosts = buildList { add(43); addAll(2..40) }
        kotlinx.coroutines.coroutineScope {
            hosts.map { host ->
                async {
                    val ip = "192.168.49.$host"
                    val ok = runCatching {
                        val socket = network?.socketFactory?.createSocket() ?: java.net.Socket()
                        socket.use { it.connect(java.net.InetSocketAddress(ip, 80), PROBE_TIMEOUT_MS); true }
                    }.getOrDefault(false)
                    if (ok) ip else null
                }
            }.awaitAll().firstOrNull { it != null }
        }
    }

    /**
     * Read the glasses' IP from the kernel ARP table when available: a complete entry on the P2P
     * interface in the Wi-Fi Direct subnet, excluding our own group-owner address. Often empty on
     * modern Android (sandbox) — [probePort80] is the fallback.
     */
    private fun glassesClientIp(ifaceName: String?): String? = runCatching {
        java.io.File("/proc/net/arp").readLines()
            .drop(1)
            .mapNotNull { line ->
                val cols = line.trim().split(Regex("\\s+"))
                if (cols.size < 6) return@mapNotNull null
                val ip = cols[0]; val flags = cols[2]; val dev = cols[5]
                val ifaceOk = ifaceName == null || dev == ifaceName
                if (ifaceOk && flags != "0x0" && ip.startsWith("192.168.49.")) ip else null
            }
            .firstOrNull { it != "192.168.49.1" }
    }.getOrNull()

    /**
     * Resolve the Wi-Fi Direct group's [Network] so download sockets bind to it rather than infra
     * Wi-Fi or mobile data. The P2P group is a Wi-Fi transport WITHOUT internet capability, on the
     * interface named by [ifaceName] (e.g. `p2p-wlan0-0`) — both are used to distinguish it from a
     * regular Wi-Fi network the phone may also be on. Returns null if it can't be identified (the
     * caller then falls back to the default network, which still works when P2P is the only link).
     */
    private fun boundP2pNetwork(ifaceName: String?): Network? {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? android.net.ConnectivityManager ?: return null
        return runCatching {
            val wifiNets = cm.allNetworks.filter { net ->
                cm.getNetworkCapabilities(net)
                    ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            // Prefer an exact interface-name match; that is unambiguously the P2P group.
            ifaceName?.let { name ->
                wifiNets.firstOrNull { cm.getLinkProperties(it)?.interfaceName == name }
            }
                // Otherwise pick a Wi-Fi network that lacks internet — infra Wi-Fi has internet,
                // the P2P group does not.
                ?: wifiNets.firstOrNull { net ->
                    cm.getNetworkCapabilities(net)
                        ?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == false
                }
        }.getOrNull()
    }

    private fun String?.isGlasses(): Boolean {
        val n = this?.lowercase() ?: return false
        return glassesNamePrefixes.any { n.contains(it) }
    }

    private fun registerReceiver() {
        if (receiver != null) return
        val mgr = manager ?: return
        val ch = channel ?: return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION ->
                        mgr.requestPeers(ch) { peers -> onPeersAvailable(peers.deviceList) }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION ->
                        mgr.requestConnectionInfo(ch) { info -> onConnectionInfo(info) }
                }
            }
        }
        receiver = r
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(r, filter, flags)
        } else {
            appContext.registerReceiver(r, filter)
        }
    }

    private fun unregisterReceiver() {
        receiver?.let { runCatching { appContext.unregisterReceiver(it) } }
        receiver = null
    }

    private fun reasonName(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
        WifiP2pManager.BUSY -> "busy"
        WifiP2pManager.ERROR -> "error"
        else -> "reason $reason"
    }

    companion object {
        private const val TAG = "WifiDirectConnection"
        /** Grace period after the BLE trigger for the glasses to bring up their P2P group. */
        private const val TRANSFER_MODE_SETTLE_MS = 3_000L
        /** Rounds to find the glasses' client IP (ARP read, else port-80 probe of the range). */
        private const val CLIENT_IP_ATTEMPTS = 8
        private const val CLIENT_IP_POLL_MS = 1_500L
        /** Per-host TCP connect timeout when probing the DHCP range for the glasses' server. */
        private const val PROBE_TIMEOUT_MS = 400
    }
}
