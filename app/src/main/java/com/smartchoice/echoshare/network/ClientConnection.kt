package com.smartchoice.echoshare.network

import android.util.Log
import com.google.gson.Gson
import com.smartchoice.echoshare.model.ControlMessage
import com.smartchoice.echoshare.model.MessageType
import com.smartchoice.echoshare.utils.NetworkUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Manages the client-side TCP connection to the host.
 *
 * Provides:
 *  - Auto-reconnect with exponential back-off.
 *  - [messages] SharedFlow of [ControlMessage] received from the host.
 *  - [connectionState] StateFlow of [ConnectionState].
 *  - [send] for outgoing control messages.
 */
class ClientConnection(
    private val hostIp: String,
    private val deviceName: String,
    private val scope: CoroutineScope,
    private val gson: Gson
) {
    companion object {
        private const val TAG = "ClientConnection"
        private const val MAX_RETRY_DELAY_MS = 8000L
        private const val INITIAL_RETRY_DELAY_MS = 1000L
    }

    // ── Public observables ────────────────────────────────────────────────────

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState = _connectionState.asStateFlow()

    private val _messages = MutableSharedFlow<ControlMessage>(extraBufferCapacity = 64)
    val messages = _messages.asSharedFlow()

    // ── Internal ──────────────────────────────────────────────────────────────

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private var reader: BufferedReader? = null
    private var connectJob: Job? = null

    @Volatile private var shouldReconnect = true

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Connect and start the read loop with auto-reconnect */
    fun connect() {
        shouldReconnect = true
        connectJob = scope.launch(Dispatchers.IO) {
            var retryDelay = INITIAL_RETRY_DELAY_MS
            while (isActive && shouldReconnect) {
                _connectionState.value = ConnectionState.CONNECTING
                try {
                    openSocket()
                    retryDelay = INITIAL_RETRY_DELAY_MS // reset on success
                    readLoop()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Connection error: ${e.message}")
                }
                if (shouldReconnect && isActive) {
                    _connectionState.value = ConnectionState.RECONNECTING
                    Log.i(TAG, "Reconnecting in ${retryDelay}ms…")
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
                }
            }
            _connectionState.value = ConnectionState.DISCONNECTED
        }
    }

    /** Gracefully disconnect (no reconnect) */
    fun disconnect() {
        shouldReconnect = false
        send(ControlMessage(type = MessageType.DISCONNECT))
        closeSocket()
        connectJob?.cancel()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun openSocket() {
        val sock = Socket()
        sock.soTimeout = NetworkUtils.SOCKET_TIMEOUT_MS
        sock.connect(
            java.net.InetSocketAddress(hostIp, NetworkUtils.CONTROL_PORT),
            NetworkUtils.SOCKET_TIMEOUT_MS
        )
        socket = sock
        writer = PrintWriter(BufferedWriter(OutputStreamWriter(sock.getOutputStream())), true)
        reader = BufferedReader(InputStreamReader(sock.getInputStream()))

        // Handshake
        send(ControlMessage(type = MessageType.HELLO, deviceName = deviceName))
        _connectionState.value = ConnectionState.CONNECTED
        Log.i(TAG, "Connected to $hostIp:${NetworkUtils.CONTROL_PORT}")
    }

    private fun readLoop() {
        val r = reader ?: return
        try {
            var line: String?
            while (true) {
                line = r.readLine() ?: break
                val msg = gson.fromJson(line, ControlMessage::class.java)
                scope.launch { _messages.emit(msg) }
                // Handle kick
                if (msg.type == MessageType.KICK) {
                    shouldReconnect = false
                    break
                }
            }
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "Socket timeout — server may be unreachable")
        } catch (e: IOException) {
            Log.w(TAG, "Read error: ${e.message}")
        } finally {
            closeSocket()
        }
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
        writer = null
        reader = null
    }

    /** Thread-safe write of one JSON control line */
    @Synchronized
    fun send(msg: ControlMessage) {
        try {
            writer?.println(gson.toJson(msg))
        } catch (e: Exception) {
            Log.w(TAG, "Send error: ${e.message}")
        }
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    ERROR
}
