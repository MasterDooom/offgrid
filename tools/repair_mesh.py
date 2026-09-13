from pathlib import Path

p = Path("app/src/main/java/com/offgrid/app/data/transport/MockCommunicationTransport.kt")
s = p.read_text()

replacements = [
    (
        "                    scope.launch { announceRoutesTo(endpointId) }",
        """                    // Re-advertise the complete route table to every connected neighbour.\n                    // This lets an existing A-B link learn about C when B connects to C later.\n                    scope.launch { announceRoutesTo(null) }""",
    ),
    (
        """    private val seenPackets = ConcurrentHashMap.newKeySet<String>()\n    private val sendMutex = Mutex()""",
        """    private val seenPackets = ConcurrentHashMap.newKeySet<String>()\n    // A packet is only permanently deduplicated after successful forwarding.\n    private val forwardingPackets = ConcurrentHashMap.newKeySet<String>()\n    private val sendMutex = Mutex()""",
    ),
    (
        """                logicalIdEndpoints[logicalId] = endpointId\n                val status = if (connectedEndpoints.contains(endpointId)) NodeStatus.CONNECTED else NodeStatus.AVAILABLE""",
        """                // Discovery can report a new endpoint for the same logical device.\n                // Never replace a live mapping with an unconnected endpoint.\n                val existingEndpoint = logicalIdEndpoints[logicalId]\n                if (existingEndpoint == null || !connectedEndpoints.contains(existingEndpoint)) {\n                    logicalIdEndpoints[logicalId] = endpointId\n                }\n                val status = if (connectedEndpoints.contains(endpointId)) NodeStatus.CONNECTED else NodeStatus.AVAILABLE""",
    ),
    (
        """        if (packet.messageId in seenPackets) {\n            _transportStatus.value = \"Duplicate packet ignored\"\n            return\n        }""",
        """        if (packet.messageId in seenPackets || !forwardingPackets.add(packet.messageId)) {\n            _transportStatus.value = \"Duplicate packet ignored\"\n            return\n        }""",
    ),
    (
        """        if (packet.destinationNodeId == selfId) {\n            emitDecryptedMessage(endpointId, packet, plaintext, nextHopCount)\n            return\n        }""",
        """        if (packet.destinationNodeId == selfId) {\n            seenPackets.add(packet.messageId)\n            forwardingPackets.remove(packet.messageId)\n            emitDecryptedMessage(endpointId, packet, plaintext, nextHopCount)\n            return\n        }""",
    ),
    (
        """            if (discovered != null) {\n                scope.launch {\n                    if (connectToDevice(discovered).isSuccess) {\n                        forwardAfterConnection(discovered.id, packet, plaintext)\n                    }\n                }\n            }\n            return""",
        """            if (discovered != null) {\n                scope.launch {\n                    if (connectToDevice(discovered).isSuccess) {\n                        forwardAfterConnection(discovered.id, packet, plaintext)\n                    } else {\n                        forwardingPackets.remove(packet.messageId)\n                        _transportStatus.value = \"RELAYING • unable to connect to ${packet.destinationNodeId}\"\n                    }\n                }\n            } else {\n                forwardingPackets.remove(packet.messageId)\n            }\n            return""",
    ),
    (
        """        if (nextEndpoint == null || !connectedEndpoints.contains(nextEndpoint)) {\n            _transportStatus.value = \"RELAYING • next hop unavailable\"\n            return\n        }\n\n        forwardPacket(nextEndpoint, nextHopId, packet, plaintext, nextHopCount)""",
        """        if (nextEndpoint == null || !connectedEndpoints.contains(nextEndpoint)) {\n            _transportStatus.value = \"RELAYING • next hop unavailable\"\n            forwardingPackets.remove(packet.messageId)\n            return\n        }\n\n        forwardPacket(nextEndpoint, nextHopId, packet, plaintext, nextHopCount)""",
    ),
    (
        """        scope.launch { sendToEndpoint(nextEndpoint, forwarded) }""",
        """        scope.launch {\n            val result = sendToEndpoint(nextEndpoint, forwarded)\n            if (result.isSuccess) {\n                seenPackets.add(packet.messageId)\n                forwardingPackets.remove(packet.messageId)\n                _transportStatus.value = \"FORWARDED • hop $nextHopCount • ${selfId.takeLast(4)} → ${nextHopId.takeLast(4)}\"\n            } else {\n                // Allow the same message to be retried after a transient transport failure.\n                forwardingPackets.remove(packet.messageId)\n            }\n        }""",
    ),
]

for old, new in replacements:
    if old not in s:
        raise SystemExit(f"Expected source block not found: {old[:80]!r}")
    s = s.replace(old, new, 1)

p.write_text(s)
print("patched MockCommunicationTransport.kt")
