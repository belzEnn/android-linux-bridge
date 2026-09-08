package io.github.belzenn.androidlinuxbridge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.belzenn.androidlinuxbridge.features.notifications.NotificationForwarder
import io.github.belzenn.androidlinuxbridge.features.notifications.NotificationSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class NotificationListenerTest {
    private fun shell(command: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { it.readBytes() }
        }
    }

    @Test fun systemListenerHonorsFilterAndAccessRevocation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val component = "${context.packageName}/.features.notifications.NotificationForwardingService"
        val oldAccess = NotificationSettings.accessGranted(context)
        val oldEnabled = NotificationSettings.enabled(context, "com.android.shell")
        val oldSender = NotificationForwarder.send
        val received = LinkedBlockingQueue<JSONObject>()
        fun enable(value: Boolean) {
            NotificationSettings.set(context, JSONObject().put("package", "com.android.shell").put("enabled", value))
        }
        try {
            shell("cmd notification allow_listener $component")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!NotificationSettings.listenerConnected && System.nanoTime() < deadline) Thread.sleep(50)
            assertTrue(NotificationSettings.listenerConnected)
            NotificationForwarder.send = { event -> received.offer(event) }
            enable(false)
            shell("cmd notification post -t BridgeTest bridge-filter-off blocked")
            assertNull(received.poll(500, TimeUnit.MILLISECONDS))
            enable(true)
            shell("cmd notification post -t BridgeTest bridge-filter-on forwarded")
            val event = received.poll(5, TimeUnit.SECONDS)
            assertNotNull(event)
            assertEquals("notification.posted", event!!.getString("event"))
            assertEquals("forwarded", event.getJSONObject("data").getString("text"))
            enable(false)
            shell("cmd notification post -t BridgeTest bridge-filter-again blocked")
            assertNull(received.poll(500, TimeUnit.MILLISECONDS))
            shell("cmd notification disallow_listener $component")
            val revokeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (NotificationSettings.accessGranted(context) && System.nanoTime() < revokeDeadline) Thread.sleep(50)
            assertFalse(NotificationSettings.accessGranted(context))
        } finally {
            NotificationForwarder.send = oldSender
            enable(oldEnabled)
            shell("cmd notification ${if (oldAccess) "allow_listener" else "disallow_listener"} $component")
        }
    }
}
