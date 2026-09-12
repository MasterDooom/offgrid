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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns message/conversation state. */
class MessagingRepository(
    private val transport: CommunicationTransport,
    private val selfId: String,
    scope: CoroutineScope,
) {
    private val _conversations = MutableStateFlow<Map<String, Conversation>>(emptyMap())
    val conversations: StateFlow<Map<String, Conversation>> = _conversations.asStateFlow()
    private val sendMutex = Mutex()

    init {
        scope.launch {
            try {
                transport.incomingMessages.collect { message ->
                    runCatching { append(message.conversationId, message) }
                }
            } catch (_: Throwable) {
                // A malformed/failed packet must never terminate the UI coroutine.
            }
        }
    }

    fun conversationWith(node: Node): Conversation =
        _conversations.value[node.id] ?: Conversation(id = node.id, peer = node)

    suspend fun send(node: Node, text: String) {
        if (text.isBlank()) return

        sendMutex.withLock {
            val outgoing = Message(
                conversationId = node.id,
                senderId = selfId,
                receiverId = node.id,
                content = text,
            )
            runCatching { append(node.id, outgoing) }

            val result = try {
                transport.sendMessage(outgoing)
            } catch (error: Throwable) {
                Result.failure(error)
            }

            runCatching {
                replaceStatus(
                    node.id,
                    outgoing.id,
                    if (result.isSuccess) MessageStatus.DELIVERED else MessageStatus.FAILED,
                )
            }
        }
    }

    suspend fun sendEmergency(node: Node, text: String): Result<Unit> = sendMutex.withLock {
        val outgoing = Message(
            conversationId = node.id,
            senderId = selfId,
            receiverId = node.id,
            content = text,
            type = MessageType.EMERGENCY,
        )
        runCatching { append(node.id, outgoing) }

        val result = try {
            transport.sendMessage(outgoing)
        } catch (error: Throwable) {
            Result.failure(error)
        }

        runCatching {
            replaceStatus(
                node.id,
                outgoing.id,
                if (result.isSuccess) MessageStatus.DELIVERED else MessageStatus.FAILED,
            )
        }
        result
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
