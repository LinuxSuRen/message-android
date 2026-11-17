package com.linuxsuren.message.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.linuxsuren.message.MessageApp
import com.linuxsuren.message.R
import com.linuxsuren.message.data.ErrorKind
import com.linuxsuren.message.data.HttpException
import com.linuxsuren.message.data.LocalMessageState
import com.linuxsuren.message.data.MessageApi
import com.linuxsuren.message.data.MessageBus
import com.linuxsuren.message.data.MessageItem
import com.linuxsuren.message.data.Settings
import com.linuxsuren.message.databinding.ActivityMessagesBinding
import com.linuxsuren.message.service.StreamController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Message list: scope filter (all / own tenant / public), manual refresh and
 * a live list fed by [MessageBus] — the SSE stream itself is owned by the
 * foreground service ([com.linuxsuren.message.service.MessageStreamService]),
 * so messages keep arriving while the app is backgrounded.
 *
 * Incoming bus messages are inserted at the top of the list; in the
 * foreground a light beep plays, in the background the service posts a
 * system notification (Android 13+ asks for POST_NOTIFICATIONS once).
 */
class MessagesActivity : Activity() {

    private lateinit var binding: ActivityMessagesBinding
    private lateinit var settings: Settings
    private lateinit var api: MessageApi
    private lateinit var adapter: MessageAdapter
    private lateinit var localState: LocalMessageState

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loadJob: Job? = null
    private var tone: ToneGenerator? = null
    private var currentScope: String = MessageApi.SCOPE_ALL
    private var authHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settings = Settings(this)
        val base = settings.serverUrl
        if (base.isBlank()) {
            goLogin()
            return
        }
        settings.restoreSessionCookie(base)
        api = MessageApi(base)

        binding = ActivityMessagesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.headerAccount.text =
            getString(R.string.signed_in_as, settings.displayName.ifBlank { settings.email })

        localState = LocalMessageState(this)
        adapter = MessageAdapter(localState)
        adapter.onItemClick = { item ->
            startActivity(
                Intent(this, MessageDetailActivity::class.java).putExtras(
                    MessageDetailActivity.args(item.id, item.title, item.body, item.createdAtMs)
                )
            )
        }
        adapter.onItemLongClick = { item -> showItemMenu(item) }
        binding.messageList.layoutManager = LinearLayoutManager(this)
        binding.messageList.adapter = adapter

        binding.scopeGroup.setOnCheckedChangeListener { _, checkedId ->
            currentScope = when (checkedId) {
                R.id.scope_own -> MessageApi.SCOPE_OWN
                R.id.scope_public -> MessageApi.SCOPE_PUBLIC
                else -> MessageApi.SCOPE_ALL
            }
            reload()
        }
        binding.readGroup.setOnCheckedChangeListener { _, checkedId ->
            adapter.readFilter = when (checkedId) {
                R.id.read_filter_unread -> MessageAdapter.FILTER_UNREAD
                R.id.read_filter_read -> MessageAdapter.FILTER_READ
                else -> MessageAdapter.FILTER_ALL
            }
            updateEmptyHint()
        }
        binding.refreshButton.setOnClickListener { reload() }
        binding.menuButton.setOnClickListener { showOverflowMenu() }

        binding.streamStatus.text = getString(R.string.sse_connecting)

        requestNotificationPermissionIfNeeded()
        // Ensures the foreground stream service is running (cold start,
        // return after process death, fresh login, …). Already-running is a
        // no-op that just re-checks the stored server/session.
        StreamController.ensureStarted(this)
        observeBus()
        reload()
    }

    /** Subscribes to the service-owned stream: live messages + connection state. */
    private fun observeBus() {
        scope.launch {
            MessageBus.messages.collect { item -> onLiveMessage(item) }
        }
        scope.launch {
            MessageBus.connection.collect { state -> onConnState(state) }
        }
    }

    private fun onConnState(state: MessageBus.ConnState) {
        if (isFinishing || isDestroyed) {
            return
        }
        when (state) {
            MessageBus.ConnState.Connecting ->
                binding.streamStatus.text = getString(R.string.sse_connecting)

            MessageBus.ConnState.Connected -> {
                binding.streamStatus.text = getString(R.string.sse_connected)
                // 断线重连成功后立即对账：SSE 每条消息只推一次，
                // 退避窗口内错过的消息需靠重新拉取补齐。
                reload()
            }

            is MessageBus.ConnState.Retrying ->
                binding.streamStatus.text = getString(R.string.sse_reconnecting, state.seconds)

            MessageBus.ConnState.AuthError -> onSessionExpired()

            MessageBus.ConnState.Idle, MessageBus.ConnState.Stopped -> Unit
        }
    }

    override fun onResume() {
        super.onResume()
        // Returning from the detail screen may have changed read/deleted state.
        if (::adapter.isInitialized && ::localState.isInitialized) {
            adapter.refreshAll()
            updateEmptyHint()
        }
    }

    override fun onDestroy() {
        tone?.release()
        tone = null
        scope.cancel()
        super.onDestroy()
    }

    private fun reload() {
        loadJob?.cancel()
        binding.emptyView.visibility = View.GONE
        binding.listProgress.visibility = View.VISIBLE
        loadJob = scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { api.listMessages(scope = currentScope) }
                adapter.submit(page.items)
                binding.listProgress.visibility = View.GONE
                updateEmptyHint()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.listProgress.visibility = View.GONE
                if (e is HttpException && e.kind == ErrorKind.UNAUTHORIZED) {
                    onSessionExpired()
                } else {
                    Toast.makeText(this@MessagesActivity, ErrorText.friendly(this@MessagesActivity, e), Toast.LENGTH_LONG).show()
                }
                if (adapter.itemCount == 0) {
                    binding.emptyView.visibility = View.VISIBLE
                }
            }
        }
    }

    /**
     * A live message arrived via the bus: top-insert; beep in the foreground.
     * The background system notification is posted by the stream service.
     */
    private fun onLiveMessage(item: MessageItem) {
        if (isFinishing || isDestroyed) {
            return
        }
        val inserted = adapter.prepend(item)
        updateEmptyHint()
        if (inserted && MessageApp.isForeground) {
            beep()
        }
    }

    private fun beep() {
        try {
            val generator = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, BEEP_VOLUME)
                .also { tone = it }
            generator.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
        } catch (_: RuntimeException) {
            // Tone generator resources are occasionally busy; a missed beep is fine.
        }
    }

    private fun onSessionExpired() {
        if (authHandled) {
            return
        }
        authHandled = true
        settings.clearSession()
        Toast.makeText(this, R.string.err_session_expired, Toast.LENGTH_LONG).show()
        goLogin()
    }

    /** Empty hint differs: truly no messages vs. none matching the read filter. */
    private fun updateEmptyHint() {
        binding.emptyView.setText(
            if (adapter.itemCount == 0 && adapter.hasMasterItems()) {
                R.string.list_empty_filtered
            } else {
                R.string.list_empty
            }
        )
        binding.emptyView.visibility = if (adapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    /** Long-press actions on a card: read toggle + local delete. */
    private fun showItemMenu(item: MessageItem) {
        val popup = PopupMenu(this, binding.messageList)
        val read = localState.isRead(item.id)
        popup.menu.add(0, MENU_MARK_READ, 0, R.string.action_mark_read).isEnabled = !read
        popup.menu.add(0, MENU_MARK_UNREAD, 0, R.string.action_mark_unread).isEnabled = read
        popup.menu.add(0, MENU_DELETE, 0, R.string.menu_delete)
        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                MENU_MARK_READ -> {
                    localState.setRead(item.id, true)
                    adapter.refreshItem(item.id)
                    updateEmptyHint()
                    true
                }

                MENU_MARK_UNREAD -> {
                    localState.setRead(item.id, false)
                    adapter.refreshItem(item.id)
                    updateEmptyHint()
                    true
                }

                MENU_DELETE -> {
                    localState.setDeleted(item.id)
                    adapter.removeById(item.id)
                    updateEmptyHint()
                    Toast.makeText(this, R.string.msg_deleted, Toast.LENGTH_SHORT).show()
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun showOverflowMenu() {
        val popup = PopupMenu(this, binding.menuButton)
        popup.menuInflater.inflate(R.menu.menu_messages, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                // Both stop the background stream service and return to the
                // sign-in screen with the session cleared; switching servers
                // keeps the remembered account, signing out forgets it.
                R.id.menu_switch_server -> {
                    StreamController.stop(this)
                    settings.clearSession()
                    goLogin()
                    true
                }

                R.id.menu_logout -> {
                    StreamController.stop(this)
                    settings.forgetAccount()
                    goLogin()
                    true
                }

                else -> false
            }
        }
        popup.show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFICATIONS)
        }
    }

    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    companion object {
        private const val REQ_POST_NOTIFICATIONS = 1001
        private const val BEEP_VOLUME = 80
        private const val BEEP_MS = 120

        private const val MENU_MARK_READ = 1
        private const val MENU_MARK_UNREAD = 2
        private const val MENU_DELETE = 3
    }
}
