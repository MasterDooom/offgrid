package com.offgrid.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.offgrid.app.data.repository.IdentityManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoSetupScreen(
    identity: IdentityManager,
    onBack: () -> Unit,
    onApply: (selfPort: Int, peerHost: String, peerPort: Int) -> Unit,
) {
    var selfPort by remember { mutableStateOf(identity.selfPort.toString()) }
    var peerHost by remember { mutableStateOf(identity.peerHost) }
    var peerPort by remember { mutableStateOf(identity.peerPort.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Two-emulator demo setup") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) } },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Each emulator listens on its own port and dials the other over 10.0.2.2. " +
                    "Pick a preset per device, forward the matching adb ports, then hit Apply.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { selfPort = "8990"; peerPort = "8991" }) { Text("This is Device A") }
                OutlinedButton(onClick = { selfPort = "8991"; peerPort = "8990" }) { Text("This is Device B") }
            }

            OutlinedTextField(
                value = selfPort, onValueChange = { selfPort = it }, label = { Text("Listen port (this device)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = peerHost, onValueChange = { peerHost = it }, label = { Text("Peer host") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = peerPort, onValueChange = { peerPort = it }, label = { Text("Peer port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("adb commands (run once per emulator, from a terminal):", style = MaterialTheme.typography.labelLarge)
                    Text("adb -s <deviceA-serial> forward tcp:8990 tcp:8990", style = MaterialTheme.typography.bodySmall)
                    Text("adb -s <deviceB-serial> forward tcp:8991 tcp:8991", style = MaterialTheme.typography.bodySmall)
                }
            }

            Button(
                onClick = {
                    val port = selfPort.toIntOrNull() ?: identity.selfPort
                    val peer = peerPort.toIntOrNull() ?: identity.peerPort
                    onApply(port, peerHost, peer)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Apply and restart link") }
        }
    }
}
