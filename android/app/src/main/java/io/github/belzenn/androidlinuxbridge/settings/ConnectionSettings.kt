package io.github.belzenn.androidlinuxbridge.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

data class ServerAddress(val host: String, val port: Int)

object ConnectionSettings {
    private const val PREFERENCES_NAME = "bridge_connection"
    private const val HOST_KEY = "server_host"
    private const val PORT_KEY = "server_port"
    private const val DEVICE_ID_KEY = "device_id"
    private const val SERVICE_NAME_KEY = "service_name"
    private const val PAIRING_PREFERENCES_NAME = "bridge_secure_pairing"
    private const val PAIRING_TOKEN_KEY = "pairing_token"

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

    fun pairingToken(context: Context): String? =
        securePreferences(context).getString(PAIRING_TOKEN_KEY, null)

    fun savePairingToken(context: Context, token: String) {
        securePreferences(context).edit().putString(PAIRING_TOKEN_KEY, token).apply()
    }

    fun clearPairingToken(context: Context) {
        securePreferences(context).edit().remove(PAIRING_TOKEN_KEY).apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun securePreferences(context: Context) = EncryptedSharedPreferences.create(
        context,
        PAIRING_PREFERENCES_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
