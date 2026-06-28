package com.smartchoice.echoshare.network

import android.util.Log
import com.smartchoice.echoshare.utils.NetworkUtils
import kotlinx.coroutines.*
import java.io.*
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Client-side audio receiver.
 *
 * Connects to the host's audio TCP port and exposes a [PipedInputStream]
 * that ExoPlayer can read from via a custom [DataSource].
 *
 * Data flow:
 *   Host AudioStreamer → TCP → AudioReceiver → PipedOutputStream → PipedInputStream → ExoPlayer
 */
class AudioReceiver(
    private val hostIp: String,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "AudioReceiver"
        private const val PIPE_BUFFER = 512 * 1024  // 512 KB pipe buffer
    }

    private val pipedOut = PipedOutputStream()
    val pipedIn = PipedInputStream(pipedOut, PIPE_BUFFER)

    private var socket: Socket? = null
    private var receiveJob: Job? = null

    @Volatile var connected = false
        private set

    fun connect() {
        receiveJob = scope.launch(Dispatchers.IO) {
            var retryDelay = 1000L
            while (isActive) {
                try {
                    val sock = Socket()
                    sock.soTimeout = NetworkUtils.SOCKET_TIMEOUT_MS
                    sock.connect(
                        java.net.InetSocketAddress(hostIp, NetworkUtils.AUDIO_PORT),
                        NetworkUtils.SOCKET_TIMEOUT_MS
                    )
                    socket = sock
                    connected = true
                    retryDelay = 1000L
                    Log.i(TAG, "Audio connected to $hostIp:${NetworkUtils.AUDIO_PORT}")

                    val buf = ByteArray(NetworkUtils.AUDIO_CHUNK_SIZE)
                    val inputStream = BufferedInputStream(sock.getInputStream(), NetworkUtils.AUDIO_CHUNK_SIZE * 2)
                    while (isActive) {
                        val read = inputStream.read(buf)
                        if (read == -1) break
                        pipedOut.write(buf, 0, read)
                        pipedOut.flush()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SocketTimeoutException) {
                    Log.w(TAG, "Audio socket timeout, reconnecting…")
                } catch (e: IOException) {
                    Log.w(TAG, "Audio receive error: ${e.message}")
                } finally {
                    connected = false
                    runCatching { socket?.close() }
                }
                delay(retryDelay)
                retryDelay = (retryDelay * 2).coerceAtMost(8000L)
            }
        }
    }

    fun disconnect() {
        receiveJob?.cancel()
        runCatching { socket?.close() }
        runCatching { pipedOut.close() }
        connected = false
    }
}
