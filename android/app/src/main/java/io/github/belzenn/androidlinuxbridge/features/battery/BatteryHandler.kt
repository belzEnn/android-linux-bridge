package io.github.belzenn.androidlinuxbridge.features.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

class BatteryHandler(
    context: Context,
    private val onBatteryRead: (Int) -> Unit
) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Suppress("UNUSED_PARAMETER")
    fun handle(params: JSONObject): JSONObject {
        val batteryIntent = applicationContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: error("Battery information is unavailable")

        val rawLevel = batteryIntent.getIntExtra(
            BatteryManager.EXTRA_LEVEL,
            -1
        )
        val scale = batteryIntent.getIntExtra(
            BatteryManager.EXTRA_SCALE,
            -1
        )

        if (rawLevel < 0 || scale <= 0) {
            error("Battery level is unavailable")
        }

        val level = rawLevel * 100 / scale
        val status = batteryIntent.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        )
        val charging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL

        mainHandler.post {
            onBatteryRead(level)
        }

        return JSONObject()
            .put("level", level)
            .put("charging", charging)
    }
}
