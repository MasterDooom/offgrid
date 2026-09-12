package com.offgrid.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.offgrid.app.data.model.DeviceCapability
import com.offgrid.app.data.model.Node
import com.offgrid.app.data.model.NodeStatus
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(node: Node, onBack: () -> Unit, onMessage: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device profile") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) } },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(node.name, style = MaterialTheme.typography.headlineMedium)
            Text(node.id, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoRow("Connection status", statusLabel(node.status))
                    InfoRow("Last seen", DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(node.lastSeen)))
                    InfoRow("Hops", node.hops.toString())
                    node.signalStrength?.let { InfoRow("Signal", "$it%") }
                    InfoRow("Relay capable", if (DeviceCapability.RELAY in node.capabilities) "Yes" else "No")
                    InfoRow("Emergency responder", if (DeviceCapability.EMERGENCY_RESPONDER in node.capabilities) "Yes" else "No")
                    InfoRow("Source", if (node.isSimulated) "Simulated demo node" else "Real linked device")
                }
            }

            Button(onClick = onMessage, modifier = Modifier.fillMaxWidth()) { Text("Message") }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun statusLabel(status: NodeStatus): String = when (status) {
    NodeStatus.CONNECTED -> "Connected"
    NodeStatus.CONNECTING -> "Connecting…"
    NodeStatus.AVAILABLE -> "Nearby · Available"
    NodeStatus.OFFLINE -> "Not reachable"
}
