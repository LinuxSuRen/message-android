package com.linuxsuren.message.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.linuxsuren.message.R
import com.linuxsuren.message.data.MessageItem
import com.linuxsuren.message.ui.MessagesActivity

/** Posts system notifications for messages that arrive while the app is backgrounded. */
object Notifier {

    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_LISTENING = "listening"

    private const val AUTH_NOTIF_ID = 2

    /** Idempotent; called once from the Application and the service. */
    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.notif_channel_desc)
            },
        )
        // The silent, low-importance channel for the persistent "listening"
        // notification of the foreground service.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_LISTENING,
                context.getString(R.string.notif_channel_listening_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_listening_desc)
                setShowBadge(false)
            },
        )
    }

    /**
     * "Your session has expired" heads-up for the case where the stream
     * service dies of a 401 while no activity is around to show a toast.
     */
    fun notifyAuthExpired(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = Intent(context, MessagesActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            AUTH_NOTIF_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_auth_expired_title))
            .setContentText(context.getString(R.string.notif_auth_expired_text))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(AUTH_NOTIF_ID, notification)
    }

    /**
     * Posts a notification for [message]; silently skipped when the runtime
     * notification permission (Android 13+) has not been granted. Tapping it
     * opens MessagesActivity.
     */
    fun notifyMessage(context: Context, message: MessageItem) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val intent = Intent(context, MessagesActivity::class.java)
        val pending = PendingIntent.getActivity(
            context,
            message.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                message.title.ifBlank { context.getString(R.string.notif_default_title) }
            )
            .setContentText(message.body)
            .setStyle(Notification.BigTextStyle().bigText(message.body))
            .setWhen(if (message.createdAtMs > 0) message.createdAtMs else System.currentTimeMillis())
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(message.id.toInt(), notification)
    }
}
