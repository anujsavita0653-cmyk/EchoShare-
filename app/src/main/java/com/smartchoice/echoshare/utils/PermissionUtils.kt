package com.smartchoice.echoshare.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionUtils {

    const val REQUEST_CODE_ALL = 1001

    /**
     * Returns the list of permissions needed based on Android version.
     */
    fun getRequiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Android 10–12
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return perms.toTypedArray()
    }

    /** Returns true if all required permissions are already granted */
    fun allGranted(context: Context): Boolean =
        getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    /** Requests all needed permissions from the given Activity */
    fun requestAll(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            getRequiredPermissions(),
            REQUEST_CODE_ALL
        )
    }

    /** Helper to check if all entries in the result map were granted */
    fun allGranted(results: Map<String, Boolean>): Boolean =
        results.values.all { it }
}
