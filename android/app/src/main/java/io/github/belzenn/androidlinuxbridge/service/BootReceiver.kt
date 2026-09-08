package io.github.belzenn.androidlinuxbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            BridgeService.start(context)
        } catch (exception: IllegalStateException) {
            Log.w("BridgeBoot", "Background service start unavailable", exception)
        } catch (exception: SecurityException) {
            Log.w("BridgeBoot", "Permission required to start bridge", exception)
        }
    }
}
