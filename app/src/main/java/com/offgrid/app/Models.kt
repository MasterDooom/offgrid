package com.offgrid.app

import org.json.JSONObject
import java.util.UUID

data class Node(
    val nodeId: String,
    val displayName: String,
    val connectionState: ConnectionState = ConnectionState.UNAVAILABLE,
    val lastSeen: Long = System.currentTimeMillis(),
)

enum class ConnectionState { UNAVAILABLE, DISCOVERED, CONNECTING, CONNECTED }

enum class PacketType { MESSAGE, EMERGENCY, STATUS }

enum class PacketPriority { NORMAL, HIGH, CRITICAL }

data class Packet(
    val messageId: String = UUID.randomUUID().toString(),
    val sourceId: String,
    val destinationId: String? = null,
    val ttl: Int = 8,
    val timestamp: Long = System.currentTimeMillis(),
    val payload: String,
    val route: List<String> = emptyList(),
    val type: PacketType = PacketType.MESSAGE,
    val priority: PacketPriority = PacketPriority.NORMAL,
) {
    fun toJson(): String = JSONObject().apply {
        put("messageId", messageId)
        put("sourceId", sourceId)
        put("destinationId", destinationId)
        put("ttl", ttl)
        put("timestamp", timestamp)
        put("payload", payload)
        put("route", route)
        put("type", type.name)
        put("priority", priority.name)
    }.toString()

    companion object {
        fun fromJson(value: String): Packet = JSONObject(value).let { json ->
            Packet(
                messageId = json.getString("messageId"),
                sourceId = json.getString("sourceId"),
                destinationId = json.optString("destinationId").ifBlank { null },
                ttl = json.optInt("ttl", 8),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                payload = json.getString("payload"),
                route = json.optJSONArray("route")?.let { array ->
                    List(array.length()) { array.getString(it) }
                } ?: emptyList(),
                type = runCatching { PacketType.valueOf(json.optString("type", PacketType.MESSAGE.name)) }
                    .getOrDefault(PacketType.MESSAGE),
                priority = runCatching { PacketPriority.valueOf(json.optString("priority", PacketPriority.NORMAL.name)) }
                    .getOrDefault(PacketPriority.NORMAL),
            )
        }
    }
}

data class ChatMessage(val packet: Packet, val inbound: Boolean)
