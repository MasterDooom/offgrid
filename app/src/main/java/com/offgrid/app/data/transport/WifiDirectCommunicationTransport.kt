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
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
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

/**
 * Native Android Wi-Fi P2P transport for OFFGRID.
 *
 * Wi-Fi P2P is the physical link. HopRouter decides the logical next hop and HopEncryption protects
 * every message hop. The transport keeps Android device addresses separate from stable OffGrid IDs.
 *
 * Direct range is hardware/environment dependent; this class deliberately makes no fixed range claim.
 */
class WifiDirectCommunicationTransport(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : CommunicationTransport {

    companion object {
        private const val PORT = 45871
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val MAX_PACKET_BYTES = 16 * 1024
        private const val HELLO = "OFFGRID_WIFI_P2P_V1"
        private const val ROUTE_ANNOUNCEMENT = "OFFGRID_ROUTES_V1"
        private const val DISCOVERY_RETRY_MS = 4_000L
        private const val DISCOVERY_REFRESH_MS = 12_000L
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
    ) {
        val deliveryAttemptRatePercent: Double
            get() = if (packetsSent == 0L) 0.0
            else (packetsSent - packetsFailed).coerceAtLeast(0).toDouble() / packetsSent * 100.0
    }

    private data class PeerConnection(
        val socketKey: String,
        val deviceAddress: String?,
        val logicalId: String,
        val name: String,
        val socket: Socket,
        val writer: BufferedWriter,
    )

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val channel = manager?.initialize(appContext, Looper.getMainLooper(), null)

    private val _nodes = MutableStateFlow<List<Node>>(emptyList())
    override val discoveredNodes = _nodes.asStateFlow()

    private val _linkState = MutableStateFlow(LinkState.OFFLINE)
    override val linkState = _linkState.asStateFlow()

    private val _incoming = MutableSharedFlow<Message>(extraBufferCapacity = 128)
    override val incomingMessages = _incoming.asSharedFlow()

    private val _diagnostics = MutableStateFlow(Diagnostics())
    val diagnostics = _diagnostics.asStateFlow()

    private val _status = MutableStateFlow("Wi-Fi P2P idle")
    val transportStatus = _status.asStateFlow()

    private val router = HopRouter()
    private val peersBySocket = ConcurrentHashMap<String, PeerConnection>()
    private val pendingSockets = ConcurrentHashMap<String, Socket>()
    private val pendingWriters = ConcurrentHashMap<String, BufferedWriter>()
    private val socketByLogicalId = ConcurrentHashMap<String, String>()
    private val socketByDeviceAddress = ConcurrentHashMap<String, String>()
    private val writerLocks = ConcurrentHashMap<String, Mutex>()
    private val seenPackets = ConcurrentHashMap.newKeySet<String>()
    private val processingPackets = ConcurrentHashMap.newKeySet<String>()
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Result<Unit>>>()

    private val sendMutex = Mutex()
    private var selfId = ""
    private var selfName = ""
    private var server: ServerSocket? = null
    private var registered = false
    private var discoveryJob: Job? = null
    private var pendingConnectionAddress: String? = null

    private val peersListener = WifiP2pManager.PeerListListener { list: WifiP2pDeviceList ->
        publishPeers(list.deviceList)
        val count = list.deviceList.size
        updateDiagnostics(peerCount = count)
        _status.value = "Wi-Fi P2P peers updated • Android peers: $count"
    }

    private val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info: WifiP2pInfo ->
        if (!info.groupFormed) {
            _linkState.value = LinkState.OFFLINE
            _status.value = "Wi-Fi P2P group not formed"
            return@ConnectionInfoListener
        }

        _linkState.value = LinkState.CONNECTED
        if (info.isGroupOwner) {
            _status.value = "Wi-Fi P2P group owner • relay ready"
            startServer()
            announceRoutesToPeers()
        } else {
            _status.value = "Wi-Fi P2P connected • opening data channel"
            info.groupOwnerAddress?.hostAddress?.let(::connectToOwner)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(
                        WifiP2pManager.EXTRA_WIFI_STATE,
                        WifiP2pManager.WIFI_P2P_STATE_DISABLED,
                    ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    updateDiagnostics(p2pEnabled = enabled)
                    if (!enabled) {
                        _linkState.value = LinkState.OFFLINE
                        _status.value = "Wi-Fi P2P disabled • turn Wi-Fi on"
                    } else {
                        _status.value = "Wi-Fi P2P enabled • starting discovery"
                        startDiscoveryLoop()
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    _status.value = "Wi-Fi P2P device identity updated"
                }
            }
        }
    }

    override suspend fun start(selfId: String, selfName: String) {
        requirePermission()
        this.selfId = selfId
        this.selfName = selfName
        router.setIdentity(selfId)
        if (registered) return

        registerReceiver()
        refreshDiagnostics()
        requestPeers()
        requestConnectionInfo()
        startDiscoveryLoop()
    }

    override suspend fun discoverDevices() {
        requirePermission()
        refreshDiagnostics()
        requestPeers()
        discoverOnce()
        requestPeers()
        startDiscoveryLoop()
    }

    private fun startDiscoveryLoop() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch {
            var delayMs = 0L
            while (registered) {
                if (delayMs > 0) delay(delayMs)
                val success = discoverOnce()
                delayMs = if (success) DISCOVERY_REFRESH_MS else DISCOVERY_RETRY_MS
            }
        }
    }

    private suspend fun discoverOnce(): Boolean = withContext(Dispatchers.IO) {
        val p2p = manager
        val ch = channel
        if (p2p == null || ch == null) {
            recordDiscovery("UNAVAILABLE: Wi-Fi P2P manager/channel missing")
            return@withContext false
        }
        if (!hasPermission()) {
            recordDiscovery("BLOCKED: required Wi-Fi permission missing")
            return@withContext false
        }
        if (!isLocationModeEnabled()) {
            recordDiscovery("BLOCKED: Location Mode is OFF")
            return@withContext false
        }
        if (wifiManager?.isWifiEnabled == false) {
            recordDiscovery("BLOCKED: Wi-Fi is OFF")
            return@withContext false
        }

        val result = CompletableDeferred<Boolean>()
        runCatching {
            p2p.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    updateDiagnostics(p2pEnabled = true)
                    _status.value = "Wi-Fi P2P discovery started • waiting for peer callback"
                    recordDiscovery("SUCCESS: discoverPeers() started")
                    result.complete(true)
                    scope.launch {
                        delay(350)
                        requestPeers()
                    }
                }

                override fun onFailure(reason: Int) {
                    val reasonText = reasonText(reason)
                    if (reason == WifiP2pManager.BUSY) {
                        _status.value = "Wi-Fi P2P busy • stopping stale discovery and retrying"
                        p2p.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                            override fun onSuccess() = result.complete(false)
                            override fun onFailure(_) = result.complete(false)
                        })
                    } else {
                        recordDiscovery("FAILED: $reasonText")
                        result.complete(false)
                    }
                }
            })
        }.onFailure { error ->
            recordDiscovery("FAILED: ${error.message ?: error.javaClass.simpleName}")
            result.complete(false)
        }
        result.await()
    }

    override suspend fun connectToDevice(node: Node): Result<Unit> = withContext(Dispatchers.IO) {
        requirePermission()
        val address = node.id.trim()
        if (address.isBlank()) return@withContext Result.failure(IllegalArgumentException("Peer address is empty"))

        val p2p = manager ?: return@withContext Result.failure(IllegalStateException("Wi-Fi P2P unavailable"))
        val ch = channel ?: return@withContext Result.failure(IllegalStateException("Wi-Fi P2P channel unavailable"))

        val existingSocket = socketByDeviceAddress[address]
        if (existingSocket != null && peersBySocket[existingSocket] != null) {
            return@withContext Result.success(Unit)
        }

        waiters[address]?.let { return@withContext await(address, it) }

        val waiter = CompletableDeferred<Result<Unit>>()
        waiters[address] = waiter
        pendingConnectionAddress = address
        _linkState.value = LinkState.CONNECTING
        _status.value = "Connecting to ${node.name} over Wi-Fi P2P…"

        val config = WifiP2pConfig().apply { deviceAddress = address }
        runCatching {
            p2p.connect(ch, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _status.value = "Wi-Fi P2P connection requested • waiting for group"
                    requestConnectionInfo()
                }

                override fun onFailure(reason: Int) {
                    pendingConnectionAddress = null
                    waiters.remove(address)?.complete(
                        Result.failure(IllegalStateException("Wi-Fi P2P connect failed: ${reasonText(reason)}"))
                    )
                    _linkState.value = LinkState.OFFLINE
                }
            })
        }.onFailure { error ->
            pendingConnectionAddress = null
            waiters.remove(address)?.complete(Result.failure(error))
            _linkState.value = LinkState.OFFLINE
        }

        await(address, waiter)
    }

    private suspend fun await(
        address: String,
        waiter: CompletableDeferred<Result<Unit>>,
    ): Result<Unit> = try {
        withTimeout(CONNECT_TIMEOUT_MS.toLong()) { waiter.await() }
    } catch (t: Throwable) {
        waiters.remove(address)?.complete(Result.failure(t))
        if (pendingConnectionAddress == address) pendingConnectionAddress = null
        Result.failure(t)
    }

    override suspend fun sendMessage(message: Message): Result<Unit> = sendMutex.withLock {
        val destination = message.recipientNodeId ?: message.receiverId
        val nextHop = router.nextHop(destination, listOf(selfId)) ?: destination
        val connection = peersBySocket.values.firstOrNull { it.logicalId == nextHop }
            ?: return@withLock Result.failure(IllegalStateException("No active Wi-Fi P2P link to $nextHop"))

        val plaintext = messageJson(message, destination).toString().toByteArray(Charsets.UTF_8)
        val packet = HopPacket(
            messageId = message.id,
            sourceNodeId = selfId,
            destinationNodeId = destination,
            previousHopId = selfId,
            hopCount = 0,
            maxHops = 8,
            ciphertext = HopEncryption.encrypt(
                plaintext,
                HopEncryption.linkKey(selfId, nextHop),
            ),
            path = listOf(selfId),
        )

        writePacket(connection, packet).fold(
            onSuccess = {
                _status.value = "Wi-Fi P2P • AES-256-GCM • sent to ${nextHop.takeLast(4)}"
                Result.success(Unit)
            },
            onFailure = { Result.failure(it) },
        )
    }

    override fun stop() {
        discoveryJob?.cancel()
        discoveryJob = null

        peersBySocket.values.forEach { runCatching { it.socket.close() } }
        pendingSockets.values.forEach { runCatching { it.close() } }
        peersBySocket.clear()
        pendingSockets.clear()
        pendingWriters.clear()
        socketByLogicalId.clear()
        socketByDeviceAddress.clear()
        writerLocks.clear()
        seenPackets.clear()
        processingPackets.clear()
        waiters.values.forEach { it.cancel() }
        waiters.clear()
        pendingConnectionAddress = null

        runCatching { server?.close() }
        server = null

        if (registered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            registered = false
        }

        runCatching { manager?.removeGroup(channel, null) }
        _nodes.value = emptyList()
        _linkState.value = LinkState.OFFLINE
        _status.value = "Wi-Fi P2P stopped"
        refreshDiagnostics(peerCount = 0)
    }

    private fun registerReceiver() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        // Wi-Fi P2P broadcasts are system/framework broadcasts. Exported is required so broadcasts
        // from highly privileged framework components are not filtered out on modern Android.
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        registered = true
    }

    private fun requestPeers() {
        val p2p = manager ?: return
        val ch = channel ?: return
        if (!hasPermission()) {
            _status.value = "Wi-Fi P2P peer request blocked • permission missing"
            return
        }
        if (!isLocationModeEnabled()) {
            _status.value = "Wi-Fi P2P peer request blocked • Location Mode is OFF"
            return
        }
        runCatching { p2p.requestPeers(ch, peersListener) }
            .onFailure { error ->
                _status.value = "Wi-Fi P2P requestPeers failed • ${error.message ?: "permission/state error"}"
            }
    }

    private fun requestConnectionInfo() {
        val p2p = manager ?: return
        val ch = channel ?: return
        if (!hasPermission()) return
        runCatching { p2p.requestConnectionInfo(ch, connectionInfoListener) }
    }

    private fun publishPeers(devices: Collection<WifiP2pDevice>) {
        _nodes.value = devices
            .filter { it.deviceAddress.isNotBlank() && !it.deviceAddress.equals(selfId, ignoreCase = true) }
            .map { device ->
                val connection = socketByDeviceAddress[device.deviceAddress]?.let { peersBySocket[it] }
                val logicalId = connection?.logicalId ?: device.deviceAddress
                Node(
                    id = device.deviceAddress,
                    name = connection?.name ?: device.deviceName.ifBlank { "Wi-Fi P2P device" },
                    status = when {
                        connection != null -> NodeStatus.CONNECTED
                        device.status == WifiP2pDevice.INVITED -> NodeStatus.CONNECTING
                        else -> NodeStatus.AVAILABLE
                    },
                    lastSeen = System.currentTimeMillis(),
                    hops = router.routeTo(logicalId)?.distance ?: 1,
                    isSimulated = false,
                    logicalId = logicalId,
                )
            }
            .distinctBy { it.id }
    }

    private fun refreshConnectedNodes() {
        _nodes.value = _nodes.value.map { node ->
            val connection = socketByDeviceAddress[node.id]?.let { peersBySocket[it] }
            node.copy(
                name = connection?.name ?: node.name,
                status = if (connection != null) NodeStatus.CONNECTED else node.status,
                logicalId = connection?.logicalId ?: node.logicalId,
                hops = router.routeTo(connection?.logicalId ?: node.logicalId)?.distance ?: node.hops,
            )
        }
    }

    private fun startServer() {
        if (server?.isClosed == false) return
        scope.launch {
            runCatching {
                ServerSocket(PORT).also { server = it }.use { listening ->
                    while (!listening.isClosed) handleSocket(listening.accept())
                }
            }.onFailure {
                _status.value = "Wi-Fi P2P server stopped: ${it.message ?: "socket error"}"
            }
        }
    }

    private fun connectToOwner(host: String) {
        scope.launch {
            runCatching {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
                handleSocket(socket)
            }.onFailure {
                _status.value = "Wi-Fi P2P data channel failed: ${it.message ?: "socket error"}"
            }
        }
    }

    private fun handleSocket(socket: Socket) {
        scope.launch {
            val key = socket.remoteSocketAddress.toString() + "#" + System.nanoTime()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            pendingSockets[key] = socket
            pendingWriters[key] = writer
            updateDiagnostics(connection = true)

            runCatching { sendHello(writer) }.onFailure {
                removePeer(key)
                return@launch
            }

            try {
                while (!socket.isClosed) {
                    val line = reader.readLine() ?: break
                    if (line.toByteArray(Charsets.UTF_8).size > MAX_PACKET_BYTES) {
                        _status.value = "Wi-Fi P2P • oversized packet dropped"
                        continue
                    }
                    handleLine(key, line)
                }
            } catch (_: Throwable) {
                _status.value = "Wi-Fi P2P link lost"
            } finally {
                removePeer(key)
                runCatching { socket.close() }
            }
        }
    }

    private fun sendHello(writer: BufferedWriter) {
        writer.write(
            JSONObject().apply {
                put("kind", "OFFGRID_HELLO")
                put("protocol", HELLO)
                put("nodeId", selfId)
                put("name", selfName)
            }.toString()
        )
        writer.newLine()
        writer.flush()
    }

    private suspend fun handleLine(socketKey: String, line: String) {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return
        when (json.optString("kind")) {
            "OFFGRID_HELLO" -> handleHello(socketKey, json)
            ROUTE_ANNOUNCEMENT -> handleRoutes(socketKey, json)
            HopPacket.KIND -> handleHopPacket(socketKey, line.toByteArray(Charsets.UTF_8))
        }
    }

    private fun handleHello(socketKey: String, json: JSONObject) {
        if (json.optString("protocol") != HELLO) return
        val logicalId = json.optString("nodeId").takeIf { it.isNotBlank() } ?: return
        if (logicalId == selfId) return

        val socket = pendingSockets[socketKey] ?: peersBySocket[socketKey]?.socket ?: return
        val writer = pendingWriters[socketKey] ?: peersBySocket[socketKey]?.writer ?: return
        val name = json.optString("name").ifBlank { "OffGrid device" }

        // For an outgoing connection we know the Android Wi-Fi P2P address. Incoming group-owner
        // sockets may not have a safe address mapping yet, but logical routing still works.
        val deviceAddress = pendingConnectionAddress
            ?: _nodes.value.firstOrNull { it.logicalId == logicalId }?.id

        val peer = PeerConnection(socketKey, deviceAddress, logicalId, name, socket, writer)
        peersBySocket[socketKey]?.let { removePeer(it.socketKey) }
        peersBySocket[socketKey] = peer
        pendingSockets.remove(socketKey)
        pendingWriters.remove(socketKey)
        socketByLogicalId[logicalId] = socketKey
        deviceAddress?.let { socketByDeviceAddress[it] = socketKey }

        router.learnDirect(logicalId)
        _linkState.value = LinkState.CONNECTED
        _status.value = "Wi-Fi P2P link ready • ${name.take(18)}"
        refreshConnectedNodes()

        pendingConnectionAddress?.let { address ->
            pendingConnectionAddress = null
            waiters.remove(address)?.complete(Result.success(Unit))
        }
        waiters.remove(logicalId)?.complete(Result.success(Unit))

        sendRoutesToPeer(peer)
        announceRoutesToPeers()
    }

    private fun handleRoutes(socketKey: String, json: JSONObject) {
        val peer = peersBySocket[socketKey] ?: return
        val routes = json.optJSONArray("routes") ?: return

        for (index in 0 until routes.length()) {
            val route = routes.optJSONObject(index) ?: continue
            val destination = route.optString("destination")
            if (destination.isBlank() || destination == selfId) continue

            val advertisedDistance = route.optInt("distance", 1).coerceAtLeast(1)
            val pathJson = route.optJSONArray("path")
            val advertisedPath = buildList {
                if (pathJson != null) {
                    for (i in 0 until pathJson.length()) {
                        val id = pathJson.optString(i)
                        if (id.isNotBlank()) add(id)
                    }
                }
            }

            // A route received from peer B must use B as our next hop. Never copy B's own
            // nextHop field; that describes the route from B's perspective.
            if (selfId in advertisedPath || destination in advertisedPath.dropLast(1)) continue
            router.learn(
                destinationNodeId = destination,
                nextHopNodeId = peer.logicalId,
                distance = (advertisedDistance + 1).coerceAtMost(8),
                path = advertisedPath,
            )
        }
        refreshConnectedNodes()
    }

    private fun announceRoutesToPeers() {
        peersBySocket.values.forEach { sendRoutesToPeer(it) }
    }

    private fun sendRoutesToPeer(peer: PeerConnection) {
        val routes = JSONArray()
        router.routes().forEach { route ->
            routes.put(JSONObject().apply {
                put("destination", route.destinationNodeId)
                put("nextHop", route.nextHopNodeId)
                put("distance", route.distance)
                put("path", JSONArray(route.path))
            })
        }
        val control = JSONObject().apply {
            put("kind", ROUTE_ANNOUNCEMENT)
            put("routes", routes)
        }
        scope.launch { writeLine(peer, control.toString()) }
    }

    private suspend fun handleHopPacket(socketKey: String, bytes: ByteArray) {
        val packet = HopPacket.fromJson(bytes) ?: return
        if (seenPackets.contains(packet.messageId) || !processingPackets.add(packet.messageId)) return

        try {
            val peer = peersBySocket[socketKey] ?: return
            val previousHop = peer.logicalId
            val plaintext = runCatching {
                HopEncryption.decrypt(
                    packet.ciphertext,
                    HopEncryption.linkKey(previousHop, selfId),
                )
            }.getOrElse {
                _status.value = "Wi-Fi P2P • encrypted packet rejected"
                return
            }
            updateDiagnostics(received = true)

            if (packet.destinationNodeId == selfId) {
                decodeAndEmit(plaintext, packet.hopCount + 1)
                seenPackets.add(packet.messageId)
                _status.value = "Wi-Fi P2P • delivered after ${packet.hopCount + 1} hop(s)"
                return
            }

            if (packet.hopCount >= packet.maxHops || selfId in packet.path) {
                seenPackets.add(packet.messageId)
                _status.value = "Wi-Fi P2P • relay dropped: loop/hop limit"
                return
            }

            val nextHop = router.nextHop(packet.destinationNodeId, packet.path + selfId)
                ?: packet.destinationNodeId
            val nextPeer = peersBySocket.values.firstOrNull { it.logicalId == nextHop }
            if (nextPeer == null) {
                _status.value = "Wi-Fi P2P • no route to ${packet.destinationNodeId.takeLast(4)}"
                return
            }

            val forwarded = packet.copy(
                previousHopId = selfId,
                hopCount = packet.hopCount + 1,
                path = packet.path + selfId,
                ciphertext = HopEncryption.encrypt(
                    plaintext,
                    HopEncryption.linkKey(selfId, nextHop),
                ),
            )

            if (writePacket(nextPeer, forwarded).isSuccess) {
                seenPackets.add(packet.messageId)
                _status.value = "RELAYING • Wi-Fi P2P • ${selfId.takeLast(4)} → ${nextHop.takeLast(4)}"
            }
        } finally {
            processingPackets.remove(packet.messageId)
        }
    }

    private suspend fun writePacket(peer: PeerConnection, packet: HopPacket): Result<Unit> =
        writeLine(peer, packet.toJson().toString(Charsets.UTF_8), countPacket = true)

    private suspend fun writeLine(
        peer: PeerConnection,
        line: String,
        countPacket: Boolean = false,
    ): Result<Unit> {
        val lock = writerLocks.computeIfAbsent(peer.socketKey) { Mutex() }
        return lock.withLock {
            val started = System.nanoTime()
            runCatching {
                peer.writer.write(line)
                peer.writer.newLine()
                peer.writer.flush()
                if (countPacket) {
                    updateDiagnostics(sent = true, latencyMs = (System.nanoTime() - started) / 1_000_000)
                }
            }.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { error ->
                    if (countPacket) updateDiagnostics(failed = true)
                    removePeer(peer.socketKey)
                    Result.failure(error)
                },
            )
        }
    }

    private fun removePeer(socketKey: String) {
        pendingSockets.remove(socketKey)?.let { runCatching { it.close() } }
        pendingWriters.remove(socketKey)
        val peer = peersBySocket.remove(socketKey) ?: return

        if (socketByLogicalId[peer.logicalId] == socketKey) socketByLogicalId.remove(peer.logicalId)
        peer.deviceAddress?.let {
            if (socketByDeviceAddress[it] == socketKey) socketByDeviceAddress.remove(it)
        }
        writerLocks.remove(socketKey)
        router.clearPeer(peer.logicalId)
        updateDiagnostics(disconnect = true)
        refreshConnectedNodes()
        announceRoutesToPeers()
    }

    private fun decodeAndEmit(bytes: ByteArray, hopCount: Int) {
        val json = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        if (json.optString("kind") != "OFFGRID_MESSAGE") return

        val senderId = json.optString("senderId")
        val receiverId = json.optString("receiverId")
        val type = runCatching {
            MessageType.valueOf(json.optString("type", MessageType.TEXT.name))
        }.getOrDefault(MessageType.TEXT)

        _incoming.tryEmit(
            Message(
                id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                conversationId = senderId,
                senderId = senderId,
                receiverId = receiverId,
                content = json.optString("content"),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                status = MessageStatus.RECEIVED,
                type = type,
                recipientNodeId = selfId,
                hopCount = hopCount,
                hopEncrypted = true,
            )
        )
    }

    private fun messageJson(message: Message, destination: String) = JSONObject().apply {
        put("kind", "OFFGRID_MESSAGE")
        put("id", message.id)
        put("conversationId", message.conversationId)
        put("senderId", message.senderId)
        put("receiverId", message.receiverId)
        put("recipientNodeId", destination)
        put("content", message.content)
        put("timestamp", message.timestamp)
        put("status", message.status.name)
        put("type", message.type.name)
    }

    private fun hasPermission(): Boolean {
        val wifiPermission = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        }

        val accessWifi = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_WIFI_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        val changeWifi = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CHANGE_WIFI_STATE,
        ) == PackageManager.PERMISSION_GRANTED

        return wifiPermission && accessWifi && changeWifi
    }

    private fun requirePermission() {
        if (!hasPermission()) {
            throw SecurityException("Required Wi-Fi P2P permission is not granted")
        }
    }

    private fun isLocationModeEnabled(): Boolean {
        return runCatching {
            Settings.Secure.getInt(
                appContext.contentResolver,
                Settings.Secure.LOCATION_MODE,
            ) != Settings.Secure.LOCATION_MODE_OFF
        }.getOrDefault(false)
    }

    private fun refreshDiagnostics(
        p2pEnabled: Boolean = _diagnostics.value.p2pEnabled,
        peerCount: Int = _nodes.value.size,
    ) {
        _diagnostics.value = _diagnostics.value.copy(
            wifiEnabled = wifiManager?.isWifiEnabled == true,
            p2pEnabled = p2pEnabled,
            permissionGranted = hasPermission(),
            locationModeEnabled = isLocationModeEnabled(),
            androidPeerCount = peerCount,
        )
    }

    private fun recordDiscovery(result: String) {
        _diagnostics.value = _diagnostics.value.copy(
            wifiEnabled = wifiManager?.isWifiEnabled == true,
            permissionGranted = hasPermission(),
            locationModeEnabled = isLocationModeEnabled(),
            lastDiscoveryResult = result,
            lastDiscoveryAt = System.currentTimeMillis(),
            androidPeerCount = _nodes.value.size,
        )
        _status.value = "Wi-Fi P2P • $result • peers=${_nodes.value.size}"
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
        _diagnostics.value = _diagnostics.value.let {
            it.copy(
                wifiEnabled = wifiManager?.isWifiEnabled == true,
                p2pEnabled = p2pEnabled ?: it.p2pEnabled,
                permissionGranted = hasPermission(),
                locationModeEnabled = isLocationModeEnabled(),
                androidPeerCount = peerCount ?: it.androidPeerCount,
                packetsSent = it.packetsSent + if (sent) 1 else 0,
                packetsReceived = it.packetsReceived + if (received) 1 else 0,
                packetsFailed = it.packetsFailed + if (failed) 1 else 0,
                connections = it.connections + if (connection) 1 else 0,
                disconnects = it.disconnects + if (disconnect) 1 else 0,
                lastSendLatencyMs = latencyMs ?: it.lastSendLatencyMs,
            )
        }
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
        WifiP2pManager.ERROR -> "internal error"
        WifiP2pManager.BUSY -> "framework busy"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
        else -> "error $reason"
    }
}
