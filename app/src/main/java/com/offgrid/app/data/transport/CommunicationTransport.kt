package com.offgrid.app.data.transport

import com.offgrid.app.data.model.LinkState
import com.offgrid.app.data.model.Message
import com.offgrid.app.data.model.Node
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single seam between the app (UI + domain/repositories) and whatever radio/network moves
 * bytes between OFFGRID nodes. Nothing above this interface should know a socket, BLE stack,
 * or Meshtastic device exists.
 *
 * Today:    MockCommunicationTransport — TCP over the emulator's host loopback (10.0.2.2),
 *           bridged between two emulator instances with `adb forward`. Real demo, no radios.
 * Tomorrow: MeshtasticCommunicationTransport — implements the same four methods against a
 *           Meshtastic radio over BLE/USB-serial (see https://meshtastic.org/docs/software/android/).
 *           MessagingRepository, EmergencyRepository and every screen in ui/ stay unchanged.
 */
interface CommunicationTransport {
    /** Everything currently known: simulated demo nodes + the real peer link, if any. */
    val discoveredNodes: StateFlow<List<Node>>

    /** Coarse status of this device's own link to the network. */
    val linkState: StateFlow<LinkState>

    /** Messages arriving from any node, in real time. */
    val incomingMessages: Flow<Message>

    /** Must be called once with this device's identity before send/receive will work. */
    suspend fun start(selfId: String, selfName: String)

    /** Refresh/announce presence. Cheap and safe to call repeatedly (e.g. pull-to-refresh). */
    suspend fun discoverDevices()

    /** Establish a link to [node]. For simulated nodes this is instant; for the real peer it dials the socket. */
    suspend fun connectToDevice(node: Node): Result<Unit>

    /** Send [message] to its receiverId. Fails loudly (Result.failure) rather than pretending to succeed. */
    suspend fun sendMessage(message: Message): Result<Unit>

    /** Release sockets/threads. */
    fun stop()
}
