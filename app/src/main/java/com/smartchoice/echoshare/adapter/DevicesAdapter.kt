package com.smartchoice.echoshare.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.smartchoice.echoshare.databinding.ItemDeviceBinding
import com.smartchoice.echoshare.model.DeviceInfo
import com.smartchoice.echoshare.model.DeviceStatus

/**
 * RecyclerView adapter showing connected client devices on the Host screen.
 */
class DevicesAdapter(
    private val onKick: (DeviceInfo) -> Unit = {}
) : ListAdapter<DeviceInfo, DevicesAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DeviceInfo>() {
            override fun areItemsTheSame(a: DeviceInfo, b: DeviceInfo) = a.id == b.id
            override fun areContentsTheSame(a: DeviceInfo, b: DeviceInfo) = a == b
        }
    }

    inner class ViewHolder(private val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(device: DeviceInfo) {
            binding.apply {
                tvDeviceName.text = device.name
                tvDeviceIp.text = device.ip
                tvDeviceStatus.text = when (device.status) {
                    DeviceStatus.CONNECTED    -> "Connected"
                    DeviceStatus.CONNECTING   -> "Connecting…"
                    DeviceStatus.DISCONNECTED -> "Disconnected"
                    DeviceStatus.ERROR        -> "Error"
                }
                tvLatency.text = if (device.latencyMs > 0) "${device.latencyMs} ms" else "—"

                // Status indicator colour
                val colour = when (device.status) {
                    DeviceStatus.CONNECTED    -> 0xFF4CAF50.toInt()
                    DeviceStatus.CONNECTING   -> 0xFFFFC107.toInt()
                    DeviceStatus.DISCONNECTED -> 0xFF9E9E9E.toInt()
                    DeviceStatus.ERROR        -> 0xFFF44336.toInt()
                }
                viewStatusDot.setBackgroundColor(colour)

                btnKick.setOnClickListener { onKick(device) }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDeviceBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
