package com.offgrid.app.ui.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
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
import com.offgrid.app.data.model.Node
import com.offgrid.app.data.model.NodeStatus

private val Navy = Color(0xFF10213A)
private val Blue = Color(0xFF1769FF)
private val Green = Color(0xFF08A66A)
private val SoftGreen = Color(0xFFDDF7EC)
private val SoftBlue = Color(0xFFE8F0FF)
private val Border = Color(0xFFE3E9F1)

@Composable
fun MessagesScreen(
    conversations: Map<String, Conversation>,
    onOpenConversation: (Node) -> Unit,
    onHome: () -> Unit,
    onNetwork: () -> Unit,
) {
    val recent = conversations.values.filter { it.lastMessage != null }.sortedByDescending { it.lastMessage!!.timestamp }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BottomBar(onHome, {}, onNetwork) },
        floatingActionButton = { FloatingActionButton(onClick = { }, containerColor = Blue, contentColor = Color.White, shape = CircleShape) { Text("+", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Light) } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Messages", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, color = Navy)
                    Text("Private conversations, no internet required.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
                    IconButton(onClick = {}) { Text("⌕", color = Navy, style = MaterialTheme.typography.titleLarge) }
                }
                IconButton(onClick = {}) { Text("⋮", color = Navy, style = MaterialTheme.typography.titleLarge) }
            }
            Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
                Row(Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("⌕", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
                    Text("Search conversations", Modifier.padding(start = 9.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tab("All", true); Tab("Unread", false); Tab("Groups", false); Tab("SOS", false)
            }
            if (recent.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 35.dp)) {
                        Surface(Modifier.size(64.dp), CircleShape, color = SoftBlue) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("□", color = Blue, style = MaterialTheme.typography.headlineSmall) } }
                        Text("No messages yet", Modifier.padding(top = 14.dp), color = Navy, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("Start a conversation with a nearby OffGrid node.", Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, top = 13.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    items(recent, key = { it.id }) { conversation -> ConversationRow(conversation) { onOpenConversation(conversation.peer) } }
                }
            }
        }
    }
}

@Composable private fun Tab(label: String, selected: Boolean) {
    Surface(RoundedCornerShape(50), color = if (selected) Navy else MaterialTheme.colorScheme.surface, border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Text(label, Modifier.padding(horizontal = 15.dp, vertical = 8.dp), color = if (selected) Color.White else Navy, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable private fun ConversationRow(conversation: Conversation, onClick: () -> Unit) {
    val peer = conversation.peer
    val message = conversation.lastMessage
    val connected = peer.status == NodeStatus.CONNECTED
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), RoundedCornerShape(17.dp), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(50.dp), CircleShape, color = if (connected) SoftGreen else SoftBlue) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(peer.name.take(1).uppercase(), color = if (connected) Green else Blue, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleMedium) } }
            Column(Modifier.weight(1f).padding(start = 13.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(peer.name, color = Navy, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatTime(message?.timestamp ?: 0L), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message?.content.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (connected) { Box(Modifier.padding(start = 8.dp).size(7.dp).background(Green, CircleShape)) }
                }
            }
        }
    }
}

@Composable private fun BottomBar(onHome: () -> Unit, onMessages: () -> Unit, onNetwork: () -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, modifier = Modifier.navigationBarsPadding()) {
        NavigationBarItem(false, onHome, icon = { Text("⌂", color = Navy, style = MaterialTheme.typography.titleLarge) }, label = { Text("Home") })
        NavigationBarItem(true, onMessages, icon = { Text("□", color = Blue, style = MaterialTheme.typography.titleLarge) }, label = { Text("Messages") })
        NavigationBarItem(false, onNetwork, icon = { Text("⌘", color = Navy, style = MaterialTheme.typography.titleLarge) }, label = { Text("Network") })
    }
}

private fun formatTime(timestamp: Long): String = if (timestamp <= 0L) "" else runCatching { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(timestamp)) }.getOrDefault("")
