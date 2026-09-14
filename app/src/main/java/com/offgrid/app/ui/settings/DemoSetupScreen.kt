package com.offgrid.app.ui.settings

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
import androidx.compose.ui.unit.dp
import com.offgrid.app.data.model.LinkState
import com.offgrid.app.data.repository.IdentityManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoSetupScreen(
    identity: IdentityManager,
    linkState: LinkState,
    onBack: () -> Unit,
    onApply: (selfPort: Int, peerHost: String, peerPort: Int) -> Unit,
    onTestConnection: () -> Unit,
    onRename: (String) -> Unit,
) {
    var displayName by remember(identity.nodeId) { mutableStateOf(identity.displayName) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("OFFGRID settings", style = MaterialTheme.typography.headlineSmall)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Your device", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Choose the name other OffGrid users will see instead of your unique node ID.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it.take(32) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Device name") },
                        singleLine = true,
                    )
                    Button(
                        onClick = {
                            val cleanName = displayName.trim()
                            if (cleanName.isNotEmpty()) {
                                onRename(cleanName)
                                displayName = cleanName
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Save device name") }
                    Text(
                        "Node ID: ${identity.nodeId}",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Live offline mesh", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Wi-Fi Direct handles the local phone-to-phone link. Messages remain transport-agnostic and use the existing mesh routing + hop encryption.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        when (linkState) {
                            LinkState.CONNECTED -> "Live link active"
                            LinkState.CONNECTING -> "Connecting to nearby nodes…"
                            LinkState.LISTENING -> "Advertising + scanning for nearby nodes"
                            LinkState.OFFLINE -> "Transport offline — check Nearby permission and radios"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            OutlinedButton(
                onClick = onTestConnection,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scan for nearby devices") }

            Button(
                onClick = { onApply(identity.selfPort, identity.peerHost, identity.peerPort) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Done") }
        }
    }
}
