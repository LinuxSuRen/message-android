package com.linuxsuren.message.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import com.linuxsuren.message.R
import com.linuxsuren.message.data.LocalMessageState
import com.linuxsuren.message.databinding.ActivityMessageDetailBinding

/**
 * Full-text view of a single message, opened by tapping a list card.
 *
 * Opening the detail marks the message read (viewing *is* reading); the
 * bottom action row can flip it back to unread or delete it. Deletion is
 * client-local: the confirm dialog says so explicitly, and the id is added
 * to [LocalMessageState]'s deleted set — the server keeps the message.
 */
class MessageDetailActivity : Activity() {

    private lateinit var binding: ActivityMessageDetailBinding
    private lateinit var state: LocalMessageState

    private var messageId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        messageId = intent.getLongExtra(EXTRA_ID, 0L)
        if (messageId <= 0L) {
            finish()
            return
        }

        state = LocalMessageState(this)
        binding = ActivityMessageDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.detailTitle.text =
            intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { getString(R.string.msg_no_title) }

        val createdAtMs = intent.getLongExtra(EXTRA_CREATED_AT, 0L)
        if (createdAtMs > 0) {
            binding.detailTime.text = DateUtils.getRelativeTimeSpanString(this, createdAtMs, true)
            binding.detailTime.visibility = View.VISIBLE
        } else {
            binding.detailTime.visibility = View.GONE
        }

        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        binding.detailBody.text = body
        binding.detailBody.visibility = if (body.isBlank()) View.GONE else View.VISIBLE

        // Viewing the full text counts as reading it.
        state.setRead(messageId, true)

        binding.detailCloseButton.setOnClickListener { finish() }
        binding.detailToggleRead.setOnClickListener { toggleRead() }
        binding.detailDelete.setOnClickListener { confirmDelete() }
    }

    override fun onResume() {
        super.onResume()
        renderToggleLabel()
    }

    private fun toggleRead() {
        state.setRead(messageId, !state.isRead(messageId))
        renderToggleLabel()
    }

    private fun renderToggleLabel() {
        val read = state.isRead(messageId)
        binding.detailToggleRead.setText(
            if (read) R.string.action_mark_unread else R.string.action_mark_read
        )
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_delete_title)
            .setMessage(R.string.confirm_delete_body)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                state.setDeleted(messageId)
                Toast.makeText(this, R.string.msg_deleted, Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    companion object {
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_BODY = "body"
        private const val EXTRA_CREATED_AT = "created_at"

        /** Flat extras by design: one message is tiny, no Parcelable needed. */
        fun args(id: Long, title: String, body: String, createdAtMs: Long) =
            Bundle().apply {
                putLong(EXTRA_ID, id)
                putString(EXTRA_TITLE, title)
                putString(EXTRA_BODY, body)
                putLong(EXTRA_CREATED_AT, createdAtMs)
            }
    }
}
