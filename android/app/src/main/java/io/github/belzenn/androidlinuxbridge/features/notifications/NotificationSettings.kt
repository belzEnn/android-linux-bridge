package io.github.belzenn.androidlinuxbridge.features.notifications

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

object NotificationSettings {
    @Volatile var listenerConnected = false
    private fun prefs(context: Context) = context.getSharedPreferences("bridge_notifications", Context.MODE_PRIVATE)

    fun accessGranted(context: Context): Boolean {
        val component = ComponentName(context, NotificationForwardingService::class.java)
        return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == component } == true
    }

    @Synchronized fun enabled(context: Context, pkg: String): Boolean =
        prefs(context).getStringSet("enabled", emptySet())!!.contains(pkg)

    @Synchronized fun set(context: Context, params: JSONObject): JSONObject {
        val pkg = params.getString("package")
        require(pkg.isNotBlank() && params.get("enabled") is Boolean)
        require(pkg != context.packageName) { "Bridge notifications cannot be forwarded" }
        val packages = prefs(context).getStringSet("enabled", emptySet())!!.toMutableSet()
        if (params.getBoolean("enabled")) packages.add(pkg) else packages.remove(pkg)
        check(prefs(context).edit().putStringSet("enabled", packages).commit())
        return JSONObject().put("ok", true)
    }

    @Synchronized fun observe(context: Context, pkg: String): String {
        val label = try {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) { pkg }
        val preferences = prefs(context)
        if (preferences.getString("app:$pkg", null) != label) preferences.edit().putString("app:$pkg", label).apply()
        return label
    }

    @Synchronized fun get(context: Context): JSONObject {
        val apps = mutableMapOf<String, String>()
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager.queryIntentActivities(intent, 0).forEach {
            apps[it.activityInfo.packageName] = it.loadLabel(context.packageManager).toString()
        }
        prefs(context).all.forEach { (key, value) ->
            if (key.startsWith("app:") && value is String) apps.putIfAbsent(key.removePrefix("app:"), value)
        }
        prefs(context).getStringSet("enabled", emptySet())!!.forEach { apps.putIfAbsent(it, it) }
        apps.remove(context.packageName)
        val array = JSONArray()
        apps.toList().sortedBy { it.second.lowercase() }.forEach { (pkg, label) ->
            array.put(JSONObject().put("package", pkg).put("label", label).put("enabled", enabled(context, pkg)))
        }
        return JSONObject().put("access_granted", accessGranted(context))
            .put("listener_connected", listenerConnected).put("apps", array)
    }
}
