package com.offgrid.app.ui.network

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import com.offgrid.app.data.model.DeviceCapability
import com.offgrid.app.data.model.Node
import com.offgrid.app.data.model.NodeStatus
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NetworkScreen(
    nodes: List<Node>,
    selfId: String,
    selfName: String,
    onBack: () -> Unit,
    onSelectNode: (Node) -> Unit,
    transportStatus: String = "",
    onHome: (() -> Unit)? = null,
    onMessages: (() -> Unit)? = null,
) {
    Scaffold(bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            NavigationBarItem(selected = false, onClick = { onHome?.invoke() ?: onBack() }, icon = { Text("⌂") }, label = { Text("Home") })
            NavigationBarItem(selected = false, onClick = { onMessages?.invoke() ?: onBack() }, icon = { Text("□") }, label = { Text("Messages") })
            NavigationBarItem(selected = true, onClick = {}, icon = { Text("⌘") }, label = { Text("Network") })
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp)) {
                Text("Network", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("See the people around you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NetworkTab("Local Map", true)
                NetworkTab("List", false)
                NetworkTab("Diagnostics", false)
            }

            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 1.dp,
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Live transport", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text(
                        transportStatus.ifBlank { "Wi-Fi P2P waiting for diagnostics…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Android peers: ${nodes.size}  •  OffGrid links: ${nodes.count { it.status == NodeStatus.CONNECTED }}  •  Max hops: ${nodes.maxOfOrNull { it.hops } ?: 0}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            NetworkMap(nodes, selfId, selfName, onSelectNode, Modifier.fillMaxWidth().size(330.dp).padding(horizontal = 20.dp))
            Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
                Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    NetworkMetric(nodes.size.toString(), "devices")
                    NetworkMetric(nodes.count { it.status == NodeStatus.CONNECTED }.toString(), "linked")
                    NetworkMetric(nodes.maxOfOrNull { it.hops }?.toString() ?: "0", "max hops")
                }
            }
            Text("Nearby devices", Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(nodes, key = { it.logicalId }) { node ->
                    Surface(Modifier.fillMaxWidth().clickable { onSelectNode(node) }, color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(node.status)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(node.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                Text(
                                    when {
                                        node.capabilities.contains(DeviceCapability.RELAY) -> "Relay node · ${node.hops} hops"
                                        node.status == NodeStatus.CONNECTED -> "Direct connection · ${node.hops} hop${if (node.hops == 1) "" else "s"}"
                                        node.status == NodeStatus.CONNECTING -> "Connecting…"
                                        else -> "Nearby · tap to connect"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkTab(label: String, selected: Boolean) {
    Surface(shape = RoundedCornerShape(14.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
        Text(label, Modifier.padding(horizontal = 13.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NetworkMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NetworkMap(nodes: List<Node>, selfId: String, selfName: String, onSelectNode: (Node) -> Unit, modifier: Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val surface = MaterialTheme.colorScheme.surface
    val visibleNodes = nodes.take(8)
    val pulse by rememberInfiniteTransition(label = "network-pulse").animateFloat(.55f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val positions = remember(visibleNodes.map { it.logicalId }) {
        visibleNodes.mapIndexed { index, node ->
            val angle = index.toDouble() / maxOf(visibleNodes.size, 1) * Math.PI * 2 - Math.PI / 2
            node.logicalId to Offset((.5f + .32f * cos(angle)).toFloat(), (.5f + .32f * sin(angle)).toFloat())
        }
    }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = minOf(size.width, size.height) * .35f
            for (i in 1..3) drawCircle(primary.copy(alpha = .04f + .01f * pulse), radius * i / 3f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            for (point in positions.map { it.second }) drawLine(secondary.copy(alpha = .28f), center, Offset(point.x * size.width, point.y * size.height), 1.5.dp.toPx(), StrokeCap.Round)
        }
        Surface(Modifier.align(Alignment.Center).size(82.dp), CircleShape, primary.copy(alpha = .14f), tonalElevation = 3.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("●", color = primary, style = MaterialTheme.typography.titleLarge)
                Text("YOU", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
        for ((index, node) in visibleNodes.withIndex()) {
            val angle = index.toDouble() / maxOf(visibleNodes.size, 1) * Math.PI * 2 - Math.PI / 2
            val x = (.5f + .32f * cos(angle)).toFloat()
            val y = (.5f + .32f * sin(angle)).toFloat()
            val connected = node.status == NodeStatus.CONNECTED
            Surface(
                Modifier.align(Alignment.TopStart).padding(start = (x * 300 - 30).dp, top = (y * 300 - 30).dp).size(60.dp).clickable { onSelectNode(node) },
                CircleShape,
                if (connected) secondary.copy(alpha = .15f) else surface,
                tonalElevation = 2.dp,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("●", color = if (connected) secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(node.name.take(8), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("${node.hops} hop${if (node.hops == 1) "" else "s"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Surface(Modifier.align(Alignment.TopStart).padding(12.dp), CircleShape, MaterialTheme.colorScheme.surface.copy(alpha = .92f)) {
            Text("${nodes.size} devices", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
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
