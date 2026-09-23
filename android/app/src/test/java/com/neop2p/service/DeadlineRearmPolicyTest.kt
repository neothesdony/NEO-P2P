package com.neop2p.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeadlineRearmPolicyTest {

    @Test
    fun `rearm is enqueued only for boot completed`() {
        assertTrue(BootReceiver.shouldRearm("android.intent.action.BOOT_COMPLETED"))
        assertFalse(BootReceiver.shouldRearm("android.intent.action.SCREEN_ON"))
        assertFalse(BootReceiver.shouldRearm(null))
    }
}
