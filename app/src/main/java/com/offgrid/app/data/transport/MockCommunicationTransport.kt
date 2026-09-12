package com.offgrid.app.data.transport

import android.util.Log
import com.offgrid.app.data.model.DeviceCapability
import com.offgrid.app.data.model.LinkState
import com.offgrid.app.data.model.Message
import com.offgrid.app.data.model.MessageStatus
import com.offgrid.app.data.model.MessageType
import com.offgrid.app.data.model.Node
import com.offgrid.app.data.model.NodeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Demo transport: two real running app instances exchange newline-delimited JSON over a TCP socket.
 * Android emulators can reach the host machine at 10.0.2.2; adb forwarding then bridges a unique
 * host port to each emulator's listening port. This is genuine socket I/O, not a local fake.
 *
 * A few simulated nodes keep the Nearby screen useful on one emulator. The linked device is the
 * only non-simulated peer. The stable local peer key is intentionally kept as PEER_ID so the chat
 * history does not jump to a different conversation key after the HELLO handshake.
 *
 * Swap-in point for real hardware: implement MeshtasticCommunicationTransport against BLE/serial
 * while keeping CommunicationTransport unchanged.
 */
class MockCommunicationTransport(
    private var selfPort: Int = DEFAULT_SELF_PORT,
    private var peerHost: String = DEFAULT_PEER_HOST,
    private var peerPort: Int = DEFAULT_PEER_PORT,
) : CommunicationTransport {

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var selfId: String = ""
    private var selfName: String = ""
    private var serverJob: Job? = null
    private var socket: Socket? = null
    private var writer: BufferedWriter? = null

    private val _discoveredNodes = MutableStateFlow(simulatedNodes() + peerPlaceholder())
    override val discoveredNodes: StateFlow<List<Node>> = _discoveredNodes.asStateFlow()

    private val _linkState = MutableStateFlow(LinkState.OFFLINE)
    override val linkState: StateFlow<LinkState> = _linkState.asStateFlow()

    private val _incoming = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    override val incomingMessages: Flow<Message> = _incoming

    override suspend fun start(selfId: String, selfName: String) {
        this.selfId = selfId
        this.selfName = selfName
        startServer()
    }

    fun configurePorts(selfPort: Int, peerHost: String, peerPort: Int) {
        this.selfPort = selfPort
        this.peerHost = peerHost
        this.peerPort = peerPort
        stopServer()
        startServer()
    }

    override suspend fun discoverDevices() {
        _discoveredNodes.value = _discoveredNodes.value.map {
            if (it.isSimulated) it.copy(lastSeen = System.currentTimeMillis()) else it
        }
    }

    override suspend fun connectToDevice(node: Node): Result<Unit> {
        if (node.isSimulated) {
            updateNode(node.id) { it.copy(status = NodeStatus.CONNECTED, lastSeen = System.currentTimeMillis()) }
            return Result.success(Unit)
        }
        return connectToPeer()
    }

    private suspend fun connectToPeer(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            updateNode(PEER_ID) { it.copy(status = NodeStatus.CONNECTING) }
            _linkState.value = LinkState.CONNECTING
            val client = Socket()
            client.connect(InetSocketAddress(peerHost, peerPort), CONNECT_TIMEOUT_MS)
            attach(client)
            sendHello(expectReply = true).getOrThrow()
        }.onFailure {
            Log.w(TAG, "Connect to $peerHost:$peerPort failed", it)
            updateNode(PEER_ID) { it.copy(status = NodeStatus.OFFLINE) }
            _linkState.value = LinkState.LISTENING
        }
    }

    override suspend fun sendMessage(message: Message): Result<Unit> {
        val target = _discoveredNodes.value.find { it.id == message.receiverId }
        return if (target?.isSimulated == true) {
            simulateReply(target)
            Result.success(Unit)
        } else {
            sendOverSocket(message)
        }
    }

    private suspend fun sendOverSocket(message: Message): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val out = checkNotNull(writer) { "Not connected to the linked device yet" }
            out.write(messageToJson(message))
            out.newLine()
            out.flush()
        }
    }

    private fun simulateReply(target: Node) {
        scope.launch {
            delay(600)
            _incoming.emit(
                Message(
                    conversationId = target.id,
                    senderId = target.id,
                    receiverId = selfId,
                    content = cannedReply(target.name),
                    status = MessageStatus.RECEIVED,
                )
            )
        }
    }

    private fun cannedReply(name: String): String =
        if (name.contains("Relay", ignoreCase = true)) "Relay node acknowledges. Standing by."
        else "($name is a simulated demo node — message received.)"

    private fun startServer() {
        if (serverJob?.isActive == true) return
        _linkState.value = LinkState.LISTENING
        serverJob = scope.launch {
            runCatching {
                ServerSocket(selfPort).use { server ->
                    while (!server.isClosed) attach(server.accept())
                }
            }.onFailure { Log.e(TAG, "Server on port $selfPort stopped", it) }
        }
    }

    private fun stopServer() {
        writer = null
        socket?.close()
        socket = null
        serverJob?.cancel()
        serverJob = null
    }

    private fun attach(newSocket: Socket) {
        socket?.close()
        socket = newSocket
        writer = BufferedWriter(OutputStreamWriter(newSocket.getOutputStream(), Charsets.UTF_8))
        _linkState.value = LinkState.CONNECTED
        updateNode(PEER_ID) { it.copy(status = NodeStatus.CONNECTED, lastSeen = System.currentTimeMillis()) }
        scope.launch {
            runCatching {
                BufferedReader(InputStreamReader(newSocket.getInputStream(), Charsets.UTF_8)).useLines { lines ->
                    lines.forEach(::handleLine)
                }
            }.onFailure { Log.w(TAG, "Peer connection ended", it) }
            updateNode(PEER_ID) { it.copy(status = NodeStatus.OFFLINE) }
            _linkState.value = LinkState.LISTENING
        }
    }

    private suspend fun sendHello(expectReply: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val out = checkNotNull(writer) { "Not connected to the linked device yet" }
            out.write(JSONObject().apply {
                put("kind", "HELLO")
                put("nodeId", selfId)
                put("name", selfName)
                put("reply", expectReply)
            }.toString())
            out.newLine()
            out.flush()
        }
    }

    private fun handleLine(line: String) {
        val json = JSONObject(line)
        when (json.optString("kind")) {
            "HELLO" -> {
                // Keep PEER_ID as the local conversation key; store the remote identity in the
                // node's display name/status without replacing the key.
                updateNode(PEER_ID) {
                    it.copy(name = json.getString("name"), status = NodeStatus.CONNECTED)
                }
                if (json.optBoolean("reply", true)) scope.launch { sendHello(expectReply = false) }
            }
            "MESSAGE" -> scope.launch { _incoming.emit(jsonToMessage(json)) }
        }
    }

    private fun updateNode(id: String, transform: (Node) -> Node) {
        _discoveredNodes.value = _discoveredNodes.value.map { if (it.id == id) transform(it) else it }
    }

    private fun messageToJson(message: Message): String = JSONObject().apply {
        put("kind", "MESSAGE")
        put("id", message.id)
        put("senderId", message.senderId)
        put("receiverId", message.receiverId)
        put("content", message.content)
        put("timestamp", message.timestamp)
        put("type", message.type.name)
    }.toString()

    private fun jsonToMessage(json: JSONObject): Message = Message(
        id = json.getString("id"),
        // PEER_ID is the stable local conversation key for the linked socket.
        conversationId = PEER_ID,
        senderId = json.getString("senderId"),
        receiverId = json.getString("receiverId"),
        content = json.getString("content"),
        timestamp = json.optLong("timestamp", System.currentTimeMillis()),
        status = MessageStatus.RECEIVED,
        type = runCatching { MessageType.valueOf(json.optString("type", MessageType.TEXT.name)) }
            .getOrDefault(MessageType.TEXT),
    )

    private fun peerPlaceholder() = Node(
        id = PEER_ID,
        name = "Linked Device",
        status = NodeStatus.OFFLINE,
        isSimulated = false,
        capabilities = setOf(DeviceCapability.MESSAGING),
    )

    private fun simulatedNodes() = listOf(
        Node("OFFGRID-7A21", "Aarav", NodeStatus.AVAILABLE, isSimulated = true, signalStrength = 82, hops = 1),
        Node("OFFGRID-92BF", "Rahul", NodeStatus.AVAILABLE, isSimulated = true, signalStrength = 64, hops = 1),
        Node(
            "OFFGRID-RELAY01", "Emergency Relay", NodeStatus.AVAILABLE, isSimulated = true, hops = 2,
            capabilities = setOf(DeviceCapability.MESSAGING, DeviceCapability.RELAY, DeviceCapability.EMERGENCY_RESPONDER),
        ),
    )

    override fun stop() {
        stopServer()
        scope.cancel()
    }

    companion object {
        private const val TAG = "MockTransport"
        const val PEER_ID = "OFFGRID-PEER"
        const val DEFAULT_SELF_PORT = 8990
        const val DEFAULT_PEER_HOST = "10.0.2.2"
        const val DEFAULT_PEER_PORT = 8991
        private const val CONNECT_TIMEOUT_MS = 5_000
    }
}
