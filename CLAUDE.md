# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

ScreenCastDemo is an Android WebRTC screen casting client that implements the NearHub WebCast protocol. It captures the device screen and streams it to a NearHub receiver via P2P WebRTC connection.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Run a single test (if tests exist)
./gradlew test --tests "com.example.screencast.*"

# Clean and rebuild
./gradlew clean assembleDebug

# Run lint
./gradlew lint
```

## Architecture

### Two Signaling Modes

The app operates in one of two modes selected on the HomeScreen:

1. **NearHub Cloud Mode**: Connects to `wss://cast.nearhub.us/?role=sender` for cloud-mediated signaling
2. **Local Direct Mode**: Discovers `_webrtc-signal._tcp.` services via NSD on the LAN, then connects directly to `ws://<host>:<port>`

Both modes use the same `WebRtcManager` but different `SignalingClient` implementations (`NearHubSignalingClient` vs `LocalDirectSignalingClient`). The `SignalingClient` interface abstracts both.

### Two-Phase WebRTC Connection

The app uses a two-phase connection strategy:

1. **Phase 1 - P2P Setup** (`WebRtcManager.connectP2P`): Creates PeerConnection with DataChannel, exchanges offer/answer to establish P2P connection without media. The initial offer has `OfferToReceiveVideo=false` and `OfferToReceiveAudio=false`.
2. **Phase 2 - Screen Capture** (`WebRtcManager.startScreenCapture`): Uses MediaProjection to capture screen, adds video+audio tracks with stream ID `screen-share`, renegotiates to start streaming.

**Critical**: The stream ID `screen-share` is required by the receiver to group tracks into a MediaStream and bind to its video player. Without it, `ontrack.streams` may be empty on the receiver side.

### DataChannel Usage

After P2P is established, the DataChannel (label: `control`) is used for:
- WebRTC signaling relay (offer/answer/ice sent via DataChannel when open, falling back to WebSocket)
- Control messages (leave, etc.)

DataChannel message format: `{ "action": "webrtc-signal"|"leave", "data": { ... } }`

### ICE Configuration

Only STUN servers are configured (Google STUN). No TURN server is configured:
```
stun:stun.l.google.com:19302
stun:stun1.l.google.com:19302
```

### Key Components

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Entry point, permission handling, screen navigation, device discovery orchestration |
| `ScreenCastApp.kt` | Application class |
| `ScreenCaptureService.kt` | Foreground service required for MediaProjection (Android 10+) |
| `webrtc/SignalingClient.kt` | Interface abstracting NearHub/local signaling |
| `webrtc/NearHubSignalingClient.kt` | Cloud signaling via NearHub WebSocket |
| `webrtc/LocalDirectSignalingClient.kt` | LAN direct signaling via discovered NSD service |
| `webrtc/WebRtcManager.kt` | WebRTC connection lifecycle, two-phase setup, DataChannel management |
| `HomeScreen.kt` | Pairing code input UI + device discovery button |
| `SessionScreen.kt` | Connection status, start/stop casting UI |
| `discovery/NsdDiscoveryManager.kt` | mDNS/NSD service discovery (coroutine-based) |
| `discovery/DiscoveredService.kt` | Data class for discovered device info |
| `ui/screens/DeviceDiscoveryBottomSheet.kt` | Modal bottom sheet listing nearby devices |

### Signaling Protocol (NearHub Cloud)

The app connects to `wss://cast.nearhub.us/?role=sender` and exchanges JSON messages over WebSocket:
- `join` / `joined` / `join_failed` for room joining
- `offer` / `answer` / `ice-candidate` for WebRTC negotiation
- `peer_leave` / `room_closed` for session end
- `ping` / `pong` for heartbeat
- `reconnection_token` / `restored` for session persistence

### Dependencies

- `io.getstream:stream-webrtc-android:1.3.9` - WebRTC implementation
- `com.squareup.okhttp3:okhttp:4.12.0` - WebSocket signaling
- Jetpack Compose with Material3 for UI

## Service Discovery (mDNS/NSD)

The app supports AirPlay-like local network device discovery via Android's built-in `NsdManager`. When user taps "Scan for Devices" on HomeScreen:

1. NSD scans for `_webrtc-signal._tcp.` services
2. Resolves each service to get host IP and port
3. Displays devices in a bottom sheet with name and IP
4. User taps a device → prompts for pair code → auto-fills serverUrl and pairCode
5. Proceeds with normal join flow using `LocalDirectSignalingClient`

**Permission required**: `ACCESS_WIFI_STATE`

## Local Signaling Server (Testing)

A local signaling server exists in `signaling-server/` for testing without NearHub:

```bash
cd signaling-server
pip install websockets
python server.py
```

To use: temporarily change the URL in `MainActivity.joinRoom()` from `wss://cast.nearhub.us/` to `ws://<IP>:8080`.

## Build Configuration

- **compileSdk**: 35
- **minSdk**: 24 (Android 7.0)
- **targetSdk**: 35
- **Java**: 11
- **Kotlin**: 1.9.25
- **Compose compiler**: 1.5.15
