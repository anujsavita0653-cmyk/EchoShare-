package com.smartchoice.echoshare.model

/**
 * Room information broadcast over UDP for auto-discovery.
 *
 * Serialized as JSON via Gson and sent as a UDP datagram on the
 * DISCOVERY_PORT. Clients listen for these broadcasts and display
 * available rooms to the user.
 *
 * @param roomName  Human-readable room name chosen by the host
 * @param hostIp    IP address of the host device
 * @param tcpPort   TCP port clients should connect to
 * @param hostName  Device name of the host
 * @param capacity  Maximum number of clients allowed
 * @param current   Number of currently connected clients
 */
data class RoomInfo(
    val roomName: String,
    val hostIp: String,
    val tcpPort: Int,
    val hostName: String,
    val capacity: Int = 10,
    val current: Int = 0
)
