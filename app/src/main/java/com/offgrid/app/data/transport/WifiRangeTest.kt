package com.offgrid.app.data.transport

import com.offgrid.app.data.model.Message
import com.offgrid.app.data.model.MessageType
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Small engineering utility for measuring a direct Wi-Fi P2P link.
 *
 * This intentionally measures packets accepted by the transport. It does NOT invent an RSSI or
 * distance value and it does not call a link reliable merely because Socket.write() succeeded.
 * For a true end-to-end range study, run this against a second physical phone and compare the
 * receiver's delivered-packet count with the sender's transmitted count.
 */
object WifiRangeTest {
    data class Result(
        val requestedPackets: Int,
        val acceptedPackets: Int,
        val failedPackets: Int,
        val elapsedMs: Long,
    ) {
        val transportAcceptancePercent: Double
            get() = if (requestedPackets == 0) 0.0 else acceptedPackets.toDouble() / requestedPackets * 100.0
    }

    suspend fun run(
        transport: WifiDirectCommunicationTransport,
        destinationNodeId: String,
        packets: Int = 100,
        intervalMs: Long = 50,
    ): Result {
        require(destinationNodeId.isNotBlank()) { "Destination node ID is required" }
        require(packets in 1..1_000) { "Packets must be between 1 and 1000" }

        var accepted = 0
        var failed = 0
        val started = System.currentTimeMillis()

        repeat(packets) { index ->
            val message = Message(
                id = "range-${UUID.randomUUID()}",
                conversationId = destinationNodeId,
                senderId = "RANGE_TEST",
                receiverId = destinationNodeId,
                recipientNodeId = destinationNodeId,
                content = "OFFGRID_RANGE_TEST:$index",
                type = MessageType.SYSTEM,
            )
            if (transport.sendMessage(message).isSuccess) accepted++ else failed++
            if (intervalMs > 0) delay(intervalMs)
        }

        return Result(
            requestedPackets = packets,
            acceptedPackets = accepted,
            failedPackets = failed,
            elapsedMs = System.currentTimeMillis() - started,
        )
    }
}
