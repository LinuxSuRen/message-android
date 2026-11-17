package com.linuxsuren.message.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * In-process message bus between the foreground service (SSE owner) and the
 * UI. The service is the single producer; activities are consumers that come
 * and go. Zero extra dependencies: plain coroutines flows.
 *
 * Missed live events are not a problem — history is always recoverable via
 * `GET /api/v1/messages`, and the service signals [ConnState.Connected] so
 * the UI can backfill after a reconnect.
 */
object MessageBus {

    /** Lifecycle of the SSE stream owned by the service. */
    sealed interface ConnState {
        /** No stream yet (service not started). */
        data object Idle : ConnState

        data object Connecting : ConnState

        data object Connected : ConnState

        data class Retrying(val seconds: Int) : ConnState

        /** Stream ended for good (service stopped / logged out). */
        data object Stopped : ConnState

        /** 401/403: the session is gone and re-login is required. */
        data object AuthError : ConnState
    }

    private val _messages = MutableSharedFlow<MessageItem>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Live `message` events, already parsed. */
    val messages: SharedFlow<MessageItem> = _messages

    private val _connection = MutableStateFlow<ConnState>(ConnState.Idle)

    /** Latest stream state; new collectors immediately get the current value. */
    val connection: StateFlow<ConnState> = _connection

    /** Called by the service for every parsed message event. */
    fun publishMessage(item: MessageItem) {
        _messages.tryEmit(item)
    }

    /** Called by the service on every stream state transition. */
    fun publishState(state: ConnState) {
        _connection.value = state
    }
}
