package com.linuxsuren.message.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-JVM tests for [MessageApi] JSON parsing paths. */
class ApiParsingTest {

    @Test
    fun parseIso8601_acceptsOffsetForms() {
        // Z form
        assertEquals(1793700000000L, MessageItem.parseIso8601("2026-11-03T10:00:00Z"))
        // +08:00 offset (same instant as the Z case above, 02:00 UTC)
        assertEquals(1793700000000L, MessageItem.parseIso8601("2026-11-03T18:00:00+08:00"))
        // Fractional seconds are dropped, not fatal
        assertEquals(1793700000123L, MessageItem.parseIso8601("2026-11-03T10:00:00.123Z"))
    }

    @Test
    fun parseIso8601_returnsZeroOnGarbage() {
        assertEquals(0L, MessageItem.parseIso8601("not-a-date"))
        assertEquals(0L, MessageItem.parseIso8601(""))
    }

    @Test
    fun parseMessageEvent_mapsWellFormedPayload() {
        val item = MessageApi.parseMessageEvent(
            """{"id":7,"tenant_id":1,"user_id":2,"topic":"ci","title":"构建失败",
               "body":"查看日志","level":"error","public":false,
               "created_at":"2026-11-03T10:00:00Z"}"""
        )
        assertNotNull(item)
        item!!
        assertEquals(7L, item.id)
        assertEquals("构建失败", item.title)
        assertEquals(MessageApi.LEVEL_ERROR, item.level)
        assertEquals("ci", item.topic)
        assertEquals(1793700000000L, item.createdAtMs)
    }

    @Test
    fun parseMessageEvent_defaultsAndSurvivesMissingFields() {
        val item = MessageApi.parseMessageEvent("""{"id":1,"title":"t"}""")
        assertNotNull(item)
        item!!
        assertEquals(MessageApi.LEVEL_INFO, item.level)
        assertEquals(0L, item.createdAtMs)
    }

    @Test
    fun parseMessageEvent_nullOnInvalidJson() {
        assertNull(MessageApi.parseMessageEvent("{not json"))
        assertNull(MessageApi.parseMessageEvent("42"))
    }
}
