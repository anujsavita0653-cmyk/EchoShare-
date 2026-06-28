package com.smartchoice.echoshare

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.smartchoice.echoshare.client.ClientActivity
import com.smartchoice.echoshare.databinding.ActivityMainBinding
import com.smartchoice.echoshare.host.HostActivity
import com.smartchoice.echoshare.utils.NetworkUtils
import com.smartchoice.echoshare.utils.PermissionUtils
import com.smartchoice.echoshare.utils.toast
import com.smartchoice.echoshare.utils.toastLong

/**
 * Entry point — lets the user choose to HOST or JOIN a room.
 *
 * Also handles runtime permission requests before any action proceeds.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Permission launcher (ActivityResult API)
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (PermissionUtils.allGranted(results)) {
            pendingAction?.invoke()
        } else {
            toastLong("Some permissions were denied. The app may not work correctly.")
        }
        pendingAction = null
    }

    /** Action to run after permissions are granted */
    private var pendingAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.apply {
            tvAppVersion.text = "v${BuildConfig.VERSION_NAME}"

            btnHost.setOnClickListener {
                checkWifiThen { checkPermissionsThen { navigateToHost() } }
            }

            btnJoin.setOnClickListener {
                checkWifiThen { checkPermissionsThen { navigateToClient() } }
            }
        }
    }

    // ── Navigation ─────────────────────────────────────────────────────────────

    private fun navigateToHost() {
        startActivity(Intent(this, HostActivity::class.java))
    }

    private fun navigateToClient() {
        startActivity(Intent(this, ClientActivity::class.java))
    }

    // ── Guards ─────────────────────────────────────────────────────────────────

    private fun checkWifiThen(action: () -> Unit) {
        if (!NetworkUtils.isWifiConnected(this)) {
            toast("Please connect to Wi-Fi or enable a Mobile Hotspot first.")
            return
        }
        action()
    }

    private fun checkPermissionsThen(action: () -> Unit) {
        if (PermissionUtils.allGranted(this)) {
            action()
        } else {
            pendingAction = action
            permLauncher.launch(PermissionUtils.getRequiredPermissions())
        }
    }
}
