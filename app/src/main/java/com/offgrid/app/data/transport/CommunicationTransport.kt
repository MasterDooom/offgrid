package com.offgrid.app.data.transport

import com.offgrid.app.data.model.LinkState
import com.offgrid.app.data.model.Message
import com.offgrid.app.data.model.Node
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Common seam between the app and the physical/offline transport. */
interface CommunicationTransport {
    val discoveredNodes: StateFlow<List<Node>>
    val linkState: StateFlow<LinkState>
    val incomingMessages: Flow<Message>
    val transportStatus: StateFlow<String>

    suspend fun start(selfId: String, selfName: String)
    suspend fun discoverDevices()
    suspend fun connectToDevice(node: Node): Result<Unit>
    suspend fun sendMessage(message: Message): Result<Unit>
    fun stop()
}
