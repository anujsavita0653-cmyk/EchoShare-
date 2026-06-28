package com.smartchoice.echoshare.network

import android.util.Log
import com.google.gson.Gson
import com.smartchoice.echoshare.model.RoomInfo
import com.smartchoice.echoshare.utils.NetworkUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Handles UDP-based room discovery.
 *
 * HOST MODE: Periodically broadcasts [RoomInfo] to the subnet broadcast
 * address so that clients can discover the room without manual IP entry.
 *
 * CLIENT MODE: Listens for incoming broadcasts and exposes discovered
 * rooms via [discoveredRooms] StateFlow.
 */
class DiscoveryService(private val gson: Gson) {

    companion object {
        private const val TAG = "DiscoveryService"
        private const val BUFFER_SIZE = 2048
    }

    // ── Discovered rooms (client side) ────────────────────────────────────────
    private val _discoveredRooms = MutableStateFlow<List<RoomInfo>>(emptyList())
    val discoveredRooms = _discoveredRooms.asStateFlow()

    // ── Internal state ────────────────────────────────────────────────────────
    private var broadcastJob: Job? = null
    private var listenJob: Job? = null
    private var broadcastSocket: DatagramSocket? = null
    private var listenSocket: DatagramSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Map of hostIp → (RoomInfo, lastSeen timestamp)
    private val roomCache = mutableMapOf<String, Pair<RoomInfo, Long>>()

    // ── Host: broadcast presence ──────────────────────────────────────────────

    /**
     * Start broadcasting [roomInfo] every [NetworkUtils.DISCOVERY_INTERVAL_MS].
     *
     * @param broadcastAddress  Subnet broadcast address (e.g. "192.168.1.255")
     */
    fun startBroadcasting(roomInfo: RoomInfo, broadcastAddress: String) {
        stopBroadcasting()
        broadcastSocket = DatagramSocket().apply { broadcast = true }
        broadcastJob = scope.launch {
            val json = gson.toJson(roomInfo).toByteArray()
            val address = InetAddress.getByName(broadcastAddress)
            Log.i(TAG, "Broadcasting room '${roomInfo.roomName}' to $broadcastAddress")
            while (isActive) {
                try {
                    val packet = DatagramPacket(json, json.size, address, NetworkUtils.DISCOVERY_PORT)
                    broadcastSocket?.send(packet)
                } catch (e: Exception) {
                    Log.w(TAG, "Broadcast error: ${e.message}")
                }
                delay(NetworkUtils.DISCOVERY_INTERVAL_MS)
            }
        }
    }

    fun stopBroadcasting() {
        broadcastJob?.cancel()
        runCatching { broadcastSocket?.close() }
        broadcastSocket = null
    }

    // ── Client: listen for broadcasts ─────────────────────────────────────────

    /** Start listening for room discovery broadcasts */
    fun startListening() {
        stopListening()
        _discoveredRooms.value = emptyList()
        roomCache.clear()

        listenSocket = DatagramSocket(NetworkUtils.DISCOVERY_PORT).apply {
            reuseAddress = true
            soTimeout = 0
        }

        // Periodic cleanup of stale rooms (not seen for > 8 s)
        listenJob = scope.launch {
            launch {
                val buf = ByteArray(BUFFER_SIZE)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        listenSocket?.receive(packet)
                        val json = String(packet.data, 0, packet.length)
                        val room = gson.fromJson(json, RoomInfo::class.java)
                        val now = System.currentTimeMillis()
                        roomCache[room.hostIp] = Pair(room, now)
                        publishRooms()
                        Log.d(TAG, "Discovered room '${room.roomName}' at ${room.hostIp}")
                    } catch (e: Exception) {
                        if (isActive) Log.w(TAG, "Listen error: ${e.message}")
                    }
                }
            }
            // Cleanup loop
            while (isActive) {
                delay(3000)
                val cutoff = System.currentTimeMillis() - 8000
                roomCache.entries.removeIf { it.value.second < cutoff }
                publishRooms()
            }
        }
    }

    fun stopListening() {
        listenJob?.cancel()
        runCatching { listenSocket?.close() }
        listenSocket = null
        _discoveredRooms.value = emptyList()
        roomCache.clear()
    }

    private fun publishRooms() {
        _discoveredRooms.value = roomCache.values.map { it.first }
    }

    fun destroy() {
        stopBroadcasting()
        stopListening()
        scope.cancel()
    }
}
