package io.github.belzenn.androidlinuxbridge

import android.app.Notification
import android.os.Process
import android.service.notification.StatusBarNotification
import android.text.SpannableString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.belzenn.androidlinuxbridge.features.notifications.NotificationPayload
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationPayloadTest {
    private fun sbn(title: CharSequence?, text: CharSequence?, bigText: CharSequence? = null): StatusBarNotification {
        val context = InstrumentationRegistry.getInstrumentation().context
        val notification = Notification.Builder(context, "test").setContentTitle(title).setContentText(text).build()
        if (bigText != null) notification.extras.putCharSequence(Notification.EXTRA_BIG_TEXT, bigText)
        return StatusBarNotification("org.chat", "org.chat", 42, "tag", Process.myUid(), 0, 0,
            notification, Process.myUserHandle(), System.currentTimeMillis())
    }

    @Test fun readsStyledTitleAndPrefersExpandedText() {
        val notification = sbn(SpannableString("Анна"), "Short", "Expanded")
        val event = NotificationPayload.create(notification, "Chat")!!
        val data = event.getJSONObject("data")
        assertEquals("Анна", data.getString("title"))
        assertEquals("Expanded", data.getString("text"))
        assertEquals(notification.key, data.getString("id"))
        assertEquals("org.chat", data.getString("package"))
    }

    @Test fun fallsBackToPlainTextAndSkipsEmptyNotifications() {
        assertEquals("Plain", NotificationPayload.create(sbn(null, "Plain"), "Chat")!!
            .getJSONObject("data").getString("text"))
        assertNull(NotificationPayload.create(sbn(null, null), "Chat"))
    }

    @Test fun boundsSerializedUtf8AndEscapesNewlines() {
        val event = NotificationPayload.create(sbn("Title\nNext", "😀\n".repeat(16000)), "Chat")!!
        val json = event.toString()
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= 32768)
        assertFalse(json.contains('\n'))
        assertEquals("Title\nNext", JSONObject(json).getJSONObject("data").getString("title"))
    }
}
