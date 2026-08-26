package io.github.belzenn.androidlinuxbridge.settings

import android.content.Context

data class ServerAddress(
    val host: String,
    val port: Int
)

object ConnectionSettings {
    const val DEFAULT_HOST = "192.168.1.102"
    const val DEFAULT_PORT = 4242

    private const val PREFERENCES_NAME = "bridge_connection"
    private const val HOST_KEY = "server_host"
    private const val PORT_KEY = "server_port"

    fun load(context: Context): ServerAddress {
        val preferences = context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )

        return ServerAddress(
            host = preferences.getString(HOST_KEY, DEFAULT_HOST)
                ?: DEFAULT_HOST,
            port = preferences.getInt(PORT_KEY, DEFAULT_PORT)
        )
    }

    fun save(
        context: Context,
        host: String,
        port: Int
    ) {
        context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit()
            .putString(HOST_KEY, host)
            .putInt(PORT_KEY, port)
            .apply()
    }
}
