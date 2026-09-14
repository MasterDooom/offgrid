package com.offgrid.app.ui.messages

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
        floatingActionButton = { FloatingActionButton(onClick = { }, containerColor = Blue, contentColor = Color.White) { Text("✎", style = MaterialTheme.typography.titleLarge) } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 10.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Messages", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Navy)
                    Text("Direct. Private. Off the grid.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = {}) { Text("⌕", color = Navy, style = MaterialTheme.typography.headlineSmall) }
                IconButton(onClick = {}) { Text("⋮", color = Navy, style = MaterialTheme.typography.titleLarge) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Tab("All", true); Tab("Unread  2", false); Tab("Groups", false); Tab("SOS", false)
            }
            if (recent.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No messages yet", color = Navy, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                        Text("Start a conversation with a nearby OffGrid node.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    items(recent, key = { it.id }) { conversation -> ConversationRow(conversation) { onOpenConversation(conversation.peer) } }
                }
            }
        }
    }
}

@Composable private fun Tab(label: String, selected: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = if (selected) Blue else Navy, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.size(8.dp))
        Box(Modifier.size(width = if (selected) 44.dp else 0.dp, height = 2.dp).background(Blue))
    }
}

@Composable private fun ConversationRow(conversation: Conversation, onClick: () -> Unit) {
    val peer = conversation.peer
    val message = conversation.lastMessage
    val connected = peer.status == NodeStatus.CONNECTED
    Surface(Modifier.fillMaxWidth().clickable(onClick = onClick), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(50.dp), CircleShape, color = if (connected) SoftGreen else SoftBlue) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(peer.name.take(1).uppercase(), color = if (connected) Green else Blue, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(peer.name, color = Navy, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(formatTime(message?.timestamp ?: 0L), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message?.content.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (connected) Text("●", color = Blue, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
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
