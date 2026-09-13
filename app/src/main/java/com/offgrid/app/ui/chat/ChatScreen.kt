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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Text("‹", style = MaterialTheme.typography.headlineMedium) } },
                title = {
                    Column {
                        Text(node.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            if (canSend) "● Offline link" else "○ Waiting for mesh route…",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .65f),
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        when {
                            transportStatus.contains("Relay") -> "MESH RELAY · $transportStatus"
                            transportStatus.contains("encrypted", ignoreCase = true) -> "ENCRYPTED HOP · $transportStatus"
                            canSend -> "OFFLINE LINK · Messages travel device-to-device"
                            else -> "WAITING FOR MESH ROUTE · Keep a relay nearby"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (transportStatus.contains("Relay") || transportStatus.contains("hops", ignoreCase = true)) {
                        Text(
                            "Each relay decrypts its incoming hop and re-encrypts for the next hop.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (messages.isEmpty()) {
                    Text(
                        "No messages yet.\nStart the conversation offline.",
                        Modifier.fillMaxWidth().padding(top = 90.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    messages.forEach { MessageBubble(it, selfId) }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(12.dp),
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
                    shape = RoundedCornerShape(20.dp),
                )
                Button(
                    onClick = { val text = draft.trim(); if (text.isNotEmpty() && canSend) { draft = ""; onSend(text) } },
                    enabled = canSend && draft.isNotBlank(),
                ) { Text("Send") }
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
            color = if (fromMe) MaterialTheme.colorScheme.primary.copy(alpha = .13f) else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                if (message.type == MessageType.EMERGENCY) {
                    Text("EMERGENCY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
                Text(message.content, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    val time = runCatching { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestamp)) }.getOrDefault("")
                    Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (fromMe) Text("  ${statusLabel(message.status)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (message.hopEncrypted && message.hopCount > 1) {
                    Text(
                        "Encrypted relay • ${message.hopCount} hops",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
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
