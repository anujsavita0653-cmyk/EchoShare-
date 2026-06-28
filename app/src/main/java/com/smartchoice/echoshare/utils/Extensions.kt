package com.smartchoice.echoshare.utils

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.util.concurrent.TimeUnit

/** Show a short toast */
fun Context.toast(msg: String) =
    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

/** Show a long toast */
fun Context.toastLong(msg: String) =
    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

/** Observe a LiveData only once */
fun <T> LiveData<T>.observeOnce(owner: LifecycleOwner, observer: Observer<T>) {
    observe(owner, object : Observer<T> {
        override fun onChanged(value: T) {
            observer.onChanged(value)
            removeObserver(this)
        }
    })
}

/**
 * Formats milliseconds as MM:SS (e.g. 65000 → "1:05")
 */
fun Long.toTimeString(): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(this)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(this) % 60
    return String.format("%d:%02d", minutes, seconds)
}

/**
 * Returns the file name portion of a URI path string.
 */
fun String.fileNameFromPath(): String = substringAfterLast("/").substringAfterLast("%2F")
