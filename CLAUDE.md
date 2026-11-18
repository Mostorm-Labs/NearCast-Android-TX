# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

NearHub Cast is an Android WebRTC screen casting client that implements the NearHub WebCast protocol. It captures the device screen and streams it to a NearHub receiver via P2P WebRTC connection. The repository remains `NearCast-Android-TX`; retain `com.auditoryworks.nearcast`, its signing identity, and the `nearcast-tx-android` update product when changing visible branding. See `docs/app-name-and-updates.md`.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Run a single test (if tests exist)
./gradlew test --tests "com.auditoryworks.nearcast.*"

# Clean and rebuild
./gradlew clean assembleDebug

# Run lint
./gradlew lint
```

## AAR Patching (Critical Build Detail)

The upstream `io.getstream:stream-webrtc-android:1.3.9` AAR is patched at build time. Two custom Gradle tasks in `app/build.gradle.kts` strip `org.webrtc.audio.WebRtcAudioRecord` and `org.webrtc.audio.JavaAudioDeviceModule` from the AAR's `classes.jar`, then publish the patched AAR to a local Maven repo at `build/local-maven/`. The app then depends on this patched artifact instead of the original.

The app supplies its own versions of those two classes in `app/src/main/java/org/webrtc/audio/`. The patched `WebRtcAudioRecord.java` exposes an `AudioRecordDataCallback` hook that `WebRtcManager` uses to inject system audio PCM directly into the WebRTC audio pipeline.

This patching runs automatically as part of `preBuild` — no manual step needed.

## Architecture

### Signaling and Receiver Discovery

The production HomeScreen supports two explicit pairing routes:

1. **NearHub Cloud Mode**: Connects to `wss://cast.nearhub.us/?role=sender` for cloud-mediated signaling
2. **Local WebCast**: Browse `_nearcast._tcp.` through Android NSD, physically confirm the receiver SPKI pin, request its local code, then choose the local receiver and enter that code. `LocalReceiverConnection` opens pinned WSS to native A/AAAA/SRV routes and reuses the NearHub room/SDP/ICE envelope. It never falls back to cloud. TXT never supplies a destination URL.

`LocalDirectSignalingClient` remains debug-only and uses a different flat protocol. Production local WebCast uses `NearHubSignalingClient` with an isolated, pinned `LocalReceiverConnection`. See `docs/device-discovery.md`.

### Two-Phase WebRTC Connection

The app uses a two-phase connection strategy:

1. **Phase 1 - P2P Setup** (`WebRtcManager.connectP2P`): Creates PeerConnection with DataChannel, exchanges offer/answer to establish P2P connection without media. The initial offer has `OfferToReceiveVideo=false` and `OfferToReceiveAudio=false`.
2. **Phase 2 - Screen Capture** (`WebRtcManager.startScreenCapture`): Uses MediaProjection to capture screen, adds video+audio tracks with stream ID `screen-share`, renegotiates to start streaming.
3. **Stop Casting** (`WebRtcManager.stopScreenCapture`): Removes all senders, disposes tracks, then calls `createAndSendOffer()` again to renegotiate the connection back to a media-free state.

**Critical**: The stream ID `screen-share` is required by the receiver to group tracks into a MediaStream and bind to its video player. Without it, `ontrack.streams` may be empty on the receiver side.

### System Audio Capture

`SystemAudioCapture` uses `AudioPlaybackCaptureConfiguration` (Android 10+) to capture device playback audio into a PCM queue. `WebRtcManager` wires this into the `AudioRecordDataCallback` on the patched `JavaAudioDeviceModule`: each time WebRTC requests an audio frame, the callback drains a chunk from the queue and injects it into the WebRTC audio buffer. If the queue is empty, the mic input passes through unchanged (fallback).

During casting, local music volume is muted via `AudioManager` and restored when casting stops.

### DataChannel Usage

After P2P is established, the DataChannel (label: `control`) is used for:
- WebRTC signaling relay (offer/answer/ice sent via DataChannel when open, falling back to WebSocket)
- Control messages (leave, etc.)

DataChannel message format (NearHub Part 2 protocol): `{ "action": "webrtc-signal", "data": { "type": "offer"|"answer"|"ice-candidate", "payload": { ... } } }`. After casting starts, offer/answer/ice are sent via DataChannel first, falling back to WebSocket. The `leave` action is also sent via DataChannel.

### ICE Configuration

Only STUN servers are configured (Google STUN). No TURN server is configured:
```
stun:stun.l.google.com:19302
stun:stun1.l.google.com:19302
```

### Permission Chain

Starting a cast requires three permissions requested in sequence:
1. `POST_NOTIFICATIONS` (Android 13+) — best-effort, cast proceeds regardless
2. `RECORD_AUDIO` — required; cast is blocked if denied
3. `MediaProjection` consent intent — required for screen capture

### Key Components

| File | Purpose |
|------|---------|
| `MainActivity.kt` | Entry point, permission chain, screen navigation, device discovery orchestration |
| `ScreenCastApp.kt` | Application class |
| `ScreenCaptureService.kt` | Foreground service required for MediaProjection (Android 10+) |
| `webrtc/SignalingClient.kt` | Interface abstracting NearHub/local signaling |
| `webrtc/NearHubSignalingClient.kt` | Shared cloud/local WebCast room signaling; defines `NearHubEvent` and fences retired socket callbacks |
| `webrtc/LocalDirectSignalingClient.kt` | Debug/local fixture signaling, independent of production discovery |
| `webrtc/WebRtcManager.kt` | WebRTC connection lifecycle, two-phase setup, DataChannel management, audio injection |
| `webrtc/SystemAudioCapture.kt` | Captures Android playback audio via `AudioPlaybackCaptureConfiguration`, feeds PCM into WebRTC |
| `HomeScreen.kt` | Pairing code input UI + device discovery button |
| `SessionScreen.kt` | Connection status, start/stop casting UI |
| `discovery/NsdDiscoveryManager.kt` | mDNS/NSD service discovery (coroutine-based) |
| `ui/screens/DeviceDiscoveryBottomSheet.kt` | Modal bottom sheet listing nearby devices |
| `app/src/main/java/org/webrtc/audio/` | Patched WebRTC audio classes that expose `AudioRecordDataCallback` |

### Signaling Protocol (NearHub Cloud / Local WebCast)

The app connects to `wss://cast.nearhub.us/?role=sender` and exchanges JSON messages over WebSocket:
- `join` / `joined` / `join_failed` for room joining
- `offer` / `answer` / `ice-candidate` for WebRTC negotiation
- `peer_leave` / `room_closed` for session end
- `ping` / `pong` for heartbeat
- `reconnection_token` / `restored` for session persistence

`LocalDirectSignalingClient` uses a simpler flat message format (no `roomId`/`userId` wrapping). It treats `state=Ready`, `joined`, or an incoming `offer` as the trigger to emit `NearHubEvent.Joined` and start Phase 1.

### Dependencies

- `com.example.patched:stream-webrtc-android-patched` — patched AAR (built locally from `io.getstream:stream-webrtc-android:1.3.9`)
- `com.squareup.okhttp3:okhttp:4.12.0` - WebSocket signaling
- Jetpack Compose with Material3 for UI

## Service Discovery (mDNS/NSD)

When the user opens "Find receivers" on HomeScreen:

1. NSD browses only `_nearcast._tcp.` and resolves native A/AAAA addresses and SRV port.
2. A foreground bottom sheet shows compatible receivers; stop/background/loss fences stale callbacks.
3. First selection observes TLS SPKI without HTTP, then requests `/nearcast/trust-display` pinned to that observed key.
4. The user compares the full fingerprint on the receiver and explicitly saves trust. `/nearcast/code-display` requires that saved key; rotation is blocked until a new physical comparison.
5. Display responses contain status only; pairing codes are entered manually. When advertised, `signaling-select-v1` / `code-display-v2` explicitly requests the local backend and verifies receiver/runtime/backend in the response. Older receivers require the user to confirm the code is local.
6. A selected local target keeps foreground discovery active until joining. WSS uses a fresh TLS context, exact SPKI pin, native network socket factory, and no proxy/redirect/cookies. Removing trust invalidates active local sockets. Cloud TLS stays independent.
7. `SenderAnnouncements` sends peer-scoped `nearcast-source` (android/mobile/nearhub-cast) and `nearcast-share-state`. Only visible, calibrated full-screen capture claims `monitor`; `SenderConsent` responds to RX requests using current local permission and binding state. Identity metadata never grants permission by itself.

**Permissions**: Existing `INTERNET`/`ACCESS_WIFI_STATE` plus normal `CHANGE_WIFI_MULTICAST_STATE`; multicast reception is held only during foreground discovery. Current target SDK remains 35. Review Android local network runtime permission requirements when raising target SDK to 37+.

## Local Signaling Server (Testing)

A local signaling server exists in `signaling-server/` for testing without NearHub:

```bash
cd signaling-server
pip install websockets
python server.py
```

`signaling-server/viewer.html` is a browser-based WebRTC receiver for end-to-end testing without a NearHub device.

To use: temporarily change the URL in `MainActivity.joinRoom()` from `wss://cast.nearhub.us/` to `ws://<IP>:8080`.

## Build Configuration

- **compileSdk**: 35
- **minSdk**: 24 (Android 7.0)
- **targetSdk**: 35
- **Java**: 11
- **Kotlin**: 1.9.25
- **Compose compiler**: 1.5.15
