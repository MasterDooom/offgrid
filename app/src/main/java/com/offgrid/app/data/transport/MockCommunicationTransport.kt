package com.offgrid.app.data.transport

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.offgrid.app.data.model.DeviceCapability
import com.offgrid.app.data.model.LinkState
import com.offgrid.app.data.model.Message
import com.offgrid.app.data.model.MessageStatus
import com.offgrid.app.data.model.MessageType
import com.offgrid.app.data.model.Node
import com.offgrid.app.data.model.NodeStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Real phone-to-phone Nearby Connections transport used by the prototype. */
class MockCommunicationTransport(private val context: Context) : CommunicationTransport {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var selfId = ""
    @Volatile private var selfName = ""
    @Volatile private var started = false
    @Volatile private var discoveryRunning = false
    @Volatile private var advertisingRunning = false

    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val pendingConnections = ConcurrentHashMap<String, CompletableDeferred<Result<Unit>>>()
    private val endpointNames = ConcurrentHashMap<String, String>()
    private val sendMutex = Mutex()

    private val _discoveredNodes = MutableStateFlow<List<Node>>(emptyList())
    override val discoveredNodes = _discoveredNodes.asStateFlow()

    private val _linkState = MutableStateFlow(LinkState.OFFLINE)
    override val linkState = _linkState.asStateFlow()

    private val _incoming = MutableSharedFlow<Message>(extraBufferCapacity = 128)
    override val incomingMessages = _incoming.asSharedFlow()

    private val _transportStatus = MutableStateFlow("Starting nearby discovery…")
    val transportStatus = _transportStatus.asStateFlow()

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            safeCallback("connection initiated") {
                if (endpointId.isBlank()) return@safeCallback
                endpointNames[endpointId] = connectionInfo.endpointName.ifBlank { "OFFGRID device" }
                upsertNode(endpointId, endpointNames[endpointId]!!, NodeStatus.CONNECTING)
                _transportStatus.value = "Connecting to ${endpointNames[endpointId]}…"

                // Nearby requires both sides to accept before a connection is established.
                // Accepting here is deliberate for the review/demo prototype.
                runCatching {
                    client.acceptConnection(endpointId, payloadCallback)
                }.onFailure { error ->
                    Log.e(TAG, "acceptConnection failed: $endpointId", error)
                    pendingConnections.remove(endpointId)?.complete(Result.failure(error))
                    _transportStatus.value = "Accept failed: ${errorMessage(error)}"
                }
            }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            safeCallback("connection result") {
                val code = resolution.status.statusCode
                if (code == ConnectionsStatusCodes.STATUS_OK) {
                    connectedEndpoints.add(endpointId)
                    val name = endpointNames[endpointId] ?: "OFFGRID device"
                    upsertNode(endpointId, name, NodeStatus.CONNECTED)
                    pendingConnections.remove(endpointId)?.complete(Result.success(Unit))
                    refreshLinkState()
                    _transportStatus.value = "Connected to $name"
                    Log.d(TAG, "Connected endpoint=$endpointId name=$name")
                } else {
                    connectedEndpoints.remove(endpointId)
                    val name = endpointNames[endpointId] ?: "OFFGRID device"
                    upsertNode(endpointId, name, NodeStatus.AVAILABLE)
                    val message = "${ConnectionsStatusCodes.getStatusCodeString(code)} ($code)"
                    pendingConnections.remove(endpointId)?.complete(Result.failure(IllegalStateException(message)))
                    refreshLinkState()
                    _transportStatus.value = "Connection failed: $message"
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            safeCallback("disconnect") {
                connectedEndpoints.remove(endpointId)
                pendingConnections.remove(endpointId)?.complete(
                    Result.failure(IllegalStateException("Device disconnected"))
                )
                _discoveredNodes.value = _discoveredNodes.value.map {
                    if (it.id == endpointId) it.copy(status = NodeStatus.AVAILABLE) else it
                }
                refreshLinkState()
                _transportStatus.value = "Device disconnected"
            }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            safeCallback("endpoint found") {
                if (endpointId.isBlank()) return@safeCallback
                val name = info.endpointName.ifBlank { "OFFGRID device" }
                endpointNames[endpointId] = name
                val status = if (connectedEndpoints.contains(endpointId)) {
                    NodeStatus.CONNECTED
                } else {
                    NodeStatus.AVAILABLE
                }
                upsertNode(endpointId, name, status)
                _transportStatus.value = "Found $name"
                Log.d(TAG, "FOUND endpoint=$endpointId name=$name")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            safeCallback("endpoint lost") {
                if (!connectedEndpoints.contains(endpointId)) removeEndpoint(endpointId)
                Log.d(TAG, "LOST endpoint=$endpointId")
            }
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            safeCallback("payload received") {
                if (payload.type != Payload.Type.BYTES) return@safeCallback
                val bytes = payload.asBytes() ?: return@safeCallback
                if (bytes.isEmpty() || bytes.size > MAX_MESSAGE_BYTES) return@safeCallback

                // Deliver onto the main thread rather than mutating/collecting app state directly
                // inside a Google Play services callback.
                mainHandler.post {
                    safeCallback("deliver received message") {
                        decodeAndEmit(endpointId, bytes)
                    }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // BYTES payloads are complete when onPayloadReceived() fires. Keep this callback
            // deliberately side-effect-free so transfer callbacks can never destabilize Compose.
            Log.d(TAG, "Payload update endpoint=$endpointId payload=${update.payloadId} status=${update.status}")
        }
    }

    override suspend fun start(selfId: String, selfName: String) {
        this.selfId = selfId
        this.selfName = selfName
        if (started) return
        started = true
        _transportStatus.value = "Starting advertising + discovery…"
        startAdvertisingSafely()
        startDiscoverySafely()
        refreshLinkState()
    }

    override suspend fun discoverDevices() {
        if (!started) {
            start(selfId, selfName)
            return
        }
        stopDiscoverySafely()
        _transportStatus.value = "Scanning for nearby OFFGRID devices…"
        startDiscoverySafely()
    }

    override suspend fun connectToDevice(node: Node): Result<Unit> {
        val endpointId = node.id
        if (endpointId.isBlank()) return Result.failure(IllegalStateException("Invalid nearby device"))
        if (connectedEndpoints.contains(endpointId)) return Result.success(Unit)
        if (!started) start(selfId, selfName)

        pendingConnections[endpointId]?.let { return awaitConnection(endpointId, it) }

        val deferred = CompletableDeferred<Result<Unit>>()
        if (pendingConnections.putIfAbsent(endpointId, deferred) != null) {
            return awaitConnection(endpointId, pendingConnections[endpointId]!!)
        }

        upsertNode(endpointId, node.name, NodeStatus.CONNECTING)
        _transportStatus.value = "Connecting to ${node.name}…"

        try {
            client.requestConnection(selfName, endpointId, connectionCallback)
                .addOnFailureListener { error ->
                    safeCallback("connection request failure") {
                        pendingConnections.remove(endpointId)?.complete(Result.failure(error))
                        upsertNode(endpointId, node.name, NodeStatus.AVAILABLE)
                        _transportStatus.value = "Connection request failed: ${errorMessage(error)}"
                    }
                }
        } catch (error: Throwable) {
            pendingConnections.remove(endpointId)?.complete(Result.failure(error))
            upsertNode(endpointId, node.name, NodeStatus.AVAILABLE)
            return Result.failure(error)
        }

        return awaitConnection(endpointId, deferred)
    }

    private suspend fun awaitConnection(
        endpointId: String,
        deferred: CompletableDeferred<Result<Unit>>,
    ): Result<Unit> = try {
        withTimeout(CONNECTION_TIMEOUT_MS) { deferred.await() }
    } catch (error: Throwable) {
        pendingConnections.remove(endpointId)?.complete(Result.failure(error))
        upsertNode(endpointId, endpointNames[endpointId] ?: "OFFGRID device", NodeStatus.AVAILABLE)
        Result.failure(error)
    }

    override suspend fun sendMessage(message: Message): Result<Unit> = sendMutex.withLock {
        val endpointId = message.receiverId
        if (endpointId.isBlank()) {
            return@withLock Result.failure(IllegalStateException("Missing recipient"))
        }
        if (!connectedEndpoints.contains(endpointId)) {
            return@withLock Result.failure(
                IllegalStateException("Not connected to ${endpointNames[endpointId] ?: "device"}")
            )
        }

        val bytes = try {
            messageToJson(message).toByteArray(Charsets.UTF_8)
        } catch (error: Throwable) {
            return@withLock Result.failure(error)
        }
        if (bytes.size > MAX_MESSAGE_BYTES) {
            return@withLock Result.failure(IllegalArgumentException("Message is too large"))
        }

        try {
            // No coroutine bridge and no UI work in the Google Task callback. Nearby accepts
            // this byte payload and invokes PayloadCallback on the receiving endpoint.
            client.sendPayload(endpointId, Payload.fromBytes(bytes))
            Log.d(TAG, "Payload queued for endpoint=$endpointId")
            _transportStatus.value = "Message sent"
            Result.success(Unit)
        } catch (error: Throwable) {
            Log.e(TAG, "sendPayload threw for endpoint=$endpointId", error)
            _transportStatus.value = "Message failed: ${errorMessage(error)}"
            Result.failure(error)
        }
    }

    private fun startAdvertisingSafely() {
        if (advertisingRunning) return
        try {
            client.startAdvertising(
                selfName,
                SERVICE_ID,
                connectionCallback,
                AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
            ).addOnSuccessListener {
                advertisingRunning = true
                refreshLinkState()
                Log.d(TAG, "Advertising started")
            }.addOnFailureListener { error ->
                advertisingRunning = false
                _transportStatus.value = "Advertising failed: ${errorMessage(error)}"
                Log.e(TAG, "Advertising failed", error)
            }
        } catch (error: Throwable) {
            advertisingRunning = false
            _transportStatus.value = "Advertising failed: ${errorMessage(error)}"
        }
    }

    private fun startDiscoverySafely() {
        if (discoveryRunning) return
        try {
            client.startDiscovery(
                SERVICE_ID,
                endpointDiscoveryCallback,
                DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
            ).addOnSuccessListener {
                discoveryRunning = true
                _transportStatus.value = "Live • advertising + scanning"
                refreshLinkState()
                Log.d(TAG, "Discovery started")
            }.addOnFailureListener { error ->
                discoveryRunning = false
                _transportStatus.value = "Discovery failed: ${errorMessage(error)}"
                Log.e(TAG, "Discovery failed", error)
            }
        } catch (error: Throwable) {
            discoveryRunning = false
            _transportStatus.value = "Discovery failed: ${errorMessage(error)}"
        }
    }

    private fun stopDiscoverySafely() {
        runCatching { client.stopDiscovery() }
        discoveryRunning = false
    }

    private fun safeCallback(operation: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            Log.e(TAG, "$operation callback failed", error)
            runCatching { _transportStatus.value = "$operation failed: ${errorMessage(error)}" }
        }
    }

    private fun errorMessage(error: Throwable): String {
        val code = (error as? ApiException)?.statusCode
        return if (code != null) {
            "${ConnectionsStatusCodes.getStatusCodeString(code)} ($code)"
        } else {
            error.message ?: error.javaClass.simpleName
        }
    }

    private fun decodeAndEmit(endpointId: String, bytes: ByteArray) {
        try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            if (json.optString("kind") != "MESSAGE") return
            val content = json.optString("content")
            if (content.isBlank()) return

            val messageId = json.optString("id")
                .ifBlank { "rx-$endpointId-${System.nanoTime()}" }
            val type = runCatching {
                MessageType.valueOf(json.optString("type", MessageType.TEXT.name))
            }.getOrDefault(MessageType.TEXT)

            _incoming.tryEmit(
                Message(
                    id = messageId,
                    conversationId = endpointId,
                    senderId = endpointId,
                    receiverId = selfId,
                    content = content,
                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                    status = MessageStatus.RECEIVED,
                    type = type,
                )
            )
            _transportStatus.value = "Message received from ${endpointNames[endpointId] ?: "OFFGRID device"}"
        } catch (error: Throwable) {
            Log.w(TAG, "Invalid message payload from $endpointId", error)
        }
    }

    private fun messageToJson(message: Message): String = JSONObject().apply {
        put("kind", "MESSAGE")
        put("id", message.id)
        put("senderId", selfId)
        put("receiverId", message.receiverId)
        put("content", message.content)
        put("timestamp", message.timestamp)
        put("type", message.type.name)
    }.toString()

    private fun upsertNode(endpointId: String, name: String, status: NodeStatus) {
        val safeName = name.ifBlank { "OFFGRID device" }
        val node = Node(
            id = endpointId,
            name = safeName,
            status = status,
            lastSeen = System.currentTimeMillis(),
            isSimulated = false,
            hops = 1,
            capabilities = setOf(DeviceCapability.MESSAGING),
        )
        _discoveredNodes.value = (_discoveredNodes.value.filterNot { it.id == endpointId } + node)
            .distinctBy { it.id }
    }

    private fun removeEndpoint(endpointId: String) {
        endpointNames.remove(endpointId)
        _discoveredNodes.value = _discoveredNodes.value.filterNot { it.id == endpointId }
    }

    private fun refreshLinkState() {
        _linkState.value = when {
            connectedEndpoints.isNotEmpty() -> LinkState.CONNECTED
            started -> LinkState.LISTENING
            else -> LinkState.OFFLINE
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        runCatching { client.stopAdvertising() }
        runCatching { client.stopDiscovery() }
        runCatching { client.stopAllEndpoints() }
        advertisingRunning = false
        discoveryRunning = false
        connectedEndpoints.clear()
        pendingConnections.values.forEach { deferred ->
            runCatching {
                deferred.complete(Result.failure(IllegalStateException("Transport stopped")))
            }
        }
        pendingConnections.clear()
        endpointNames.clear()
        _discoveredNodes.value = emptyList()
        _linkState.value = LinkState.OFFLINE
        _transportStatus.value = "Offline"
    }

    companion object {
        private const val TAG = "OffGridNearby"
        private const val SERVICE_ID = "com.offgrid.app.offline"
        private const val CONNECTION_TIMEOUT_MS = 10_000L
        private const val MAX_MESSAGE_BYTES = 8_192
        private val STRATEGY = Strategy.P2P_CLUSTER
    }
}
