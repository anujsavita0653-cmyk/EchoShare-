package com.smartchoice.echoshare.host

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.smartchoice.echoshare.adapter.DevicesAdapter
import com.smartchoice.echoshare.databinding.ActivityHostBinding
import com.smartchoice.echoshare.utils.toast
import com.smartchoice.echoshare.utils.toTimeString
import kotlinx.coroutines.launch

/**
 * Host screen — create a room, pick a track, control playback, and view connected clients.
 */
class HostActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHostBinding
    private val viewModel: HostViewModel by viewModels()
    private lateinit var devicesAdapter: DevicesAdapter

    // File picker for MP3 selection
    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Grant read permission so the service can access the URI
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.loadTrack(uri)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHostBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupDevicesList()
        setupControls()
        observeState()
        observeEvents()

        // Start the service with the room name entered by the user (show dialog)
        if (savedInstanceState == null) showCreateRoomDialog()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Service stays alive in background via foreground service;
        // viewModel handles binding lifecycle
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    private fun setupDevicesList() {
        devicesAdapter = DevicesAdapter(onKick = { device ->
            viewModel.service?.hostServer?.kickClient(device.id)
        })
        binding.rvDevices.adapter = devicesAdapter
    }

    private fun setupControls() {
        binding.apply {
            btnPickFile.setOnClickListener {
                filePicker.launch(arrayOf("audio/*", "audio/mpeg"))
            }

            btnPlay.setOnClickListener  { viewModel.play() }
            btnPause.setOnClickListener { viewModel.pause() }
            btnStop.setOnClickListener  { viewModel.stop() }

            // Seek bar
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                private var dragging = false
                override fun onStartTrackingTouch(sb: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(sb: SeekBar) {
                    dragging = false
                    val state = viewModel.uiState.value
                    val pos = (sb.progress / 1000.0 * state.durationMs).toLong()
                    viewModel.seekTo(pos)
                }
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {}
            })

            // Volume
            seekBarVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) viewModel.setVolume(progress / 100f)
                }
            })
            seekBarVolume.progress = 100
        }
    }

    // ── State observation ─────────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.apply {
                        // Room info
                        tvRoomName.text = if (state.roomName.isEmpty()) "—" else state.roomName
                        tvHostIp.text = if (state.hostIp.isEmpty()) "—" else state.hostIp

                        // Track
                        tvTrackName.text = if (state.trackName.isEmpty()) "No track loaded" else state.trackName
                        tvTrackName.visibility = View.VISIBLE

                        // Position / duration
                        tvPosition.text = state.positionMs.toTimeString()
                        tvDuration.text = state.durationMs.toTimeString()

                        // Seek bar (only update when not dragging)
                        if (state.durationMs > 0) {
                            val pct = (state.positionMs * 1000 / state.durationMs).toInt()
                            seekBar.progress = pct
                        }

                        // Playback buttons
                        val hasTrack = state.trackName.isNotEmpty()
                        btnPlay.isEnabled  = hasTrack && !state.isPlaying
                        btnPause.isEnabled = hasTrack && state.isPlaying
                        btnStop.isEnabled  = hasTrack

                        // Devices
                        val count = state.connectedDevices.size
                        tvDeviceCount.text = "$count device${if (count == 1) "" else "s"} connected"
                        devicesAdapter.submitList(state.connectedDevices)
                        rvDevices.visibility = if (count > 0) View.VISIBLE else View.GONE
                        tvNoDevices.visibility = if (count == 0) View.VISIBLE else View.GONE
                    }
                }
            }
        }
    }

    private fun observeEvents() {
        lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is HostEvent.Error -> toast(event.message)
                    HostEvent.TrackLoaded -> toast("Track loaded")
                }
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showCreateRoomDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Room name (e.g. Living Room)"
            setPadding(48, 24, 48, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("Create Room")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "EchoShare Room" }
                viewModel.startService(name)
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }
}
