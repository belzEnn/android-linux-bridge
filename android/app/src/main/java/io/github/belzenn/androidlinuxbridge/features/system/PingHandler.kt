package io.github.belzenn.androidlinuxbridge.features.system

import org.json.JSONObject

class PingHandler {
    @Suppress("UNUSED_PARAMETER")
    fun handle(params: JSONObject): JSONObject {
        return JSONObject()
            .put("pong", true)
    }
}
