package io.github.belzenn.androidlinuxbridge.settings

import android.content.Context
import java.util.UUID

data class ServerAddress(val host: String, val port: Int)

object ConnectionSettings {
    private const val PREFERENCES_NAME = "bridge_connection"
    private const val HOST_KEY = "server_host"
    private const val PORT_KEY = "server_port"
    private const val DEVICE_ID_KEY = "device_id"
    private const val SERVICE_NAME_KEY = "service_name"

    fun loadServer(context: Context): ServerAddress? {
        val preferences = preferences(context)
        if (preferences.getString(SERVICE_NAME_KEY, null).isNullOrBlank()) {
            return null
        }
        val host = preferences.getString(HOST_KEY, null) ?: return null
        val port = preferences.getInt(PORT_KEY, 0)
        return ServerAddress(host, port).takeIf {
            it.host.isNotBlank() && it.port in 1..65535
        }
    }

    fun saveServer(context: Context, host: String, port: Int, serviceName: String) {
        preferences(context).edit()
            .putString(HOST_KEY, host)
            .putInt(PORT_KEY, port)
            .putString(SERVICE_NAME_KEY, serviceName)
            .apply()
    }

    fun preferredServiceName(context: Context): String? =
        preferences(context).getString(SERVICE_NAME_KEY, null)

    fun deviceId(context: Context): String {
        val preferences = preferences(context)
        return preferences.getString(DEVICE_ID_KEY, null) ?: UUID.randomUUID().toString().also {
            preferences.edit().putString(DEVICE_ID_KEY, it).apply()
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
