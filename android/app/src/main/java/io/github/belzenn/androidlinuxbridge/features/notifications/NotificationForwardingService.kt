package io.github.belzenn.androidlinuxbridge.features.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

/** The bridge owns the transport; the listener never starts a second connection. */
object NotificationForwarder {
    @Volatile var send: ((JSONObject) -> Unit)? = null
    @Volatile var dismiss: ((String) -> Boolean)? = null
}

class NotificationForwardingService : NotificationListenerService() {
    private val policy by lazy { NotificationPolicy(packageName) }

    override fun onListenerConnected() {
        NotificationSettings.listenerConnected = true
        NotificationForwarder.dismiss = { key -> runCatching { cancelNotification(key) }.isSuccess }
    }

    override fun onListenerDisconnected() {
        NotificationSettings.listenerConnected = false
        NotificationForwarder.dismiss = null
        policy.clear()
    }

    override fun onDestroy() {
        NotificationSettings.listenerConnected = false
        NotificationForwarder.dismiss = null
        policy.clear()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val label = NotificationSettings.observe(this, sbn.packageName)
        val notification = sbn.notification
        val event = NotificationPayload.create(sbn, label) ?: return
        val data = event.getJSONObject("data")
        if (!policy.accept(sbn.key, sbn.packageName, NotificationSettings.enabled(this, sbn.packageName),
                notification.flags and Notification.FLAG_ONGOING_EVENT != 0,
                notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                data.getString("title"), data.getString("text"))) return
        NotificationForwarder.send?.invoke(event)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (policy.remove(sbn.key)) {
            NotificationForwarder.send?.invoke(
                JSONObject().put("kind", "event").put("event", "notification.removed")
                    .put("data", JSONObject().put("id", sbn.key))
            )
        }
    }
}
