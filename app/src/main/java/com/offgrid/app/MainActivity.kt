package com.offgrid.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var transport: WifiDirectTransport
    private val nodeId by lazy {
        getSharedPreferences("offgrid", MODE_PRIVATE).getString("node_id", null)
            ?: UUID.randomUUID().toString().also { getSharedPreferences("offgrid", MODE_PRIVATE).edit().putString("node_id", it).apply() }
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transport = WifiDirectTransport(applicationContext)
        setContent { OffGridScreen(nodeId, transport, ::ensurePermissions, ::hasNearbyPermission, ::locationEnabled, ::openLocationSettings) }
    }
    override fun onStart() { super.onStart(); transport.registerReceiver() }
    override fun onStop() { transport.unregisterReceiver(); super.onStop() }
    override fun onDestroy() { transport.dispose(); super.onDestroy() }

    private fun ensurePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        permissionLauncher.launch(permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray())
    }
    private fun hasNearbyPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }
    private fun locationEnabled(): Boolean = (getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled
    private fun openLocationSettings() = startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
}

@androidx.compose.runtime.Composable
private fun OffGridScreen(
    nodeId: String,
    transport: WifiDirectTransport,
    requestPermission: () -> Unit,
    hasNearbyPermission: () -> Boolean,
    locationEnabled: () -> Boolean,
    openLocationSettings: () -> Unit,
) {
    val peers by transport.peers.collectAsState()
    val connection by transport.connection.collectAsState()
    val scope = rememberCoroutineScope()
    val logs = remember { mutableStateListOf<String>() }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var draft by remember { mutableStateOf("") }
    LaunchedEffect(transport) {
        launch { transport.events.collect { logs.add(0, it); if (logs.size > 8) logs.removeLast() } }
        launch { transport.incoming.collect { messages.add(ChatMessage(it, true)); logs.add(0, "Received packet ${it.messageId.take(8)}") } }
    }

    MaterialTheme {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("OFFGRID", style = MaterialTheme.typography.headlineMedium)
            Text("Node ${nodeId.take(8)} • $connection")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = requestPermission) { Text("Grant permission") }
                Button(onClick = {
                    when {
                        !hasNearbyPermission() -> requestPermission()
                        !locationEnabled() -> openLocationSettings()
                        else -> scope.launch { transport.discoverPeers() }
                    }
                }) { Text("Discover peers") }
                Button(onClick = { scope.launch { transport.disconnect() } }) { Text("Disconnect") }
            }
            if (!locationEnabled()) Text("Location Mode is off. Android requires it for Wi-Fi Direct discovery on many versions/devices.")
            Text("Nearby devices", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.weight(0.8f)) {
                items(peers, key = { it.nodeId }) { peer ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp).clickable { scope.launch { transport.connect(peer) } }) {
                        Text("${peer.displayName} — tap to connect", Modifier.padding(12.dp))
                    }
                }
            }
            OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), label = { Text("Message") }, enabled = connection == ConnectionState.CONNECTED)
            Button(enabled = draft.isNotBlank() && connection == ConnectionState.CONNECTED, onClick = {
                val packet = Packet(sourceId = nodeId, payload = draft)
                scope.launch { runCatching { transport.send(packet); messages.add(ChatMessage(packet, false)); draft = "" }.onFailure { logs.add(0, "Send failed: ${it.message}") } }
            }) { Text("Send") }
            Text("Messages", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.weight(1f)) { items(messages, key = { it.packet.messageId }) { message -> Text((if (message.inbound) "← " else "→ ") + message.packet.payload) } }
            Text("Event log", style = MaterialTheme.typography.titleMedium)
            logs.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
