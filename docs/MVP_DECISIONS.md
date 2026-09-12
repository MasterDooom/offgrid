# MVP transport decision

**Selected: Wi-Fi Direct plus TCP sockets.** It is Android platform functionality, needs no Google Play services or internet route, and exposes the peer/group lifecycle needed to make failures visible during the first two-phone test. It also provides substantially more throughput and range than Bluetooth for later attachments.

**Not selected for the MVP: Nearby Connections.** It is a useful future adapter because it can coordinate advertising, discovery, connections, and payloads across Bluetooth/Wi-Fi technologies. For this prototype it adds a Google Play services dependency and hides the Wi-Fi Direct group/socket boundary we need to observe. It remains compatible with the `TransportLayer` seam.

**Bluetooth: investigate later, do not co-implement now.** BLE is attractive for low-power discovery and Bluetooth Classic as a fallback, but Android 12+ adds `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, and `BLUETOOTH_CONNECT` runtime permissions. Combining that permission and lifecycle model with Wi-Fi Direct before validating the two-phone flow would complicate fault isolation.

## Milestone 1 topology

Wi-Fi Direct creates a group. The elected group owner opens a TCP server on port 8988. The non-owner connects to the owner's group address. Both ends exchange newline-delimited JSON `Packet` objects over that socket.

This is a two-device link, not a mesh. `PacketForwarder` supplies packet identity, deduplication, route avoidance, and TTL mechanics for Milestone 2; it is not yet enabled because Wi-Fi Direct devices generally participate in one group at a time and multi-hop needs a tested connection-management strategy.

## Security boundary

Messages are plaintext in this milestone. Packet serialization is deliberately separated from the socket transport so an AES-GCM envelope and Android Keystore-backed key agreement can be added before any real sensitive use. No custom cryptography is used.

## Test log

Before continuing to forwarding, test on two physical devices with no cellular data and no joined Wi-Fi network. Record Android version, manufacturer, whether Location Mode was on, both event logs, group-owner role, and whether each direction received a packet.
