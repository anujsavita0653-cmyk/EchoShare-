package com.smartchoice.echoshare.network

import android.util.Log
import com.smartchoice.echoshare.utils.NetworkUtils
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Streams raw audio bytes from the host to all connected clients over TCP.
 *
 * Architecture:
 *  - Opens a [ServerSocket] on [NetworkUtils.AUDIO_PORT].
 *  - Each connecting client gets an [AudioClientOutput] — a coroutine
 *    that drains a per-client [PipedInputStream]/[PipedOutputStream] pair.
 *  - The host writes audio chunks to [pushChunk]; the chunk is tee'd to
 *    every connected client's pipe.
 *
 * This design keeps audio delivery independent of control messages and
 * avoids head-of-line blocking between clients.
 */
class AudioStreamer(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "AudioStreamer"
    }

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val outputs = CopyOnWriteArrayList<AudioClientOutput>()

    /** Whether the streamer is actively accepting connections */
    @Volatile var running = false
        private set

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        serverSocket = ServerSocket(NetworkUtils.AUDIO_PORT)
        running = true
        acceptJob = scope.launch(Dispatchers.IO) {
            Log.i(TAG, "Audio streamer listening on port ${NetworkUtils.AUDIO_PORT}")
            while (isActive) {
                try {
                    val socket = serverSocket!!.accept()
                    addClient(socket)
                } catch (e: IOException) {
                    if (isActive) Log.w(TAG, "Accept error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        running = false
        acceptJob?.cancel()
        outputs.forEach { it.close() }
        outputs.clear()
        runCatching { serverSocket?.close() }
        Log.i(TAG, "Audio streamer stopped")
    }

    // ── Client management ─────────────────────────────────────────────────────

    private fun addClient(socket: Socket) {
        val out = AudioClientOutput(socket, scope) { removeClient(it) }
        outputs.add(out)
        out.start()
        Log.i(TAG, "Audio client connected: ${socket.inetAddress.hostAddress}")
    }

    private fun removeClient(out: AudioClientOutput) {
        outputs.remove(out)
        Log.i(TAG, "Audio client disconnected: ${out.ip}")
    }

    // ── Data push ─────────────────────────────────────────────────────────────

    /**
     * Push an audio chunk to all connected clients.
     * Called from the host's ExoPlayer read loop.
     */
    fun pushChunk(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        if (!running || outputs.isEmpty()) return
        for (out in outputs) {
            out.write(data, offset, length)
        }
    }

    val clientCount: Int get() = outputs.size
}

// ─── AudioClientOutput ────────────────────────────────────────────────────────

/**
 * Buffered TCP output for one audio client.
 * Writes are queued in a [ByteArrayOutputStream] buffer and flushed
 * by a background coroutine, preventing slow clients from blocking the
 * main streaming loop.
 */
class AudioClientOutput(
    private val socket: Socket,
    private val scope: CoroutineScope,
    private val onDisconnect: (AudioClientOutput) -> Unit
) {
    val ip: String = socket.inetAddress.hostAddress ?: "?"

    private val bufferLock = Any()
    private val pendingBuffer = ByteArrayOutputStream(NetworkUtils.AUDIO_CHUNK_SIZE * 4)
    private var writeJob: Job? = null

    @Volatile private var closed = false
    private lateinit var outStream: OutputStream

    fun start() {
        outStream = BufferedOutputStream(socket.getOutputStream(), NetworkUtils.AUDIO_CHUNK_SIZE * 2)
        writeJob = scope.launch(Dispatchers.IO) {
            while (isActive && !closed) {
                val chunk: ByteArray? = synchronized(bufferLock) {
                    if (pendingBuffer.size() > 0) {
                        val data = pendingBuffer.toByteArray()
                        pendingBuffer.reset()
                        data
                    } else null
                }
                if (chunk != null) {
                    try {
                        outStream.write(chunk)
                        outStream.flush()
                    } catch (e: IOException) {
                        if (!closed) {
                            Log.w("AudioClientOutput", "Write failed for $ip: ${e.message}")
                            close()
                            onDisconnect(this@AudioClientOutput)
                        }
                        return@launch
                    }
                } else {
                    delay(5) // wait for more data
                }
            }
        }
    }

    fun write(data: ByteArray, offset: Int, length: Int) {
        if (closed) return
        synchronized(bufferLock) {
            pendingBuffer.write(data, offset, length)
        }
    }

    fun close() {
        closed = true
        writeJob?.cancel()
        runCatching { socket.close() }
    }
}
