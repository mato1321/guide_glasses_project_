package com.example.rokidglasses_project.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.rokidglasses_project.R
import com.example.rokidglasses_project.model.Message

class MessageAdapter(private val items: MutableList<Message>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_BOT = 0
        private const val TYPE_USER = 1
    }

    // 根據 message.from 回傳 TYPE_BOT 或 TYPE_USER，決定使用哪個 layout。
    override fun getItemViewType(position: Int): Int {
        return if (items[position].from == Message.From.USER) TYPE_USER else TYPE_BOT
    }


    //根據 viewType inflate 對應 layout（item_message_bot 或 item_message_user）
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_BOT) {
            val v = inflater.inflate(R.layout.item_message_assistant, parent, false)
            BotHolder(v)
        } else {
            val v = inflater.inflate(R.layout.item_message_user, parent, false)
            UserHolder(v)
        }
    }

    override fun getItemCount(): Int = items.size


    // 把 message 的 text/time 塞進對應 View
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = items[position]
        if (holder is BotHolder) holder.bind(msg)
        if (holder is UserHolder) holder.bind(msg)
    }


    // 把新訊息加到 list 並 notifyItemInserted，觸發 RecyclerView 自動顯示新項目。
    fun addMessage(msg: Message) {
        items.add(msg)
        notifyItemInserted(items.size - 1)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    class BotHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val tvMessage: TextView = v.findViewById(R.id.tvMessage)
        private val tvTime: TextView = v.findViewById(R.id.tvTime)
        private val ivAvatar: ImageView = v.findViewById(R.id.ivAvatar)

        fun bind(m: Message) {
            tvMessage.text = m.text
            tvTime.text = m.time
            itemView.contentDescription = "助理，${m.text}, ${m.time}"
            // 輔助：讓 TalkBack 容易感知新訊息（fragment 也會 announce）
            itemView.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        }
    }

    class UserHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val tvMessage: TextView = v.findViewById(R.id.tvMessage)
        private val tvTime: TextView = v.findViewById(R.id.tvTime)
        private val ivAvatar: ImageView = v.findViewById(R.id.ivAvatar)

        fun bind(m: Message) {
            tvMessage.text = m.text
            tvTime.text = m.time
            itemView.contentDescription = "使用者，${m.text}, ${m.time}"
        }
    }
}