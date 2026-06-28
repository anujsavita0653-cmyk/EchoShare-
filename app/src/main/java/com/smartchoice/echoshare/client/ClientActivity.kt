package com.smartchoice.echoshare.client

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.smartchoice.echoshare.adapter.RoomsAdapter
import com.smartchoice.echoshare.databinding.ActivityClientBinding
import com.smartchoice.echoshare.network.ConnectionState
import com.smartchoice.echoshare.utils.toast
import com.smartchoice.echoshare.utils.toTimeString
import kotlinx.coroutines.launch

/**
 * Client screen — discover rooms, connect, and view synchronized playback status.
 */
class ClientActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClientBinding
    private val viewModel: ClientViewModel by viewModels()
    private lateinit var roomsAdapter: RoomsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClientBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupRoomsList()
        setupControls()
        observeState()
        observeEvents()

        viewModel.startDiscovery()
    }

    override fun onStop() {
        super.onStop()
        viewModel.stopDiscovery()
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupRoomsList() {
        roomsAdapter = RoomsAdapter { room ->
            viewModel.connect(room)
        }
        binding.rvRooms.adapter = roomsAdapter
    }

    private fun setupControls() {
        binding.apply {
            btnScan.setOnClickListener { viewModel.startDiscovery() }

            btnManualConnect.setOnClickListener { showManualConnectDialog() }

            btnDisconnect.setOnClickListener { viewModel.disconnect() }

            seekBarLocalVolume.setOnSeekBarChangeListener(
                object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(sb: android.widget.SeekBar) {}
                    override fun onProgressChanged(
                        sb: android.widget.SeekBar, progress: Int, fromUser: Boolean
                    ) {
                        if (fromUser) viewModel.setLocalVolume(progress / 100f)
                    }
                }
            )
            seekBarLocalVolume.progress = 100
        }
    }

    // ── State observation ─────────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.apply {

                        // ── Discovery panel ──────────────────────────────────
                        val isConnected = state.connectionState == ConnectionState.CONNECTED
                        val isConnecting = state.connectionState == ConnectionState.CONNECTING ||
                                           state.connectionState == ConnectionState.RECONNECTING

                        cardDiscovery.visibility = if (isConnected) View.GONE else View.VISIBLE
                        cardPlayback.visibility  = if (isConnected) View.VISIBLE else View.GONE

                        // Scanning indicator
                        progressScanning.visibility = if (state.scanning) View.VISIBLE else View.GONE
                        tvScanStatus.text = when {
                            state.scanning && state.discoveredRooms.isEmpty() -> "Scanning for rooms…"
                            state.scanning -> "${state.discoveredRooms.size} room(s) found"
                            else -> "Scan stopped"
                        }

                        // Rooms list
                        roomsAdapter.submitList(state.discoveredRooms)
                        tvNoRooms.visibility = if (!state.scanning || state.discoveredRooms.isNotEmpty())
                            View.GONE else View.VISIBLE

                        // ── Playback panel ───────────────────────────────────
                        tvConnectionStatus.text = when (state.connectionState) {
                            ConnectionState.CONNECTED    -> "Connected"
                            ConnectionState.CONNECTING   -> "Connecting…"
                            ConnectionState.RECONNECTING -> "Reconnecting…"
                            ConnectionState.DISCONNECTED -> "Disconnected"
                            ConnectionState.ERROR        -> "Error"
                        }

                        val statusColor = when (state.connectionState) {
                            ConnectionState.CONNECTED    -> 0xFF4CAF50.toInt()
                            ConnectionState.RECONNECTING -> 0xFFFFC107.toInt()
                            else                         -> 0xFF9E9E9E.toInt()
                        }
                        viewConnectionDot.setBackgroundColor(statusColor)

                        tvRoomNamePlayback.text = state.selectedRoom?.roomName ?: "—"
                        tvTrackNamePlayback.text = if (state.trackName.isEmpty()) "Waiting for track…" else state.trackName
                        tvPlaybackStatus.text = if (state.isPlaying) "Playing" else "Paused"
                        tvPosition.text = state.positionMs.toTimeString()
                        tvDuration.text = state.totalDurationMs.toTimeString()
                        tvLatency.text = if (state.latencyMs > 0) "~${state.latencyMs} ms" else "—"

                        if (state.totalDurationMs > 0) {
                            seekBarProgress.max = 1000
                            seekBarProgress.progress =
                                (state.positionMs * 1000 / state.totalDurationMs).toInt()
                        }
                        // Progress bar is read-only on client side
                        seekBarProgress.isEnabled = false

                        btnDisconnect.isEnabled = isConnected || isConnecting
                    }
                }
            }
        }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is ClientEvent.Error -> toast(event.message)
                }
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showManualConnectDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "192.168.1.xxx"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Enter Host IP")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) viewModel.connectManual(ip)
                else toast("Please enter a valid IP address")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
