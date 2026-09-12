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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Live offline demo") },
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
            Text("OFFGRID live mesh", style = MaterialTheme.typography.headlineSmall)
            Text(
                "This build uses Nearby Connections for direct phone-to-phone communication. " +
                    "Messages do not go through an OffGrid server or the mobile network.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Demo checklist", style = MaterialTheme.typography.titleMedium)
                    Text("• Install this same APK on 2–3 Android phones")
                    Text("• Give OffGrid the Nearby devices permission")
                    Text("• Turn on Wi-Fi + Bluetooth on every phone")
                    Text("• Then enable Airplane mode on every phone")
                    Text("• If Airplane mode turns the radios off, turn Wi-Fi/Bluetooth back on manually")
                    Text("• Open OffGrid and leave the phones near each other")
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your node", style = MaterialTheme.typography.titleMedium)
                    Text(identity.displayName)
                    Text(identity.nodeId, color = MaterialTheme.colorScheme.primary)
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
