package com.offgrid.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.offgrid.app.data.model.Message
import com.offgrid.app.data.model.MessageStatus
import com.offgrid.app.data.model.MessageType
import com.offgrid.app.data.model.Node
import java.text.DateFormat
import java.util.Date

private val Navy = Color(0xFF10213A)
private val Blue = Color(0xFF1769FF)
private val Green = Color(0xFF08A66A)
private val SoftBlue = Color(0xFFEAF1FF)
private val Border = Color(0xFFE3E9F1)

@Composable
fun ChatScreen(
    node: Node,
    messages: List<Message>,
    selfId: String,
    canSend: Boolean,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    transportStatus: String = "",
) {
    var draft by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val statusColor = if (canSend) Green else MaterialTheme.colorScheme.onSurfaceVariant

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Text("‹", color = Navy, style = MaterialTheme.typography.headlineMedium) }
                Surface(Modifier.size(44.dp), CircleShape, color = SoftBlue) { BoxCenter { Text(node.name.take(1).uppercase(), color = Blue, fontWeight = FontWeight.ExtraBold) } }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(node.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Navy)
                    Row(verticalAlignment = Alignment.CenterVertically) { BoxDot(statusColor); Text(if (canSend) "Connected to mesh" else "Waiting for mesh route", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Surface(RoundedCornerShape(50), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, Border)) { IconButton(onClick = {}) { Text("⋮", color = Navy, style = MaterialTheme.typography.titleLarge) } }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), RoundedCornerShape(14.dp), color = SoftBlue, border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD9E6FF))) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("⌁", color = Blue, fontWeight = FontWeight.Bold)
                    Column(Modifier.padding(start = 9.dp)) {
                        Text(if (canSend) "OFFLINE LINK ACTIVE" else "SEARCHING FOR A ROUTE", color = Navy, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelSmall)
                        Text(transportStatus.ifBlank { "Messages travel device-to-device." }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (messages.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(top = 90.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(Modifier.size(62.dp), CircleShape, color = SoftBlue) { BoxCenter { Text("✦", color = Blue, style = MaterialTheme.typography.titleLarge) } }
                        Text("Start the conversation", Modifier.padding(top = 13.dp), color = Navy, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text("Your messages stay on the local mesh.", Modifier.padding(top = 5.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                } else messages.forEach { MessageBubble(it, selfId) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(value = draft, onValueChange = { draft = it }, Modifier.weight(1f), placeholder = { Text(if (canSend) "Write a message…" else "Waiting for connection…") }, enabled = canSend, maxLines = 4, shape = RoundedCornerShape(23.dp))
                Surface(onClick = { val text = draft.trim(); if (text.isNotEmpty() && canSend) { draft = ""; onSend(text) } }, enabled = canSend && draft.isNotBlank(), shape = CircleShape, color = if (canSend && draft.isNotBlank()) Blue else MaterialTheme.colorScheme.surfaceVariant) {
                    Text("↑", Modifier.padding(horizontal = 16.dp, vertical = 13.dp), color = if (canSend && draft.isNotBlank()) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable private fun BoxCenter(content: @Composable () -> Unit) = androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
@Composable private fun BoxDot(color: Color) = androidx.compose.foundation.layout.Box(Modifier.size(7.dp).background(color, CircleShape))

@Composable private fun MessageBubble(message: Message, selfId: String) {
    val fromMe = message.senderId == selfId
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromMe) Arrangement.End else Arrangement.Start) {
        Surface(shape = if (fromMe) RoundedCornerShape(19.dp, 19.dp, 6.dp, 19.dp) else RoundedCornerShape(19.dp, 19.dp, 19.dp, 6.dp), color = if (fromMe) Blue else MaterialTheme.colorScheme.surface, contentColor = if (fromMe) Color.White else Navy, modifier = Modifier.widthIn(max = 315.dp), border = if (fromMe) null else androidx.compose.foundation.BorderStroke(1.dp, Border)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (message.type == MessageType.EMERGENCY) Text("EMERGENCY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.ExtraBold)
                Text(message.content, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    val time = runCatching { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestamp)) }.getOrDefault("")
                    Text(time, style = MaterialTheme.typography.labelSmall, color = if (fromMe) Color.White.copy(alpha = .72f) else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (fromMe) Text("  ${statusLabel(message.status)}", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .78f))
                }
                if (message.hopEncrypted && message.hopCount > 1) Text("Encrypted relay · ${message.hopCount} hops", style = MaterialTheme.typography.labelSmall, color = if (fromMe) Color.White.copy(alpha = .78f) else Blue, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

private fun statusLabel(status: MessageStatus): String = when (status) {
    MessageStatus.SENDING -> "Sending…"
    MessageStatus.DELIVERED -> "Delivered"
    MessageStatus.FAILED -> "Failed"
    MessageStatus.RECEIVED -> "Received"
}
