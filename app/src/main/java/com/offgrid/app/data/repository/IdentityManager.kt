package com.offgrid.app.data.repository

import android.content.Context
import java.util.UUID

/** Generates/persists this installation's OFFGRID identity and its demo transport ports. */
class IdentityManager(context: Context) {
    private val prefs = context.getSharedPreferences("offgrid_identity", Context.MODE_PRIVATE)

    val nodeId: String = prefs.getString(KEY_NODE_ID, null) ?: generateNodeId().also {
        prefs.edit().putString(KEY_NODE_ID, it).apply()
    }

    var displayName: String
        get() = prefs.getString(KEY_NAME, null) ?: "Node ${nodeId.takeLast(4)}"
        set(value) = prefs.edit().putString(KEY_NAME, value).apply()

    var selfPort: Int
        get() = prefs.getInt(KEY_SELF_PORT, 8990)
        set(value) = prefs.edit().putInt(KEY_SELF_PORT, value).apply()

    var peerHost: String
        get() = prefs.getString(KEY_PEER_HOST, "10.0.2.2") ?: "10.0.2.2"
        set(value) = prefs.edit().putString(KEY_PEER_HOST, value).apply()

    var peerPort: Int
        get() = prefs.getInt(KEY_PEER_PORT, 8991)
        set(value) = prefs.edit().putInt(KEY_PEER_PORT, value).apply()

    private fun generateNodeId(): String =
        "OFFGRID-" + UUID.randomUUID().toString().replace("-", "").take(4).uppercase()

    companion object {
        private const val KEY_NODE_ID = "node_id"
        private const val KEY_NAME = "display_name"
        private const val KEY_SELF_PORT = "self_port"
        private const val KEY_PEER_HOST = "peer_host"
        private const val KEY_PEER_PORT = "peer_port"
    }
}
