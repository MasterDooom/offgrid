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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Real phone-to-phone Nearby Connections transport used by the prototype.
 *
 * Nearby Connections remains the physical transport. The routing layer above it
 * learns routes from connected neighbours and forwards encrypted HopPackets.
 * Encryption is deliberately hop-by-hop: a relay decrypts one physical hop and
 * re-encrypts the plaintext for the next physical hop.
 */
class MockCommunicationTransport(private val context: Context) : CommunicationTransport {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val router = HopRouter(selfId = "")

    @Volatile private var selfId = ""
    @Volatile private var selfName = ""
    @Volatile private var started = false
    @Volatile private var discoveryRunning = false
    @Volatile private var advertisingRunning = false

    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val pendingConnections = ConcurrentHashMap<String, CompletableDeferred<Result<Unit>>>()
    private val endpointNames = ConcurrentHashMap<String, String>()
    private val endpointLogicalIds = ConcurrentHashMap<String, String>()
    private val logicalIdEndpoints = ConcurrentHashMap<String, String>()
    private val seenPackets = ConcurrentHashMap.newKeySet<String>()
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
                val name = displayNameFromAdvertisedName(connectionInfo.endpointName)
                val logicalId = logicalIdFromAdvertisedName(connectionInfo.endpointName) ?: endpointId
                endpointNames[endpointId] = name
                endpointLogicalIds[endpointId] = logicalId
                logicalIdEndpoints[logicalId] = endpointId
                upsertNode(endpointId, name, NodeStatus.CONNECTING, logicalId)
                _transportStatus.value = "Connecting to $name…"
                runCatching { client.acceptConnection(endpointId, payloadCallback) }
                    .onFailure { error ->
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
                    val logicalId = endpointLogicalIds[endpointId] ?: endpointId
                    logicalIdEndpoints[logicalId] = endpointId
                    upsertNode(endpointId, name, NodeStatus.CONNECTED, logicalId)
                    router.learnDirect(logicalId)
                    pendingConnections.remove(endpointId)?.complete(Result.success(Unit))
                    refreshLinkState()
                    _transportStatus.value = "Connected to $name • encrypted hop ready"
                    Log.d(TAG, "Connected endpoint=$endpointId logicalId=$logicalId name=$name")
                    scope.launch { announceRoutesTo(endpointId) }
                } else {
                    connectedEndpoints.remove(endpointId)
                    val name = endpointNames[endpointId] ?: "OFFGRID device"
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
                pendingConnections.remove(endpointId)?.complete(Result.failure(IllegalStateException("Device disconnected")))
                val logicalId = endpointLogicalIds.remove(endpointId)
                if (logicalId != null) {
                    logicalIdEndpoints.remove(logicalId, endpointId)
                    router.clearPeer(logicalId)
                }
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
                val name = displayNameFromAdvertisedName(info.endpointName)
                val logicalId = logicalIdFromAdvertisedName(info.endpointName) ?: endpointId
                endpointNames[endpointId] = name
                endpointLogicalIds[endpointId] = logicalId
                logicalIdEndpoints[logicalId] = endpointId
                val status = if (connectedEndpoints.contains(endpointId)) NodeStatus.CONNECTED else NodeStatus.AVAILABLE
                upsertNode(endpointId, name, status, logicalId)
                _transportStatus.value = "Found $name"
                Log.d(TAG, "FOUND endpoint=$endpointId logicalId=$logicalId name=$name")
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
                mainHandler.post {
                    safeCallback("deliver received message") { handlePayload(endpointId, bytes) }
                }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
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

        upsertNode(endpointId, node.name, NodeStatus.CONNECTING, node.logicalId)
        endpointLogicalIds[endpointId] = node.logicalId
        logicalIdEndpoints[node.logicalId] = endpointId
        _transportStatus.value = "Connecting to ${node.name}…"

        try {
            client.requestConnection(advertisedEndpointName(), endpointId, connectionCallback)
                .addOnFailureListener { error ->
                    safeCallback("connection request failure") {
                        pendingConnections.remove(endpointId)?.complete(Result.failure(error))
                        upsertNode(endpointId, node.name, NodeStatus.AVAILABLE, node.logicalId)
                        _transportStatus.value = "Connection request failed: ${errorMessage(error)}"
                    }
                }
        } catch (error: Throwable) {
            pendingConnections.remove(endpointId)?.complete(Result.failure(error))
            upsertNode(endpointId, node.name, NodeStatus.AVAILABLE, node.logicalId)
            return Result.failure(error)
        }

        return awaitConnection(endpointId, deferred)
    }

    private suspend fun awaitConnection(endpointId: String, deferred: CompletableDeferred<Result<Unit>>): Result<Unit> = try {
        withTimeout(CONNECTION_TIMEOUT_MS) { deferred.await() }
    } catch (error: Throwable) {
        pendingConnections.remove(endpointId)?.complete(Result.failure(error))
        upsertNode(endpointId, endpointNames[endpointId] ?: "OFFGRID device", NodeStatus.AVAILABLE, endpointLogicalIds[endpointId] ?: endpointId)
        Result.failure(error)
    }

    override suspend fun sendMessage(message: Message): Result<Unit> = sendMutex.withLock {
        val destinationId = message.recipientNodeId ?: endpointLogicalIds[message.receiverId] ?: message.receiverId
        if (destinationId.isBlank()) return@withLock Result.failure(IllegalStateException("Missing recipient"))

        val nextHopId = router.nextHop(destinationId, listOf(selfId))
            ?: if (logicalIdEndpoints[destinationId]?.let { connectedEndpoints.contains(it) } == true) destinationId else null
            ?: return@withLock Result.failure(IllegalStateException("No route to $destinationId"))

        val nextEndpoint = logicalIdEndpoints[nextHopId]
            ?: return@withLock Result.failure(IllegalStateException("No Nearby endpoint for $nextHopId"))
        if (!connectedEndpoints.contains(nextEndpoint)) {
            return@withLock Result.failure(IllegalStateException("Next hop $nextHopId is not connected"))
        }

        val plaintext = messageToJson(message, destinationId).toByteArray(Charsets.UTF_8)
        val packet = HopPacket(
            messageId = message.id,
            sourceNodeId = selfId,
            destinationNodeId = destinationId,
            previousHopId = selfId,
            hopCount = 0,
            maxHops = MAX_HOPS,
            ciphertext = HopEncryption.encrypt(plaintext, HopEncryption.linkKey(selfId, nextHopId)),
            path = listOf(selfId),
        )

        _transportStatus.value = if (nextHopId == destinationId) {
            "ENCRYPTING • AES-256-GCM • direct hop"
        } else {
            "ENCRYPTING • AES-256-GCM • route ${selfId.takeLast(4)} → ${nextHopId.takeLast(4)} → ${destinationId.takeLast(4)}"
        }
        sendToEndpoint(nextEndpoint, packet)
    }

    private fun handlePayload(endpointId: String, bytes: ByteArray) {
        val json = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }.getOrNull()
        if (json?.optString("kind") == ROUTE_KIND) {
            handleRouteAdvertisement(endpointId, json)
            return
        }

        val packet = HopPacket.fromJson(bytes)
        if (packet == null) {
            decodeLegacyMessage(endpointId, bytes)
            return
        }

        if (packet.messageId in seenPackets) {
            _transportStatus.value = "Duplicate packet ignored"
            return
        }
        if (packet.maxHops <= 0 || packet.maxHops > MAX_HOPS || packet.hopCount < 0 || packet.hopCount >= packet.maxHops) {
            _transportStatus.value = "Route dropped • hop limit reached"
            return
        }

        val previousHopId = endpointLogicalIds[endpointId] ?: packet.previousHopId
        val plaintext = try {
            _transportStatus.value = "DECRYPTING • hop ${packet.hopCount + 1}"
            HopEncryption.decrypt(packet.ciphertext, HopEncryption.linkKey(previousHopId, selfId))
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to decrypt hop packet from $endpointId", error)
            _transportStatus.value = "Encrypted packet rejected"
            return
        }

        if (packet.path.contains(selfId)) {
            _transportStatus.value = "Route dropped • loop detected"
            return
        }

        seenPackets.add(packet.messageId)
        val nextHopCount = packet.hopCount + 1

        if (packet.destinationNodeId == selfId) {
            emitDecryptedMessage(endpointId, packet, plaintext, nextHopCount)
            return
        }

        val nextHopId = router.nextHop(packet.destinationNodeId, packet.path + selfId)
            ?: if (logicalIdEndpoints[packet.destinationNodeId]?.let { connectedEndpoints.contains(it) } == true) packet.destinationNodeId else null

        if (nextHopId == null) {
            _transportStatus.value = "RELAYING • no route to ${packet.destinationNodeId}"
            val discovered = _discoveredNodes.value.firstOrNull { it.logicalId == packet.destinationNodeId && it.id !in packet.path }
            if (discovered != null) {
                scope.launch {
                    if (connectToDevice(discovered).isSuccess) {
                        forwardAfterConnection(discovered.id, packet, plaintext)
                    }
                }
            }
            return
        }

        if (nextHopId in packet.path || nextHopId == selfId) {
            _transportStatus.value = "Route dropped • loop detected"
            return
        }

        val nextEndpoint = logicalIdEndpoints[nextHopId]
        if (nextEndpoint == null || !connectedEndpoints.contains(nextEndpoint)) {
            _transportStatus.value = "RELAYING • next hop unavailable"
            return
        }

        forwardPacket(nextEndpoint, nextHopId, packet, plaintext, nextHopCount)
    }

    private fun handleRouteAdvertisement(endpointId: String, json: JSONObject) {
        val senderId = endpointLogicalIds[endpointId] ?: return
        val destinationId = json.optString("destinationNodeId")
        val advertisedDistance = json.optInt("distance", 0)
        val advertisedPath = json.optJSONArray("path").toStringList()
        if (destinationId.isBlank() || advertisedDistance <= 0) return
        if (senderId == selfId || destinationId == selfId) return
        if (selfId in advertisedPath || destinationId in advertisedPath.dropLast(1)) return

        val distance = advertisedDistance + 1
        val path = listOf(selfId) + advertisedPath
        val changed = router.learn(destinationId, senderId, distance, path)
        if (changed) {
            _transportStatus.value = "ROUTE LEARNED • ${selfId.takeLast(4)} → ${senderId.takeLast(4)} → ${destinationId.takeLast(4)}"
            Log.d(TAG, "Route learned destination=$destinationId nextHop=$senderId distance=$distance")
            scope.launch { announceRoutesTo(null) }
        }
    }

    private suspend fun announceRoutesTo(targetEndpointId: String?) {
        val routes = router.routes()
        val targets = if (targetEndpointId != null) listOf(targetEndpointId) else connectedEndpoints.toList()
        for (endpointId in targets) {
            if (!connectedEndpoints.contains(endpointId)) continue
            val peerId = endpointLogicalIds[endpointId] ?: continue
            for (route in routes) {
                if (peerId in route.path) continue
                val packet = JSONObject().apply {
                    put("kind", ROUTE_KIND)
                    put("version", 1)
                    put("destinationNodeId", route.destinationNodeId)
                    put("distance", route.distance)
                    put("path", JSONArray(route.path))
                }.toString().toByteArray(Charsets.UTF_8)
                runCatching { client.sendPayload(endpointId, Payload.fromBytes(packet)) }
            }
        }
    }

    private fun forwardAfterConnection(endpointId: String, packet: HopPacket, plaintext: ByteArray) {
        if (endpointId !in connectedEndpoints) return
        val nextHopId = endpointLogicalIds[endpointId] ?: return
        if (nextHopId in packet.path || nextHopId == selfId) return
        forwardPacket(endpointId, nextHopId, packet, plaintext, packet.hopCount + 1)
    }

    private fun forwardPacket(
        nextEndpoint: String,
        nextHopId: String,
        packet: HopPacket,
        plaintext: ByteArray,
        nextHopCount: Int,
    ) {
        val forwarded = packet.copy(
            previousHopId = selfId,
            hopCount = nextHopCount,
            path = packet.path + selfId,
            ciphertext = HopEncryption.encrypt(plaintext, HopEncryption.linkKey(selfId, nextHopId)),
        )
        _transportStatus.value = "RELAYING • hop $nextHopCount • DECRYPTED → RE-ENCRYPTED → ${nextHopId.takeLast(4)}"
        scope.launch { sendToEndpoint(nextEndpoint, forwarded) }
    }

    private suspend fun sendToEndpoint(endpointId: String, packet: HopPacket): Result<Unit> {
        if (!connectedEndpoints.contains(endpointId)) return Result.failure(IllegalStateException("Next hop is no longer connected"))
        val bytes = packet.toJson()
        if (bytes.size > MAX_MESSAGE_BYTES) return Result.failure(IllegalArgumentException("Encrypted packet is too large"))
        return try {
            client.sendPayload(endpointId, Payload.fromBytes(bytes))
            Log.d(TAG, "Encrypted packet ${packet.messageId}: ${packet.previousHopId} -> ${endpointLogicalIds[endpointId]} hop=${packet.hopCount}")
            Result.success(Unit)
        } catch (error: Throwable) {
            Log.e(TAG, "sendPayload failed for endpoint=$endpointId", error)
            _transportStatus.value = "Message failed: ${errorMessage(error)}"
            Result.failure(error)
        }
    }

    private fun emitDecryptedMessage(endpointId: String, packet: HopPacket, plaintext: ByteArray, hopCount: Int) {
        try {
            val json = JSONObject(plaintext.toString(Charsets.UTF_8))
            if (json.optString("kind") != "MESSAGE") return
            val content = json.optString("content")
            if (content.isBlank()) return
            val messageId = json.optString("id").ifBlank { packet.messageId }
            val type = runCatching { MessageType.valueOf(json.optString("type", MessageType.TEXT.name)) }.getOrDefault(MessageType.TEXT)
            _incoming.tryEmit(
                Message(
                    id = messageId,
                    conversationId = packet.sourceNodeId,
                    senderId = packet.sourceNodeId,
                    receiverId = selfId,
                    content = content,
                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                    status = MessageStatus.RECEIVED,
                    type = type,
                    recipientNodeId = selfId,
                    hopCount = hopCount,
                    hopEncrypted = true,
                )
            )
            _transportStatus.value = if (hopCount > 1) "DELIVERED • decrypted • arrived via $hopCount hops" else "DELIVERED • decrypted • direct hop"
            Log.d(TAG, "Delivered ${packet.messageId} after $hopCount hop(s)")
        } catch (error: Throwable) {
            Log.w(TAG, "Invalid decrypted message payload", error)
        }
    }

    private fun decodeLegacyMessage(endpointId: String, bytes: ByteArray) {
        try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            if (json.optString("kind") != "MESSAGE") return
            val content = json.optString("content")
            if (content.isBlank()) return
            val messageId = json.optString("id").ifBlank { "rx-$endpointId-${System.nanoTime()}" }
            val type = runCatching { MessageType.valueOf(json.optString("type", MessageType.TEXT.name)) }.getOrDefault(MessageType.TEXT)
            _incoming.tryEmit(
                Message(
                    id = messageId,
                    conversationId = endpointLogicalIds[endpointId] ?: endpointId,
                    senderId = endpointLogicalIds[endpointId] ?: endpointId,
                    receiverId = selfId,
                    content = content,
                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                    status = MessageStatus.RECEIVED,
                    type = type,
                )
            )
        } catch (error: Throwable) {
            Log.w(TAG, "Invalid legacy message payload from $endpointId", error)
        }
    }

    private fun messageToJson(message: Message, destinationId: String): String = JSONObject().apply {
        put("kind", "MESSAGE")
        put("id", message.id)
        put("senderId", selfId)
        put("receiverId", destinationId)
        put("content", message.content)
        put("timestamp", message.timestamp)
        put("type", message.type.name)
    }.toString()

    private fun advertisedEndpointName(): String = "$selfName|id=$selfId"

    private fun startAdvertisingSafely() {
        if (advertisingRunning) return
        try {
            client.startAdvertising(
                advertisedEndpointName(),
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
        try { block() } catch (error: Throwable) {
            Log.e(TAG, "$operation callback failed", error)
            runCatching { _transportStatus.value = "$operation failed: ${errorMessage(error)}" }
        }
    }

    private fun errorMessage(error: Throwable): String {
        val code = (error as? ApiException)?.statusCode
        return if (code != null) "${ConnectionsStatusCodes.getStatusCodeString(code)} ($code)" else error.message ?: error.javaClass.simpleName
    }

    private fun displayNameFromAdvertisedName(name: String): String = name.substringBefore("|id=").ifBlank { "OFFGRID device" }

    private fun logicalIdFromAdvertisedName(name: String): String? = name.substringAfter("|id=", missingDelimiterValue = "").substringBefore("|").ifBlank { null }

    private fun upsertNode(endpointId: String, name: String, status: NodeStatus, logicalId: String) {
        val node = Node(
            id = endpointId,
            name = name.ifBlank { "OFFGRID device" },
            status = status,
            lastSeen = System.currentTimeMillis(),
            isSimulated = false,
            hops = router.routeTo(logicalId)?.distance ?: 1,
            capabilities = setOf(DeviceCapability.MESSAGING, DeviceCapability.RELAY),
            logicalId = logicalId,
        )
        _discoveredNodes.value = (_discoveredNodes.value.filterNot { it.id == endpointId } + node).distinctBy { it.id }
    }

    private fun removeEndpoint(endpointId: String) {
        val logicalId = endpointLogicalIds.remove(endpointId)
        if (logicalId != null) {
            logicalIdEndpoints.remove(logicalId, endpointId)
            router.clearPeer(logicalId)
        }
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
        pendingConnections.values.forEach { deferred -> runCatching { deferred.complete(Result.failure(IllegalStateException("Transport stopped"))) } }
        pendingConnections.clear()
        endpointNames.clear()
        endpointLogicalIds.clear()
        logicalIdEndpoints.clear()
        seenPackets.clear()
        _discoveredNodes.value = emptyList()
        _linkState.value = LinkState.OFFLINE
        _transportStatus.value = "Offline"
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList { for (i in 0 until length()) add(optString(i)) }
    }

    companion object {
        private const val TAG = "OffGridNearby"
        private const val SERVICE_ID = "com.offgrid.app.offline"
        private const val CONNECTION_TIMEOUT_MS = 10_000L
        private const val MAX_MESSAGE_BYTES = 8_192
        private const val MAX_HOPS = 8
        private const val ROUTE_KIND = "OFFGRID_ROUTE"
        private val STRATEGY = Strategy.P2P_CLUSTER
    }
}
