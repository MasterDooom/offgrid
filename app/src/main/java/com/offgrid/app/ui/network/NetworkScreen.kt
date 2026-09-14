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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offgrid.app.data.model.DeviceCapability
import com.offgrid.app.data.model.Node
import com.offgrid.app.data.model.NodeStatus
import kotlin.math.cos
import kotlin.math.sin

private val Navy = Color(0xFF10213A)
private val Blue = Color(0xFF1769FF)
private val Green = Color(0xFF08A66A)
private val MapBg = Color(0xFF142333)
private val MapGrid = Color(0xFF425A70)
private val Border = Color(0xFFE3E9F1)

@Composable
fun NetworkScreen(nodes: List<Node>, selfId: String, selfName: String, onBack: () -> Unit, onSelectNode: (Node) -> Unit, transportStatus: String = "", onHome: (() -> Unit)? = null, onMessages: (() -> Unit)? = null) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            NavigationBarItem(false, { onHome?.invoke() ?: onBack() }, icon = { Text("⌂", color = Navy) }, label = { Text("Home") })
            NavigationBarItem(false, { onMessages?.invoke() ?: onBack() }, icon = { Text("□", color = Navy) }, label = { Text("Messages") })
            NavigationBarItem(true, {}, icon = { Text("⌘", color = Blue) }, label = { Text("Network") })
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Network", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = Navy); Text("See the people around you.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Text("⌁", Modifier.padding(horizontal = 13.dp, vertical = 9.dp), color = Green, fontWeight = FontWeight.Bold) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().background(Color(0xFFE9EDF2), RoundedCornerShape(14.dp)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    NetworkTab("Local Map", true, Modifier.weight(1f)); NetworkTab("List", false, Modifier.weight(1f)); NetworkTab("Diagnostics", false, Modifier.weight(1f))
                }
            }
            item { NetworkMap(nodes, selfId, selfName, onSelectNode, Modifier.fillMaxWidth().height(350.dp)) }
            item { Surface(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Row(Modifier.padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly) { NetworkMetric(nodes.size.toString(), "devices"); NetworkMetric((nodes.maxOfOrNull { it.hops } ?: 0).toString(), "max hops"); NetworkMetric("AES-256", "encrypted") } } }
            item { Text("Nearby devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy) }
            if (nodes.isEmpty()) item { Surface(Modifier.fillMaxWidth(), RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Text(transportStatus.ifBlank { "Searching for nearby OffGrid devices…" }, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) } }
            else items(nodes, key = { it.logicalId }) { node ->
                Surface(Modifier.fillMaxWidth().clickable { onSelectNode(node) }, RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
                    Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(node.status)
                        Column(Modifier.weight(1f).padding(start = 11.dp)) {
                            Text(node.name, color = Navy, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text(when { node.capabilities.contains(DeviceCapability.RELAY) -> "Relay node · ${node.hops} hops"; node.status == NodeStatus.CONNECTED -> "Direct connection · ${node.hops} hop${if (node.hops == 1) "" else "s"}"; node.status == NodeStatus.CONNECTING -> "Connecting…"; else -> "Nearby · tap to connect" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}

@Composable private fun NetworkTab(label: String, selected: Boolean, modifier: Modifier) { Surface(modifier = modifier, shape = RoundedCornerShape(11.dp), color = if (selected) Color.White else Color.Transparent) { Text(label, Modifier.fillMaxWidth().padding(vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = if (selected) Blue else Navy, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, textAlign = TextAlign.Center) } }
@Composable private fun NetworkMetric(value: String, label: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = Navy); Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }

@Composable private fun NetworkMap(nodes: List<Node>, selfId: String, selfName: String, onSelectNode: (Node) -> Unit, modifier: Modifier) {
    val visibleNodes = nodes.take(6)
    val pulse by rememberInfiniteTransition(label = "network-pulse").animateFloat(.78f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val positions = remember(visibleNodes.map { it.logicalId }) { visibleNodes.mapIndexed { index, node -> val angle = index.toDouble() / maxOf(visibleNodes.size, 1) * Math.PI * 2 - Math.PI / 2; node.logicalId to Offset((.5f + .32f * cos(angle)).toFloat(), (.5f + .32f * sin(angle)).toFloat()) } }
    BoxWithConstraints(modifier.background(MapBg, RoundedCornerShape(19.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = minOf(size.width, size.height) * .38f
            for (i in 1..4) drawCircle(MapGrid.copy(alpha = .38f), radius * i / 4f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            for (point in positions.map { it.second }) drawLine(Color.White.copy(alpha = .38f), center, Offset(point.x * size.width, point.y * size.height), 1.3.dp.toPx(), StrokeCap.Round)
        }
        Surface(modifier = Modifier.align(Alignment.TopStart).padding(12.dp), shape = RoundedCornerShape(11.dp), color = Color(0xCC182A3A)) { Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) { Text("⌁  ${nodes.size} devices", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold); Text("↳  ${nodes.maxOfOrNull { it.hops } ?: 0} hops maximum", color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.labelSmall) } }
        Surface(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(38.dp), shape = CircleShape, color = Color(0xCC182A3A)) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("N", color = Color.White, fontWeight = FontWeight.Bold) } }
        Surface(modifier = Modifier.align(Alignment.Center).size(70.dp), shape = CircleShape, color = Blue, tonalElevation = 4.dp) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("●", color = Color.White, style = MaterialTheme.typography.titleMedium); Text("You", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall) } }
        for ((index, node) in visibleNodes.withIndex()) {
            val position = positions[index].second
            val connected = node.status == NodeStatus.CONNECTED
            Surface(modifier = Modifier.align(Alignment.TopStart).offset(x = maxWidth * position.x - 31.dp, y = 350.dp * position.y - 31.dp).size(62.dp).clickable { onSelectNode(node) }, shape = CircleShape, color = if (connected) Color(0xFF0F9D72).copy(alpha = .20f * pulse + .08f) else Blue.copy(alpha = .20f), border = androidx.compose.foundation.BorderStroke(1.dp, if (connected) Color(0xFF5AC6A0).copy(alpha = .55f) else Color(0xFF76A4FF).copy(alpha = .55f))) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Box(Modifier.size(12.dp).background(if (connected) Green else Blue, CircleShape)); Text(node.name.take(8), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${node.hops} hop${if (node.hops == 1) "" else "s"}", color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.labelSmall) } }
        }
        Text("LOCAL MESH  ·  LIVE", Modifier.align(Alignment.BottomStart).padding(13.dp), color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun StatusDot(status: NodeStatus) { val color = when (status) { NodeStatus.CONNECTED -> Green; NodeStatus.CONNECTING, NodeStatus.AVAILABLE -> Blue; NodeStatus.OFFLINE -> MaterialTheme.colorScheme.onSurfaceVariant }; Box(Modifier.size(10.dp).background(color, CircleShape)) }
