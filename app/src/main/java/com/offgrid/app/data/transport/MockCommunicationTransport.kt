package com.offgrid.app.data.transport

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Real phone-to-phone transport for the MVP.
 *
 * Uses Google Nearby Connections with the P2P_CLUSTER strategy. Nearby Connections establishes
 * fully-offline peer-to-peer links using the device radios and exchanges byte payloads directly
 * between participating devices. There is no OffGrid cloud/backend in the message path.
 *
 * The historical class name is kept so the rest of the MVP remains small. The old emulator TCP
 * implementation is no longer used by the app.
 */
class MockCommunicationTransport(private val context: Context) : CommunicationTransport {

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var selfId: String = ""
    private var selfName: String = ""
    private var started = false

    private val endpointToNodeId = ConcurrentHashMap<String, String>()
    private val nodeIdToEndpoint = ConcurrentHashMap<String, String>()
    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val pendingConnections = ConcurrentHashMap<String, CompletableDeferred<Result<Unit>>>()

    private val _discoveredNodes = MutableStateFlow<List<Node>>(emptyList())
    override val discoveredNodes = _discoveredNodes.asStateFlow()

    private val _linkState = MutableStateFlow(LinkState.OFFLINE)
    override val linkState = _linkState.asStateFlow()

    private val _incoming = MutableSharedFlow<Message>(extraBufferCapacity = 64)
    override val incomingMessages = _incoming.asSharedFlow()

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            Log.d(TAG, "Connection initiated: ${connectionInfo.endpointName} ($endpointId)")
            // Controlled demo network: auto-accept. Production should verify authentication digits.
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                setNodeStatus(endpointId, NodeStatus.CONNECTED)
                _linkState.value = LinkState.CONNECTED
                pendingConnections.remove(endpointId)?.complete(Result.success(Unit))
                sendHello(endpointId)
                Log.d(TAG, "Connected: $endpointId")
            } else {
                connectedEndpoints.remove(endpointId)
                setNodeStatus(endpointId, NodeStatus.AVAILABLE)
                pendingConnections.remove(endpointId)?.complete(
                    Result.failure(IllegalStateException("Connection rejected: ${resolution.status.statusCode}"))
                )
                refreshLinkState()
            }
        }

        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            val nodeId = endpointToNodeId.remove(endpointId)
            if (nodeId != null) nodeIdToEndpoint.remove(nodeId)
            removeEndpoint(endpointId)
            pendingConnections.remove(endpointId)?.complete(
                Result.failure(IllegalStateException("Device disconnected"))
            )
            refreshLinkState()
            Log.d(TAG, "Disconnected: $endpointId")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.endpointName == selfName) return

            endpointToNodeId.putIfAbsent(endpointId, endpointId)
            upsertNode(
                Node(
                    id = endpointId,
                    name = info.endpointName,
                    status = if (connectedEndpoints.contains(endpointId)) NodeStatus.CONNECTED else NodeStatus.AVAILABLE,
                    isSimulated = false,
                    hops = 1,
                    capabilities = setOf(DeviceCapability.MESSAGING),
                )
            )

            if (!connectedEndpoints.contains(endpointId) && !pendingConnections.containsKey(endpointId)) {
                val deferred = CompletableDeferred<Result<Unit>>()
                pendingConnections[endpointId] = deferred
                client.requestConnection(selfName, endpointId, connectionCallback)
                    .addOnFailureListener { error ->
                        pendingConnections.remove(endpointId)?.complete(Result.failure(error))
                        setNodeStatus(endpointId, NodeStatus.AVAILABLE)
                    }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            if (!connectedEndpoints.contains(endpointId)) removeEndpoint(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            handlePayload(endpointId, bytes.toString(Charsets.UTF_8))
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.FAILURE ||
                update.status == PayloadTransferUpdate.Status.CANCELED
            ) {
                Log.w(TAG, "Payload failed from/to $endpointId: ${update.status}")
            }
        }
    }

    override suspend fun start(selfId: String, selfName: String) {
        this.selfId = selfId
        this.selfName = selfName
        if (started) return
        started = true
        startAdvertising()
        startDiscovery()
    }

    override suspend fun discoverDevices() {
        if (!started) start(selfId, selfName) else startDiscovery()
    }

    override suspend fun connectToDevice(node: Node): Result<Unit> {
        val endpointId = nodeIdToEndpoint[node.id]
            ?: node.id.takeIf { endpointToNodeId.containsKey(it) }
            ?: return Result.failure(IllegalStateException("Device is no longer nearby"))

        if (connectedEndpoints.contains(endpointId)) return Result.success(Unit)

        val deferred = pendingConnections[endpointId] ?: CompletableDeferred<Result<Unit>>().also {
            pendingConnections[endpointId] = it
            client.requestConnection(selfName, endpointId, connectionCallback)
                .addOnFailureListener { error ->
                    pendingConnections.remove(endpointId)?.complete(Result.failure(error))
                }
        }

        return runCatching { withTimeout(CONNECTION_TIMEOUT_MS) { deferred.await() } }
            .getOrElse { Result.failure(it) }
    }

    override suspend fun sendMessage(message: Message): Result<Unit> {
        val endpointId = nodeIdToEndpoint[message.receiverId]
            ?: endpointToNodeId.entries.firstOrNull { it.value == message.receiverId }?.key
            ?: return Result.failure(IllegalStateException("No live connection to ${message.receiverId}"))

        if (!connectedEndpoints.contains(endpointId)) {
            return Result.failure(IllegalStateException("Device is not connected"))
        }

        val payload = Payload.fromBytes(messageToJson(message).toByteArray(Charsets.UTF_8))
        return awaitTask(client.sendPayload(endpointId, payload))
    }

    private fun startAdvertising() {
        client.startAdvertising(
            selfName,
            SERVICE_ID,
            connectionCallback,
            AdvertisingOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnSuccessListener {
            Log.d(TAG, "Advertising started")
            refreshLinkState()
        }.addOnFailureListener {
            Log.e(TAG, "Advertising failed", it)
            refreshLinkState()
        }
    }

    private fun startDiscovery() {
        client.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(STRATEGY).build(),
        ).addOnSuccessListener {
            Log.d(TAG, "Discovery started")
            refreshLinkState()
        }.addOnFailureListener {
            Log.e(TAG, "Discovery failed", it)
            refreshLinkState()
        }
    }

    private fun sendHello(endpointId: String) {
        val hello = JSONObject().apply {
            put("kind", "HELLO")
            put("nodeId", selfId)
            put("name", selfName)
            put("version", 1)
        }.toString().toByteArray(Charsets.UTF_8)
        client.sendPayload(endpointId, Payload.fromBytes(hello))
            .addOnFailureListener { Log.w(TAG, "HELLO send failed", it) }
    }

    private fun handlePayload(endpointId: String, raw: String) {
        runCatching {
            val json = JSONObject(raw)
            when (json.optString("kind")) {
                "HELLO" -> handleHello(endpointId, json)
                "MESSAGE" -> {
                    val senderId = json.getString("senderId")
                    _incoming.tryEmit(jsonToMessage(json, senderId))
                }
            }
        }.onFailure { Log.w(TAG, "Invalid payload", it) }
    }

    private fun handleHello(endpointId: String, json: JSONObject) {
        val remoteId = json.getString("nodeId")
        val remoteName = json.optString("name", "OFFGRID device")
        endpointToNodeId[endpointId] = remoteId
        nodeIdToEndpoint[remoteId] = endpointId

        val existing = _discoveredNodes.value.firstOrNull { it.id == endpointId || it.id == remoteId }
        val node = (existing ?: Node(id = remoteId, name = remoteName)).copy(
            id = remoteId,
            name = remoteName,
            status = NodeStatus.CONNECTED,
            lastSeen = System.currentTimeMillis(),
            isSimulated = false,
            hops = 1,
            capabilities = setOf(DeviceCapability.MESSAGING),
        )

        _discoveredNodes.value = _discoveredNodes.value
            .filterNot { it.id == endpointId || it.id == remoteId }
            .plus(node)
            .distinctBy { it.id }
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

    private fun jsonToMessage(json: JSONObject, senderId: String): Message = Message(
        id = json.getString("id"),
        conversationId = senderId,
        senderId = senderId,
        receiverId = json.getString("receiverId"),
        content = json.getString("content"),
        timestamp = json.optLong("timestamp", System.currentTimeMillis()),
        status = MessageStatus.RECEIVED,
        type = runCatching { MessageType.valueOf(json.optString("type", MessageType.TEXT.name)) }
            .getOrDefault(MessageType.TEXT),
    )

    private fun upsertNode(node: Node) {
        _discoveredNodes.value = (_discoveredNodes.value.filterNot { it.id == node.id } + node)
            .distinctBy { it.id }
    }

    private fun setNodeStatus(endpointId: String, status: NodeStatus) {
        val id = endpointToNodeId[endpointId] ?: endpointId
        _discoveredNodes.value = _discoveredNodes.value.map {
            if (it.id == id || it.id == endpointId) it.copy(status = status, lastSeen = System.currentTimeMillis()) else it
        }
    }

    private fun removeEndpoint(endpointId: String) {
        val id = endpointToNodeId[endpointId]
        _discoveredNodes.value = _discoveredNodes.value.filterNot {
            it.id == endpointId || (id != null && it.id == id)
        }
    }

    private fun refreshLinkState() {
        _linkState.value = when {
            connectedEndpoints.isNotEmpty() -> LinkState.CONNECTED
            started -> LinkState.LISTENING
            else -> LinkState.OFFLINE
        }
    }

    private suspend fun awaitTask(task: com.google.android.gms.tasks.Task<Void>): Result<Unit> =
        suspendCancellableCoroutine { continuation ->
            task.addOnSuccessListener { continuation.resume(Result.success(Unit)) }
                .addOnFailureListener { error -> continuation.resume(Result.failure(error)) }
        }

    override fun stop() {
        if (!started) return
        started = false
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        endpointToNodeId.clear()
        nodeIdToEndpoint.clear()
        connectedEndpoints.clear()
        pendingConnections.values.forEach {
            it.complete(Result.failure(IllegalStateException("Transport stopped")))
        }
        pendingConnections.clear()
        _discoveredNodes.value = emptyList()
        _linkState.value = LinkState.OFFLINE
        scope.cancel()
    }

    companion object {
        private const val TAG = "OffGridNearby"
        private const val SERVICE_ID = "com.offgrid.app.offline"
        private const val CONNECTION_TIMEOUT_MS = 10_000L
        private val STRATEGY = Strategy.P2P_CLUSTER
    }
}
