package com.offgrid.app.data.repository

import com.offgrid.app.data.model.Conversation
import com.offgrid.app.data.model.Message
import com.offgrid.app.data.model.MessageStatus
import com.offgrid.app.data.model.MessageType
import com.offgrid.app.data.model.Node
import com.offgrid.app.data.transport.CommunicationTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns message/conversation state. The UI never touches CommunicationTransport directly —
 * it calls send()/sendEmergency() here and observes [conversations].
 */
class MessagingRepository(
    private val transport: CommunicationTransport,
    private val selfId: String,
    scope: CoroutineScope,
) {
    private val _conversations = MutableStateFlow<Map<String, Conversation>>(emptyMap())
    val conversations: StateFlow<Map<String, Conversation>> = _conversations.asStateFlow()

    init {
        scope.launch {
            runCatching {
                transport.incomingMessages.collect { message ->
                    append(message.conversationId, message)
                }
            }
        }
    }

    fun conversationWith(node: Node): Conversation =
        _conversations.value[node.id] ?: Conversation(id = node.id, peer = node)

    suspend fun send(node: Node, text: String) {
        val outgoing = Message(
            conversationId = node.id,
            senderId = selfId,
            receiverId = node.id,
            content = text,
        )
        append(node.id, outgoing)

        val result = runCatching { transport.sendMessage(outgoing) }
            .getOrElse { Result.failure(it) }

        replaceStatus(
            node.id,
            outgoing.id,
            if (result.isSuccess) MessageStatus.DELIVERED else MessageStatus.FAILED,
        )
    }

    suspend fun sendEmergency(node: Node, text: String): Result<Unit> {
        val outgoing = Message(
            conversationId = node.id,
            senderId = selfId,
            receiverId = node.id,
            content = text,
            type = MessageType.EMERGENCY,
        )
        append(node.id, outgoing)

        val result = runCatching { transport.sendMessage(outgoing) }
            .getOrElse { Result.failure(it) }

        replaceStatus(
            node.id,
            outgoing.id,
            if (result.isSuccess) MessageStatus.DELIVERED else MessageStatus.FAILED,
        )
        return result
    }

    private fun append(conversationId: String, message: Message) {
        val current = _conversations.value
        val existing = current[conversationId]
            ?: Conversation(
                id = conversationId,
                peer = Node(id = conversationId, name = conversationId),
            )
        _conversations.value = current +
            (conversationId to existing.copy(messages = existing.messages + message))
    }

    private fun replaceStatus(conversationId: String, messageId: String, status: MessageStatus) {
        val existing = _conversations.value[conversationId] ?: return
        val updated = existing.messages.map {
            if (it.id == messageId) it.copy(status = status) else it
        }
        _conversations.value = _conversations.value +
            (conversationId to existing.copy(messages = updated))
    }
}
