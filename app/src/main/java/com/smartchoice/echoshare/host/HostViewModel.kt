package com.smartchoice.echoshare.host

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartchoice.echoshare.model.DeviceInfo
import com.smartchoice.echoshare.service.AudioStreamService
import com.smartchoice.echoshare.utils.NetworkUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * ViewModel for [HostActivity].
 *
 * Manages the lifecycle of [AudioStreamService] binding and exposes
 * UI state via StateFlow / SharedFlow.
 */
class HostViewModel(application: Application) : AndroidViewModel(application) {

    // ── UI State ──────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow(HostUiState())
    val uiState: StateFlow<HostUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<HostEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    // ── Service binding ───────────────────────────────────────────────────────

    private var service: AudioStreamService? = null
    private var bound = false
    private var positionJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as AudioStreamService.LocalBinder).getService()
            bound = true
            observeServiceState()
            startPositionUpdates()
            _uiState.update { it.copy(serviceReady = true) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            bound = false
            service = null
            _uiState.update { it.copy(serviceReady = false) }
        }
    }

    // ── Initialization ────────────────────────────────────────────────────────

    fun startService(roomName: String) {
        val ctx = getApplication<Application>()
        val ip = NetworkUtils.getLocalIpAddress(ctx) ?: run {
            viewModelScope.launch { _events.emit(HostEvent.Error("Wi-Fi not connected")) }
            return
        }
        val bcastAddr = NetworkUtils.getBroadcastAddress(ctx)
        val deviceName = android.os.Build.MODEL

        _uiState.update { it.copy(roomName = roomName, hostIp = ip) }

        val intent = Intent(ctx, AudioStreamService::class.java).apply {
            action = AudioStreamService.ACTION_START
            putExtra(AudioStreamService.EXTRA_ROOM_NAME, roomName)
            putExtra(AudioStreamService.EXTRA_HOST_NAME, deviceName)
            putExtra(AudioStreamService.EXTRA_HOST_IP, ip)
            putExtra(AudioStreamService.EXTRA_BCAST_ADDR, bcastAddr)
        }
        ctx.startForegroundService(intent)
        ctx.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun stopService() {
        val ctx = getApplication<Application>()
        if (bound) {
            ctx.unbindService(serviceConnection)
            bound = false
        }
        ctx.startService(Intent(ctx, AudioStreamService::class.java).apply {
            action = AudioStreamService.ACTION_STOP
        })
        positionJob?.cancel()
        _uiState.update { HostUiState() }
    }

    // ── Playback controls ─────────────────────────────────────────────────────

    fun loadTrack(uri: Uri) {
        val ctx = getApplication<Application>()
        val name = getFileName(ctx, uri)
        service?.loadTrack(uri, name)
        _uiState.update { it.copy(trackName = name) }
    }

    fun play()  = service?.play()
    fun pause() = service?.pause()
    fun stop()  = service?.stop()
    fun seekTo(positionMs: Long) = service?.seekTo(positionMs)
    fun setVolume(volume: Float) {
        service?.setVolume(volume)
        _uiState.update { it.copy(volume = volume) }
    }

    // ── Observation ───────────────────────────────────────────────────────────

    private fun observeServiceState() {
        viewModelScope.launch {
            service?.clients?.collect { devices ->
                _uiState.update { it.copy(connectedDevices = devices) }
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
                            positionMs = svc.currentPositionMs,
                            durationMs = svc.durationMs,
                            isPlaying = svc.isPlaying
                        )
                    }
                }
                delay(500)
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun getFileName(context: Context, uri: Uri): String {
        var name = "Unknown track"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx)
            }
        }
        return name
    }

    override fun onCleared() {
        super.onCleared()
        positionJob?.cancel()
        if (bound) {
            getApplication<Application>().unbindService(serviceConnection)
        }
    }
}

// ── UiState ───────────────────────────────────────────────────────────────────

data class HostUiState(
    val serviceReady: Boolean = false,
    val roomName: String = "",
    val hostIp: String = "",
    val trackName: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float = 1.0f,
    val connectedDevices: List<DeviceInfo> = emptyList()
)

sealed class HostEvent {
    data class Error(val message: String) : HostEvent()
    object TrackLoaded : HostEvent()
}
