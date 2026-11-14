package app.malachite.player.service

import app.malachite.core.i18n.tr
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.malachite.player.MainActivity
import app.malachite.player.MalachiteApp
import app.malachite.player.R

/**
 * Keeps the app alive (with a progress notification) while healing, importing or exporting,
 * so the work is not killed when the screen turns off or the user switches apps.
 */
class LongTaskService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: tr("Работаю…")
        currentTitle = title
        val n = build(this, title, "", 0, 0)
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, n,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
            running = true
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4201
        private const val EXTRA_TITLE = "title"
        @Volatile private var running = false
        @Volatile private var currentTitle = ""
        @Volatile private var lastUpdate = 0L

        fun start(context: Context, title: String) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, LongTaskService::class.java).putExtra(EXTRA_TITLE, title))
            } catch (e: Exception) {
                // Not allowed from background on new Android versions – the work still runs while the app lives.
            }
        }

        fun progress(context: Context, text: String, done: Int, total: Int) {
            if (!running) return
            val now = System.currentTimeMillis()
            if (now - lastUpdate < 400 && done < total) return
            lastUpdate = now
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            runCatching { nm.notify(NOTIFICATION_ID, build(context, currentTitle, text, done, total)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LongTaskService::class.java))
        }

        private fun build(context: Context, title: String, text: String, done: Int, total: Int): Notification {
            val pi = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, MalachiteApp.CHANNEL_TASKS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentIntent(pi)
                .setProgress(total, done, total == 0)
                .build()
        }
    }
}
