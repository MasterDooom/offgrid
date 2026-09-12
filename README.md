# OffGrid — Milestone 1

An Android Wi-Fi Direct prototype for discovering a nearby OffGrid device, forming a P2P group, and exchanging framed JSON packets over a local TCP socket. No cloud service or internet route is used.

## Run on two phones

1. Open this folder in Android Studio, let it install the Android/Gradle tooling, then deploy the debug build to both phones.
2. Grant the nearby-device permission. On Android 12L and lower, also grant precise location and turn on **Location**; Android requires Location Mode for peer discovery.
3. Tap **Discover peers** on both devices. Tap a peer on one device and accept the system connection prompt if shown.
4. When the state says `Connected`, type a message and tap **Send**. The Wi-Fi Direct group owner listens on TCP port 8988; the client connects to its group-owner address.

## Design boundary

`TransportLayer` isolates mesh code from radios. `WifiDirectTransport` is the only implemented transport. Packets have UUIDs, source/destination IDs, timestamps, TTL, and JSON serialization. `PacketForwarder` is deliberately present but not wired into transport until the two-phone path is verified on actual devices.

## Known MVP constraints

- One Wi-Fi Direct group is normally a star, not a production multi-hop mesh.
- Packets are plaintext during this milestone. Do not use it for sensitive traffic.
- Manufacturer Wi-Fi Direct behavior varies. Use the in-app event log when diagnosing discovery, permission, or negotiation failures.

The permission declarations and runtime flow follow the current Android Wi-Fi Direct documentation: Android 13+ uses `NEARBY_WIFI_DEVICES`; prior releases use `ACCESS_FINE_LOCATION`, and discovery APIs can require Location Mode. [Android Wi-Fi Direct documentation](https://developer.android.com/develop/connectivity/wifi/wifip2p)
