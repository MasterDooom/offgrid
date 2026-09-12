package com.offgrid.app.data.transport

import android.content.Context
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Real phone-to-phone Nearby Connections transport used by the prototype.
 * Node.id is deliberately the local Nearby endpoint id. This avoids a HELLO/identity race
 * changing the id while a chat is sending its first message.
 */
class MockCommunicationTransport(private val context: Context) : CommunicationTransport {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)

    @Volatile private var selfId = ""
    @Volatile private var selfName = ""
    @Volatile private var started = false
    @Volatile private var discoveryRunning = false
    @Volatile private var advertisingRunning = false

    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val pendingConnections = ConcurrentHashMap<String, CompletableDeferred<Result<Unit>>>()
    private val endpointNames = ConcurrentHashMap<String, String>()
    private val endpointRemoteIds = ConcurrentHashMap<String, String>()

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
                endpointNames[endpointId] = connectionInfo.endpointName
                upsertNode(endpointId, connectionInfo.endpointName, NodeStatus.CONNECTING)
                _transportStatus.value = "Connecting to ${connectionInfo.endpointName}…"

                client.acceptConnection(endpointId, payloadCallback)
                    .addOnSuccessListener {
                        Log.d(TAG, "Accepted connection: $endpointId")
                    }
                    .addOnFailureListener { error ->
                        Log.e(TAG, "Accept failed for $endpointId", error)
                        _transportStatus.value = "Accept failed: ${errorMessage(error)}"
                        pendingConnections.remove(endpointId)?.complete(Result.failure(error))
                    }
            }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            safeCallback("connection result") {
                if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                    connectedEndpoints.add(endpointId)
                    val name = endpointNames[endpointId] ?: "OFFGRID device"
                    upsertNode(endpointId, name, NodeStatus.CONNECTED)
                    _linkState.value = LinkState.CONNECTED
                    _transportStatus.value = "Connected to $name"
                    pendingConnections.remove(endpointId)?.complete(Result.success(Unit))
                    sendHello(endpointId)
                    Log.d(TAG, "Connected: $endpointId")
                } else {
                    val message = "${ConnectionsStatusCodes.getStatusCodeString(resolution.status.statusCode)} (${resolution.status.statusCode})"
                    connectedEndpoints.remove(endpointId)
                    upsertNode(endpointId, endpointNames[endpointId] ?: "OFFGRID device", NodeStatus.AVAILABLE)
                    pendingConnections.remove(endpointId)?.complete(Result.failure(IllegalStateException(message)))
                    _transportStatus.value = "Connection failed: $message"
                    refreshLinkState()
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            safeCallback("disconnect") {
                connectedEndpoints.remove(endpointId)
                pendingConnections.remove(endpointId)?.complete(Result.failure(IllegalStateException("Device disconnected")))
                removeEndpoint(endpointId)
                _transportStatus.value = "Device disconnected"
                refreshLinkState()
                Log.d(TAG, "Disconnected: $endpointId")
            }
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            safeCallback("endpoint found") {
                endpointNames[endpointId] = info.endpointName
                upsertNode(
                    endpointId,
                    info.endpointName,
                    if (connectedEndpoints.contains(endpointId)) NodeStatus.CONNECTED else NodeStatus.AVAILABLE,
                )
                _transportStatus.value = "Found ${info.endpointName}"
                Log.d(TAG, "FOUND endpoint=$endpointId name=${info.endpointName}")
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
                if (bytes.size > MAX_MESSAGE_BYTES) {
                    Log.w(TAG, "Ignoring oversized payload from $endpointId")
                    return@safeCallback
                }
                handlePayload(endpointId, bytes.toString(Charsets.UTF_8))
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            safeCallback("payload transfer update") {
                when (update.status) {
                    PayloadTransferUpdate.Status.SUCCESS ->
                        _transportStatus.value = "Message delivered"
                    PayloadTransferUpdate.Status.FAILURE,
                    PayloadTransferUpdate.Status.CANCELED ->
                        _transportStatus.value = "Message transfer failed"
                    else -> Unit
                }
            }
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

        val existing = pendingConnections[endpointId]
        if (existing != null) return awaitConnection(endpointId, existing)

        val deferred = CompletableDeferred<Result<Unit>>()
        val previous = pendingConnections.putIfAbsent(endpointId, deferred)
        if (previous != null) return awaitConnection(endpointId, previous)

        upsertNode(endpointId, node.name, NodeStatus.CONNECTING)
        _transportStatus.value = "Connecting to ${node.name}…"

        try {
            client.requestConnection(selfName, endpointId, connectionCallback)
                .addOnFailureListener { error ->
                    pendingConnections.remove(endpointId)?.complete(Result.failure(error))
                    upsertNode(endpointId, node.name, NodeStatus.AVAILABLE)
                    _transportStatus.value = "Connection request failed: ${errorMessage(error)}"
                }
        } catch (error: Throwable) {
            pendingConnections.remove(endpointId)?.complete(Result.failure(error))
            upsertNode(endpointId, node.name, NodeStatus.AVAILABLE)
        }

        return awaitConnection(endpointId, deferred)
    }

    private suspend fun awaitConnection(
        endpointId: String,
        deferred: CompletableDeferred<Result<Unit>>,
    ): Result<Unit> = runCatching {
        withTimeout(CONNECTION_TIMEOUT_MS) { deferred.await() }
    }.getOrElse { error ->
        pendingConnections.remove(endpointId)?.complete(Result.failure(error))
        upsertNode(endpointId, endpointNames[endpointId] ?: "OFFGRID device", NodeStatus.AVAILABLE)
        Result.failure(error)
    }

    override suspend fun sendMessage(message: Message): Result<Unit> {
        val endpointId = message.receiverId
        if (endpointId.isBlank()) return Result.failure(IllegalStateException("Missing recipient"))
        if (!connectedEndpoints.contains(endpointId)) {
            return Result.failure(IllegalStateException("Not connected to ${endpointNames[endpointId] ?: "device"}"))
        }

        val bytes = runCatching { messageToJson(message).toByteArray(Charsets.UTF_8) }
            .getOrElse { return Result.failure(it) }
        if (bytes.size > MAX_MESSAGE_BYTES) {
            return Result.failure(IllegalArgumentException("Message is too large"))
        }

        return runCatching {
            val task = client.sendPayload(endpointId, Payload.fromBytes(bytes))
            awaitTask(task)
        }.getOrElse { error ->
            Log.e(TAG, "sendMessage failed for $endpointId", error)
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
            )
                .addOnSuccessListener {
                    advertisingRunning = true
                    Log.d(TAG, "Advertising started")
                    refreshLinkState()
                }
                .addOnFailureListener { error ->
                    advertisingRunning = false
                    _transportStatus.value = "Advertising failed: ${errorMessage(error)}"
                    Log.e(TAG, "Advertising failed", error)
                }
        } catch (error: Throwable) {
            advertisingRunning = false
            _transportStatus.value = "Advertising failed: ${errorMessage(error)}"
            Log.e(TAG, "Advertising threw", error)
        }
    }

    private fun startDiscoverySafely() {
        if (discoveryRunning) return
        try {
            client.startDiscovery(
                SERVICE_ID,
                endpointDiscoveryCallback,
                DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
            )
                .addOnSuccessListener {
                    discoveryRunning = true
                    _transportStatus.value = "Live • advertising + scanning"
                    refreshLinkState()
                    Log.d(TAG, "Discovery started")
                }
                .addOnFailureListener { error ->
                    discoveryRunning = false
                    _transportStatus.value = "Discovery failed: ${errorMessage(error)}"
                    Log.e(TAG, "Discovery failed", error)
                }
        } catch (error: Throwable) {
            discoveryRunning = false
            _transportStatus.value = "Discovery failed: ${errorMessage(error)}"
            Log.e(TAG, "Discovery threw", error)
        }
    }

    private fun stopDiscoverySafely() {
        try { client.stopDiscovery() } catch (error: Throwable) { Log.w(TAG, "stopDiscovery failed", error) }
        discoveryRunning = false
    }

    private fun safeCallback(operation: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            Log.e(TAG, "$operation callback failed", error)
            _transportStatus.value = "$operation failed: ${errorMessage(error)}"
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

    private fun sendHello(endpointId: String) {
        try {
            val hello = JSONObject().apply {
                put("kind", "HELLO")
                put("nodeId", selfId)
                put("name", selfName)
                put("version", 1)
            }.toString().toByteArray(Charsets.UTF_8)
            client.sendPayload(endpointId, Payload.fromBytes(hello))
                .addOnFailureListener { Log.w(TAG, "HELLO send failed", it) }
        } catch (error: Throwable) {
            Log.w(TAG, "HELLO creation failed", error)
        }
    }

    private fun handlePayload(endpointId: String, raw: String) {
        try {
            val json = JSONObject(raw)
            when (json.optString("kind")) {
                "HELLO" -> {
                    endpointRemoteIds[endpointId] = json.optString("nodeId", endpointId)
                    val name = json.optString("name").takeIf { it.isNotBlank() } ?: endpointNames[endpointId] ?: "OFFGRID device"
                    endpointNames[endpointId] = name
                    upsertNode(endpointId, name, if (connectedEndpoints.contains(endpointId)) NodeStatus.CONNECTED else NodeStatus.AVAILABLE)
                }
                "MESSAGE" -> {
                    val message = jsonToMessage(json, endpointId)
                    _incoming.tryEmit(message)
                }
                else -> Log.d(TAG, "Ignoring unknown payload kind")
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Invalid payload from $endpointId", error)
        }
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

    private fun jsonToMessage(json: JSONObject, endpointId: String): Message {
        val remoteId = endpointRemoteIds[endpointId] ?: endpointId
        val type = runCatching {
            MessageType.valueOf(json.optString("type", MessageType.TEXT.name))
        }.getOrDefault(MessageType.TEXT)
        return Message(
            id = json.optString("id").ifBlank { "rx-$endpointId-${System.nanoTime()}" },
            conversationId = endpointId,
            senderId = remoteId,
            receiverId = selfId,
            content = json.optString("content"),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            status = MessageStatus.RECEIVED,
            type = type,
        )
    }

    private fun upsertNode(endpointId: String, name: String, status: NodeStatus) {
        val node = Node(
            id = endpointId,
            name = name,
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
        endpointRemoteIds.remove(endpointId)
        _discoveredNodes.value = _discoveredNodes.value.filterNot { it.id == endpointId }
    }

    private fun refreshLinkState() {
        _linkState.value = when {
            connectedEndpoints.isNotEmpty() -> LinkState.CONNECTED
            started -> LinkState.LISTENING
            else -> LinkState.OFFLINE
        }
    }

    private suspend fun awaitTask(task: com.google.android.gms.tasks.Task<Void>): Result<Unit> =
        withTimeout(PAYLOAD_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                task.addOnSuccessListener {
                    if (continuation.isActive) continuation.resume(Result.success(Unit))
                }.addOnFailureListener { error ->
                    if (continuation.isActive) continuation.resume(Result.failure(error))
                }
            }
        }

    override fun stop() {
        if (!started) return
        started = false
        stopDiscoverySafely()
        try { client.stopAdvertising() } catch (error: Throwable) { Log.w(TAG, "stopAdvertising failed", error) }
        try { client.stopAllEndpoints() } catch (error: Throwable) { Log.w(TAG, "stopAllEndpoints failed", error) }
        advertisingRunning = false
        connectedEndpoints.clear()
        pendingConnections.values.forEach { deferred ->
            if (!deferred.isCompleted) deferred.complete(Result.failure(IllegalStateException("Transport stopped")))
        }
        pendingConnections.clear()
        endpointNames.clear()
        endpointRemoteIds.clear()
        _discoveredNodes.value = emptyList()
        _linkState.value = LinkState.OFFLINE
        _transportStatus.value = "Offline"
    }

    companion object {
        private const val TAG = "OffGridNearby"
        private const val SERVICE_ID = "com.offgrid.app.offline"
        private const val CONNECTION_TIMEOUT_MS = 10_000L
        private const val PAYLOAD_TIMEOUT_MS = 10_000L
        private const val MAX_MESSAGE_BYTES = 24 * 1024
        private val STRATEGY = Strategy.P2P_CLUSTER
    }
}
