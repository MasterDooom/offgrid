package com.offgrid.app.legacy.wifidirect

import kotlinx.coroutines.flow.StateFlow

interface TransportLayer {
    val peers: StateFlow<List<Node>>
    val connection: StateFlow<ConnectionState>
    suspend fun discoverPeers()
    suspend fun connect(peer: Node)
    suspend fun disconnect()
    suspend fun send(packet: Packet)
}
