package io.github.belzenn.androidlinuxbridge.features.notifications

import org.json.JSONObject

class NotificationDismissHandler {
    fun handle(params: JSONObject): JSONObject {
        val id = params.optString("id")
        require(id.isNotBlank() && id.length <= 1024) { "Invalid notification id" }
        return JSONObject().put("dismissed", NotificationForwarder.dismiss?.invoke(id) ?: false)
    }
}
