package com.offgrid.app.data.repository

import com.offgrid.app.data.model.Conversation
import com.offgrid.app.data.model.Message
import com.offgrid.app.data.model.MessageStatus
import com.offgrid.app.data.model.MessageType
import com.offgrid.app.data.model.Node
import com.offgrid.app.data.transport.CommunicationTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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
                        val conversationId = canonicalConversationId(message)
                        append(conversationId, message.copy(conversationId = conversationId))
                    } catch (_: Throwable) {
                        // Ignore malformed incoming packets without killing the collector.
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
        _conversations.value[node.logicalId] ?: Conversation(id = node.logicalId, peer = node)

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
            try { append(conversationId, outgoing) } catch (_: Throwable) { return@withLock }

            val result = deliverWithRecovery(node, outgoing)
            replaceStatus(conversationId, outgoing.id, if (result.isSuccess) MessageStatus.DELIVERED else MessageStatus.FAILED)
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
        try { append(conversationId, outgoing) } catch (error: Throwable) { return@withLock Result.failure(error) }
        val result = deliverWithRecovery(node, outgoing)
        replaceStatus(conversationId, outgoing.id, if (result.isSuccess) MessageStatus.DELIVERED else MessageStatus.FAILED)
        result
    }

    private suspend fun deliverWithRecovery(node: Node, message: Message): Result<Unit> {
        var last = runCatching { transport.sendMessage(message) }.getOrElse { Result.failure(it) }
        if (last.isSuccess) return last

        // The peer can be discovered before its socket/HELLO mapping is ready. Give the transport
        // a chance to establish the P2P data channel, then retry more than once to absorb the
        // normal Android P2P group/handshake race.
        repeat(2) { attempt ->
            runCatching { transport.connectToDevice(node) }.onFailure { last = Result.failure(it) }
            if (last.isSuccess) return@repeat
            delay(350L * (attempt + 1))
            last = runCatching { transport.sendMessage(message) }.getOrElse { Result.failure(it) }
            if (last.isSuccess) return last
        }
        return last
    }

    private fun canonicalConversationId(message: Message): String =
        if (message.senderId == selfId) message.recipientNodeId ?: message.receiverId else message.senderId

    private fun append(conversationId: String, message: Message) {
        if (conversationId.isBlank()) return
        val current = _conversations.value
        val existing = current[conversationId] ?: Conversation(
            id = conversationId,
            peer = Node(id = message.senderId, name = message.senderId, logicalId = conversationId),
        )
        if (existing.messages.any { it.id == message.id }) return
        _conversations.value = current + (conversationId to existing.copy(messages = existing.messages + message))
    }

    private fun replaceStatus(conversationId: String, messageId: String, status: MessageStatus) {
        val existing = _conversations.value[conversationId] ?: return
        _conversations.value = _conversations.value +
            (conversationId to existing.copy(messages = existing.messages.map { if (it.id == messageId) it.copy(status = status) else it }))
    }
}
