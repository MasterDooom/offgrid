package com.offgrid.app.data.transport

/**
 * Small, transport-agnostic routing table for the OffGrid mesh.
 *
 * It only decides which logical OffGrid node should be the next hop. The
 * CommunicationTransport remains responsible for turning that logical ID into
 * a Nearby endpoint and moving bytes over the physical connection.
 */
class HopRouter(
    initialSelfId: String = "",
    private val maxHops: Int = 8,
) {
    @Volatile private var selfId: String = initialSelfId

    data class Route(
        val destinationNodeId: String,
        val nextHopNodeId: String,
        val distance: Int,
        val path: List<String>,
    )

    private val routes = linkedMapOf<String, Route>()

    @Synchronized
    fun setIdentity(nodeId: String) {
        if (nodeId.isBlank() || nodeId == selfId) return
        selfId = nodeId
        routes.clear()
    }

    /** Learn a route advertised by a neighbour. Returns true when the table changed. */
    @Synchronized
    fun learn(
        destinationNodeId: String,
        nextHopNodeId: String,
        distance: Int,
        path: List<String>,
    ): Boolean {
        if (destinationNodeId.isBlank() || nextHopNodeId.isBlank()) return false
        if (destinationNodeId == selfId) return false
        if (distance < 1 || distance > maxHops) return false
        if (selfId in path || destinationNodeId in path.dropLast(1)) return false
        if (nextHopNodeId == selfId) return false

        val candidate = Route(destinationNodeId, nextHopNodeId, distance, path)
        val existing = routes[destinationNodeId]
        if (existing != null && existing.distance <= distance) return false
        routes[destinationNodeId] = candidate
        return true
    }

    @Synchronized
    fun learnDirect(peerNodeId: String) {
        if (peerNodeId.isBlank() || peerNodeId == selfId) return
        routes[peerNodeId] = Route(peerNodeId, peerNodeId, 1, listOf(selfId, peerNodeId))
    }

    /** Prefer the shortest known route while never selecting a node already in the packet path. */
    @Synchronized
    fun nextHop(destinationNodeId: String, packetPath: List<String>): String? {
        val route = routes[destinationNodeId] ?: return null
        if (route.nextHopNodeId in packetPath) return null
        return route.nextHopNodeId
    }

    @Synchronized
    fun routes(): List<Route> = routes.values.toList()

    @Synchronized
    fun routeTo(destinationNodeId: String): Route? = routes[destinationNodeId]

    @Synchronized
    fun clearPeer(peerNodeId: String) {
        routes.entries.removeIf { (_, route) ->
            route.nextHopNodeId == peerNodeId || peerNodeId in route.path
        }
    }
}
