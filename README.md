# OFFGRID — MVP prototype

Offline-first messaging with a nearby-device discovery layer and an Emergency SOS layer.

Flow: **Discover -> Select device -> Chat -> Send/Receive -> Emergency SOS**.

## Architecture

```
ui/            Compose screens: home, profile, chat, emergency, settings
data/model/    Node, Message, Conversation, SOSAlert, NetworkStatus
data/repository/  IdentityManager, MessagingRepository, EmergencyRepository
data/transport/   CommunicationTransport (interface), MockCommunicationTransport (impl)
legacy/wifidirect/  Milestone-1 Wi-Fi Direct + TCP transport, kept as a reference for real hardware
```

`CommunicationTransport` is the only thing the UI/repositories know about:

```kotlin
interface CommunicationTransport {
    val discoveredNodes: StateFlow<List<Node>>
    val linkState: StateFlow<LinkState>
    val incomingMessages: Flow<Message>
    suspend fun start(selfId: String, selfName: String)
    suspend fun discoverDevices()
    suspend fun connectToDevice(node: Node): Result<Unit>
    suspend fun sendMessage(message: Message): Result<Unit>
    fun stop()
}
```

`MockCommunicationTransport` implements it today with a real TCP socket bridged between two
emulator instances via `adb forward` (see below) -- it genuinely sends bytes between two running
app processes, not a local-only fake. A few extra `Node`s are marked `isSimulated = true` purely
to populate the Nearby list on a single emulator; messaging them gets a canned local reply,
clearly not network traffic.

To move to real hardware later, implement `MeshtasticCommunicationTransport` against a Meshtastic
radio (BLE/serial) -- no other file changes.

The previous Wi-Fi Direct + TCP implementation (`legacy/wifidirect/`) is preserved as a reference:
it's real Android Wi-Fi Direct code, but Wi-Fi Direct isn't available on emulators, which is why
it isn't wired into the app by default anymore. `docs/MVP_DECISIONS.md` still documents that
milestone's reasoning; it stays relevant background for the real-device follow-up.

## Run on one emulator

Open the project in Android Studio, run on any API 26+ emulator. You'll see your node ID, a
simulated Nearby list (Aarav, Rahul, Emergency Relay), recent conversations, and the Emergency
Mode entry point. Messaging a simulated node gets a canned reply so the chat UI is exercised
end-to-end even solo.

## Run the real two-device demo

1. Launch **two** emulator instances (Run configuration -> pick a second AVD, or launch a second
   instance from Device Manager).
2. Find each emulator's adb serial: `adb devices`.
3. Forward each instance's listen port from the host so the other instance can reach it:
   ```
   adb -s <serial-of-device-A> forward tcp:8990 tcp:8990
   adb -s <serial-of-device-B> forward tcp:8991 tcp:8991
   ```
4. In the app on Device A: tap the gear icon (top right) -> **This is Device A** -> Apply.
   (Defaults to listen 8990 / peer 10.0.2.2:8991 already, so this just confirms it.)
5. On Device B: gear -> **This is Device B** -> Apply (listen 8991 / peer 10.0.2.2:8990).
6. On Device A, tap the **Linked Device** entry under Nearby -> Message. This dials the socket
   and performs a small handshake, after which both devices show each other's real node ID/name.
7. Send "Are you there?" from A -> appears in B's chat in real time. Reply from B -> appears on A.
8. Go back to Home on either device, tap **Emergency Mode**, review the explainer text, then
   **ACTIVATE SOS**. If the link is connected, the other device actually receives the SOS as an
   emergency message; nodes-reached/helpers-found counters escalate to reflect the full roster
   (real + simulated) for a visible demo. **CANCEL SOS** stops it.

If `adb forward` isn't available (e.g. restricted environment), you can also run one instance in
the emulator and a second real device on the same Wi-Fi network by setting peer host to that
device's LAN IP address in the Demo Setup screen instead of `10.0.2.2`.

## What remains for real Meshtastic/BLE/mesh hardware

- Implement `MeshtasticCommunicationTransport : CommunicationTransport` against a Meshtastic
  radio (BLE GATT or USB-serial), following Meshtastic's Android app patterns.
- Multi-hop relay/TTL/dedup: `legacy/wifidirect/PacketForwarder.kt` already sketches the
  dedup/TTL/route-avoidance logic that would need porting onto the new `Message` model.
- Real permission/runtime handling for BLE (`BLUETOOTH_SCAN`/`CONNECT`/`ADVERTISE` on Android 12+)
  the way `legacy/wifidirect/WifiDirectTransport.kt` handles `NEARBY_WIFI_DEVICES`.
- Persistent message history (currently in-memory for the app session only).
- Location-based SOS payloads, store-and-forward queuing, and priority-based delivery -- the
  model layer (`SOSAlert.location`, `Message.type`) already leaves room for these.
