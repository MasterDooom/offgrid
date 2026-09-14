package com.offgrid.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.offgrid.app.data.model.Conversation
import com.offgrid.app.data.model.LinkState
import com.offgrid.app.data.model.Node
import com.offgrid.app.data.model.NodeStatus
import com.offgrid.app.data.repository.IdentityManager

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
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = { HomeBottomBar(onOpenMessages, onOpenNetwork) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(modifier = Modifier.size(46.dp), shape = RoundedCornerShape(14.dp), color = Navy) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("⌁", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold) } }
                    Column(Modifier.weight(1f).padding(start = 11.dp)) { Text("OFFGRID", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = Navy); Text("People stay connected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Text("⚙", Modifier.padding(horizontal = 12.dp, vertical = 9.dp).clickable(onClick = onOpenSettings), color = Navy) }
                }
            }
            item { MeshStatus(linkState, connected) }
            item { MeshCard(nodes) }
            item { Metrics(nodes.size, maxHops) }
            item { Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onEmergency), shape = RoundedCornerShape(18.dp), color = SoftRed, border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFFD0D5))) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Surface(modifier = Modifier.size(42.dp), shape = RoundedCornerShape(13.dp), color = Color.White) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("!", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.ExtraBold) } }; Column(Modifier.weight(1f).padding(start = 12.dp)) { Text("EMERGENCY SOS", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.ExtraBold); Text("Broadcast a distress signal over the mesh", color = MaterialTheme.colorScheme.error.copy(alpha = .78f), style = MaterialTheme.typography.bodySmall) } } } }
            item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Nearby devices", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy); Text("View network  ›", Modifier.clickable(onClick = onOpenNetwork), color = Blue, style = MaterialTheme.typography.labelMedium) } }
            if (nodes.isEmpty()) item { Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onDiscover), shape = RoundedCornerShape(14.dp), color = SoftBlue, border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD8E5FF))) { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) { Surface(modifier = Modifier.size(36.dp), shape = CircleShape, color = Color.White) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("⌁", color = Blue) } }; Column(Modifier.weight(1f).padding(start = 11.dp)) { Text("Find nearby nodes", color = Navy, fontWeight = FontWeight.SemiBold); Text(transportStatus.ifBlank { "Tap to start local discovery" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }; Text("Scan", color = Blue, fontWeight = FontWeight.Bold) } } }
            else items(nodes.take(3), key = { it.logicalId }) { DeviceRow(it, onSelectNode) }
            item { Text("Recent conversations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Navy) }
            if (recent.isEmpty()) item { Text("Your private conversations will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            else items(recent.take(2), key = { it.id }) { conversation -> RecentRow(conversation) { onOpenConversation(conversation.peer) } }
            item { Text(transportStatus.ifBlank { "Listening for nearby OffGrid nodes…" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable private fun MeshStatus(linkState: LinkState, connected: Int) { val active = linkState != LinkState.OFFLINE; Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Row(Modifier.padding(horizontal = 15.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(11.dp).background(if (active) Green else Color(0xFF9AA7B8), CircleShape)); Column(Modifier.weight(1f).padding(start = 11.dp)) { Text(if (active) "MESH ACTIVE" else "MESH STANDBY", fontWeight = FontWeight.ExtraBold, color = if (active) Green else Navy); Text(if (active) "Local network is ready" else "Waiting for a local link", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }; Surface(shape = RoundedCornerShape(10.dp), color = if (active) SoftGreen else MaterialTheme.colorScheme.surfaceVariant) { Text(if (connected > 0) "$connected LINKED" else "P2P READY", Modifier.padding(horizontal = 9.dp, vertical = 6.dp), color = if (active) Green else Navy, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall) } } } }

@Composable private fun MeshCard(nodes: List<Node>) { Surface(modifier = Modifier.fillMaxWidth().height(210.dp), shape = RoundedCornerShape(20.dp), color = Color(0xFF142333)) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Surface(modifier = Modifier.size(72.dp), shape = CircleShape, color = Blue) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("You", color = Color.White, fontWeight = FontWeight.ExtraBold) } }; Text("LOCAL MESH", Modifier.padding(top = 10.dp), color = Color.White, fontWeight = FontWeight.ExtraBold); Text(if (nodes.isEmpty()) "No nearby nodes yet" else "${nodes.size} nearby node${if (nodes.size == 1) "" else "s"}", color = Color.White.copy(alpha = .72f), style = MaterialTheme.typography.bodySmall) } } } }
@Composable private fun Metrics(deviceCount: Int, maxHops: Int) { Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Row(Modifier.padding(vertical = 13.dp), horizontalArrangement = Arrangement.SpaceEvenly) { Metric(deviceCount.toString(), "devices"); Metric(maxHops.toString(), "max hops"); Metric("AES-256", "hop encrypted") } } }
@Composable private fun Metric(value: String, label: String) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(value, fontWeight = FontWeight.ExtraBold, color = Navy, style = MaterialTheme.typography.titleSmall); Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) } }
@Composable private fun DeviceRow(node: Node, onSelect: (Node) -> Unit) { Surface(modifier = Modifier.fillMaxWidth().clickable { onSelect(node) }, shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Row(Modifier.padding(horizontal = 13.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(34.dp).background(if (node.status == NodeStatus.CONNECTED) SoftGreen else SoftBlue, CircleShape), contentAlignment = Alignment.Center) { Box(Modifier.size(9.dp).background(if (node.status == NodeStatus.CONNECTED) Green else Blue, CircleShape)) }; Column(Modifier.weight(1f).padding(start = 11.dp)) { Text(node.name, fontWeight = FontWeight.SemiBold, color = Navy); Text(when (node.status) { NodeStatus.CONNECTED -> "Connected · ${node.hops} hop${if (node.hops == 1) "" else "s"}"; NodeStatus.CONNECTING -> "Connecting…"; NodeStatus.AVAILABLE -> "Nearby · tap to connect"; NodeStatus.OFFLINE -> "Not reachable" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }; Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
@Composable private fun RecentRow(conversation: Conversation, onOpen: () -> Unit) { val peer = conversation.peer; Surface(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) { Surface(modifier = Modifier.size(40.dp), shape = CircleShape, color = SoftGreen) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(peer.name.take(1).uppercase(), color = Green, fontWeight = FontWeight.ExtraBold) } }; Column(Modifier.weight(1f).padding(start = 11.dp)) { Text(peer.name, color = Navy, fontWeight = FontWeight.SemiBold); Text(conversation.lastMessage?.content.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) } } } }
@Composable private fun HomeBottomBar(onMessages: () -> Unit, onNetwork: () -> Unit) { NavigationBar(containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.navigationBarsPadding()) { NavigationBarItem(true, {}, icon = { Text("⌂", color = Blue) }, label = { Text("Home") }); NavigationBarItem(false, onMessages, icon = { Text("□", color = Navy) }, label = { Text("Messages") }); NavigationBarItem(false, onNetwork, icon = { Text("⌘", color = Navy) }, label = { Text("Network") }) } }
