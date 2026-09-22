package io.github.belzenn.androidlinuxbridge.features.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import io.github.belzenn.androidlinuxbridge.BridgeState
import io.github.belzenn.androidlinuxbridge.MainActivity
import io.github.belzenn.androidlinuxbridge.R
import io.github.belzenn.androidlinuxbridge.service.BridgeService
import org.json.JSONObject

/** Owned by BridgeService; network requests and local stop actions share one lock. */
class FindPhoneHandler(private val context: Context, private val onChanged: (Boolean) -> Unit = {}) {
    private val main = Handler(Looper.getMainLooper())
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var ringtone: MediaPlayer? = null
    private var closed = false
    private val timeout = Runnable { stop() }

    @Synchronized
    @Suppress("UNUSED_PARAMETER")
    fun handle(params: JSONObject): JSONObject {
        check(!closed) { "Bridge service is stopping" }
        // Repeated requests neither overlap nor extend the automatic stop timer.
        if (ringtone != null) return JSONObject().put("ringing", true)
        val audio = context.getSystemService(AudioManager::class.java)
        check(audio.getStreamVolume(AudioManager.STREAM_ALARM) > 0) {
            "Phone alarm volume is muted"
        }
        check(RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE) != null) {
            "No phone ringtone is selected"
        }
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            // Resolve the current system ringtone on each request, including its cached copy.
            player.setDataSource(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            player.isLooping = true
            player.setVolume(1f, 1f)
            player.prepare()
            player.start()
        } catch (error: Exception) {
            player.release()
            throw error
        }
        ringtone = player
        publishState()
        main.post { BridgeState.findingPhone.value = true }
        main.postDelayed(timeout, DURATION_MS.toLong())
        // The app also provides a stop button when notification permission is denied.
        runCatching { showNotification() }
        return JSONObject().put("ringing", true)
    }

    @Synchronized
    fun stop() {
        main.removeCallbacks(timeout)
        ringtone?.release()
        ringtone = null
        publishState()
        notifications.cancel(NOTIFICATION_ID)
        main.post { BridgeState.findingPhone.value = false }
    }

    @Synchronized
    fun publishState() {
        onChanged(ringtone != null)
    }

    @Synchronized
    fun close() {
        closed = true
        stop()
    }

    private fun showNotification() {
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Find phone", NotificationManager.IMPORTANCE_HIGH
        ).apply { setSound(null, null) })
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val stop = PendingIntent.getService(context, NOTIFICATION_ID,
            Intent(context, BridgeService::class.java).setAction(BridgeService.ACTION_STOP_FIND_PHONE), flags)
        val open = PendingIntent.getActivity(context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java), flags)
        notifications.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Find phone")
            .setContentText("Your computer is ringing this phone")
            .setContentIntent(open)
            .addAction(0, "Stop ringing", stop)
            .setOngoing(true)
            .setSilent(true)
            .build())
    }

    companion object {
        private const val CHANNEL_ID = "find_phone"
        private const val NOTIFICATION_ID = 3
        private const val DURATION_MS = 60_000
    }
}
