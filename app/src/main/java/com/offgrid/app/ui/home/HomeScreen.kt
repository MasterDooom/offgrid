package com.offgrid.app.ui.home

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offgrid.app.data.model.Conversation
import com.offgrid.app.data.model.LinkState
import com.offgrid.app.data.model.Node
import com.offgrid.app.data.model.NodeStatus
import com.offgrid.app.data.repository.IdentityManager
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    identity: IdentityManager,
    nodes: List<Node>,
    linkState: LinkState,
    transportStatus: String,
    conversations: Map<String, Conversation>,
    onSelectNode: (Node) -> Unit,
    onOpenConversation: (Node) -> Unit,
    onDiscover: () -> Unit,
    onEmergency: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNetwork: () -> Unit,
) {
    val recent = conversations.values
        .filter { it.lastMessage != null }
        .sortedByDescending { it.lastMessage!!.timestamp }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OFFGRID", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Your network, nearby.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)) {
                        Text("OFFLINE", Modifier.padding(horizontal = 11.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onOpenSettings) { Text("⚙", style = MaterialTheme.typography.titleLarge) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { IdentityStrip(identity, linkState, nodes.size) }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Nearby network", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                if (nodes.isEmpty()) "Scanning for OffGrid devices…" else "${nodes.size} device${if (nodes.size == 1) "" else "s"} in range",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = onOpenNetwork) { Text("Expand") }
                    }
                    NetworkMiniMap(nodes = nodes, onSelectNode = onSelectNode)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onDiscover, modifier = Modifier.weight(1f)) { Text("Scan nearby") }
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                transportStatus,
                                Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            item { EmergencyEntry(onEmergency) }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text("Nearby devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Connect directly, no tower required.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (nodes.isEmpty()) {
                item { EmptyNearbyCard(transportStatus) }
            } else {
                items(nodes, key = { "node:${it.id}" }) { node -> NearbyRow(node) { onSelectNode(node) } }
            }

            item { Text("Recent conversations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            if (recent.isEmpty()) {
                item { EmptyConversationCard() }
            } else {
                items(recent, key = { "conversation:${it.id}" }) { conversation -> RecentConversationRow(conversation) { onOpenConversation(conversation.peer) } }
            }
        }
    }
}

@Composable
private fun IdentityStrip(identity: IdentityManager, linkState: LinkState, nearbyCount: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(modifier = Modifier.size(48.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondary.copy(alpha = .13f)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("◎", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.titleLarge) }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(identity.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(identity.nodeId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("● ${linkLabel(linkState)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text("$nearbyCount nearby", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NetworkMiniMap(nodes: List<Node>, onSelectNode: (Node) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val surface = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val positions = remember(nodes.map { it.id }) {
        nodes.mapIndexed { index, node ->
            val angle = (index.toDouble() / maxOf(nodes.size, 1)) * Math.PI * 2 - Math.PI / 2
            node.id to Offset(.5f + (.31f * cos(angle)).toFloat(), .5f + (.31f * sin(angle)).toFloat())
        }.toMap()
    }
    Box(Modifier.fillMaxWidth().height(220.dp).background(surface, RoundedCornerShape(24.dp))) {
        Canvas(Modifier.fillMaxSize().padding(12.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = minOf(size.width, size.height) * .30f
            for (i in 1..3) {
                drawCircle(color = primary.copy(alpha = .045f), radius = radius * i / 3f, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()))
            }
            positions.values.forEach { p ->
                drawLine(color = secondary.copy(alpha = .25f), start = center, end = Offset(p.x * size.width, p.y * size.height), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Surface(modifier = Modifier.align(Alignment.Center).size(64.dp), shape = CircleShape, color = primary.copy(alpha = .14f), tonalElevation = 2.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("◉", color = primary, style = MaterialTheme.typography.titleMedium)
                Text("YOU", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
        nodes.forEachIndexed { index, node ->
            val angle = (index.toDouble() / maxOf(nodes.size, 1)) * Math.PI * 2 - Math.PI / 2
            val x = (.5f + .31f * cos(angle)).toFloat()
            val y = (.5f + .31f * sin(angle)).toFloat()
            Surface(modifier = Modifier.align(Alignment.TopStart).padding(start = (x * 210 - 27).dp, top = (y * 210 - 27).dp).size(54.dp), shape = CircleShape, color = if (node.status == NodeStatus.CONNECTED) secondary.copy(alpha = .16f) else surfaceVariant, tonalElevation = 2.dp, onClick = { onSelectNode(node) }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("•", color = nodeColor(node.status, primary, secondary, onSurfaceVariant), style = MaterialTheme.typography.titleMedium)
                    Text(node.name.take(7), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text("LIVE MESH", Modifier.align(Alignment.BottomStart).padding(14.dp), style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant)
    }
}

@Composable
private fun EmergencyEntry(onEmergency: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onEmergency), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondary.copy(alpha = .16f)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("!", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold) }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Emergency broadcast", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text("Send an SOS across reachable OffGrid peers", style = MaterialTheme.typography.bodySmall)
            }
            Text("→", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun EmptyNearbyCard(status: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("No peers discovered", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Keep OffGrid open on another phone, enable Bluetooth/Wi-Fi, then scan again.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (status.contains("failed", ignoreCase = true)) Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun EmptyConversationCard() {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
        Text("Your conversations will appear here after your first offline message.", Modifier.padding(18.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NearbyRow(node: Node, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(node.status)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(node.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(node.id, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(nodeSubtitle(node), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text("→", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RecentConversationRow(conversation: Conversation, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(modifier = Modifier.size(44.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("◎", color = MaterialTheme.colorScheme.primary) }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(conversation.peer.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(conversation.lastMessage?.content.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("→", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusDot(status: NodeStatus) {
    Box(Modifier.size(11.dp).background(nodeColor(status), CircleShape))
}

private fun nodeColor(status: NodeStatus, primary: androidx.compose.ui.graphics.Color, secondary: androidx.compose.ui.graphics.Color, onSurfaceVariant: androidx.compose.ui.graphics.Color) = when (status) {
    NodeStatus.CONNECTED -> primary
    NodeStatus.CONNECTING -> secondary
    NodeStatus.AVAILABLE -> secondary.copy(alpha = .72f)
    NodeStatus.OFFLINE -> onSurfaceVariant
}

@Composable
private fun nodeColor(status: NodeStatus) = when (status) {
    NodeStatus.CONNECTED -> MaterialTheme.colorScheme.primary
    NodeStatus.CONNECTING -> MaterialTheme.colorScheme.secondary
    NodeStatus.AVAILABLE -> MaterialTheme.colorScheme.secondary.copy(alpha = .72f)
    NodeStatus.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun nodeSubtitle(node: Node): String = when (node.status) {
    NodeStatus.CONNECTED -> "Connected · Tap to message"
    NodeStatus.CONNECTING -> "Connecting…"
    NodeStatus.AVAILABLE -> "Nearby · Tap to connect"
    NodeStatus.OFFLINE -> "Not reachable"
}

private fun linkLabel(state: LinkState): String = when (state) {
    LinkState.CONNECTED -> "LINKED"
    LinkState.CONNECTING -> "CONNECTING"
    LinkState.LISTENING -> "LISTENING"
    LinkState.OFFLINE -> "OFFLINE"
}
