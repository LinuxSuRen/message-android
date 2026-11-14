package com.linuxsuren.message.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-JVM tests for [Http]: base-URL normalization and error extraction. */
class HttpTest {

    @Test
    fun normalizeBase_assumesHttpsWhenSchemeMissing() {
        assertEquals("https://msg.example.com", Http.normalizeBase("msg.example.com"))
        assertEquals("https://msg.example.com", Http.normalizeBase("  msg.example.com  "))
    }

    @Test
    fun normalizeBase_keepsExplicitScheme() {
        assertEquals("http://10.0.0.2:8080", Http.normalizeBase("http://10.0.0.2:8080"))
        // Scheme case is preserved (java.net.URL treats it case-insensitively).
        assertEquals("HTTPS://a.b.c", Http.normalizeBase("HTTPS://a.b.c"))
    }

    @Test
    fun normalizeBase_stripsTrailingSlashes() {
        assertEquals("https://msg.example.com", Http.normalizeBase("msg.example.com///"))
        assertEquals("https://msg.example.com", Http.normalizeBase("https://msg.example.com/"))
    }

    @Test
    fun serverErrorMessage_extractsErrorField() {
        assertEquals("已触发流量限制", Http.serverErrorMessage("""{"error":"已触发流量限制"}"""))
    }

    @Test
    fun serverErrorMessage_returnsNullForNonJsonOrMissing() {
        assertNull(Http.serverErrorMessage("gateway timeout"))
        assertNull(Http.serverErrorMessage("""{"message":"no error field"}"""))
    }
}
