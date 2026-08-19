package dev.flashtool.xperia.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.flashtool.xperia.R
import dev.flashtool.xperia.flash.FlashPhase
import dev.flashtool.xperia.flash.Flasher
import dev.flashtool.xperia.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and the CPU awake for the duration of a flash, and mirrors progress
 * into a notification. Interrupting a flash because the screen turned off would brick the phone.
 */
class FlashService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Preparing", 0))

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "XperiaFlashtool:flash")
            .apply { setReferenceCounted(false); acquire(MAX_WAKELOCK_MS) }

        scope.launch {
            Flasher.engine.state.collectLatest { state ->
                val manager = getSystemService(NotificationManager::class.java)
                val text = when (state.phase) {
                    FlashPhase.FLASHING, FlashPhase.SENDING_LOADER ->
                        "${state.currentItem} · ${state.throughputText}"
                    else -> state.phase.title
                }
                manager.notify(NOTIFICATION_ID, buildNotification(text, (state.overallFraction * 100).toInt()))
                if (!state.phase.isRunning && state.phase != FlashPhase.IDLE) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String, progress: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Flashing — do not unplug")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, false)
            .setContentIntent(open)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.flash_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "flashing"
        private const val NOTIFICATION_ID = 1
        private const val MAX_WAKELOCK_MS = 60 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FlashService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FlashService::class.java))
        }
    }
}
