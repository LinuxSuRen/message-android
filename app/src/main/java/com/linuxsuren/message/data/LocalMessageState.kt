package com.linuxsuren.message.data

import android.content.Context

/**
 * Client-local read/deleted state for messages, persisted in a private
 * SharedPreferences file. The server has no read concept (by design, see
 * README), so both sets live on this device only:
 *
 *  - `readIds`: message ids the user has read (opening the detail screen or
 *    the long-press action); everything else is "unread".
 *  - `deletedIds`: message ids hidden locally. The server still stores them —
 *    a deleted id is filtered out of every list load and live push, and the
 *    state survives process death and reboots.
 *
 * Intentionally no pruning in v1: ids only matter while they still appear in
 * the first server page the client fetches; the sets are plain id strings and
 * stay far below any SharedPreferences concern for years of use.
 */
class LocalMessageState(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("message_local", Context.MODE_PRIVATE)

    fun isRead(id: Long): Boolean = id.toString() in readIds()

    fun isDeleted(id: Long): Boolean = id.toString() in deletedIds()

    /** Marks [id] read; marking unread removes it from the set. */
    fun setRead(id: Long, read: Boolean) {
        mutate(KEY_READ, id, read)
    }

    /** Hides [id] on this device. There is no undo — deliberate simplicity. */
    fun setDeleted(id: Long) {
        mutate(KEY_DELETED, id, add = true)
    }

    private fun readIds(): Set<String> = prefs.getStringSet(KEY_READ, emptySet()).orEmpty()

    private fun deletedIds(): Set<String> = prefs.getStringSet(KEY_DELETED, emptySet()).orEmpty()

    private fun mutate(key: String, id: Long, add: Boolean) {
        val current = prefs.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        if (add) {
            current.add(id.toString())
        } else {
            current.remove(id.toString())
        }
        prefs.edit().putStringSet(key, current).apply()
    }

    private companion object {
        const val KEY_READ = "read_ids"
        const val KEY_DELETED = "deleted_ids"
    }
}
