package com.linuxsuren.message.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boot / UI auto-start decision: only with both a server address and a
 * stored session may the service come up unattended.
 */
class AutoStartDecisionTest {

    @Test
    fun `requires both server and session`() {
        assertTrue(shouldAutoStart("http://192.168.1.10:8080", true))
    }

    @Test
    fun `no server means no autostart`() {
        assertFalse(shouldAutoStart("", true))
        assertFalse(shouldAutoStart("   ", true))
    }

    @Test
    fun `no session means no autostart`() {
        assertFalse(shouldAutoStart("http://192.168.1.10:8080", false))
    }

    @Test
    fun `neither means no autostart`() {
        assertFalse(shouldAutoStart("", false))
    }
}
