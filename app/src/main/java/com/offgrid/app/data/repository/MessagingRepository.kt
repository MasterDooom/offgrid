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
                        // Always derive the conversation from stable logical identities.
                        // A Nearby endpoint ID is temporary and must never create a second chat.
                        val conversationId = canonicalConversationId(message)
                        append(conversationId, message.copy(conversationId = conversationId))
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
        _conversations.value[node.logicalId] ?: Conversation(
            id = node.logicalId,
            peer = node,
        )

    suspend fun send(node: Node, text: String) {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return

        sendMutex.withLock {
            val conversationId = node.logicalId
            val outgoing = Message(
                conversationId = conversationId,
                senderId = selfId,
                receiverId = node.id,
                content = cleanText,
                recipientNodeId = node.logicalId,
            )

            try {
                append(conversationId, outgoing)
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
                    conversationId,
                    outgoing.id,
                    if (result.isSuccess) MessageStatus.DELIVERED else MessageStatus.FAILED,
                )
            } catch (_: Throwable) {
                // Status rendering is best-effort and must never crash the app.
            }
        }
    }

    suspend fun sendEmergency(node: Node, text: String): Result<Unit> = sendMutex.withLock {
        val conversationId = node.logicalId
        val outgoing = Message(
            conversationId = conversationId,
            senderId = selfId,
            receiverId = node.id,
            content = text,
            type = MessageType.EMERGENCY,
            recipientNodeId = node.logicalId,
        )
        try {
            append(conversationId, outgoing)
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
                conversationId,
                outgoing.id,
                if (result.isSuccess) MessageStatus.DELIVERED else MessageStatus.FAILED,
            )
        } catch (_: Throwable) {
            // Ignore UI-state update failure.
        }
        result
    }

    /**
     * The logical peer is the conversation key. For an outgoing message the peer is the
     * recipient; for an incoming message the peer is the original sender. This remains true
     * even when the packet travelled through one or more relay nodes.
     */
    private fun canonicalConversationId(message: Message): String =
        if (message.senderId == selfId) {
            message.recipientNodeId ?: message.receiverId
        } else {
            message.senderId
        }

    private fun append(conversationId: String, message: Message) {
        if (conversationId.isBlank()) return
        val current = _conversations.value
        val existing = current[conversationId]
            ?: Conversation(
                id = conversationId,
                peer = Node(
                    id = message.senderId,
                    name = message.senderId,
                    logicalId = conversationId,
                ),
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