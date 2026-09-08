package io.github.belzenn.androidlinuxbridge

import io.github.belzenn.androidlinuxbridge.features.notifications.NotificationPolicy
import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    private val policy = NotificationPolicy("bridge", 2)
    private fun accept(key: String = "key", pkg: String = "chat", enabled: Boolean = true,
                       ongoing: Boolean = false, summary: Boolean = false,
                       title: String = "Title", text: String = "Text") =
        policy.accept(key, pkg, enabled, ongoing, summary, title, text)

    @Test fun filtersOwnDisabledOngoingSummaryAndEmpty() {
        assertFalse(accept(pkg = "bridge"))
        assertFalse(accept(enabled = false))
        assertFalse(accept(ongoing = true))
        assertFalse(accept(summary = true))
        assertFalse(accept(title = " ", text = ""))
        assertTrue(accept())
    }

    @Test fun suppressesDuplicatesButForwardsChangedContent() {
        assertTrue(accept())
        assertFalse(accept())
        assertTrue(accept(text = "Updated"))
        assertTrue(accept(title = "New title", text = "Updated"))
    }

    @Test fun removedNotificationsCanBePostedAgain() {
        assertTrue(accept())
        policy.remove("key")
        assertTrue(accept())
        policy.clear()
        assertTrue(accept())
    }

    @Test fun boundsMemoryAndDistinguishesKeys() {
        assertTrue(accept(key = "first"))
        assertTrue(accept(key = "second"))
        assertTrue(accept(key = "third"))
        assertFalse(accept(key = "second"))
        assertTrue(accept(key = "first"))
    }
}
