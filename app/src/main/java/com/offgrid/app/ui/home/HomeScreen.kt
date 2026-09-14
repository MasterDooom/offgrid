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

private val Navy = androidx.compose.ui.graphics.Color(0xFF10213A)
private val Blue = androidx.compose.ui.graphics.Color(0xFF1769FF)
private val Green = androidx.compose.ui.graphics.Color(0xFF08A66A)
private val SoftBlue = androidx.compose.ui.graphics.Color(0xFFEAF1FF)
private val SoftGreen = androidx.compose.ui.graphics.Color(0xFFE1F7EF)
private val SoftRed = androidx.compose.ui.graphics.Color(0xFFFFE1E4)

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
    val recent = conversations.values.filter { it.lastMessage != null }.sortedByDescending { it.lastMessage!!.timestamp }
    val connected = nodes.count { it.status == NodeStatus.CONNECTED && !it.isSimulated }
    val maxHops = nodes.maxOfOrNull { it.hops } ?: 0

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BottomBar(HomeTab.HOME, {}, onOpenMessages, onOpenNetwork) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LogoMark()
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text("OffGrid", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Navy)
                        Text("People stay connected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onOpenSettings) { Text("⚙", color = Navy, style = MaterialTheme.typography.titleLarge) }
                }
            }
            item { MeshStatus(linkState, transportStatus, connected) }
            item { MeshTopology(nodes, onSelectNode) }
            item { Metrics(nodes.size, maxHops) }
            item { EmergencyCard(onEmergency) }
            item { SectionHeader("Nearby Devices", "See all", onOpenNetwork) }
            if (nodes.isEmpty()) item { EmptyText(if (transportStatus.contains("scan", true)) transportStatus else "Scan to find nearby OffGrid devices.") }
            else items(nodes.take(3), key = { it.logicalId }) { DeviceRow(it, onSelectNode) }
            item { SectionHeader("Recent Conversations", null, null) }
            if (recent.isEmpty()) item { EmptyText("Your conversations will appear here after your first offline message.") }
            else items(recent.take(2), key = { it.id }) { RecentRow(it, onOpenConversation) }
            item { Text(transportStatus.ifBlank { "Listening for nearby OffGrid nodes…" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable private fun LogoMark() {
    Canvas(Modifier.size(42.dp)) {
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(3.dp.toPx(), 31.dp.toPx()); lineTo(18.dp.toPx(), 7.dp.toPx()); lineTo(31.dp.toPx(), 25.dp.toPx()); lineTo(37.dp.toPx(), 17.dp.toPx())
        }
        drawPath(p, color = Navy, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
        drawLine(Navy, Offset(19.dp.toPx(), 31.dp.toPx()), Offset(27.dp.toPx(), 21.dp.toPx()), 2.5.dp.toPx(), StrokeCap.Round)
    }
}

@Composable private fun MeshStatus(linkState: LinkState, transportStatus: String, connected: Int) {
    val active = linkState != LinkState.OFFLINE
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(if (active) Green else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(if (active) "MESH ACTIVE" else "MESH OFFLINE", fontWeight = FontWeight.Bold, color = if (active) Green else Navy, style = MaterialTheme.typography.titleSmall)
                Text(if (active) "Local network operational" else "Waiting for mesh transport", color = Green, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(if (connected > 0) "$connected linked" else "No internet", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium, color = Navy)
                Text("P2P mode", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun MeshTopology(nodes: List<Node>, onSelect: (Node) -> Unit) {
    val visible = nodes.take(5)
    val pulse by rememberInfiniteTransition(label = "home-pulse").animateFloat(.85f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    BoxWithConstraints(Modifier.fillMaxWidth().height(300.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = minOf(size.width, size.height) * .43f
            for (i in 1..4) drawCircle(Blue.copy(alpha = (.025f + i * .012f) * pulse), r * i / 4f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            visible.forEachIndexed { i, _ ->
                val a = i.toDouble() / maxOf(visible.size, 1) * Math.PI * 2 - Math.PI / 2
                drawLine(Blue.copy(alpha = .45f), c, Offset((c.x + cos(a) * r * .75).toFloat(), (c.y + sin(a) * r * .75).toFloat()), 1.5.dp.toPx(), StrokeCap.Round)
            }
        }
        Surface(Modifier.align(Alignment.Center).size(72.dp), CircleShape, color = Blue, tonalElevation = 4.dp) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("●", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleLarge)
                Text("You", color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
        visible.forEachIndexed { i, node ->
            val a = i.toDouble() / maxOf(visible.size, 1) * Math.PI * 2 - Math.PI / 2
            val x = (.5 + .36 * cos(a)).toFloat(); val y = (.5 + .38 * sin(a)).toFloat()
            val connected = node.status == NodeStatus.CONNECTED
            Surface(
                Modifier.offset(x = maxWidth * x - 32.dp, y = 300.dp * y - 32.dp).size(64.dp).clickable { onSelect(node) },
                CircleShape,
                color = if (connected) SoftGreen else SoftBlue,
                tonalElevation = 1.dp,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(15.dp).background(if (connected) Green else Blue, CircleShape))
                    Text(node.name.take(9), color = Navy, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${node.hops} hop${if (node.hops == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable private fun Metrics(deviceCount: Int, maxHops: Int) {
    Surface(Modifier.fillMaxWidth(), RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Row(Modifier.padding(vertical = 13.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Metric("$deviceCount", "devices")
            DividerLine()
            Metric("$maxHops", "max hops")
            DividerLine()
            Metric("AES-256", "hop encrypted")
        }
    }
}
@Composable private fun Metric(value: String, label: String) = Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, fontWeight = FontWeight.Bold, color = Navy, style = MaterialTheme.typography.titleSmall); Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) }
@Composable private fun DividerLine() = Box(Modifier.size(width = 1.dp, height = 30.dp).background(MaterialTheme.colorScheme.outlineVariant))

@Composable private fun EmergencyCard(onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), RoundedCornerShape(16.dp), color = SoftRed) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(42.dp), RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.error.copy(alpha = .12f)) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("!", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge) } }
            Column(Modifier.weight(1f).padding(start = 12.dp)) { Text("EMERGENCY SOS", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall); Text("Hold to broadcast distress signal", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Text("›", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable private fun SectionHeader(title: String, action: String?, onAction: (() -> Unit)?) = Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy); if (action != null && onAction != null) Text(action, Modifier.clickable(onClick = onAction), color = Blue, style = MaterialTheme.typography.labelMedium) }

@Composable private fun DeviceRow(node: Node, onSelect: (Node) -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable { onSelect(node) }, RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(18.dp).background(if (node.status == NodeStatus.CONNECTED) SoftGreen else SoftBlue, CircleShape), contentAlignment = Alignment.Center) { Box(Modifier.size(9.dp).background(if (node.status == NodeStatus.CONNECTED) Green else Blue, CircleShape)) }
            Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(node.name, fontWeight = FontWeight.SemiBold, color = Navy, style = MaterialTheme.typography.bodyMedium); Text(when (node.status) { NodeStatus.CONNECTED -> "Direct connection · ${node.hops} hop${if (node.hops == 1) "" else "s"}"; NodeStatus.CONNECTING -> "Connecting…"; NodeStatus.AVAILABLE -> "Nearby · tap to connect"; NodeStatus.OFFLINE -> "Not reachable" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable private fun RecentRow(conversation: Conversation, onOpen: (Node) -> Unit) {
    val peer = conversation.peer
    Surface(Modifier.fillMaxWidth().clickable { onOpen(peer) }, RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(40.dp), CircleShape, color = SoftGreen) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(peer.name.take(1).uppercase(), color = Green, fontWeight = FontWeight.Bold) } }
            Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(peer.name, color = Navy, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium); Text(conversation.lastMessage?.content.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable private fun EmptyText(text: String) = Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))

enum class HomeTab { HOME, MESSAGES, NETWORK }
@Composable private fun BottomBar(selected: HomeTab, onHome: () -> Unit, onMessages: () -> Unit, onNetwork: () -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.navigationBarsPadding()) {
        NavigationBarItem(selected == HomeTab.HOME, onHome, icon = { Text("⌂", style = MaterialTheme.typography.titleLarge) }, label = { Text("Home") })
        NavigationBarItem(selected == HomeTab.MESSAGES, onMessages, icon = { Text("□", style = MaterialTheme.typography.titleLarge) }, label = { Text("Messages") })
        NavigationBarItem(selected == HomeTab.NETWORK, onNetwork, icon = { Text("⌘", style = MaterialTheme.typography.titleLarge) }, label = { Text("Network") })
    }
}
