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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
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
) {
    var selfPort by remember { mutableStateOf(identity.selfPort.toString()) }
    var peerHost by remember { mutableStateOf(identity.peerHost) }
    var peerPort by remember { mutableStateOf(identity.peerPort.toString()) }
    val clipboard = LocalClipboardManager.current

    val isA = selfPort == "8990" && peerPort == "8991"
    val isB = selfPort == "8991" && peerPort == "8990"
    val adbCommand = if (isA) {
        "adb -s <DEVICE_A_SERIAL> forward tcp:8990 tcp:8990"
    } else if (isB) {
        "adb -s <DEVICE_B_SERIAL> forward tcp:8991 tcp:8991"
    } else {
        "adb -s <DEVICE_SERIAL> forward tcp:$selfPort tcp:$selfPort"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Demo connection") },
                navigationIcon = { IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) } },
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Two-device TCP demo", style = MaterialTheme.typography.headlineSmall)
            Text(
                "This prototype uses a real bidirectional TCP socket between two running OFFGRID app instances. " +
                    "No internet service is required. The simulated nodes are only there to make solo demos useful.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { selfPort = "8990"; peerPort = "8991" }) { Text("Device A") }
                OutlinedButton(onClick = { selfPort = "8991"; peerPort = "8990" }) { Text("Device B") }
            }

            OutlinedTextField(
                value = selfPort,
                onValueChange = { selfPort = it },
                label = { Text("Listen port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = peerHost,
                onValueChange = { peerHost = it },
                label = { Text("Peer host") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = peerPort,
                onValueChange = { peerPort = it },
                label = { Text("Peer port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("1. Forward this device's port", style = MaterialTheme.typography.labelLarge)
                    Text(adbCommand, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(adbCommand)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Copy command") }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("2. Check the link", style = MaterialTheme.typography.labelLarge)
                    Text(
                        when (linkState) {
                            LinkState.CONNECTED -> "Connected — the peer handshake is complete."
                            LinkState.CONNECTING -> "Connecting…"
                            LinkState.LISTENING -> "Listening. Tap Test connection after the other device is running."
                            LinkState.OFFLINE -> "Offline. Start the app on both devices first."
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onTestConnection, modifier = Modifier.fillMaxWidth()) {
                        Text("Test connection")
                    }
                }
            }

            Button(
                onClick = {
                    val local = selfPort.toIntOrNull() ?: identity.selfPort
                    val remote = peerPort.toIntOrNull() ?: identity.peerPort
                    onApply(local, peerHost, remote)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Apply settings") }
        }
    }
}
