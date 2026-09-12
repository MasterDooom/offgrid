package com.offgrid.app.data.repository

import com.offgrid.app.data.model.Conversation
import com.offgrid.app.data.model.Message
import com.offgrid.app.data.model.MessageStatus
import com.offgrid.app.data.model.MessageType
import com.offgrid.app.data.model.Node
import com.offgrid.app.data.transport.CommunicationTransport
import kotlinx.coroutines.CancellationException
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
                    try {
                        append(message.conversationId, message)
                    } catch (_: Throwable) {
                        // Never let a malformed packet crash the UI collector.
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Transport failure must not terminate the application UI.
            }
        }
    }

    fun conversationWith(node: Node): Conversation =
        _conversations.value[node.id] ?: Conversation(id = node.id, peer = node)

    suspend fun send(node: Node, text: String) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return

        sendMutex.withLock {
            val outgoing = Message(
                conversationId = node.id,
                senderId = selfId,
                receiverId = node.id,
                content = cleanText,
            )

            try {
                append(node.id, outgoing)
            } catch (_: Throwable) {
                return@withLock
            }

            val result = try {
                transport.sendMessage(outgoing)
            } catch (_: Throwable) {
                Result.failure<Unit>(IllegalStateException("Message transport failed"))
            }

            try {
                replaceStatus(
                    node.id,
                    outgoing.id,
                    if (result.isSuccess) MessageStatus.DELIVERED else MessageStatus.FAILED,
                )
            } catch (_: Throwable) {
                // Status rendering is best-effort and must never crash the app.
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
        try {
            append(node.id, outgoing)
        } catch (error: Throwable) {
            return@withLock Result.failure(error)
        }

        val result = try {
            transport.sendMessage(outgoing)
        } catch (error: Throwable) {
            Result.failure(error)
        }

        try {
            replaceStatus(
                node.id,
                outgoing.id,
                if (result.isSuccess) MessageStatus.DELIVERED else MessageStatus.FAILED,
            )
        } catch (_: Throwable) {
            // Ignore UI-state update failure.
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

        // Nearby may retry a payload. Never insert the same message twice.
        if (existing.messages.any { it.id == message.id }) return

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
