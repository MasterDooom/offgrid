# OFFGRID — offline mesh MVP

OffGrid is an Android offline-first messaging prototype for situations where normal cellular infrastructure is unavailable.

Core flow: **Discover → Connect → Chat → Relay → Emergency SOS**.

## Current architecture

```text
Compose UI
    ↓
MessagingRepository / EmergencyRepository
    ↓
CommunicationTransport
    ↓
WifiDirectCommunicationTransport
    ↓
Android Wi-Fi Direct (P2P)
    ↓
TCP socket inside the P2P group
    ↓
HopPacket + HopRouter + AES-256-GCM hop encryption
```

`CommunicationTransport` is the stable seam between the UI/repositories and the physical transport. The app currently wires the real Wi-Fi Direct transport through `OffGridRuntime`.

### Identity

Every installation has a stable local OffGrid node ID and editable display name. Android Wi-Fi P2P device addresses are treated as transport/link identifiers only; conversations and routes use stable logical OffGrid IDs.

### Messaging

`MessagingRepository` owns conversation state and sends `Message` objects through `CommunicationTransport`. Incoming messages are canonicalized by the original sender so a relay or changing Android endpoint does not create a second conversation.

### Routing

`HopRouter` maintains direct and learned routes. `HopPacket` carries the message ID, source, destination, hop count, path, and the encrypted payload. Relays deduplicate packets, reject loops, enforce a maximum hop count, and forward only after successful re-encryption and socket write.

### Encryption

Each physical hop uses AES-256-GCM with a fresh 96-bit IV and 128-bit authentication tag. The current prototype derives a deterministic per-link key from the two stable node IDs. This is **hop-by-hop encryption**, not end-to-end encryption: a relay must decrypt the current hop before forwarding it. A production deployment should replace the prototype key derivation with authenticated ECDH and key rotation.

## Wi-Fi Direct transport

`WifiDirectCommunicationTransport.kt` is a separate native transport. The previous Nearby transport remains in the repository and is not deleted or rewritten.

The Wi-Fi transport handles:

- Wi-Fi P2P peer discovery and connection
- system Wi-Fi P2P broadcasts
- stable OffGrid handshake over the P2P socket
- group-owner server and group-client socket
- route announcements and multi-hop relay
- packet deduplication and loop/hop protection
- the existing `HopPacket` and `HopEncryption` formats
- live transport diagnostics
- explicit scan/retry status

The Network screen exposes the live transport status and current Android peer count so a hardware test can distinguish discovery failure from connection/handshake failure.

**There is no fixed range claim.** Wi-Fi Direct can exceed Bluetooth range, but actual range depends on phone hardware, antennas, power settings, interference, walls, orientation, and the environment.

## Permissions

- Android 13+: `NEARBY_WIFI_DEVICES`
- Android 12L and below: `ACCESS_FINE_LOCATION`
- Wi-Fi state/change and network permissions required by Wi-Fi P2P/TCP
- Foreground service permission for the background mesh service
- notification permission on Android 13+

Location Mode must be enabled for the Wi-Fi P2P discovery APIs used by the app.

## Background mesh

`OffGridNetworkService` runs the transport as a foreground service so discovery, receiving, and relay can continue while the UI is backgrounded or the screen is locked. Android still controls battery/background behavior, and a user force-stop or powered-off phone cannot relay messages.

The foreground service notification is intentionally low priority; incoming normal messages and emergency messages use separate notifications.

## Testing

For the real Wi-Fi Direct test, use **two physical Android phones** with Wi-Fi enabled, Location Mode enabled, and the required OffGrid permissions granted. Keep both phones close together for the first connection test.

1. Install the same current debug APK on both phones.
2. Give each phone a different OffGrid display name in Settings.
3. Open both apps and let Mesh Active start.
4. On Home, use **Scan for nearby devices**.
5. Open Network → Live transport and check the discovery status and Android peer count.
6. Select the other device and connect.
7. Send a normal message in both directions.
8. For a relay test, use three phones and verify the relay device is running OffGrid with the mesh service active.

`WifiRangeTest.kt` is an engineering helper for packet-acceptance testing. It does not invent a physical distance or RSSI value.

## Important topology limitation

Android Wi-Fi Direct normally forms a P2P group with a group owner and clients. This makes a local relay topology practical, but it is not an unrestricted phone-to-phone mesh radio. For city-scale or Vellore-to-Pune communication, OffGrid would need a bridge such as an Internet gateway or dedicated long-range radio infrastructure.

## Build

The project targets Java/Kotlin 17, Android SDK 35, and uses Gradle 8.7 with Android Gradle Plugin 8.6.1.

```powershell
.\gradlew assembleDebug
```

The debug APK is produced at:

```text
app\build\outputs\apk\debug\app-debug.apk
```
