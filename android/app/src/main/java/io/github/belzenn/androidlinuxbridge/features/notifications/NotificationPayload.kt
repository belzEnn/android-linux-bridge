package io.github.belzenn.androidlinuxbridge.features.notifications

import android.app.Notification
import android.service.notification.StatusBarNotification
import org.json.JSONObject

object NotificationPayload {
    fun create(sbn: StatusBarNotification, label: String): JSONObject? {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().take(2048)
        var text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().take(16000)
        if (title.isBlank() && text.isBlank()) return null
        val data = JSONObject().put("id", sbn.key).put("package", sbn.packageName)
            .put("app_name", label.take(256)).put("title", title).put("text", text)
        val event = JSONObject().put("kind", "event").put("event", "notification.posted").put("data", data)
        while (event.toString().toByteArray(Charsets.UTF_8).size > 32768 && text.isNotEmpty()) {
            text = text.take(text.length / 2)
            data.put("text", text)
        }
        return event.takeIf { it.toString().toByteArray(Charsets.UTF_8).size <= 32768 }
    }
}
