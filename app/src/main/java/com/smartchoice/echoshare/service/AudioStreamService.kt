package com.smartchoice.echoshare.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import com.smartchoice.echoshare.model.RoomInfo
import com.smartchoice.echoshare.network.AudioStreamer
import com.smartchoice.echoshare.network.DiscoveryService
import com.smartchoice.echoshare.network.HostServer
import com.smartchoice.echoshare.utils.NetworkUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow

/**
 * Foreground Service for the HOST role.
 *
 * Responsibilities:
 *  1. Run the TCP [HostServer] (control messages).
 *  2. Run the [AudioStreamer] (raw audio bytes).
 *  3. Run [DiscoveryService] (UDP broadcasts).
 *  4. Play audio locally via ExoPlayer and tee the decoded PCM to [AudioStreamer].
 *  5. Broadcast PLAY / PAUSE / SEEK / STOP sync messages to clients.
 *  6. Emit periodic SYNC heartbeats so clients stay in lock-step.
 */
class AudioStreamService : LifecycleService() {

    companion object {
        private const val TAG = "AudioStreamService"
        private const val NOTIF_CHANNEL_ID = "echoshare_host"
        private const val NOTIF_ID = 1001

        const val ACTION_START = "com.smartchoice.echoshare.ACTION_START_HOST"
        const val ACTION_STOP  = "com.smartchoice.echoshare.ACTION_STOP_HOST"
        const val EXTRA_ROOM_NAME  = "room_name"
        const val EXTRA_HOST_NAME  = "host_name"
        const val EXTRA_HOST_IP    = "host_ip"
        const val EXTRA_BCAST_ADDR = "bcast_addr"
    }

    // ── Binder ────────────────────────────────────────────────────────────────
    inner class LocalBinder : Binder() {
        fun getService(): AudioStreamService = this@AudioStreamService
    }
    private val binder = LocalBinder()

    // ── Components ────────────────────────────────────────────────────────────
    private val gson = Gson()
    lateinit var hostServer: HostServer
        private set
    lateinit var audioStreamer: AudioStreamer
        private set
    private lateinit var discoveryService: DiscoveryService
    private lateinit var exoPlayer: ExoPlayer

    // ── State exposed to ViewModel ─────────────────────────────────────────────
    val clients get() = hostServer.clients
    val currentPositionMs: Long get() = if (::exoPlayer.isInitialized) exoPlayer.currentPosition else 0L
    val durationMs: Long get() = if (::exoPlayer.isInitialized) exoPlayer.duration.coerceAtLeast(0) else 0L
    val isPlaying: Boolean get() = if (::exoPlayer.isInitialized) exoPlayer.isPlaying else false

    private var trackName: String = ""
    private var syncJob: Job? = null

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val roomName  = intent.getStringExtra(EXTRA_ROOM_NAME) ?: "EchoShare Room"
                val hostName  = intent.getStringExtra(EXTRA_HOST_NAME) ?: "Host"
                val hostIp    = intent.getStringExtra(EXTRA_HOST_IP) ?: "0.0.0.0"
                val bcastAddr = intent.getStringExtra(EXTRA_BCAST_ADDR) ?: "255.255.255.255"
                startHosting(roomName, hostName, hostIp, bcastAddr)
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
        syncJob?.cancel()
        if (::exoPlayer.isInitialized) exoPlayer.release()
        if (::audioStreamer.isInitialized) audioStreamer.stop()
        if (::hostServer.isInitialized) hostServer.stop()
        if (::discoveryService.isInitialized) discoveryService.destroy()
        Log.i(TAG, "Service destroyed")
    }

    // ── Hosting logic ─────────────────────────────────────────────────────────

    private fun startHosting(
        roomName: String,
        hostName: String,
        hostIp: String,
        broadcastAddr: String
    ) {
        startForeground(NOTIF_ID, buildNotification(roomName))

        // Initialise components
        hostServer = HostServer(lifecycleScope, gson)
        audioStreamer = AudioStreamer(lifecycleScope)
        discoveryService = DiscoveryService(gson)

        hostServer.start()
        audioStreamer.start()
        discoveryService.startBroadcasting(
            RoomInfo(roomName, hostIp, NetworkUtils.CONTROL_PORT, hostName),
            broadcastAddr
        )

        // ExoPlayer for local playback
        exoPlayer = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    broadcastPlayState(playing)
                    if (playing) startSyncJob() else syncJob?.cancel()
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        broadcastStop()
                    }
                }
            })
        }

        Log.i(TAG, "Hosting started — room '$roomName' at $hostIp")
    }

    // ── Playback controls (called from HostViewModel via bound service) ────────

    fun loadTrack(uri: Uri, name: String) {
        trackName = name
        exoPlayer.apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
        hostServer.broadcast(
            ControlMessage(
                type = MessageType.TRACK_INFO,
                trackName = name,
                totalDurationMs = durationMs
            )
        )
    }

    fun play() {
        exoPlayer.play()
        broadcastPlayState(playing = true)
    }

    fun pause() {
        exoPlayer.pause()
        broadcastPlayState(playing = false)
    }

    fun stop() {
        exoPlayer.stop()
        broadcastStop()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        hostServer.broadcast(
            ControlMessage(
                type = MessageType.SEEK,
                positionMs = positionMs,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    fun setVolume(volume: Float) {
        exoPlayer.volume = volume
        hostServer.broadcast(ControlMessage(type = MessageType.VOLUME, volume = volume))
    }

    // ── Sync heartbeat ────────────────────────────────────────────────────────

    private fun startSyncJob() {
        syncJob?.cancel()
        syncJob = lifecycleScope.launch {
            while (isActive) {
                delay(NetworkUtils.SYNC_INTERVAL_MS)
                if (exoPlayer.isPlaying) {
                    hostServer.broadcast(
                        ControlMessage(
                            type = MessageType.SYNC,
                            positionMs = exoPlayer.currentPosition,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
    }

    private fun broadcastPlayState(playing: Boolean) {
        val type = if (playing) MessageType.PLAY else MessageType.PAUSE
        hostServer.broadcast(
            ControlMessage(
                type = type,
                positionMs = exoPlayer.currentPosition,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    private fun broadcastStop() {
        hostServer.broadcast(ControlMessage(type = MessageType.STOP))
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "EchoShare Host",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Audio streaming service" }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(roomName: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("EchoShare — Hosting")
            .setContentText("Room: $roomName")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
