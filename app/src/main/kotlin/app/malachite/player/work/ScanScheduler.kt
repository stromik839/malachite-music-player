package app.malachite.player.work

import app.malachite.core.i18n.tr
import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.malachite.player.MainActivity
import app.malachite.player.MalachiteApp
import app.malachite.player.R
import app.malachite.player.data.DeviceScanner
import app.malachite.player.data.LibraryRepository
import app.malachite.player.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Looks for new songs on the device every N minutes (10 by default, configurable):
 *  - while the app process is alive: an in-app timer plus a MediaStore observer;
 *  - in the background: WorkManager (Android allows at least 15 minutes there).
 * New music is copied into the main folder (if auto-import is on) or announced.
 */
class ScanScheduler(
    private val context: Context,
    private val settings: SettingsRepository,
    private val library: LibraryRepository,
    private val scanner: DeviceScanner,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var timer: Job? = null
    private val _lastResult = MutableStateFlow<String?>(null)
    val lastResult: StateFlow<String?> = _lastResult.asStateFlow()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        private var pending: Job? = null
        override fun onChange(selfChange: Boolean) {
            // Our own import changes the media index too – that is not "new music".
            if (scanner.isBusy || System.currentTimeMillis() < scanner.quietUntil) return
            pending?.cancel()
            pending = scope.launch {
                delay(45_000) // let downloads finish writing
                scanNow(auto = true)
            }
        }
    }

    fun start() {
        restartTimer()
        schedulePeriodic()
        runCatching {
            context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        }
    }

    fun restartTimer() {
        timer?.cancel()
        timer = scope.launch {
            while (isActive) {
                delay(settings.value.scanIntervalMin.coerceAtLeast(1) * 60_000L)
                scanNow(auto = true)
            }
        }
    }

    fun schedulePeriodic() {
        val minutes = settings.value.scanIntervalMin.coerceAtLeast(15).toLong()
        val req = PeriodicWorkRequestBuilder<ScanWorker>(minutes, TimeUnit.MINUTES).build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("device-scan", ExistingPeriodicWorkPolicy.UPDATE, req)
        }
    }

    /**
     * One scan. With [auto] = true the result is applied silently according to settings
     * (auto-import or notification); the onboarding / manual import uses [DeviceScanner] directly.
     */
    private val prefs = context.getSharedPreferences("scan", Context.MODE_PRIVATE)

    suspend fun scanNow(auto: Boolean): Int = mutex.withLock {
        if (!library.isOpen || !settings.value.onboardingDone) return@withLock 0
        val now = System.currentTimeMillis()
        if (auto && now < scanner.quietUntil) return@withLock 0
        // Walking the whole storage is heavy: automatic checks do it at most every 6 hours and
        // otherwise rely on Android's media index (cheap).
        val lastDeep = prefs.getLong("last_deep", 0)
        val deep = settings.value.deepScan && (!auto || now - lastDeep > 6 * 3600_000L)
        val found = runCatching { if (auto) scanner.tryFind(deep) else scanner.find(deep) }.getOrNull() ?: return@withLock 0
        if (deep) prefs.edit().putLong("last_deep", now).apply()
        if (found.music.isEmpty()) return@withLock 0
        if (auto && settings.value.autoImport) {
            val r = scanner.import(found.music)
            library.rescan()
            val msg = tr("Добавлено новых песен: ${r.imported}")
            _lastResult.value = msg
            if (r.imported > 0) notify(msg, tr("Они уже разложены по папкам исполнителей и альбомов"))
            r.imported
        } else {
            val msg = tr("Найдено новых песен: ${found.music.size}")
            _lastResult.value = msg
            notify(msg, tr("Откройте Malachite, чтобы добавить их в главную папку"))
            found.music.size
        }
    }

    private fun notify(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pi = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, MalachiteApp.CHANNEL_NEW_MUSIC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(4202, n) }
    }
}

class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = MalachiteApp.graph
        graph.openLibraryIfPossible()
        runCatching { graph.scanScheduler.scanNow(auto = true) }
        return Result.success()
    }
}
