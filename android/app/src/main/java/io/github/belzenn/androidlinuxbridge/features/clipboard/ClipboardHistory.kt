package io.github.belzenn.androidlinuxbridge.features.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ClipboardHistoryItem(val id: String, val text: String, val createdAt: String)

object ClipboardHistory {
    private const val PREFERENCES = "bridge_clipboard"
    private const val ITEMS = "items"
    private const val REVISION = "revision"
    private const val MAX_ITEMS = 50
    private const val MAX_ITEM_BYTES = 256 * 1024
    private const val MAX_CACHE_BYTES = 768 * 1024

    fun replace(context: Context, params: JSONObject): JSONObject {
        val revision = params.optLong("revision", -1)
        val values = params.optJSONArray("items")
            ?: throw IllegalArgumentException("Clipboard items are missing")
        if (revision < 0) throw IllegalArgumentException("Invalid clipboard revision")

        val items = mutableListOf<ClipboardHistoryItem>()
        var totalBytes = 0
        for (index in 0 until minOf(values.length(), MAX_ITEMS)) {
            val value = values.optJSONObject(index)
                ?: throw IllegalArgumentException("Invalid clipboard item")
            val id = value.optString("id")
            val text = value.optString("text")
            val createdAt = value.optString("created_at")
            val size = text.toByteArray(Charsets.UTF_8).size
            if (id.isBlank() || createdAt.isBlank() || text.isEmpty() || '\u0000' in text ||
                size > MAX_ITEM_BYTES || totalBytes + size > MAX_CACHE_BYTES
            ) continue
            items += ClipboardHistoryItem(id, text, createdAt)
            totalBytes += size
        }

        val encoded = JSONArray().apply {
            items.forEach { item ->
                put(JSONObject().put("id", item.id).put("text", item.text).put("created_at", item.createdAt))
            }
        }.toString()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putLong(REVISION, revision)
            .putString(ITEMS, encoded)
            .apply()
        return JSONObject().put("accepted_revision", revision)
    }

    fun load(context: Context): List<ClipboardHistoryItem> {
        val encoded = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(ITEMS, "[]") ?: "[]"
        return runCatching {
            val values = JSONArray(encoded)
            buildList {
                for (index in 0 until values.length()) {
                    val value = values.getJSONObject(index)
                    add(ClipboardHistoryItem(value.getString("id"), value.getString("text"), value.getString("created_at")))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun delete(context: Context, id: String) {
        val remaining = load(context).filterNot { it.id == id }
        val encoded = JSONArray().apply {
            remaining.forEach { put(JSONObject().put("id", it.id).put("text", it.text).put("created_at", it.createdAt)) }
        }.toString()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putString(ITEMS, encoded).apply()
        ClipboardForwarder.send?.invoke(
            JSONObject().put("kind", "event").put("event", "clipboard.history.delete")
                .put("data", JSONObject().put("id", id))
        )
    }

    fun copy(context: Context, item: ClipboardHistoryItem) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Android Linux Bridge", item.text))
    }

    fun sendCurrent(context: Context): ClipboardSendResult {
        val manager = context.getSystemService(ClipboardManager::class.java)
        val clip = manager.primaryClip ?: return ClipboardSendResult.EMPTY
        if (clip.itemCount == 0 || manager.primaryClipDescription?.hasMimeType("text/*") != true) {
            return ClipboardSendResult.EMPTY
        }
        val text = clip.getItemAt(0).coerceToText(context).toString()
        val size = text.toByteArray(Charsets.UTF_8).size
        if (text.isEmpty() || '\u0000' in text || size > MAX_ITEM_BYTES) {
            return ClipboardSendResult.INVALID
        }
        if (load(context).any { it.text == text }) return ClipboardSendResult.ALREADY_SYNCED
        val event = JSONObject().put("kind", "event").put("event", "clipboard.history.add")
            .put("data", JSONObject().put("text", text))
        return if (ClipboardForwarder.send?.invoke(event) == true) {
            ClipboardSendResult.SENT
        } else {
            ClipboardSendResult.OFFLINE
        }
    }
}

enum class ClipboardSendResult {
    SENT,
    ALREADY_SYNCED,
    EMPTY,
    INVALID,
    OFFLINE,
}

object ClipboardForwarder {
    @Volatile var send: ((JSONObject) -> Boolean)? = null
}
