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

data class Packet(
    val messageId: String = UUID.randomUUID().toString(),
    val sourceId: String,
    val destinationId: String? = null,
    val ttl: Int = 8,
    val timestamp: Long = System.currentTimeMillis(),
    val payload: String,
    val route: List<String> = emptyList(),
) {
    fun toJson(): String = JSONObject().apply {
        put("messageId", messageId); put("sourceId", sourceId); put("destinationId", destinationId)
        put("ttl", ttl); put("timestamp", timestamp); put("payload", payload); put("route", route)
    }.toString()

    companion object {
        fun fromJson(value: String): Packet = JSONObject(value).let { json ->
            Packet(json.getString("messageId"), json.getString("sourceId"),
                json.optString("destinationId").ifBlank { null }, json.getInt("ttl"),
                json.getLong("timestamp"), json.getString("payload"),
                json.optJSONArray("route")?.let { array -> List(array.length()) { array.getString(it) } } ?: emptyList())
        }
    }
}

data class ChatMessage(val packet: Packet, val inbound: Boolean)
