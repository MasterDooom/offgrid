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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {
    private lateinit var transport: WifiDirectTransport
    private val nodeId by lazy {
        getSharedPreferences("offgrid", MODE_PRIVATE).getString("node_id", null)
            ?: UUID.randomUUID().toString().also {
                getSharedPreferences("offgrid", MODE_PRIVATE).edit().putString("node_id", it).apply()
            }
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transport = WifiDirectTransport(applicationContext)
        setContent {
            OffGridApp(nodeId, transport, ::ensurePermissions, ::hasNearbyPermission, ::locationEnabled, ::openLocationSettings)
        }
    }

    override fun onStart() { super.onStart(); transport.registerReceiver() }
    override fun onStop() { transport.unregisterReceiver(); super.onStop() }
    override fun onDestroy() { transport.dispose(); super.onDestroy() }

    private fun ensurePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(
            permissions.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
        )
    }

    private fun hasNearbyPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun locationEnabled(): Boolean =
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled

    private fun openLocationSettings() = startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
}

private enum class AppTab { HOME, NETWORK, CHAT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OffGridApp(
    nodeId: String,
    transport: WifiDirectTransport,
    requestPermission: () -> Unit,
    hasNearbyPermission: () -> Boolean,
    locationEnabled: () -> Boolean,
    openLocationSettings: () -> Unit,
) {
    var tab by remember { mutableStateOf(AppTab.HOME) }
    val peers by transport.peers.collectAsState()
    val connection by transport.connection.collectAsState()
    val scope = rememberCoroutineScope()
    val logs = remember { mutableStateListOf<String>() }
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var draft by remember { mutableStateOf("") }

    LaunchedEffect(transport) {
        launch {
            transport.events.collect {
                logs.add(0, it)
                if (logs.size > 12) logs.removeLast()
            }
        }
        launch {
            transport.incoming.collect {
                messages.add(ChatMessage(it, true))
                logs.add(0, "Received ${it.type.name.lowercase()} packet ${it.messageId.take(8)}")
            }
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Column {
                        Text("OFFGRID", style = MaterialTheme.typography.titleLarge)
                        Text("Local communication layer", style = MaterialTheme.typography.labelSmall)
                    }
                })
            },
            bottomBar = {
                Row(
                    Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    FilterChip(selected = tab == AppTab.HOME, onClick = { tab = AppTab.HOME }, label = { Text("Home") })
                    FilterChip(selected = tab == AppTab.NETWORK, onClick = { tab = AppTab.NETWORK }, label = { Text("Network") })
                    FilterChip(selected = tab == AppTab.CHAT, onClick = { tab = AppTab.CHAT }, label = { Text("Messages") })
                }
            }
        ) { padding ->
            when (tab) {
                AppTab.HOME -> HomeScreen(
                    Modifier.padding(padding), nodeId, peers.size, connection,
                    hasNearbyPermission(), locationEnabled(), requestPermission, openLocationSettings,
                    onDiscover = { scope.launch { transport.discoverPeers() } },
                    onEmergency = {
                        if (connection == ConnectionState.CONNECTED) {
                            val packet = Packet(
                                sourceId = nodeId,
                                payload = "EMERGENCY BROADCAST — assistance required",
                                type = PacketType.EMERGENCY,
                                priority = PacketPriority.CRITICAL,
                            )
                            scope.launch {
                                runCatching { transport.send(packet); logs.add(0, "Emergency broadcast sent") }
                                    .onFailure { logs.add(0, "Emergency failed: ${it.message}") }
                            }
                        } else logs.add(0, "Emergency blocked: no connected peer")
                    },
                    logs = logs,
                )
                AppTab.NETWORK -> NetworkScreen(
                    Modifier.padding(padding), nodeId, peers, connection,
                    onDiscover = { scope.launch { transport.discoverPeers() } },
                    onConnect = { peer -> scope.launch { transport.connect(peer) } },
                    onDisconnect = { scope.launch { transport.disconnect() } },
                )
                AppTab.CHAT -> ChatScreen(
                    Modifier.padding(padding), connection, messages, draft, { draft = it },
                    onSend = {
                        val packet = Packet(sourceId = nodeId, payload = draft)
                        scope.launch {
                            runCatching {
                                transport.send(packet)
                                messages.add(ChatMessage(packet, false))
                                draft = ""
                            }.onFailure { logs.add(0, "Send failed: ${it.message}") }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier,
    nodeId: String,
    peerCount: Int,
    connection: ConnectionState,
    permissionGranted: Boolean,
    locationOn: Boolean,
    requestPermission: () -> Unit,
    openLocationSettings: () -> Unit,
    onDiscover: () -> Unit,
    onEmergency: () -> Unit,
    logs: List<String>,
) {
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Your node", style = MaterialTheme.typography.labelLarge)
                    Text(nodeId.take(8).uppercase(), style = MaterialTheme.typography.headlineMedium)
                    Text(connectionLabel(connection))
                    Spacer(Modifier.height(4.dp))
                    Text("${peerCount} nearby node${if (peerCount == 1) "" else "s"}")
                }
            }
        }
        item {
            if (!permissionGranted) {
                OutlinedButton(onClick = requestPermission, Modifier.fillMaxWidth()) { Text("Grant nearby-device permission") }
            } else if (!locationOn) {
                OutlinedButton(onClick = openLocationSettings, Modifier.fillMaxWidth()) { Text("Turn on Location for discovery") }
            } else {
                Button(onClick = onDiscover, Modifier.fillMaxWidth()) { Text("Discover peers") }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Emergency channel", style = MaterialTheme.typography.titleMedium)
                    Text("Send a high-priority broadcast through the connected local link.")
                    Button(onClick = onEmergency, Modifier.fillMaxWidth()) { Text("SEND EMERGENCY BROADCAST") }
                }
            }
        }
        item { Text("System events", style = MaterialTheme.typography.titleMedium) }
        items(logs.take(6)) { event -> Text("• $event", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun NetworkScreen(
    modifier: Modifier,
    nodeId: String,
    peers: List<Node>,
    connection: ConnectionState,
    onDiscover: () -> Unit,
    onConnect: (Node) -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Mesh view", style = MaterialTheme.typography.headlineSmall)
                Text("YOU • ${nodeId.take(8).uppercase()}")
            }
            if (connection == ConnectionState.CONNECTING) CircularProgressIndicator(Modifier.size(24.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDiscover) { Text("Scan") }
            OutlinedButton(onClick = onDisconnect, enabled = connection != ConnectionState.UNAVAILABLE) { Text("Disconnect") }
        }
        Text("${peers.size} discovered node${if (peers.size == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium)
        if (peers.isEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Text("No peers discovered yet. Run Scan on both devices and keep them nearby.", Modifier.padding(16.dp))
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(peers, key = { it.nodeId }) { peer ->
                    Card(Modifier.fillMaxWidth().clickable { onConnect(peer) }) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(peer.displayName)
                                Text("ID ${peer.nodeId.take(8)}", style = MaterialTheme.typography.bodySmall)
                            }
                            Text(peer.connectionState.name)
                        }
                    }
                }
            }
        }
        Text("Transport: Wi-Fi Direct + TCP", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ChatScreen(
    modifier: Modifier,
    connection: ConnectionState,
    messages: List<ChatMessage>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Messages", style = MaterialTheme.typography.headlineSmall)
        Text(if (connection == ConnectionState.CONNECTED) "Connected • messages can be sent" else "Connect to a peer to send messages")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages, key = { it.packet.messageId }) { message ->
                Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (message.inbound) "← Incoming" else "→ Outgoing", style = MaterialTheme.typography.labelSmall)
                        Text(message.packet.payload)
                        if (message.packet.type == PacketType.EMERGENCY) Text("CRITICAL • ${message.packet.priority}")
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                label = { Text("Message") },
                enabled = connection == ConnectionState.CONNECTED,
                maxLines = 3,
            )
            Button(onClick = onSend, enabled = draft.isNotBlank() && connection == ConnectionState.CONNECTED) {
                Text("Send")
            }
        }
    }
}

private fun connectionLabel(state: ConnectionState): String = when (state) {
    ConnectionState.UNAVAILABLE -> "Offline • no active peer connection"
    ConnectionState.DISCOVERED -> "Peer discovered • not connected"
    ConnectionState.CONNECTING -> "Connecting to peer…"
    ConnectionState.CONNECTED -> "Connected • local link active"
}
