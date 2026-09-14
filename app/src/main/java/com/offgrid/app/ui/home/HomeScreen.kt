package com.offgrid.app.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    onOpenMessages: () -> Unit,
) {
    val recent = conversations.values
        .filter { it.lastMessage != null }
        .sortedByDescending { it.lastMessage!!.timestamp }
    val connected = nodes.count { it.status == NodeStatus.CONNECTED && !it.isSimulated }
    val maxHops = nodes.maxOfOrNull { it.hops } ?: 0

    Scaffold(
        bottomBar = {
            OffGridBottomBar(selected = HomeTab.HOME, onHome = {}, onMessages = onOpenMessages, onNetwork = onOpenNetwork)
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("OFFGRID", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("People stay connected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onOpenSettings) { Text("⚙", style = MaterialTheme.typography.titleLarge) }
                }
            }

            item { MeshStatusHeader(linkState, transportStatus, connected) }

            item {
                MeshTopology(nodes = nodes, onSelectNode = onSelectNode)
            }

            if (nodes.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Metric("${nodes.size}", "devices")
                        Metric("$maxHops", "max hops")
                        Metric("AES-256", "hop encrypted")
                    }
                }
            }

            item { EmergencySosCard(onEmergency) }

            item {
                SectionHeader("Nearby Devices", "See all", onOpenNetwork)
            }
            if (nodes.isEmpty()) {
                item {
                    Text(
                        "No OffGrid devices discovered yet. Scan nearby to find peers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(nodes.take(4), key = { "node:${it.logicalId}" }) { node ->
                    NearbyDeviceRow(node, onSelectNode)
                }
                if (nodes.size > 4) {
                    item { Text("${nodes.size - 4} more devices", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                }
            }

            item { SectionHeader("Recent Conversations", null, null) }
            if (recent.isEmpty()) {
                item {
                    Text(
                        "Your conversations will appear here after your first offline message.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(recent.take(4), key = { "conversation:${it.id}" }) { conversation ->
                    RecentConversationRow(conversation, onOpenConversation)
                }
            }

            item {
                Spacer(Modifier.height(2.dp))
                Text(
                    transportStatus.ifBlank { "Listening for nearby OffGrid nodes…" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onDiscover),
                )
            }
        }
    }
}

@Composable
private fun MeshStatusHeader(linkState: LinkState, transportStatus: String, connected: Int) {
    val active = linkState != LinkState.OFFLINE
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(color, CircleShape))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(if (active) "MESH ACTIVE" else "MESH OFFLINE", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = color)
                Text(
                    if (active) "Local network operational" else "Start mesh mode to connect nearby peers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (connected > 0) "$connected linked" else "P2P mode", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                if (transportStatus.contains("internet", ignoreCase = true)) {
                    Text("Internet available", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("No tower required", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MeshTopology(nodes: List<Node>, onSelectNode: (Node) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val surface = MaterialTheme.colorScheme.surface
    val pulse by rememberInfiniteTransition(label = "mesh-pulse").animateFloat(
        initialValue = .78f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val positions = remember(nodes.map { it.logicalId }) {
        nodes.take(8).mapIndexed { index, node ->
            val angle = index.toDouble() / maxOf(nodes.take(8).size, 1) * Math.PI * 2 - Math.PI / 2
            node.logicalId to Offset(
                (.5f + .32f * cos(angle)).toFloat(),
                (.5f + .32f * sin(angle)).toFloat(),
            )
        }.toMap()
    }

    Box(Modifier.fillMaxWidth().height(300.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = minOf(size.width, size.height) * .34f
            for (i in 1..3) {
                drawCircle(primary.copy(alpha = .035f + .01f * pulse), radius * i / 3f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            }
            positions.values.forEach { point ->
                drawLine(
                    secondary.copy(alpha = .25f),
                    center,
                    Offset(point.x * size.width, point.y * size.height),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.Center).size(78.dp),
            shape = CircleShape,
            color = primary.copy(alpha = .12f),
            tonalElevation = 2.dp,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("●", color = primary, style = MaterialTheme.typography.titleLarge)
                Text("YOU", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }

        nodes.take(8).forEachIndexed { index, node ->
            val angle = index.toDouble() / maxOf(nodes.take(8).size, 1) * Math.PI * 2 - Math.PI / 2
            val x = (.5f + .32f * cos(angle)).toFloat()
            val y = (.5f + .32f * sin(angle)).toFloat()
            val connected = node.status == NodeStatus.CONNECTED
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(start = (x * 280 - 31).dp, top = (y * 300 - 31).dp).size(62.dp),
                shape = CircleShape,
                color = if (connected) secondary.copy(alpha = .12f) else surface,
                tonalElevation = 2.dp,
                onClick = { onSelectNode(node) },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("●", color = if (connected) secondary else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
                    Text(node.name.take(9), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (node.hops > 0) Text("${node.hops} hop${if (node.hops == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Metric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmergencySosCard(onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = .09f),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(42.dp), CircleShape, color = MaterialTheme.colorScheme.error.copy(alpha = .12f)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("!", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold) }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("EMERGENCY SOS", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                Text("Hold to broadcast distress signal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String?, onAction: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(action, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onAction))
        }
    }
}

@Composable
private fun NearbyDeviceRow(node: Node, onSelectNode: (Node) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onSelectNode(node) },
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusDot(node.status)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(node.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    when (node.status) {
                        NodeStatus.CONNECTED -> "Direct connection · ${node.hops} hop${if (node.hops == 1) "" else "s"}"
                        NodeStatus.CONNECTING -> "Connecting…"
                        NodeStatus.AVAILABLE -> "Nearby · tap to connect"
                        NodeStatus.OFFLINE -> "Not reachable"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecentConversationRow(conversation: Conversation, onOpen: (Node) -> Unit) {
    val peer = conversation.peer
    val message = conversation.lastMessage
    Surface(Modifier.fillMaxWidth().clickable { onOpen(peer) }, color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(44.dp), CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(peer.name.take(1).uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(peer.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(message?.content.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusDot(status: NodeStatus) {
    val color = when (status) {
        NodeStatus.CONNECTED -> MaterialTheme.colorScheme.primary
        NodeStatus.CONNECTING, NodeStatus.AVAILABLE -> MaterialTheme.colorScheme.secondary
        NodeStatus.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(Modifier.size(9.dp).background(color, CircleShape))
}

enum class HomeTab { HOME, MESSAGES, NETWORK }

@Composable
private fun OffGridBottomBar(selected: HomeTab, onHome: () -> Unit, onMessages: () -> Unit, onNetwork: () -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        NavigationBarItem(selected == HomeTab.HOME, onClick = onHome, icon = { Text("⌂") }, label = { Text("Home") })
        NavigationBarItem(selected == HomeTab.MESSAGES, onClick = onMessages, icon = { Text("□") }, label = { Text("Messages") })
        NavigationBarItem(selected == HomeTab.NETWORK, onClick = onNetwork, icon = { Text("⌘") }, label = { Text("Network") })
    }
}
