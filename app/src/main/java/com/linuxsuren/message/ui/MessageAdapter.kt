package com.linuxsuren.message.ui

import android.content.res.ColorStateList
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.linuxsuren.message.R
import com.linuxsuren.message.data.LocalMessageState
import com.linuxsuren.message.data.MessageApi
import com.linuxsuren.message.data.MessageItem
import com.linuxsuren.message.databinding.ItemMessageBinding

/**
 * Card list of messages: title, body summary, topic chip, public badge,
 * relative time and a level color dot (info gray-green / warning orange /
 * error red). Unread items carry an extra "unread" chip, driven by the
 * client-local [LocalMessageState].
 *
 * The adapter keeps a master list (server order, minus locally deleted ids)
 * and a filtered view on top of it: the read filter (all / unread / read)
 * only affects what is displayed, never the underlying data.
 *
 * Tapping a card opens the full-text detail screen ([onItemClick]); a
 * long-press opens the item actions menu ([onItemLongClick]).
 */
class MessageAdapter(private val state: LocalMessageState) :
    RecyclerView.Adapter<MessageAdapter.ItemView>() {

    /** Read-state filter applied on top of the master list. */
    var readFilter: Int = FILTER_ALL
        set(value) {
            field = value
            applyFilter()
        }

    private val master = mutableListOf<MessageItem>()
    private val visible = mutableListOf<MessageItem>()

    var onItemClick: ((MessageItem) -> Unit)? = null
    var onItemLongClick: ((MessageItem) -> Unit)? = null

    /** Replaces the whole list (manual refresh / scope switch), hiding deleted ids. */
    fun submit(newItems: List<MessageItem>) {
        master.clear()
        master.addAll(newItems.filter { !state.isDeleted(it.id) })
        applyFilter()
    }

    /**
     * Inserts at the top unless the id already exists or was locally deleted.
     * Returns true for a *new* (not duplicate, not deleted) message, so the
     * caller can beep/notify even when the read filter currently hides it
     * from the list.
     */
    fun prepend(item: MessageItem): Boolean {
        if (state.isDeleted(item.id) || master.any { it.id == item.id }) {
            return false
        }
        master.add(0, item)
        if (passesFilter(item)) {
            visible.add(0, item)
            notifyItemInserted(0)
        }
        return true
    }

    /** True when the server still returns items (i.e. the list is not empty pre-filter). */
    fun hasMasterItems(): Boolean = master.isNotEmpty()

    /** Re-evaluates read state and filter visibility (after read toggles). */
    fun refreshItem(id: Long) {
        val index = visible.indexOfFirst { it.id == id }
        if (index >= 0 && passesFilter(visible[index])) {
            notifyItemChanged(index)
        } else {
            applyFilter()
        }
    }

    /** Removes a locally deleted id everywhere. */
    fun removeById(id: Long) {
        master.removeAll { it.id == id }
        applyFilter()
    }

    /** Re-applies the filter after local read/deleted state changed outside the list. */
    fun refreshAll() = applyFilter()

    override fun getItemCount(): Int = visible.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemView {
        val binding = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ItemView(binding)
    }

    override fun onBindViewHolder(holder: ItemView, position: Int) {
        holder.bind(visible[position])
    }

    private fun passesFilter(item: MessageItem): Boolean = when (readFilter) {
        FILTER_UNREAD -> !state.isRead(item.id)
        FILTER_READ -> state.isRead(item.id)
        else -> true
    }

    private fun applyFilter() {
        visible.clear()
        visible.addAll(master.filter { passesFilter(it) })
        notifyDataSetChanged()
    }

    inner class ItemView(private val binding: ItemMessageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: MessageItem) {
            val context = binding.root.context
            binding.msgTitle.text = item.title.ifBlank { context.getString(R.string.msg_no_title) }

            binding.msgBody.text = item.body
            binding.msgBody.visibility = if (item.body.isBlank()) View.GONE else View.VISIBLE

            if (item.createdAtMs > 0) {
                binding.msgTime.text = DateUtils.getRelativeTimeSpanString(context, item.createdAtMs, true)
                binding.msgTime.visibility = View.VISIBLE
            } else {
                binding.msgTime.visibility = View.GONE
            }

            binding.msgTopic.text = item.topic
            binding.msgTopic.visibility = if (item.topic.isBlank()) View.GONE else View.VISIBLE
            binding.msgPublicBadge.visibility = if (item.isPublic) View.VISIBLE else View.GONE

            // Unread chip: visible while the message is not in the local read set.
            binding.msgUnreadBadge.visibility =
                if (state.isRead(item.id)) View.GONE else View.VISIBLE

            val colorRes = when (item.level) {
                MessageApi.LEVEL_WARNING -> R.color.level_warning
                MessageApi.LEVEL_ERROR -> R.color.level_error
                else -> R.color.level_info
            }
            binding.msgLevelDot.backgroundTintList = ColorStateList.valueOf(context.getColor(colorRes))

            binding.root.setOnClickListener { onItemClick?.invoke(item) }
            binding.root.setOnLongClickListener {
                onItemLongClick?.invoke(item)
                true
            }
        }
    }

    companion object {
        const val FILTER_ALL = 0
        const val FILTER_UNREAD = 1
        const val FILTER_READ = 2
    }
}
