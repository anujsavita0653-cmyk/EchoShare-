package com.smartchoice.echoshare.model

/**
 * Represents a connected client device visible to the Host.
 *
 * @param id       Unique identifier (IP:port)
 * @param name     Human-readable device name sent during handshake
 * @param ip       Client IP address
 * @param port     Client TCP port
 * @param status   Current connection state
 * @param latencyMs Last measured round-trip latency in milliseconds
 */
data class DeviceInfo(
    val id: String,
    val name: String,
    val ip: String,
    val port: Int,
    var status: DeviceStatus = DeviceStatus.CONNECTED,
    var latencyMs: Long = 0L
)

enum class DeviceStatus {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}
