package com.offgrid.app.legacy.wifidirect

/** Foundation for Milestone 2. The caller supplies a routing policy and persistence queue later. */
class PacketForwarder(private val localNodeId: String) {
    private val processedIds = LinkedHashSet<String>()

    fun shouldDeliver(packet: Packet): Boolean = packet.destinationId == null || packet.destinationId == localNodeId

    fun prepareForward(packet: Packet, arrivedFrom: String?): Packet? {
        if (!processedIds.add(packet.messageId) || packet.ttl <= 1) return null
        if (arrivedFrom == localNodeId || packet.route.contains(localNodeId)) return null
        return packet.copy(ttl = packet.ttl - 1, route = packet.route + localNodeId)
    }
}
