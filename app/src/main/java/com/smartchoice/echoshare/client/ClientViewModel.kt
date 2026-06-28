package com.smartchoice.echoshare.client

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartchoice.echoshare.model.RoomInfo
import com.smartchoice.echoshare.network.ConnectionState
import com.smartchoice.echoshare.network.DiscoveryService
import com.smartchoice.echoshare.service.AudioPlaybackService
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * ViewModel for [ClientActivity].
 *
 * Manages room discovery (via [DiscoveryService]) and binding to
 * [AudioPlaybackService] for playback state.
 */
class ClientViewModel(application: Application) : AndroidViewModel(application) {

    // ── UI State ──────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(ClientUiState())
    val uiState: StateFlow<ClientUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ClientEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    // ── Discovery ─────────────────────────────────────────────────────────────

    private val discoveryService = DiscoveryService(Gson())

    // ── Service binding ───────────────────────────────────────────────────────

    private var service: AudioPlaybackService? = null
    private var bound = false
    private var positionJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as AudioPlaybackService.LocalBinder).getService()
            bound = true
            observeServiceState()
            startPositionUpdates()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            service = null
        }
    }

    init {
        // Observe discovered rooms from UDP
        viewModelScope.launch {
            discoveryService.discoveredRooms.collect { rooms ->
                _uiState.update { it.copy(discoveredRooms = rooms) }
            }
        }
    }

    // ── Discovery ─────────────────────────────────────────────────────────────

    fun startDiscovery() {
        discoveryService.startListening()
        _uiState.update { it.copy(scanning = true) }
    }

    fun stopDiscovery() {
        discoveryService.stopListening()
        _uiState.update { it.copy(scanning = false) }
    }

    // ── Connect to a room ─────────────────────────────────────────────────────

    fun connect(room: RoomInfo) {
        val ctx = getApplication<Application>()
        val deviceName = android.os.Build.MODEL

        _uiState.update { it.copy(selectedRoom = room, connectionState = ConnectionState.CONNECTING) }

        val intent = Intent(ctx, AudioPlaybackService::class.java).apply {
            action = AudioPlaybackService.ACTION_START
            putExtra(AudioPlaybackService.EXTRA_HOST_IP, room.hostIp)
            putExtra(AudioPlaybackService.EXTRA_DEVICE_NAME, deviceName)
        }
        ctx.startForegroundService(intent)
        ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun connectManual(hostIp: String) {
        connect(RoomInfo("Manual", hostIp, 45679, "Manual Host"))
    }

    fun disconnect() {
        val ctx = getApplication<Application>()
        if (bound) {
            ctx.unbindService(serviceConnection)
            bound = false
        }
        ctx.startService(Intent(ctx, AudioPlaybackService::class.java).apply {
            action = AudioPlaybackService.ACTION_STOP
        })
        positionJob?.cancel()
        _uiState.update { it.copy(connectionState = ConnectionState.DISCONNECTED, selectedRoom = null) }
    }

    fun setLocalVolume(volume: Float) {
        service?.setLocalVolume(volume)
        _uiState.update { it.copy(localVolume = volume) }
    }

    // ── Observation ───────────────────────────────────────────────────────────

    private fun observeServiceState() {
        viewModelScope.launch {
            service?.connectionState?.collect { state ->
                _uiState.update { it.copy(connectionState = state) }
            }
        }
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (isActive) {
                service?.let { svc ->
                    _uiState.update { state ->
                        state.copy(
                            trackName = svc.trackName,
                            totalDurationMs = svc.totalDurationMs,
                            positionMs = svc.currentPositionMs,
                            isPlaying = svc.isPlaying,
                            latencyMs = svc.estimatedLatencyMs
                        )
                    }
                }
                delay(500)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        discoveryService.destroy()
        positionJob?.cancel()
        if (bound) {
            getApplication<Application>().unbindService(serviceConnection)
        }
    }
}

// ── UiState ───────────────────────────────────────────────────────────────────

data class ClientUiState(
    val scanning: Boolean = false,
    val discoveredRooms: List<RoomInfo> = emptyList(),
    val selectedRoom: RoomInfo? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val trackName: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val localVolume: Float = 1.0f,
    val latencyMs: Long = 0L
)

sealed class ClientEvent {
    data class Error(val message: String) : ClientEvent()
}
