package com.smartchoice.echoshare.network

import android.util.Log
import com.google.gson.Gson
import com.smartchoice.echoshare.model.ControlMessage
import com.smartchoice.echoshare.model.DeviceInfo
import com.smartchoice.echoshare.model.DeviceStatus
import com.smartchoice.echoshare.model.MessageType
import com.smartchoice.echoshare.utils.NetworkUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * TCP server that accepts client connections on [NetworkUtils.CONTROL_PORT].
 *
 * Each client gets its own [ClientSession] that handles reading/writing
 * control messages on a background coroutine.  Audio data is streamed
 * separately from [AudioStreamer].
 */
class HostServer(
    private val scope: CoroutineScope,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "HostServer"
    }

    // ── State ────────────────────────────────────────────────────────────────

    private val _clients = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val clients = _clients.asStateFlow()

    private val _events = MutableSharedFlow<ServerEvent>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    // Map of client id (ip:port) → session
    private val sessions = ConcurrentHashMap<String, ClientSession>()

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Start listening for incoming TCP connections */
    fun start() {
        serverSocket = ServerSocket(NetworkUtils.CONTROL_PORT)
        acceptJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Listening on port ${NetworkUtils.CONTROL_PORT}")
            while (isActive) {
                try {
                    val socket = serverSocket!!.accept()
                    handleNewClient(socket)
                } catch (e: IOException) {
                    if (isActive) Log.w(TAG, "Accept error: ${e.message}")
                }
            }
        }
    }

    /** Stop the server and disconnect all clients */
    fun stop() {
        acceptJob?.cancel()
        sessions.values.forEach { it.close() }
        sessions.clear()
        runCatching { serverSocket?.close() }
        _clients.value = emptyList()
        Log.i(TAG, "Server stopped")
    }

    // ── Client management ────────────────────────────────────────────────────

    private fun handleNewClient(socket: Socket) {
        if (sessions.size >= NetworkUtils.MAX_CLIENTS) {
            Log.w(TAG, "Max clients reached, rejecting ${socket.remoteSocketAddress}")
            socket.close()
            return
        }

        val session = ClientSession(socket, gson, scope,
            onMessage = { id, msg -> onClientMessage(id, msg) },
            onDisconnect = { id -> removeClient(id) }
        )
        sessions[session.id] = session
        session.start()
    }

    private fun onClientMessage(id: String, msg: ControlMessage) {
        when (msg.type) {
            MessageType.HELLO -> {
                val session = sessions[id] ?: return
                val device = DeviceInfo(
                    id = id,
                    name = msg.deviceName ?: "Unknown",
                    ip = session.ip,
                    port = session.port,
                    status = DeviceStatus.CONNECTED
                )
                sessions[id]?.deviceInfo = device
                updateClientList()
                scope.launch { _events.emit(ServerEvent.ClientConnected(device)) }
                Log.i(TAG, "Client connected: ${device.name} @ ${device.ip}")
            }
            MessageType.PING -> {
                val pong = ControlMessage(
                    type = MessageType.PONG,
                    pingId = msg.pingId,
                    timestamp = System.currentTimeMillis()
                )
                sessions[id]?.send(pong)
            }
            MessageType.DISCONNECT -> removeClient(id)
            else -> { /* other messages from clients ignored for now */ }
        }
    }

    private fun removeClient(id: String) {
        val session = sessions.remove(id) ?: return
        session.close()
        val device = session.deviceInfo
        if (device != null) {
            scope.launch { _events.emit(ServerEvent.ClientDisconnected(device)) }
            Log.i(TAG, "Client disconnected: ${device.name}")
        }
        updateClientList()
    }

    private fun updateClientList() {
        _clients.value = sessions.values.mapNotNull { it.deviceInfo }
    }

    // ── Broadcast control messages ────────────────────────────────────────────

    /** Send a control message to all connected clients */
    fun broadcast(msg: ControlMessage) {
        sessions.values.forEach { it.send(msg) }
    }

    /** Send a control message to a specific client */
    fun send(clientId: String, msg: ControlMessage) {
        sessions[clientId]?.send(msg)
    }

    /** Kick (disconnect) a specific client */
    fun kickClient(clientId: String) {
        sessions[clientId]?.send(ControlMessage(type = MessageType.KICK))
        removeClient(clientId)
    }

    /** Current number of connected clients */
    val clientCount: Int get() = sessions.size
}

// ─── ServerEvent ─────────────────────────────────────────────────────────────

sealed class ServerEvent {
    data class ClientConnected(val device: DeviceInfo) : ServerEvent()
    data class ClientDisconnected(val device: DeviceInfo) : ServerEvent()
    data class MessageReceived(val clientId: String, val message: ControlMessage) : ServerEvent()
}

// ─── ClientSession ───────────────────────────────────────────────────────────

/**
 * Manages a single TCP connection to a remote client.
 * Runs a read loop on a background coroutine; sends are thread-safe.
 */
class ClientSession(
    private val socket: Socket,
    private val gson: Gson,
    private val scope: CoroutineScope,
    private val onMessage: (String, ControlMessage) -> Unit,
    private val onDisconnect: (String) -> Unit
) {
    val ip: String = socket.inetAddress.hostAddress ?: "0.0.0.0"
    val port: Int = socket.port
    val id: String = "$ip:$port"

    var deviceInfo: DeviceInfo? = null

    private val writer: PrintWriter
    private val reader: BufferedReader
    private var readJob: Job? = null

    @Volatile private var closed = false

    init {
        socket.soTimeout = 0   // no timeout on server side — keep connection alive
        writer = PrintWriter(BufferedWriter(OutputStreamWriter(socket.getOutputStream())), true)
        reader = BufferedReader(InputStreamReader(socket.getInputStream()))
    }

    fun start() {
        readJob = scope.launch(Dispatchers.IO) {
            try {
                var line: String?
                while (isActive && !closed) {
                    line = reader.readLine() ?: break
                    val msg = gson.fromJson(line, ControlMessage::class.java)
                    onMessage(id, msg)
                }
            } catch (e: Exception) {
                if (!closed) Log.w("ClientSession", "Read error for $id: ${e.message}")
            } finally {
                if (!closed) onDisconnect(id)
            }
        }
    }

    /** Thread-safe send — writes one JSON line */
    @Synchronized
    fun send(msg: ControlMessage) {
        if (closed) return
        try {
            writer.println(gson.toJson(msg))
        } catch (e: Exception) {
            Log.w("ClientSession", "Write error for $id: ${e.message}")
        }
    }

    fun close() {
        closed = true
        readJob?.cancel()
        runCatching { socket.close() }
    }
}
