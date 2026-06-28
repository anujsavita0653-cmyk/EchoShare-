package com.smartchoice.echoshare.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.google.gson.Gson
import com.smartchoice.echoshare.MainActivity
import com.smartchoice.echoshare.R
import com.smartchoice.echoshare.model.ControlMessage
import com.smartchoice.echoshare.model.MessageType
import com.smartchoice.echoshare.network.AudioReceiver
import com.smartchoice.echoshare.network.ClientConnection
import com.smartchoice.echoshare.network.ConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground Service for the CLIENT role.
 *
 * Connects to the host, receives control messages, and drives
 * ExoPlayer to keep the client in sync with the host's playback.
 *
 * Sync strategy:
 *  - On PLAY:  seek ExoPlayer to (hostPosition + networkLatency/2) before starting.
 *  - On SYNC:  if drift > 500 ms, seek to correct position silently.
 *  - On SEEK:  seek immediately.
 *  - On PAUSE/STOP: mirror host state instantly.
 */
class AudioPlaybackService : LifecycleService() {

    companion object {
        private const val TAG = "AudioPlaybackService"
        private const val NOTIF_CHANNEL_ID = "echoshare_client"
        private const val NOTIF_ID = 1002
        private const val SYNC_DRIFT_THRESHOLD_MS = 500L

        const val ACTION_START  = "com.smartchoice.echoshare.ACTION_START_CLIENT"
        const val ACTION_STOP   = "com.smartchoice.echoshare.ACTION_STOP_CLIENT"
        const val EXTRA_HOST_IP = "host_ip"
        const val EXTRA_DEVICE_NAME = "device_name"
    }

    // ── Binder ────────────────────────────────────────────────────────────────
    inner class LocalBinder : Binder() {
        fun getService(): AudioPlaybackService = this@AudioPlaybackService
    }
    private val binder = LocalBinder()

    // ── Components ────────────────────────────────────────────────────────────
    private val gson = Gson()
    lateinit var clientConnection: ClientConnection
        private set
    private lateinit var audioReceiver: AudioReceiver
    private lateinit var exoPlayer: ExoPlayer

    // ── Exposed state ─────────────────────────────────────────────────────────
    val connectionState: StateFlow<ConnectionState> get() = clientConnection.connectionState
    var trackName: String = ""
        private set
    var totalDurationMs: Long = 0L
        private set
    val currentPositionMs: Long get() = if (::exoPlayer.isInitialized) exoPlayer.currentPosition else 0L
    val isPlaying: Boolean get() = if (::exoPlayer.isInitialized) exoPlayer.isPlaying else false
    var estimatedLatencyMs: Long = 0L
        private set

    private var hostIp: String = ""
    private var pingJob: Job? = null

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                hostIp = intent.getStringExtra(EXTRA_HOST_IP) ?: return START_NOT_STICKY
                val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: "Client"
                startClient(hostIp, deviceName)
            }
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        pingJob?.cancel()
        if (::clientConnection.isInitialized) clientConnection.disconnect()
        if (::audioReceiver.isInitialized) audioReceiver.disconnect()
        if (::exoPlayer.isInitialized) exoPlayer.release()
    }

    // ── Client startup ────────────────────────────────────────────────────────

    private fun startClient(hostIp: String, deviceName: String) {
        startForeground(NOTIF_ID, buildNotification("Connecting…"))

        exoPlayer = ExoPlayer.Builder(this).build()

        audioReceiver = AudioReceiver(hostIp, lifecycleScope)
        audioReceiver.connect()

        clientConnection = ClientConnection(hostIp, deviceName, lifecycleScope, gson)
        clientConnection.connect()

        // Observe control messages
        lifecycleScope.launch {
            clientConnection.messages.collect { msg -> handleMessage(msg) }
        }

        // Observe connection state for notification updates
        lifecycleScope.launch {
            clientConnection.connectionState.collect { state ->
                when (state) {
                    ConnectionState.CONNECTED -> updateNotification("Connected to host")
                    ConnectionState.RECONNECTING -> updateNotification("Reconnecting…")
                    else -> {}
                }
            }
        }

        startPingLoop()
    }

    // ── Message handling ──────────────────────────────────────────────────────

    private fun handleMessage(msg: ControlMessage) {
        when (msg.type) {
            MessageType.WELCOME -> {
                trackName = msg.trackName ?: ""
                totalDurationMs = msg.totalDurationMs ?: 0L
                updateNotification(trackName.ifEmpty { "EchoShare" })
                Log.i(TAG, "Welcome: track='$trackName' pos=${msg.positionMs}ms")
            }
            MessageType.TRACK_INFO -> {
                trackName = msg.trackName ?: ""
                totalDurationMs = msg.totalDurationMs ?: 0L
                updateNotification(trackName)
            }
            MessageType.PLAY -> {
                val hostPos = msg.positionMs ?: 0L
                val sentAt = msg.timestamp ?: System.currentTimeMillis()
                val transit = ((System.currentTimeMillis() - sentAt) / 2).coerceAtLeast(0)
                val syncPos = hostPos + transit
                exoPlayer.seekTo(syncPos)
                exoPlayer.play()
                Log.i(TAG, "PLAY synced at ${syncPos}ms (latency ~${transit}ms)")
            }
            MessageType.PAUSE -> {
                exoPlayer.pause()
                msg.positionMs?.let { exoPlayer.seekTo(it) }
            }
            MessageType.STOP -> {
                exoPlayer.stop()
            }
            MessageType.SEEK -> {
                val hostPos = msg.positionMs ?: 0L
                val sentAt = msg.timestamp ?: System.currentTimeMillis()
                val transit = ((System.currentTimeMillis() - sentAt) / 2).coerceAtLeast(0)
                exoPlayer.seekTo(hostPos + transit)
            }
            MessageType.SYNC -> {
                val hostPos = msg.positionMs ?: return
                val sentAt = msg.timestamp ?: System.currentTimeMillis()
                val transit = ((System.currentTimeMillis() - sentAt) / 2).coerceAtLeast(0)
                val expectedPos = hostPos + transit
                val drift = Math.abs(exoPlayer.currentPosition - expectedPos)
                if (drift > SYNC_DRIFT_THRESHOLD_MS) {
                    Log.i(TAG, "Correcting drift ${drift}ms → seeking to ${expectedPos}ms")
                    exoPlayer.seekTo(expectedPos)
                }
            }
            MessageType.VOLUME -> {
                msg.volume?.let { exoPlayer.volume = it }
            }
            MessageType.PONG -> {
                val rtt = System.currentTimeMillis() - (msg.timestamp ?: System.currentTimeMillis())
                estimatedLatencyMs = rtt / 2
                Log.d(TAG, "RTT=${rtt}ms, latency~${estimatedLatencyMs}ms")
            }
            MessageType.KICK, MessageType.ERROR -> {
                Log.w(TAG, "Kicked/Error: ${msg.errorMsg}")
                stopSelf()
            }
            else -> {}
        }
    }

    // ── Latency measurement ───────────────────────────────────────────────────

    private fun startPingLoop() {
        pingJob = lifecycleScope.launch {
            var pingId = 0L
            while (isActive) {
                delay(10_000) // ping every 10 s
                clientConnection.send(
                    ControlMessage(
                        type = MessageType.PING,
                        pingId = pingId++,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    // ── Volume control (local) ────────────────────────────────────────────────
    fun setLocalVolume(volume: Float) {
        if (::exoPlayer.isInitialized) exoPlayer.volume = volume
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "EchoShare Client",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Audio playback service" }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("EchoShare — Listening")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
