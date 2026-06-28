package com.smartchoice.echoshare.model

/**
 * Control messages exchanged between Host and Clients over the TCP
 * control channel. Serialized as JSON (one JSON object per line).
 *
 * Direction is indicated in the comments below.
 */
data class ControlMessage(
    val type: MessageType,
    /** Payload fields — only the relevant fields are populated per message type */
    val roomName: String? = null,
    val deviceName: String? = null,
    val positionMs: Long? = null,
    val totalDurationMs: Long? = null,
    val trackName: String? = null,
    val volume: Float? = null,
    val timestamp: Long? = null,   // Host system clock when message was sent
    val errorMsg: String? = null,
    val pingId: Long? = null       // Used for latency calculation
)

enum class MessageType {
    // ─── Client → Host ───────────────────────────────────────────────
    HELLO,           // Initial handshake: deviceName
    PING,            // Latency probe: pingId + timestamp
    DISCONNECT,      // Client is leaving gracefully

    // ─── Host → Client ───────────────────────────────────────────────
    WELCOME,         // Accepted: roomName, trackName, positionMs, totalDurationMs, volume
    PLAY,            // Start / resume playback: positionMs + timestamp (for sync)
    PAUSE,           // Pause playback: positionMs
    STOP,            // Stop and reset
    SEEK,            // Jump to position: positionMs + timestamp
    TRACK_INFO,      // New track loaded: trackName, totalDurationMs
    VOLUME,          // Volume change: volume (0.0–1.0)
    PONG,            // Latency response: pingId + timestamp
    SYNC,            // Periodic sync heartbeat: positionMs + timestamp
    ERROR,           // Error from host: errorMsg
    KICK             // Host disconnecting this client
}
