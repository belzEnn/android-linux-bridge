package io.github.belzenn.androidlinuxbridge

import androidx.test.platform.app.InstrumentationRegistry
import io.github.belzenn.androidlinuxbridge.features.clipboard.ClipboardHistory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ClipboardHistoryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    @After
    fun clearHistory() {
        context.getSharedPreferences("bridge_clipboard", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun snapshotReplacesCachedHistoryInOrder() {
        val items = JSONArray()
            .put(item("first", "First text"))
            .put(item("second", "Second text"))

        val result = ClipboardHistory.replace(
            context,
            JSONObject().put("revision", 7).put("items", items),
        )

        assertEquals(7L, result.getLong("accepted_revision"))
        assertEquals(listOf("First text", "Second text"), ClipboardHistory.load(context).map { it.text })
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSnapshotIsRejected() {
        ClipboardHistory.replace(context, JSONObject().put("revision", -1).put("items", JSONArray()))
    }

    private fun item(id: String, text: String) = JSONObject()
        .put("id", id)
        .put("text", text)
        .put("created_at", "2026-09-09T12:00:00+00:00")
}
