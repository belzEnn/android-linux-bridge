package io.github.belzenn.androidlinuxbridge

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.belzenn.androidlinuxbridge.features.notifications.NotificationSettings
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationSettingsTest {
    // Instrumentation runs as the target UID. Isolate test storage under that UID.
    private val context: Context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("test_$name", mode)
    }

    @Before fun reset() {
        context.getSharedPreferences("bridge_notifications", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun cleanup() = reset()

    @Test fun defaultsOffAndPersistsExplicitChoices() {
        assertFalse(NotificationSettings.enabled(context, "org.example.chat"))
        NotificationSettings.set(context, JSONObject().put("package", "org.example.chat").put("enabled", true))
        assertTrue(NotificationSettings.enabled(context, "org.example.chat"))
        assertTrue(context.getSharedPreferences("bridge_notifications", Context.MODE_PRIVATE)
            .getStringSet("enabled", emptySet())!!.contains("org.example.chat"))
        NotificationSettings.set(context, JSONObject().put("package", "org.example.chat").put("enabled", false))
        assertFalse(NotificationSettings.enabled(context, "org.example.chat"))
    }

    @Test fun catalogIncludesUnknownObservedPackagesAndSavedChoices() {
        NotificationSettings.observe(context, "org.example.uninstalled")
        NotificationSettings.set(context, JSONObject().put("package", "org.example.selected").put("enabled", true))
        val apps = NotificationSettings.get(context).getJSONArray("apps")
        val packages = (0 until apps.length()).map { apps.getJSONObject(it).getString("package") }
        assertTrue(packages.contains("org.example.uninstalled"))
        assertTrue(packages.contains("org.example.selected"))
        assertFalse(packages.contains(context.packageName))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonBooleanSwitch() {
        NotificationSettings.set(context, JSONObject().put("package", "org.example.chat").put("enabled", "true"))
    }
}
