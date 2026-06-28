package com.smartchoice.echoshare.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.smartchoice.echoshare.databinding.ItemRoomBinding
import com.smartchoice.echoshare.model.RoomInfo

/**
 * RecyclerView adapter showing discovered rooms on the Client screen.
 */
class RoomsAdapter(
    private val onJoin: (RoomInfo) -> Unit
) : ListAdapter<RoomInfo, RoomsAdapter.ViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<RoomInfo>() {
            override fun areItemsTheSame(a: RoomInfo, b: RoomInfo) = a.hostIp == b.hostIp
            override fun areContentsTheSame(a: RoomInfo, b: RoomInfo) = a == b
        }
    }

    inner class ViewHolder(private val binding: ItemRoomBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(room: RoomInfo) {
            binding.apply {
                tvRoomName.text = room.roomName
                tvHostName.text = room.hostName
                tvHostIp.text = room.hostIp
                tvClientCount.text = "${room.current}/${room.capacity} devices"
                btnJoin.setOnClickListener { onJoin(room) }
                root.setOnClickListener { onJoin(room) }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRoomBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
