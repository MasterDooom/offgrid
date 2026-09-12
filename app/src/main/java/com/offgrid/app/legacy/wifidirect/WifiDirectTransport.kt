package com.offgrid.app.legacy.wifidirect

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WifiDirectTransport(private val context: Context) : TransportLayer {
    private val manager = context.getSystemService(WifiP2pManager::class.java)
    private val channel = manager.initialize(context, context.mainLooper) { log("P2P framework channel lost") }
    private val socket = SocketChannel()
    private val devices = mutableMapOf<String, WifiP2pDevice>()
    private var receiverRegistered = false
    private val _peers = MutableStateFlow<List<Node>>(emptyList())
    override val peers: StateFlow<List<Node>> = _peers.asStateFlow()
    private val _connection = MutableStateFlow(ConnectionState.UNAVAILABLE)
    override val connection: StateFlow<ConnectionState> = _connection.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val events: SharedFlow<String> = _events.asSharedFlow()
    val incoming = socket.incoming

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    log(if (enabled) "Wi-Fi Direct enabled" else "Wi-Fi Direct disabled")
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeersInternal()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION") val info = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (info?.isConnected == true) requestConnectionInfo() else disconnected("Wi-Fi Direct group disconnected")
                }
            }
        }
    }

    fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    fun unregisterReceiver() { if (receiverRegistered) { context.unregisterReceiver(receiver); receiverRegistered = false } }

    @SuppressLint("MissingPermission")
    override suspend fun discoverPeers() = withContext(Dispatchers.Main) {
        if (!hasRuntimePermission()) return@withContext log("Discovery blocked: nearby-device permission is missing")
        manager.discoverPeers(channel, listener("Peer discovery started") { reason -> "Discovery failed: $reason" })
    }

    @SuppressLint("MissingPermission")
    override suspend fun connect(peer: Node) = withContext(Dispatchers.Main) {
        if (!hasRuntimePermission()) return@withContext log("Connection blocked: nearby-device permission is missing")
        val device = devices[peer.nodeId] ?: error("Peer is no longer available")
        _connection.value = ConnectionState.CONNECTING
        manager.connect(channel, WifiP2pConfig().apply { deviceAddress = device.deviceAddress }, listener("Connection negotiation started") { reason ->
            _connection.value = ConnectionState.DISCOVERED; "Connection failed: $reason"
        })
    }

    @SuppressLint("MissingPermission")
    override suspend fun disconnect() = withContext(Dispatchers.Main) {
        if (!hasRuntimePermission()) return@withContext log("Disconnect blocked: nearby-device permission is missing")
        manager.removeGroup(channel, listener("Wi-Fi Direct group removed") { "Remove group failed: $it" })
        disconnected("Disconnected")
    }

    override suspend fun send(packet: Packet) { socket.send(packet); log("Sent packet ${packet.messageId.take(8)}") }

    @SuppressLint("MissingPermission")
    private fun requestPeersInternal() {
        if (!hasRuntimePermission()) { log("Peer list request blocked: nearby-device permission is missing"); return }
        manager.requestPeers(channel) { list ->
            devices.clear(); list.deviceList.forEach { devices[it.deviceAddress] = it }
            _peers.value = list.deviceList.map { Node(it.deviceAddress, it.deviceName.ifBlank { it.deviceAddress }, ConnectionState.DISCOVERED) }
            log("Found ${_peers.value.size} peer(s)")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        if (!hasRuntimePermission()) { log("Connection-info request blocked: nearby-device permission is missing"); return }
        manager.requestConnectionInfo(channel) { info -> onConnectionInfo(info) }
    }

    private fun onConnectionInfo(info: WifiP2pInfo) {
        if (!info.groupFormed) return
        _connection.value = ConnectionState.CONNECTED
        if (info.isGroupOwner) { socket.startServer(); log("Connected as group owner; listening for socket peer") }
        else {
            val address = info.groupOwnerAddress?.hostAddress
            if (address == null) log("Connection formed but group owner address is missing")
            else kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launchSafe { socket.connect(address); log("Socket connected to group owner $address") }
        }
    }

    private fun disconnected(message: String) { socket.close(); _connection.value = ConnectionState.UNAVAILABLE; log(message) }
    private fun hasRuntimePermission(): Boolean {
        val permission = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }
    private fun listener(success: String, failure: (Int) -> String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = log(success)
        override fun onFailure(reason: Int) = log(failure(reason))
    }
    private fun log(message: String) { Log.i("OffGridP2P", message); _events.tryEmit(message) }
    fun dispose() { unregisterReceiver(); socket.dispose() }
}

private fun kotlinx.coroutines.CoroutineScope.launchSafe(block: suspend () -> Unit) = launch {
    runCatching { block() }.onFailure { Log.e("OffGridP2P", "Socket connection failed", it) }
}
