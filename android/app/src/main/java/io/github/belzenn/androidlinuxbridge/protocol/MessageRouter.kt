package io.github.belzenn.androidlinuxbridge.protocol

import org.json.JSONObject

typealias RequestHandler = (JSONObject) -> JSONObject

class MessageRouter(
    private val handlers: Map<String, RequestHandler>
) {
    fun handle(message: JSONObject): JSONObject? {
        if (message.optString("kind") != "request") {
            return null
        }

        val requestId = message.optString("id")
        val method = message.optString("method")

        if (requestId.isBlank()) {
            return null
        }

        if (method.isBlank()) {
            return errorResponse(
                requestId,
                "INVALID_REQUEST",
                "Request method is missing"
            )
        }

        val handler = handlers[method]
            ?: return errorResponse(
                requestId,
                "METHOD_NOT_FOUND",
                "Unknown method: $method"
            )

        val params = message.optJSONObject("params")
            ?: JSONObject()

        return try {
            JSONObject()
                .put("kind", "response")
                .put("id", requestId)
                .put("result", handler(params))
        } catch (exception: Exception) {
            errorResponse(
                requestId,
                "HANDLER_ERROR",
                exception.message ?: "Request handler failed"
            )
        }
    }

    private fun errorResponse(
        requestId: String,
        code: String,
        message: String
    ): JSONObject {
        return JSONObject()
            .put("kind", "response")
            .put("id", requestId)
            .put(
                "error",
                JSONObject()
                    .put("code", code)
                    .put("message", message)
            )
    }
}
