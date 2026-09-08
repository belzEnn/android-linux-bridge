package io.github.belzenn.androidlinuxbridge

import io.github.belzenn.androidlinuxbridge.connection.ReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectPolicyTest {
    @Test fun retriesContinueWithBoundedDelay() {
        val policy = ReconnectPolicy()
        assertEquals(listOf(5000L, 10000L, 20000L, 40000L, 60000L), List(5) { policy.nextDelayMs() })
        repeat(100) { assertEquals(60000L, policy.nextDelayMs()) }
    }
    @Test fun successfulPairingResetsDelay() {
        val policy = ReconnectPolicy()
        repeat(8) { policy.nextDelayMs() }
        policy.reset()
        assertEquals(5000L, policy.nextDelayMs())
    }
}
