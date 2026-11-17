package com.linuxsuren.message.service

import android.content.Context
import android.content.Intent
import com.linuxsuren.message.data.Settings

/**
 * Single entry point for starting/stopping the stream service, so callers
 * never touch the intent directly.
 */
object StreamController {

    /** Starts (or re-parameterizes) the foreground stream service. */
    fun start(context: Context) {
        context.applicationContext.startForegroundService(
            Intent(context.applicationContext, MessageStreamService::class.java),
        )
    }

    /** Stops the stream service (logout / server switch). */
    fun stop(context: Context) {
        context.applicationContext.stopService(
            Intent(context.applicationContext, MessageStreamService::class.java),
        )
    }

    /**
     * Starts the service only when a signed-in state exists. Called from the
     * UI entry points; harmless when the service is already running.
     */
    fun ensureStarted(context: Context) {
        val settings = Settings(context)
        if (!shouldAutoStart(settings.serverUrl, settings.hasStoredSession())) {
            return
        }
        start(context)
    }
}

/** Pure decision for auto-starting the service (boot receiver / UI entry). */
fun shouldAutoStart(serverUrl: String, hasStoredSession: Boolean): Boolean =
    serverUrl.isNotBlank() && hasStoredSession
