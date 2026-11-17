package com.linuxsuren.message.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.linuxsuren.message.MessageApp
import com.linuxsuren.message.R
import com.linuxsuren.message.data.MessageApi
import com.linuxsuren.message.data.MessageBus
import com.linuxsuren.message.data.Settings
import com.linuxsuren.message.data.SseClient
import com.linuxsuren.message.notification.Notifier
import com.linuxsuren.message.ui.MessagesActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service owning the SSE stream, so messages keep arriving (and
 * notifying) while the app is backgrounded or its activities are gone.
 *
 * The service is the only SSE client in the process: parsed events go to
 * [MessageBus] for the UI, and to system notifications when the app is not
 * visible. `START_STICKY` lets the system restart it after memory pressure.
 *
 * It stops itself when the session expires (with a "please sign in again"
 * notification) or when the UI orders a stop (logout / server switch).
 */
class MessageStreamService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sse: SseClient? = null
    private var currentBase: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        startInForeground(getString(R.string.notif_listening_title))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = Settings(this)
        val base = settings.serverUrl
        if (base.isBlank()) {
            // Logged out / server cleared while the service was running.
            stopSelf()
            return START_NOT_STICKY
        }
        // Cold start (boot receiver / system restart): re-inject the stored
        // session cookie into the process CookieManager before connecting.
        settings.restoreSessionCookie(base)
        startStream(base)
        return START_STICKY
    }

    private fun startStream(base: String) {
        if (base == currentBase && sse != null) {
            // Already streaming this server: repeated start calls (every
            // activity creation) must not tear the connection down.
            return
        }
        currentBase = base
        sse?.stop()
        MessageBus.publishState(MessageBus.ConnState.Connecting)
        updateForegroundNotification(getString(R.string.sse_connecting))
        val client = SseClient(MessageApi(base).streamUrl(), object : SseClient.Listener {
            override fun onSseEvent(name: String, data: String) {
                if (name != SseClient.DEFAULT_EVENT) {
                    return
                }
                val item = MessageApi.parseMessageEvent(data) ?: return
                MessageBus.publishMessage(item)
                if (!MessageApp.isForeground) {
                    Notifier.notifyMessage(this@MessageStreamService, item)
                }
            }

            override fun onSseConnected() {
                MessageBus.publishState(MessageBus.ConnState.Connected)
                updateForegroundNotification(getString(R.string.notif_listening_title))
            }

            override fun onSseRetryIn(seconds: Int) {
                MessageBus.publishState(MessageBus.ConnState.Retrying(seconds))
                updateForegroundNotification(
                    getString(R.string.notif_listening_reconnecting, seconds),
                )
            }

            override fun onSseAuthError() {
                MessageBus.publishState(MessageBus.ConnState.AuthError)
                Settings(this@MessageStreamService).clearSession()
                Notifier.notifyAuthExpired(this@MessageStreamService)
                stopSelf()
            }
        })
        sse = client
        scope.launch { client.run() }
    }

    override fun onDestroy() {
        sse?.stop()
        sse = null
        currentBase = null
        scope.cancel()
        MessageBus.publishState(MessageBus.ConnState.Stopped)
        super.onDestroy()
    }

    // ---- foreground notification ----

    private fun startInForeground(text: String) {
        val notification = listeningNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateForegroundNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, listeningNotification(text))
    }

    private fun listeningNotification(text: String): Notification {
        val intent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MessagesActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, Notifier.CHANNEL_LISTENING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(intent)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1
    }
}
