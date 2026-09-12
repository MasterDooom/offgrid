package com.offgrid.app.ui.home

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.offgrid.app.data.model.Conversation
import com.offgrid.app.data.model.LinkState
import com.offgrid.app.data.model.Node
import com.offgrid.app.data.model.NodeStatus
import com.offgrid.app.data.repository.IdentityManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    identity: IdentityManager,
    nodes: List<Node>,
    linkState: LinkState,
    conversations: Map<String, Conversation>,
    onSelectNode: (Node) -> Unit,
    onOpenConversation: (Node) -> Unit,
    onDiscover: () -> Unit,
    onEmergency: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val recent = conversations.values
        .filter { it.lastMessage != null }
        .sortedByDescending { it.lastMessage!!.timestamp }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OFFGRID", style = MaterialTheme.typography.titleLarge)
                        Text("Communicate when there's no network.", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Text("⚙", style = MaterialTheme.typography.titleLarge)
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { IdentityCard(identity, nodes.count { it.status != NodeStatus.OFFLINE }, linkState) }
            item { EmergencyEntry(onEmergency) }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Nearby", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = onDiscover) { Text("Scan") }
                }
            }
            items(nodes, key = { it.id }) { node -> NearbyRow(node) { onSelectNode(node) } }
            if (recent.isNotEmpty()) {
                item { Text("Recent conversations", style = MaterialTheme.typography.titleMedium) }
                items(recent, key = { it.id }) { conversation ->
                    RecentConversationRow(conversation) { onOpenConversation(conversation.peer) }
                }
            }
        }
    }
}

@Composable
private fun IdentityCard(identity: IdentityManager, nearbyCount: Int, linkState: LinkState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Your identity", style = MaterialTheme.typography.labelLarge)
            Text(identity.displayName, style = MaterialTheme.typography.headlineSmall)
            Text(identity.nodeId, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusDot(linkStateColor(linkState))
                Text(linkStateLabel(linkState) + " · $nearbyCount nearby")
            }
        }
    }
}

@Composable
private fun EmergencyEntry(onEmergency: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onEmergency),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.16f)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Emergency Mode", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text("Broadcast an SOS to nearby OFFGRID devices", style = MaterialTheme.typography.bodySmall)
            }
            Text("⚠", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun NearbyRow(node: Node, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(nodeStatusColor(node.status))
            Spacer(Modifier.padding(start = 8.dp))
            Column(Modifier.padding(start = 8.dp)) {
                Text(node.name, style = MaterialTheme.typography.bodyLarge)
                Text(node.id, style = MaterialTheme.typography.bodySmall)
                Text(nodeSubtitle(node), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun RecentConversationRow(conversation: Conversation, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Text(conversation.peer.name, style = MaterialTheme.typography.bodyLarge)
            Text(conversation.lastMessage?.content.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

private fun nodeSubtitle(node: Node): String {
    val availability = when (node.status) {
        NodeStatus.CONNECTED -> "Connected"
        NodeStatus.CONNECTING -> "Connecting…"
        NodeStatus.AVAILABLE -> "Nearby · Available"
        NodeStatus.OFFLINE -> "Not reachable"
    }
    val kind = if (node.isSimulated) "Simulated demo node" else "Live link"
    return "$availability · $kind"
}

private fun nodeStatusColor(status: NodeStatus): Color = when (status) {
    NodeStatus.CONNECTED -> Color(0xFF34D399)
    NodeStatus.CONNECTING -> Color(0xFFFBBF24)
    NodeStatus.AVAILABLE -> Color(0xFF60A5FA)
    NodeStatus.OFFLINE -> Color(0xFF6B7280)
}

private fun linkStateColor(state: LinkState): Color = when (state) {
    LinkState.CONNECTED -> Color(0xFF34D399)
    LinkState.CONNECTING -> Color(0xFFFBBF24)
    LinkState.LISTENING -> Color(0xFF60A5FA)
    LinkState.OFFLINE -> Color(0xFF6B7280)
}

private fun linkStateLabel(state: LinkState): String = when (state) {
    LinkState.CONNECTED -> "Linked device connected"
    LinkState.CONNECTING -> "Connecting to linked device…"
    LinkState.LISTENING -> "Listening for linked device"
    LinkState.OFFLINE -> "Offline"
}
