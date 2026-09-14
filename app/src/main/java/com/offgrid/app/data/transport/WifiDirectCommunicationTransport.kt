package com.offgrid.app.data.transport

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
 * This class is deliberately separate from the existing Nearby transport and is NOT wired into
 * the current MainActivity. The existing APK therefore keeps its current transport unchanged.
 *
 * Wi-Fi P2P is the physical link. HopRouter still decides the logical next hop and HopEncryption
 * still protects every hop with the existing AES-GCM implementation.
 *
 * Direct range is hardware/environment dependent. Diagnostics are exposed so real phones can be
 * measured instead of assigning an unsupported fixed range claim.
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
    }

    data class Diagnostics(
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

    private val manager = context.applicationContext
        .getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel = manager?.initialize(context, Looper.getMainLooper(), null)

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
    private val writers = ConcurrentHashMap<String, BufferedWriter>()
    private val sockets = ConcurrentHashMap<String, Socket>()
    private val peerIds = ConcurrentHashMap<String, String>()
    private val logicalToSocket = ConcurrentHashMap<String, String>()
    private val seenPackets = ConcurrentHashMap.newKeySet<String>()
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Result<Unit>>>()
    private val sendMutex = Mutex()

    private var selfId = ""
    private var selfName = ""
    private var server: ServerSocket? = null
    private var registered = false

    private val peersListener = WifiP2pManager.PeerListListener { list: WifiP2pDeviceList ->
        publishPeers(list.deviceList)
    }

    private val connectionInfoListener = WifiP2pManager.ConnectionInfoListener { info: WifiP2pInfo ->
        if (!info.groupFormed) {
            _linkState.value = LinkState.OFFLINE
            return@ConnectionInfoListener
        }
        _linkState.value = LinkState.CONNECTED
        if (info.isGroupOwner) {
            _status.value = "Wi-Fi P2P group owner • relay ready"
            startServer()
        } else {
            _status.value = "Wi-Fi P2P connected"
            info.groupOwnerAddress?.let { connectToOwner(it.hostAddress) }
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
                    if (!enabled) _linkState.value = LinkState.OFFLINE
                    _status.value = if (enabled) "Wi-Fi P2P enabled" else "Wi-Fi P2P disabled"
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
        router.setIdentity(selfId)
        if (registered) return
        registerReceiver()
        requestPeers()
        discover()
    }

    override suspend fun discoverDevices() {
        requirePermission()
        requestPeers()
        discover()
    }

    private fun discover() {
        val p2p = manager ?: return
        val ch = channel ?: return
        p2p.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { _status.value = "Scanning for Wi-Fi P2P peers…" }
            override fun onFailure(reason: Int) {
                _status.value = "Wi-Fi P2P discovery failed: ${reasonText(reason)}"
            }
        })
    }

    override suspend fun connectToDevice(node: Node): Result<Unit> = withContext(Dispatchers.IO) {
        requirePermission()
        val address = node.id.trim()
        val p2p = manager ?: return@withContext Result.failure(IllegalStateException("Wi-Fi P2P unavailable"))
        val ch = channel ?: return@withContext Result.failure(IllegalStateException("Wi-Fi P2P channel unavailable"))
        waiters[address]?.let { return@withContext await(address, it) }

        val waiter = CompletableDeferred<Result<Unit>>()
        waiters[address] = waiter
        _linkState.value = LinkState.CONNECTING
        _status.value = "Connecting to ${node.name} over Wi-Fi P2P…"

        val config = WifiP2pConfig().apply { deviceAddress = address }
        p2p.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { _status.value = "Wi-Fi P2P connection requested" }
            override fun onFailure(reason: Int) {
                waiters.remove(address)?.complete(
                    Result.failure(IllegalStateException("Wi-Fi P2P connect failed: ${reasonText(reason)}"))
                )
                _linkState.value = LinkState.OFFLINE
            }
        })
        await(address, waiter)
    }

    private suspend fun await(
        address: String,
        waiter: CompletableDeferred<Result<Unit>>,
    ): Result<Unit> = try {
        withTimeout(CONNECT_TIMEOUT_MS.toLong()) { waiter.await() }
    } catch (t: Throwable) {
        waiters.remove(address)?.complete(Result.failure(t))
        Result.failure(t)
    }

    override suspend fun sendMessage(message: Message): Result<Unit> = sendMutex.withLock {
        val destination = message.recipientNodeId ?: message.receiverId
        val nextHop = router.nextHop(destination, listOf(selfId)) ?: destination
        val socketKey = logicalToSocket[nextHop]
            ?: return@withLock Result.failure(IllegalStateException("No Wi-Fi P2P link to $nextHop"))
        val writer = writers[socketKey]
            ?: return@withLock Result.failure(IllegalStateException("No active Wi-Fi P2P socket"))

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

        val started = System.nanoTime()
        runCatching {
            writer.write(packet.toJson().toString(Charsets.UTF_8))
            writer.newLine()
            writer.flush()
            updateDiagnostics(sent = true, latencyMs = (System.nanoTime() - started) / 1_000_000)
            _status.value = "Wi-Fi P2P • AES-256-GCM • sent to ${nextHop.takeLast(4)}"
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { error ->
                updateDiagnostics(failed = true)
                Result.failure(error)
            },
        )
    }

    override fun stop() {
        sockets.values.forEach { runCatching { it.close() } }
        sockets.clear()
        writers.clear()
        runCatching { server?.close() }
        server = null
        if (registered) {
            runCatching { context.applicationContext.unregisterReceiver(receiver) }
            registered = false
        }
        runCatching { manager?.removeGroup(channel, null) }
        _linkState.value = LinkState.OFFLINE
        _status.value = "Wi-Fi P2P stopped"
    }

    private fun registerReceiver() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.applicationContext.registerReceiver(receiver, filter)
        }
        registered = true
    }

    private fun requestPeers() {
        if (!hasPermission()) return
        manager?.requestPeers(channel, peersListener)
    }

    private fun requestConnectionInfo() {
        if (!hasPermission()) return
        manager?.requestConnectionInfo(channel, connectionInfoListener)
    }

    private fun publishPeers(devices: Collection<WifiP2pDevice>) {
        _nodes.value = devices.map { device ->
            val logicalId = peerIds[device.deviceAddress] ?: device.deviceAddress
            logicalToSocket[logicalId] = device.deviceAddress
            Node(
                id = device.deviceAddress,
                name = device.deviceName.ifBlank { "Wi-Fi P2P device" },
                status = when (device.status) {
                    WifiP2pDevice.CONNECTED -> NodeStatus.CONNECTED
                    WifiP2pDevice.INVITED -> NodeStatus.CONNECTING
                    else -> NodeStatus.AVAILABLE
                },
                hops = 1,
                isSimulated = false,
                logicalId = logicalId,
            )
        }.distinctBy { it.id }
    }

    private fun startServer() {
        if (server?.isClosed == false) return
        scope.launch {
            runCatching {
                ServerSocket(PORT).also { server = it }.use { listening ->
                    while (!listening.isClosed) handleSocket(listening.accept())
                }
            }
        }
    }

    private fun connectToOwner(host: String) {
        scope.launch {
            runCatching {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, PORT), CONNECT_TIMEOUT_MS)
                handleSocket(socket)
            }.onFailure { _status.value = "Wi-Fi P2P socket failed" }
        }
    }

    private fun handleSocket(socket: Socket) {
        scope.launch {
            val key = socket.remoteSocketAddress.toString()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            sockets[key] = socket
            writers[key] = writer
            updateDiagnostics(connection = true)
            sendHello(writer)
            try {
                while (!socket.isClosed) {
                    val line = reader.readLine() ?: break
                    if (line.toByteArray(Charsets.UTF_8).size > MAX_PACKET_BYTES) continue
                    handleLine(key, line)
                }
            } catch (_: Throwable) {
                // Connection teardown is handled in finally.
            } finally {
                sockets.remove(key)
                writers.remove(key)
                peerIds.remove(key)
                updateDiagnostics(disconnect = true)
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

    private fun handleLine(socketKey: String, line: String) {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return
        when (json.optString("kind")) {
            "OFFGRID_HELLO" -> {
                val logicalId = json.optString("nodeId").takeIf { it.isNotBlank() } ?: return
                peerIds[socketKey] = logicalId
                logicalToSocket[logicalId] = socketKey
                router.learnDirect(logicalId)
                _status.value = "Wi-Fi P2P link ready • ${logicalId.takeLast(4)}"
                waiters.entries.firstOrNull { it.key == logicalId }?.value?.complete(Result.success(Unit))
            }
            HopPacket.KIND -> handleHopPacket(socketKey, line.toByteArray(Charsets.UTF_8))
        }
    }

    private fun handleHopPacket(socketKey: String, bytes: ByteArray) {
        val packet = HopPacket.fromJson(bytes) ?: return
        if (!seenPackets.add(packet.messageId)) return

        val previousHop = peerIds[socketKey] ?: packet.previousHopId
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
            _status.value = "Wi-Fi P2P • delivered after ${packet.hopCount + 1} hop(s)"
            return
        }

        if (packet.hopCount >= packet.maxHops || selfId in packet.path) {
            _status.value = "Wi-Fi P2P • relay dropped: loop/hop limit"
            return
        }

        val nextHop = router.nextHop(packet.destinationNodeId, packet.path + selfId)
            ?: packet.destinationNodeId
        val nextSocket = logicalToSocket[nextHop]
        val nextWriter = nextSocket?.let { writers[it] }
        if (nextWriter == null) {
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

        runCatching {
            nextWriter.write(forwarded.toJson().toString(Charsets.UTF_8))
            nextWriter.newLine()
            nextWriter.flush()
            _status.value = "RELAYING • Wi-Fi P2P • ${selfId.takeLast(4)} → ${nextHop.takeLast(4)}"
        }.onFailure { updateDiagnostics(failed = true) }
    }

    private fun decodeAndEmit(bytes: ByteArray, hopCount: Int) {
        val json = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull() ?: return
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
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
        return wifiPermission &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED
    }

    private fun requirePermission() {
        if (!hasPermission()) {
            throw SecurityException("Required Wi-Fi P2P permission is not granted")
        }
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
        WifiP2pManager.ERROR -> "internal error"
        WifiP2pManager.BUSY -> "framework busy"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
        else -> "error $reason"
    }

    private fun updateDiagnostics(
        sent: Boolean = false,
        received: Boolean = false,
        failed: Boolean = false,
        connection: Boolean = false,
        disconnect: Boolean = false,
        latencyMs: Long? = null,
    ) {
        _diagnostics.value = _diagnostics.value.let {
            it.copy(
                packetsSent = it.packetsSent + if (sent) 1 else 0,
                packetsReceived = it.packetsReceived + if (received) 1 else 0,
                packetsFailed = it.packetsFailed + if (failed) 1 else 0,
                connections = it.connections + if (connection) 1 else 0,
                disconnects = it.disconnects + if (disconnect) 1 else 0,
                lastSendLatencyMs = latencyMs ?: it.lastSendLatencyMs,
            )
        }
    }
}
