package com.offgrid.app.data.transport

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.offgrid.app.data.model.LinkState
import com.offgrid.app.data.model.Message
import com.offgrid.app.data.model.MessageStatus
import com.offgrid.app.data.model.MessageType
import com.offgrid.app.data.model.Node
import com.offgrid.app.data.model.NodeStatus
import com.offgrid.app.data.security.HopEncryption
import com.offgrid.app.data.security.HopPacket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Native Android Wi-Fi P2P transport. */
class WifiDirectCommunicationTransport(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : CommunicationTransport {
    companion object {
        private const val PORT = 45871
        private const val HELLO = "OFFGRID_WIFI_P2P_V1"
        private const val MAX_PACKET_BYTES = 16 * 1024
    }

    data class Diagnostics(
        val wifiEnabled: Boolean = false,
        val p2pEnabled: Boolean = false,
        val permissionGranted: Boolean = false,
        val locationModeEnabled: Boolean = false,
        val androidPeerCount: Int = 0,
        val lastDiscoveryResult: String = "Not started",
        val lastDiscoveryAt: Long? = null,
        val packetsSent: Long = 0,
        val packetsReceived: Long = 0,
        val packetsFailed: Long = 0,
        val connections: Long = 0,
        val disconnects: Long = 0,
        val lastSendLatencyMs: Long? = null,
    )

    private data class Peer(
        val key: String,
        val logicalId: String,
        val name: String,
        val address: String?,
        val socket: Socket,
        val writer: BufferedWriter,
    )

    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val channel = manager?.initialize(app, Looper.getMainLooper(), null)
    private val _nodes = MutableStateFlow<List<Node>>(emptyList())
    override val discoveredNodes = _nodes.asStateFlow()
    private val _linkState = MutableStateFlow(LinkState.OFFLINE)
    override val linkState = _linkState.asStateFlow()
    private val _incoming = MutableSharedFlow<Message>(extraBufferCapacity = 256)
    override val incomingMessages = _incoming.asSharedFlow()
    private val _status = MutableStateFlow("Wi-Fi P2P idle")
    val transportStatus = _status.asStateFlow()
    private val _diagnostics = MutableStateFlow(Diagnostics())
    val diagnostics = _diagnostics.asStateFlow()

    private val peers = ConcurrentHashMap<String, Peer>()
    private val pendingSockets = ConcurrentHashMap<String, Socket>()
    private val pendingWriters = ConcurrentHashMap<String, BufferedWriter>()
    private val logicalToPeer = ConcurrentHashMap<String, String>()
    private val addressToPeer = ConcurrentHashMap<String, String>()
    private val writerLocks = ConcurrentHashMap<String, Mutex>()
    private val connectionWaiters = ConcurrentHashMap<String, CompletableDeferred<Result<Unit>>>()
    private val seenMessages = ConcurrentHashMap.newKeySet<String>()
    private val sendMutex = Mutex()

    private var selfId = ""
    private var selfName = ""
    private var registered = false
    private var server: ServerSocket? = null
    private var discoveryJob: Job? = null
    private var pendingAddress: String? = null

    private val peerListener = WifiP2pManager.PeerListListener { list: WifiP2pDeviceList ->
        val result = list.deviceList.filter { it.deviceAddress.isNotBlank() && !it.deviceAddress.equals(selfId, true) }
        _nodes.value = result.map { d ->
            val p = addressToPeer[d.deviceAddress]?.let(peers::get)
            Node(
                id = d.deviceAddress,
                name = p?.name ?: d.deviceName.ifBlank { "Wi-Fi P2P device" },
                status = if (p != null) NodeStatus.CONNECTED else if (d.status == WifiP2pDevice.INVITED) NodeStatus.CONNECTING else NodeStatus.AVAILABLE,
                hops = 1,
                isSimulated = false,
                logicalId = p?.logicalId ?: d.deviceAddress,
            )
        }
        updateDiagnostics(peerCount = result.size)
        _status.value = "Wi-Fi P2P peers: ${result.size}"
    }

    private val connectionListener = WifiP2pManager.ConnectionInfoListener { info: WifiP2pInfo ->
        if (!info.groupFormed) {
            _linkState.value = LinkState.OFFLINE
            return@ConnectionInfoListener
        }
        _linkState.value = LinkState.CONNECTED
        if (info.isGroupOwner) {
            _status.value = "Wi-Fi P2P group ready • listening"
            startServer()
        } else {
            val host = info.groupOwnerAddress?.hostAddress
            if (host != null) connectToOwnerWithRetry(host)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, 1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    updateDiagnostics(p2pEnabled = enabled)
                    if (enabled) startDiscoveryLoop() else _linkState.value = LinkState.OFFLINE
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
            }
        }
    }

    override suspend fun start(selfId: String, selfName: String) {
        requirePermission()
        this.selfId = selfId
        this.selfName = selfName
        if (!registered) {
            registerReceiver()
            refreshDiagnostics()
        }
        requestPeers()
        requestConnectionInfo()
        startDiscoveryLoop()
    }

    override suspend fun discoverDevices() {
        requirePermission()
        requestPeers()
        discoverOnce()
        requestPeers()
        startDiscoveryLoop()
    }

    private fun startDiscoveryLoop() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch {
            while (registered) {
                discoverOnce()
                delay(5_000)
            }
        }
    }

    private suspend fun discoverOnce(): Boolean = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext false
        if (!isLocationModeEnabled()) {
            recordDiscovery("BLOCKED: Location Mode is OFF")
            return@withContext false
        }
        if (wifi?.isWifiEnabled == false) {
            recordDiscovery("BLOCKED: Wi-Fi is OFF")
            return@withContext false
        }
        val m = manager ?: return@withContext false
        val ch = channel ?: return@withContext false
        val done = CompletableDeferred<Boolean>()
        runCatching {
            m.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    recordDiscovery("SUCCESS: discovery started")
                    done.complete(true)
                    scope.launch { delay(300); requestPeers() }
                }
                override fun onFailure(reason: Int) {
                    if (reason == WifiP2pManager.BUSY) {
                        m.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() { done.complete(false) }
                            override fun onFailure(reason: Int) { done.complete(false) }
                        })
                    } else {
                        recordDiscovery("FAILED: ${reasonText(reason)}")
                        done.complete(false)
                    }
                }
            })
        }.onFailure { done.complete(false) }
        done.await()
    }

    override suspend fun connectToDevice(node: Node): Result<Unit> = withContext(Dispatchers.IO) {
        requirePermission()
        val address = node.id.trim()
        if (address.isBlank()) return@withContext Result.failure(IllegalArgumentException("Peer address is empty"))
        addressToPeer[address]?.let { if (peers[it] != null) return@withContext Result.success(Unit) }
        connectionWaiters[address]?.let { return@withContext awaitConnection(address, it) }
        val waiter = CompletableDeferred<Result<Unit>>()
        connectionWaiters[address] = waiter
        pendingAddress = address
        _linkState.value = LinkState.CONNECTING
        val m = manager ?: return@withContext failWaiter(address, IllegalStateException("Wi-Fi P2P unavailable"))
        val ch = channel ?: return@withContext failWaiter(address, IllegalStateException("Wi-Fi P2P channel unavailable"))
        runCatching {
            m.connect(ch, WifiP2pConfig().apply { deviceAddress = address }, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _status.value = "P2P connection requested • waiting for socket"
                    requestConnectionInfo()
                }
                override fun onFailure(reason: Int) {
                    failWaiter(address, IllegalStateException("P2P connect failed: ${reasonText(reason)}"))
                }
            })
        }.onFailure { failWaiter(address, it) }
        awaitConnection(address, waiter)
    }

    private suspend fun awaitConnection(address: String, waiter: CompletableDeferred<Result<Unit>>): Result<Unit> {
        val result = withTimeoutOrNull(12_000) { waiter.await() }
            ?: Result.failure(IllegalStateException("Timed out waiting for Wi-Fi P2P data channel"))
        connectionWaiters.remove(address)
        if (pendingAddress == address) pendingAddress = null
        return result
    }

    private fun failWaiter(address: String, error: Throwable): Result<Unit> {
        pendingAddress = null
        connectionWaiters.remove(address)?.complete(Result.failure(error))
        _linkState.value = LinkState.OFFLINE
        return Result.failure(error)
    }

    override suspend fun sendMessage(message: Message): Result<Unit> = sendMutex.withLock {
        val requested = message.recipientNodeId ?: message.receiverId
        var peer = findPeer(message.receiverId, requested)
        if (peer == null) {
            val node = _nodes.value.firstOrNull { it.id.equals(message.receiverId, true) || it.logicalId == requested }
            if (node != null) {
                val connected = connectToDevice(node)
                if (connected.isSuccess) peer = findPeer(node.id, node.logicalId)
            }
        }
        if (peer == null) return@withLock Result.failure(IllegalStateException("No active Wi-Fi P2P data channel to $requested"))

        val destination = peer.logicalId
        val payload = JSONObject().apply {
            put("kind", "OFFGRID_MESSAGE")
            put("id", message.id)
            put("senderId", message.senderId)
            put("receiverId", message.receiverId)
            put("recipientNodeId", destination)
            put("content", message.content)
            put("timestamp", message.timestamp)
            put("status", message.status.name)
            put("type", message.type.name)
        }.toString().toByteArray(Charsets.UTF_8)
        val packet = HopPacket(
            messageId = message.id,
            sourceNodeId = selfId,
            destinationNodeId = destination,
            previousHopId = selfId,
            hopCount = 0,
            maxHops = 1,
            ciphertext = HopEncryption.encrypt(payload, HopEncryption.linkKey(selfId, destination)),
            path = listOf(selfId),
        )
        val start = System.nanoTime()
        writeLine(peer, String(packet.toJson(), Charsets.UTF_8), true).also {
            if (it.isSuccess) {
                _status.value = "Message sent • ${destination.takeLast(6)}"
                updateDiagnostics(latencyMs = (System.nanoTime() - start) / 1_000_000)
            }
        }
    }

    private fun findPeer(address: String, logical: String): Peer? =
        addressToPeer[address]?.let(peers::get) ?: logicalToPeer[logical]?.let(peers::get)

    private fun startServer() {
        if (server?.isClosed == false) return
        scope.launch {
            runCatching {
                val listening = ServerSocket(PORT)
                server = listening
                while (!listening.isClosed) handleSocket(listening.accept())
            }.onFailure { _status.value = "Socket server stopped • ${it.message ?: "error"}" }
        }
    }

    private fun connectToOwnerWithRetry(host: String) {
        scope.launch {
            var last: Throwable? = null
            repeat(8) { attempt ->
                if (peers.values.any { it.socket.isConnected && !it.socket.isClosed }) return@launch
                runCatching {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(host, PORT), 2_000)
                    handleSocket(socket)
                    return@launch
                }.onFailure { last = it }
                delay(250L * (attempt + 1))
            }
            _status.value = "P2P group exists but data socket failed • ${last?.message ?: "retry later"}"
        }
    }

    private fun handleSocket(socket: Socket) {
        scope.launch {
            val key = "${socket.remoteSocketAddress}#${System.nanoTime()}"
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            pendingSockets[key] = socket
            pendingWriters[key] = writer
            updateDiagnostics(connection = true)
            try {
                sendHello(writer)
                while (!socket.isClosed) {
                    val line = reader.readLine() ?: break
                    if (line.toByteArray(Charsets.UTF_8).size > MAX_PACKET_BYTES) continue
                    handleLine(key, line)
                }
            } catch (_: Throwable) {
                _status.value = "Wi-Fi P2P socket disconnected"
            } finally {
                removePeer(key)
                runCatching { socket.close() }
            }
        }
    }

    private fun sendHello(writer: BufferedWriter) {
        writer.write(JSONObject().apply {
            put("kind", "OFFGRID_HELLO")
            put("protocol", HELLO)
            put("nodeId", selfId)
            put("name", selfName)
        }.toString())
        writer.newLine()
        writer.flush()
    }

    private suspend fun handleLine(key: String, line: String) {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return
        when (json.optString("kind")) {
            "OFFGRID_HELLO" -> registerPeer(key, json)
            HopPacket.KIND -> receivePacket(key, line.toByteArray(Charsets.UTF_8))
        }
    }

    private fun registerPeer(key: String, json: JSONObject) {
        if (json.optString("protocol") != HELLO) return
        val logical = json.optString("nodeId").takeIf { it.isNotBlank() } ?: return
        if (logical == selfId) return
        val socket = pendingSockets[key] ?: return
        val writer = pendingWriters[key] ?: return
        val address = pendingAddress
            ?: _nodes.value.firstOrNull { it.logicalId == logical }?.id
            ?: socket.inetAddress?.hostAddress
        val peer = Peer(key, logical, json.optString("name").ifBlank { "OffGrid device" }, address, socket, writer)
        peers[key]?.let { removePeer(it.key) }
        peers[key] = peer
        pendingSockets.remove(key)
        pendingWriters.remove(key)
        logicalToPeer[logical] = key
        address?.let { addressToPeer[it] = key }
        _linkState.value = LinkState.CONNECTED
        _status.value = "Wi-Fi P2P data channel ready • ${peer.name}"
        refreshNodes()
        pendingAddress?.let { addr -> connectionWaiters.remove(addr)?.complete(Result.success(Unit)) }
        pendingAddress = null
    }

    private suspend fun receivePacket(key: String, bytes: ByteArray) {
        val peer = peers[key] ?: return
        val packet = HopPacket.fromJson(bytes) ?: return
        if (!seenMessages.add(packet.messageId)) return
        val plaintext = runCatching {
            HopEncryption.decrypt(packet.ciphertext, HopEncryption.linkKey(peer.logicalId, selfId))
        }.getOrElse {
            _status.value = "Encrypted message rejected"
            return
        }
        updateDiagnostics(received = true)
        val json = runCatching { JSONObject(plaintext.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        if (json.optString("kind") != "OFFGRID_MESSAGE") return
        val sender = json.optString("senderId")
        val receiver = json.optString("receiverId")
        val type = runCatching { MessageType.valueOf(json.optString("type", "TEXT")) }.getOrDefault(MessageType.TEXT)
        _incoming.tryEmit(Message(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            conversationId = sender,
            senderId = sender,
            receiverId = receiver,
            content = json.optString("content"),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            status = MessageStatus.RECEIVED,
            type = type,
            recipientNodeId = selfId,
            hopCount = packet.hopCount + 1,
            hopEncrypted = true,
        ))
        _status.value = "Message received • from ${sender.takeLast(6)}"
    }

    private suspend fun writeLine(peer: Peer, line: String, packet: Boolean): Result<Unit> {
        val lock = writerLocks.computeIfAbsent(peer.key) { Mutex() }
        return lock.withLock {
            runCatching {
                peer.writer.write(line)
                peer.writer.newLine()
                peer.writer.flush()
            }.fold(
                { if (packet) updateDiagnostics(sent = true); Result.success(Unit) },
                { error -> if (packet) updateDiagnostics(failed = true); removePeer(peer.key); Result.failure(error) },
            )
        }
    }

    private fun removePeer(key: String) {
        pendingSockets.remove(key)?.let { runCatching { it.close() } }
        pendingWriters.remove(key)
        val peer = peers.remove(key) ?: return
        if (logicalToPeer[peer.logicalId] == key) logicalToPeer.remove(peer.logicalId)
        peer.address?.let { if (addressToPeer[it] == key) addressToPeer.remove(it) }
        writerLocks.remove(key)
        updateDiagnostics(disconnect = true)
        refreshNodes()
    }

    private fun refreshNodes() {
        _nodes.value = _nodes.value.map { node ->
            val p = findPeer(node.id, node.logicalId)
            node.copy(
                name = p?.name ?: node.name,
                status = if (p != null) NodeStatus.CONNECTED else node.status,
                logicalId = p?.logicalId ?: node.logicalId,
            )
        }
    }

    override fun stop() {
        discoveryJob?.cancel(); discoveryJob = null
        peers.values.forEach { runCatching { it.socket.close() } }
        pendingSockets.values.forEach { runCatching { it.close() } }
        peers.clear(); pendingSockets.clear(); pendingWriters.clear()
        logicalToPeer.clear(); addressToPeer.clear(); writerLocks.clear()
        connectionWaiters.values.forEach { it.cancel() }; connectionWaiters.clear()
        runCatching { server?.close() }; server = null
        if (registered) runCatching { app.unregisterReceiver(receiver) }
        registered = false
        _nodes.value = emptyList(); _linkState.value = LinkState.OFFLINE
    }

    private fun registerReceiver() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    private fun requestPeers() {
        if (!hasPermission() || !isLocationModeEnabled()) return
        manager?.requestPeers(channel, peerListener)
    }

    private fun requestConnectionInfo() {
        if (!hasPermission()) return
        manager?.requestConnectionInfo(channel, connectionListener)
    }

    private fun hasPermission(): Boolean {
        val nearby = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(app, nearby) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.CHANGE_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requirePermission() { if (!hasPermission()) throw SecurityException("Required Wi-Fi P2P permission is not granted") }

    private fun isLocationModeEnabled(): Boolean = runCatching {
        Settings.Secure.getInt(app.contentResolver, Settings.Secure.LOCATION_MODE) != Settings.Secure.LOCATION_MODE_OFF
    }.getOrDefault(false)

    private fun refreshDiagnostics(peerCount: Int = _nodes.value.size) {
        _diagnostics.value = _diagnostics.value.copy(
            wifiEnabled = wifi?.isWifiEnabled == true,
            permissionGranted = hasPermission(),
            locationModeEnabled = isLocationModeEnabled(),
            androidPeerCount = peerCount,
        )
    }

    private fun recordDiscovery(result: String) {
        _diagnostics.value = _diagnostics.value.copy(
            wifiEnabled = wifi?.isWifiEnabled == true,
            permissionGranted = hasPermission(),
            locationModeEnabled = isLocationModeEnabled(),
            lastDiscoveryResult = result,
            lastDiscoveryAt = System.currentTimeMillis(),
            androidPeerCount = _nodes.value.size,
        )
        _status.value = "Wi-Fi P2P • $result"
    }

    private fun updateDiagnostics(
        sent: Boolean = false,
        received: Boolean = false,
        failed: Boolean = false,
        connection: Boolean = false,
        disconnect: Boolean = false,
        latencyMs: Long? = null,
        peerCount: Int? = null,
        p2pEnabled: Boolean? = null,
    ) {
        _diagnostics.value = _diagnostics.value.copy(
            wifiEnabled = wifi?.isWifiEnabled == true,
            p2pEnabled = p2pEnabled ?: _diagnostics.value.p2pEnabled,
            permissionGranted = hasPermission(),
            locationModeEnabled = isLocationModeEnabled(),
            androidPeerCount = peerCount ?: _diagnostics.value.androidPeerCount,
            packetsSent = _diagnostics.value.packetsSent + if (sent) 1 else 0,
            packetsReceived = _diagnostics.value.packetsReceived + if (received) 1 else 0,
            packetsFailed = _diagnostics.value.packetsFailed + if (failed) 1 else 0,
            connections = _diagnostics.value.connections + if (connection) 1 else 0,
            disconnects = _diagnostics.value.disconnects + if (disconnect) 1 else 0,
            lastSendLatencyMs = latencyMs ?: _diagnostics.value.lastSendLatencyMs,
        )
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
        WifiP2pManager.ERROR -> "internal error"
        WifiP2pManager.BUSY -> "framework busy"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
        else -> "error $reason"
    }
}
