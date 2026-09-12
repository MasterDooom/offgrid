package com.offgrid.app.ui.emergency

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.offgrid.app.data.model.DeviceCapability
import com.offgrid.app.data.model.Node
import com.offgrid.app.data.model.SOSAlert
import com.offgrid.app.data.model.SOSStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyScreen(
    nodes: List<Node>,
    sos: SOSAlert?,
    onBack: () -> Unit,
    onActivate: (String) -> Unit,
    onCancel: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EMERGENCY MODE", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) } },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (sos == null || sos.status == SOSStatus.CANCELLED) {
                BeforeActivation(onActivate)
            } else {
                ActiveSos(sos, nodes, onCancel)
            }
        }
    }
}

@Composable
private fun BeforeActivation(onActivate: (String) -> Unit) {
    var message by remember { mutableStateOf("I'm in an emergency and need help.") }
    Text("\"I'm in an emergency.\"", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Activating SOS creates an emergency alert with your node ID and timestamp, and " +
            "broadcasts it through every reachable OFFGRID device — including the linked device " +
            "in this demo, if connected. Nearby devices that could relay or respond will be shown " +
            "as they're reached.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = message,
        onValueChange = { message = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Emergency message") },
    )
    Button(
        onClick = { onActivate(message) },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
    ) { Text("ACTIVATE SOS") }
}

@Composable
private fun ActiveSos(sos: SOSAlert, nodes: List<Node>, onCancel: () -> Unit) {
    val statusText = if (sos.status == SOSStatus.BROADCASTING) "Broadcasting…" else "SOS ACTIVE"
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(statusText, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
            Text("Your emergency signal has been broadcast.")
            Text("Nearby nodes reached: ${sos.nodesReached}")
            Text("Potential helpers: ${sos.helpersFound}")
            Text("Message: \"${sos.message}\"", style = MaterialTheme.typography.bodySmall)
        }
    }
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("CANCEL SOS") }

    Text("Nearby Help", style = MaterialTheme.typography.titleMedium)
    val helpers = nodes.take(sos.nodesReached)
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(helpers, key = { it.id }) { node ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(helperLabel(node), style = MaterialTheme.typography.bodyLarge)
                    Text("${node.hops} hop${if (node.hops == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun helperLabel(node: Node): String =
    if (DeviceCapability.EMERGENCY_RESPONDER in node.capabilities) "OFFGRID Responder — ${node.name}" else node.name
