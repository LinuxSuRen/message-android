package com.linuxsuren.message.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.linuxsuren.message.data.Settings

/**
 * Starts the stream service after boot (and after app updates), so the hub
 * connection survives device restarts without opening the app.
 *
 * BOOT_COMPLETED is an exempted case for starting a foreground service on
 * every supported API level. Some vendor ROMs additionally require the user
 * to allow "auto start" for the app — that is a system setting we cannot
 * (and should not) flip programmatically.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                val settings = Settings(context)
                if (shouldAutoStart(settings.serverUrl, settings.hasStoredSession())) {
                    StreamController.start(context)
                }
            }
        }
    }
}
