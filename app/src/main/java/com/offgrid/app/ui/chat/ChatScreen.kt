package com.offgrid.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.offgrid.app.data.model.Message
import com.offgrid.app.data.model.MessageStatus
import com.offgrid.app.data.model.MessageType
import com.offgrid.app.data.model.Node
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineMedium) }
                Surface(Modifier.padding(horizontal = 4.dp), shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = .10f)) {
                    Text(node.name.take(1).uppercase(), Modifier.padding(12.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(node.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        if (canSend) "● Connected to mesh" else "○ Waiting for a mesh route",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text(
                        when {
                            transportStatus.contains("Relay", ignoreCase = true) -> "MESH RELAY · $transportStatus"
                            transportStatus.contains("encrypted", ignoreCase = true) -> "HOP ENCRYPTED · $transportStatus"
                            canSend -> "OFFLINE LINK · Device-to-device messaging"
                            else -> "WAITING FOR MESH ROUTE · Keep a relay nearby"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (messages.any { it.hopCount > 1 }) {
                        Text(
                            "Messages can travel through multiple OffGrid nodes.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                if (messages.isEmpty()) {
                    Text(
                        "No messages yet.\nStart the conversation offline.",
                        Modifier.fillMaxWidth().padding(top = 100.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    messages.forEach { MessageBubble(it, selfId) }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    Modifier.weight(1f),
                    placeholder = { Text("Message…") },
                    enabled = canSend,
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp),
                )
                Surface(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty() && canSend) {
                            draft = ""
                            onSend(text)
                        }
                    },
                    enabled = canSend && draft.isNotBlank(),
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = if (canSend && draft.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text("↑", Modifier.padding(horizontal = 16.dp, vertical = 13.dp), color = if (canSend && draft.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: Message, selfId: String) {
    val fromMe = message.senderId == selfId
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromMe) Arrangement.End else Arrangement.Start) {
        Surface(
            shape = if (fromMe) RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 5.dp),
            color = if (fromMe) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (fromMe) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 310.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (message.type == MessageType.EMERGENCY) {
                    Text("EMERGENCY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
                Text(message.content, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    val time = runCatching { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestamp)) }.getOrDefault("")
                    Text(time, style = MaterialTheme.typography.labelSmall, color = if (fromMe) MaterialTheme.colorScheme.onPrimary.copy(alpha = .72f) else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (fromMe) Text("  ${statusLabel(message.status)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = .78f))
                }
                if (message.hopEncrypted && message.hopCount > 1) {
                    Text(
                        "Encrypted relay · ${message.hopCount} hops",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (fromMe) MaterialTheme.colorScheme.onPrimary.copy(alpha = .78f) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
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
