package com.offgrid.app.data.security

import org.json.JSONArray
import org.json.JSONObject

/** Wire envelope carried over one physical Nearby Connections link. */
data class HopPacket(
    val messageId: String,
    val sourceNodeId: String,
    val destinationNodeId: String,
    val previousHopId: String,
    val hopCount: Int,
    val maxHops: Int,
    val ciphertext: String,
    val path: List<String> = emptyList(),
) {
    fun toJson(): ByteArray = JSONObject().apply {
        put("kind", KIND)
        put("version", VERSION)
        put("messageId", messageId)
        put("sourceNodeId", sourceNodeId)
        put("destinationNodeId", destinationNodeId)
        put("previousHopId", previousHopId)
        put("hopCount", hopCount)
        put("maxHops", maxHops)
        put("ciphertext", ciphertext)
        put("path", JSONArray(path))
    }.toString().toByteArray(Charsets.UTF_8)

    companion object {
        const val KIND = "OFFGRID_HOP"
        const val VERSION = 1

        fun fromJson(bytes: ByteArray): HopPacket? = runCatching {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            if (json.optString("kind") != KIND || json.optInt("version") != VERSION) return null
            val pathJson = json.optJSONArray("path")
            val path = buildList {
                if (pathJson != null) {
                    for (i in 0 until pathJson.length()) add(pathJson.getString(i))
                }
            }
            HopPacket(
                messageId = json.getString("messageId"),
                sourceNodeId = json.getString("sourceNodeId"),
                destinationNodeId = json.getString("destinationNodeId"),
                previousHopId = json.getString("previousHopId"),
                hopCount = json.getInt("hopCount"),
                maxHops = json.getInt("maxHops"),
                ciphertext = json.getString("ciphertext"),
                path = path,
            )
        }.getOrNull()
    }
}
