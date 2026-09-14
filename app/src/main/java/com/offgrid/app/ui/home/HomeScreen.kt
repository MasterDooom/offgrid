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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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

private val Navy = Color(0xFF10213A)
private val Blue = Color(0xFF1769FF)
private val Green = Color(0xFF08A66A)
private val SoftBlue = Color(0xFFEAF1FF)
private val SoftGreen = Color(0xFFE1F7EF)
private val SoftRed = Color(0xFFFFE7E9)
private val Border = Color(0xFFE3E9F1)

@Composable
fun HomeScreen(identity: IdentityManager, nodes: List<Node>, linkState: LinkState, transportStatus: String, conversations: Map<String, Conversation>, onSelectNode: (Node) -> Unit, onOpenConversation: (Node) -> Unit, onDiscover: () -> Unit, onEmergency: () -> Unit, onOpenSettings: () -> Unit, onOpenNetwork: () -> Unit, onOpenMessages: () -> Unit) {
    val recent = conversations.values.filter { it.lastMessage != null }.sortedByDescending { it.lastMessage!!.timestamp }
    val connected = nodes.count { it.status == NodeStatus.CONNECTED && !it.isSimulated }
    val maxHops = nodes.maxOfOrNull { it.hops } ?: 0
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = { BottomBar(HomeTab.HOME, {}, onOpenMessages, onOpenNetwork) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(Modifier.size(46.dp), RoundedCornerShape(14.dp), color = Navy) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LogoMark() } }
                Column(Modifier.weight(1f).padding(start = 11.dp)) { Text("OFFGRID", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Navy); Text("People stay connected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Surface(RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { IconButton(onClick = onOpenSettings, modifier = Modifier.size(42.dp)) { Text("⚙", color = Navy, style = MaterialTheme.typography.titleMedium) } }
            } }
            item { MeshStatus(linkState, connected) }
            item { MeshTopology(nodes, onSelectNode) }
            item { Metrics(nodes.size, maxHops) }
            item { EmergencyCard(onEmergency) }
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Nearby devices", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy); Text("View network  ›", Modifier.clickable(onClick = onOpenNetwork), color = Blue, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold) } }
            if (nodes.isEmpty()) item { Surface(Modifier.fillMaxWidth().clickable(onClick = onDiscover), RoundedCornerShape(14.dp), color = SoftBlue, border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD8E5FF))) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Surface(Modifier.size(36.dp), CircleShape, color = Color.White) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("⌁", color = Blue, style = MaterialTheme.typography.titleMedium) } }; Column(Modifier.weight(1f).padding(start = 11.dp)) { Text("Find nearby nodes", color = Navy, fontWeight = FontWeight.SemiBold); Text(transportStatus.ifBlank { "Tap to start local discovery" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }; Text("Scan", color = Blue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium) } } }
            else items(nodes.take(3), key = { it.logicalId }) { DeviceRow(it, onSelectNode) }
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Recent conversations", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy); if (recent.isNotEmpty()) Text("${recent.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium) } }
            if (recent.isEmpty()) item { EmptyText("Your private conversations will appear here.") } else items(recent.take(2), key = { it.id }) { RecentRow(it, onOpenConversation) }
            item { Text(transportStatus.ifBlank { "Listening for nearby OffGrid nodes…" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
        }
    }
}

@Composable private fun LogoMark() { Canvas(Modifier.size(26.dp)) { val p = androidx.compose.ui.graphics.Path().apply { moveTo(2.dp.toPx(), 21.dp.toPx()); lineTo(10.dp.toPx(), 5.dp.toPx()); lineTo(17.dp.toPx(), 17.dp.toPx()); lineTo(22.dp.toPx(), 10.dp.toPx()) }; drawPath(p, color = Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(2.1.dp.toPx(), cap = StrokeCap.Round)); drawLine(Color.White, Offset(11.dp.toPx(), 21.dp.toPx()), Offset(16.dp.toPx(), 14.dp.toPx()), 2.1.dp.toPx(), StrokeCap.Round) } }

@Composable private fun MeshStatus(linkState: LinkState, connected: Int) {
    val active = linkState != LinkState.OFFLINE
    val pulse by rememberInfiniteTransition(label = "status-pulse").animateFloat(.72f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "status")
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Row(Modifier.padding(horizontal = 15.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size((11f * if (active) pulse else 1f).dp).background(if (active) Green else Color(0xFF9AA7B8), CircleShape))
        Column(Modifier.weight(1f).padding(start = 11.dp)) { Text(if (active) "MESH ACTIVE" else "MESH STANDBY", fontWeight = FontWeight.ExtraBold, color = if (active) Green else Navy, style = MaterialTheme.typography.labelLarge); Text(if (active) "Local network is ready" else "Waiting for a local link", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Surface(RoundedCornerShape(10.dp), color = if (active) SoftGreen else MaterialTheme.colorScheme.surfaceVariant) { Text(if (connected > 0) "$connected LINKED" else "P2P READY", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = if (active) Green else Navy, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall) }
    } }
}

@Composable private fun MeshTopology(nodes: List<Node>, onSelect: (Node) -> Unit) {
    val visible = nodes.take(5)
    val transition = rememberInfiniteTransition(label = "mesh-animation")
    val pulse by transition.animateFloat(.84f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val travel by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "travel")
    BoxWithConstraints(Modifier.fillMaxWidth().height(250.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2); val r = minOf(size.width, size.height) * .39f
            for (i in 1..4) drawCircle(Blue.copy(alpha = (.025f + i * .012f) * pulse), r * i / 4f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            visible.forEachIndexed { i, _ -> val a = i.toDouble() / maxOf(visible.size, 1) * Math.PI * 2 - Math.PI / 2; val end = Offset((c.x + cos(a) * r * .72).toFloat(), (c.y + sin(a) * r * .72).toFloat()); drawLine(Blue.copy(alpha = .28f), c, end, 1.4.dp.toPx(), StrokeCap.Round); val t = (travel + i * .17f) % 1f; val point = Offset(c.x + (end.x - c.x) * t, c.y + (end.y - c.y) * t); drawCircle(Blue.copy(alpha = .82f), 3.2.dp.toPx(), point) }
        }
        Surface(Modifier.align(Alignment.Center).size(66.dp), CircleShape, color = Blue, tonalElevation = 3.dp) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Text("●", color = Color.White, style = MaterialTheme.typography.titleMedium); Text("You", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) } }
        visible.forEachIndexed { i, node -> val a = i.toDouble() / maxOf(visible.size, 1) * Math.PI * 2 - Math.PI / 2; val x = (.5 + .36 * cos(a)).toFloat(); val y = (.5 + .39 * sin(a)).toFloat(); val connected = node.status == NodeStatus.CONNECTED; Surface(Modifier.align(Alignment.TopStart).offset(x = maxWidth * x - 30.dp, y = 250.dp * y - 30.dp).size(60.dp).clickable { onSelect(node) }, CircleShape, color = if (connected) SoftGreen else SoftBlue, border = androidx.compose.foundation.BorderStroke(1.dp, if (connected) Color(0xFFC9EBDD) else Color(0xFFD7E4FF))) { Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { Box(Modifier.size(12.dp).background(if (connected) Green else Blue, CircleShape)); Text(node.name.take(8), color = Navy, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${node.hops} hop${if (node.hops == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) } } }
    }
}

@Composable private fun Metrics(deviceCount: Int, maxHops: Int) { Surface(Modifier.fillMaxWidth(), RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Row(Modifier.padding(vertical = 13.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) { Metric("$deviceCount", "devices"); DividerLine(); Metric("$maxHops", "max hops"); DividerLine(); Metric("AES-256", "hop encrypted") } } }
@Composable private fun Metric(value: String, label: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, fontWeight = FontWeight.ExtraBold, color = Navy, style = MaterialTheme.typography.titleSmall); Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) }
@Composable private fun DividerLine() = Box(Modifier.size(width = 1.dp, height = 28.dp).background(Border))

@Composable private fun EmergencyCard(onClick: () -> Unit) { Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), RoundedCornerShape(18.dp), color = SoftRed, border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFD0D5))) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Surface(Modifier.size(42.dp), RoundedCornerShape(13.dp), color = Color.White.copy(alpha = .72f)) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("!", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge) } }; Column(Modifier.weight(1f).padding(start = 12.dp)) { Text("EMERGENCY SOS", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge); Text("Broadcast a distress signal over the mesh", color = MaterialTheme.colorScheme.error.copy(alpha = .78f), style = MaterialTheme.typography.bodySmall) }; Text("›", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.headlineSmall) } } }

@Composable private fun DeviceRow(node: Node, onSelect: (Node) -> Unit) { Surface(Modifier.fillMaxWidth().clickable { onSelect(node) }, RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(34.dp).background(if (node.status == NodeStatus.CONNECTED) SoftGreen else SoftBlue, CircleShape), contentAlignment = Alignment.Center) { Box(Modifier.size(9.dp).background(if (node.status == NodeStatus.CONNECTED) Green else Blue, CircleShape)) }; Column(Modifier.weight(1f).padding(start = 11.dp)) { Text(node.name, fontWeight = FontWeight.SemiBold, color = Navy, style = MaterialTheme.typography.bodyMedium); Text(when (node.status) { NodeStatus.CONNECTED -> "Direct connection · ${node.hops} hop${if (node.hops == 1) "" else "s"}"; NodeStatus.CONNECTING -> "Connecting…"; NodeStatus.AVAILABLE -> "Nearby · tap to connect"; NodeStatus.OFFLINE -> "Not reachable" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }; Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge) } } }

@Composable private fun RecentRow(conversation: Conversation, onOpen: (Node) -> Unit) { val peer = conversation.peer; Surface(Modifier.fillMaxWidth().clickable { onOpen(peer) }, RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) { Surface(Modifier.size(40.dp), CircleShape, color = SoftGreen) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(peer.name.take(1).uppercase(), color = Green, fontWeight = FontWeight.ExtraBold) } }; Column(Modifier.weight(1f).padding(start = 11.dp)) { Text(peer.name, color = Navy, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium); Text(conversation.lastMessage?.content.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) } } } }
@Composable private fun EmptyText(text: String) = Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 5.dp))

enum class HomeTab { HOME, MESSAGES, NETWORK }
@Composable private fun BottomBar(selected: HomeTab, onHome: () -> Unit, onMessages: () -> Unit, onNetwork: () -> Unit) { NavigationBar(containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.navigationBarsPadding()) { NavigationBarItem(selected == HomeTab.HOME, onHome, icon = { Text("⌂", style = MaterialTheme.typography.titleLarge) }, label = { Text("Home") }); NavigationBarItem(selected == HomeTab.MESSAGES, onMessages, icon = { Text("□", style = MaterialTheme.typography.titleLarge) }, label = { Text("Messages") }); NavigationBarItem(selected == HomeTab.NETWORK, onNetwork, icon = { Text("⌘", style = MaterialTheme.typography.titleLarge) }, label = { Text("Network") }) } }
