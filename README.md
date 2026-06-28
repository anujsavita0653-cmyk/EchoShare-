# EchoShare

> Stream the same audio in real time to multiple Android phones over local Wi-Fi or Hotspot.

## Quick Start

1. Open the project in **Android Studio Ladybug** (2024.2) or newer.
2. Android Studio will sync Gradle automatically. If it asks to download Gradle, accept.
3. Run on a physical device (API 29+). Emulators don't support multi-device Wi-Fi networking.

## Architecture

```
com.smartchoice.echoshare/
├── MainActivity.kt            — Entry: choose Host or Join
├── host/
│   ├── HostActivity.kt        — Room creation, file picker, playback controls
│   └── HostViewModel.kt       — Binds AudioStreamService, exposes UI state
├── client/
│   ├── ClientActivity.kt      — Room discovery, sync status, local volume
│   └── ClientViewModel.kt     — Binds AudioPlaybackService, exposes UI state
├── service/
│   ├── AudioStreamService.kt  — HOST foreground service: TCP server + ExoPlayer + broadcast
│   └── AudioPlaybackService.kt— CLIENT foreground service: TCP client + sync + playback
├── network/
│   ├── HostServer.kt          — TCP control server (accepts clients, sends PLAY/PAUSE/SEEK…)
│   ├── ClientSession.kt       — Per-client TCP read/write session
│   ├── AudioStreamer.kt       — TCP audio data server (streams raw bytes to all clients)
│   ├── AudioReceiver.kt       — Client-side audio data receiver (feeds ExoPlayer via pipe)
│   ├── ClientConnection.kt    — Client-side control TCP connection with auto-reconnect
│   └── DiscoveryService.kt    — UDP broadcast (host) / listener (client) for auto-discovery
├── adapter/
│   ├── DevicesAdapter.kt      — RecyclerView: connected clients on Host screen
│   └── RoomsAdapter.kt        — RecyclerView: discovered rooms on Client screen
├── model/
│   ├── ControlMessage.kt      — JSON control protocol (PLAY, PAUSE, SEEK, SYNC…)
│   ├── DeviceInfo.kt          — Connected client descriptor
│   └── RoomInfo.kt            — UDP room discovery payload
└── utils/
    ├── NetworkUtils.kt        — IP helpers, port constants, broadcast address
    ├── PermissionUtils.kt     — Runtime permission helpers
    └── Extensions.kt         — Kotlin extension functions
```

## Network Protocol

| Port  | Protocol | Purpose                        |
|-------|----------|--------------------------------|
| 45678 | UDP      | Room discovery broadcasts      |
| 45679 | TCP      | Control messages (JSON lines)  |
| 45680 | TCP      | Raw audio data streaming       |

### Control Messages (JSON, one per line)

| Type        | Direction      | Key Fields                         |
|-------------|----------------|------------------------------------|
| HELLO       | Client → Host  | deviceName                         |
| WELCOME     | Host → Client  | roomName, trackName, positionMs    |
| PLAY        | Host → Client  | positionMs, timestamp              |
| PAUSE       | Host → Client  | positionMs                         |
| STOP        | Host → Client  | —                                  |
| SEEK        | Host → Client  | positionMs, timestamp              |
| SYNC        | Host → Client  | positionMs, timestamp (heartbeat)  |
| VOLUME      | Host → Client  | volume (0.0–1.0)                   |
| PING / PONG | Both           | pingId, timestamp (latency probe)  |
| DISCONNECT  | Client → Host  | —                                  |
| KICK        | Host → Client  | —                                  |

### Sync Strategy

- **PLAY**: Client seeks to `positionMs + networkLatency/2` before starting, compensating for one-way transit time.
- **SYNC** (every 5 s): If client drift > 500 ms, it silently seeks to the corrected position.
- **SEEK**: Client applies same latency compensation.

## Building a Release APK

1. In Android Studio: **Build → Generate Signed App Bundle / APK**
2. Choose **APK**, create or select your keystore.
3. Select **release** build variant.
4. The signed APK will appear in `app/release/`.

> **ProGuard** is enabled in release builds and configured in `app/proguard-rules.pro`.

## Permissions

| Permission                  | Purpose                              |
|-----------------------------|--------------------------------------|
| INTERNET                    | TCP/UDP sockets                      |
| ACCESS_WIFI_STATE           | Read IP / broadcast address          |
| CHANGE_WIFI_MULTICAST_STATE | UDP multicast (discovery)            |
| READ_EXTERNAL_STORAGE       | Pick MP3 files (Android 10–12)       |
| READ_MEDIA_AUDIO            | Pick MP3 files (Android 13+)         |
| FOREGROUND_SERVICE          | Background streaming / playback      |
| POST_NOTIFICATIONS          | Foreground service notification      |
| WAKE_LOCK                   | Keep CPU active during streaming     |

## Requirements

- **Min SDK**: 29 (Android 10)
- **Target SDK**: 35 (Android 15)
- **Language**: Kotlin 2.0
- **Build Tools**: AGP 8.5, Gradle 8.7
- All phones must be on the **same Wi-Fi network or hotspot**.
