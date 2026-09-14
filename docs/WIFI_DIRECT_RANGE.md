# Wi-Fi P2P Range Experiment

## Purpose

This branch adds a native Android Wi-Fi P2P transport as a separate transport option for OFFGRID. The goal is to investigate the **maximum reliable direct A → B range** on real phone hardware.

This is different from mesh range extension:

```text
Direct range:     A ───────────── B
Mesh extension:   A ─── B ─── C ─── D
```

The existing Nearby transport remains the application default. `WifiDirectCommunicationTransport` is not wired into `MainActivity`, so the existing app flow is unchanged by this branch until the new transport is explicitly integrated/selected.

## Architecture

```text
MessagingRepository
        |
CommunicationTransport
        |
  +-----+----------------+
  |                      |
Nearby transport   Wi-Fi P2P transport
                         |
                   Wi-Fi P2P socket
                         |
                     HopPacket
                         |
                    HopRouter
                         |
                   HopEncryption
```

The transport is deliberately separated from routing and security:

- `CommunicationTransport` defines the transport contract.
- `WifiDirectCommunicationTransport` discovers peers, forms a Wi-Fi P2P group, opens sockets, and moves bytes.
- `HopRouter` still chooses the logical next hop.
- `HopPacket` keeps the existing routing envelope.
- `HopEncryption` keeps the existing AES-256-GCM hop encryption.

## Direct-link test

The `WifiRangeTest` utility can send repeated system messages over the Wi-Fi P2P transport. It reports transport-level accepted/failed writes and elapsed time.

For a real range experiment, use two physical Android devices and record the receiver's delivered packet count at each measured distance. Do not treat a successful local socket write as proof that the packet reached the other phone.

Suggested test points:

```text
10 m → 20 m → 30 m → 50 m → 75 m → 100 m → ...
```

At every point record:

- packets transmitted
- packets delivered
- delivery percentage
- latency where measurable
- disconnects
- environmental conditions

There is intentionally no hard-coded range claim. Phone radio hardware, antenna design, interference, obstacles, and regulatory limits determine the real result.

## Android permissions

The manifest already contains the Wi-Fi state/change and Wi-Fi-nearby permissions used by the project. Android 12 and below use location permission for discovery; Android 13+ uses `NEARBY_WIFI_DEVICES`. The manifest compatibility declaration was adjusted so Android 12 can receive the location permission already requested by the activity.

## Current status

This is an isolated engineering transport. It has **not** been connected to the existing `MainActivity` or replaced the existing Nearby transport. Real-device range and end-to-end multi-device behavior must be validated on physical phones before treating the transport as production-ready.
