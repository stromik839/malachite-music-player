package app.malachite.player

import app.malachite.core.i18n.tr
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import app.malachite.core.audio.TagIO
import app.malachite.core.cover.CoverFinder
import app.malachite.core.lyrics.LyricsFinder
import app.malachite.core.net.Http
import app.malachite.player.data.AppIcon
import app.malachite.player.data.AppLog
import app.malachite.player.data.DeviceScanner
import app.malachite.player.data.LibraryRepository
import app.malachite.player.data.SettingsRepository
import app.malachite.player.data.Storage
import app.malachite.player.data.widgetLyricLook
import app.malachite.player.heal.HealingEngine
import app.malachite.player.playback.PlayerConnection
import app.malachite.player.widget.WidgetUpdater
import app.malachite.player.work.ScanScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

/** Simple manual dependency container (no DI framework – fewer moving parts). */
class AppGraph(val context: Context) {
    val settings = SettingsRepository(context)
    val library = LibraryRepository(context, settings)
    val http = Http()
    val lyricsFinder = LyricsFinder(http)
    val coverFinder = CoverFinder(http)
    val online = app.malachite.core.online.OnlineCatalog(http)
    val deviceScanner = DeviceScanner(context, settings, library)
    val healing = HealingEngine(context, settings, library, coverFinder, lyricsFinder)
    val player = PlayerConnection(context, settings, library)
    val scanScheduler = ScanScheduler(context, settings, library, deviceScanner)
    /** New songs heal themselves (when switched on and online). */
    val autoHeal = app.malachite.player.heal.AutoHeal(settings, healing).also { a -> library.onNewSongs = { paths -> a.newSongs(paths) } }

    /** Opens the main folder if it can be found and accessed. */
    fun openLibraryIfPossible(): Boolean {
        if (library.isOpen) return true
        val s = settings.value
        if (!s.onboardingDone || !Storage.hasAllFilesAccess(context)) return false
        val found = Storage.locateMainFolder(context, s.mainFolder) ?: return false
        if (found.absolutePath != s.mainFolder) settings.update { it.copy(mainFolder = found.absolutePath) }
        return openLibrary(found)
    }

    /** A protected main folder waiting for its password (the unlock screen is shown). */
    private val _lockedRoot = kotlinx.coroutines.flow.MutableStateFlow<File?>(null)
    val lockedRootState: kotlinx.coroutines.flow.StateFlow<File?> = _lockedRoot
    val lockedRoot: File? get() = _lockedRoot.value

    fun openLibrary(folder: File): Boolean {
        // a folder protected with a password is opened only when it is unlocked: reading it
        // locked could never be allowed to end in an empty library written over the real one
        if (app.malachite.player.data.Protection.prepare(context, folder, settings.value.askPasswordEveryStart) ==
            app.malachite.player.data.Protection.State.LOCKED
        ) {
            _lockedRoot.value = folder
            return false
        }
        // the unlock screen stays until the library is really open: no other screen in between
        val ok = runCatching { library.open(folder) }.getOrElse { e ->
            if (e is app.malachite.core.fs.Sealed.Locked) { _lockedRoot.value = folder; return false }
            false
        }
        _lockedRoot.value = null
        if (ok) settings.update { it.copy(mainFolder = folder.absolutePath) }
        return ok
    }

    /** The password of the locked folder; true when it fits and the folder is open. Call off the main thread. */
    fun unlock(password: CharArray): Boolean {
        val f = lockedRoot ?: return false
        if (!app.malachite.player.data.Protection.unlock(context, f, password, settings.value.askPasswordEveryStart)) return false
        return openLibrary(f)
    }

    /** Unlocked by the fingerprint: [cipher] is the one the system prompt confirmed. Call off the main thread. */
    fun unlockWithBiometric(cipher: javax.crypto.Cipher): Boolean {
        val f = lockedRoot ?: return false
        if (!app.malachite.player.data.Protection.finishBiometricUnlock(context, f, cipher)) return false
        return openLibrary(f)
    }

    /** The password is forgotten: the protected data is put aside and the folder opens with new data. */
    fun startOverWithoutProtectedData(): Boolean {
        val f = lockedRoot ?: return false
        app.malachite.player.data.Protection.startOver(context, f)
        return openLibrary(f)
    }
}

class MalachiteApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // before anything else: texts are produced as their classes load
        app.malachite.player.data.Language.apply(this)
        TagIO.configure(android = true)
        graph = AppGraph(this)
        // off unless the user switched it on; the crash handler only keeps a report when allowed
        AppLog.init(this, graph.settings.value)
        app.malachite.player.data.SoundIdKey.init(this)
        AppLog.i("app", "Запуск. " + AppLog.deviceInfo())
        // which accessibility services are on: each makes every change of the screen cost more
        app.malachite.player.data.A11y.start(this)
        createChannels()
        // a new tab appears on the panel once; removed from it, it stays removed
        graph.settings.update { s ->
            if (s.statsTabAdded) s else s.copy(statsTabAdded = true, tabs = if (app.malachite.player.data.Tab.STATS in s.tabs) s.tabs else s.tabs + app.malachite.player.data.Tab.STATS)
        }
        graph.settings.update { s ->
            if (s.addTabAdded) s else {
                val newTab = app.malachite.player.data.Tab.ADD
                // before «Процессы» when it is there, else at the end
                val at = s.tabs.indexOf(app.malachite.player.data.Tab.PROCESSES).let { if (it < 0) s.tabs.size else it }
                s.copy(addTabAdded = true, tabs = if (newTab in s.tabs) s.tabs else s.tabs.toMutableList().also { it.add(at, newTab) })
            }
        }
        graph.openLibraryIfPossible()
        graph.scanScheduler.start()
        // The widget follows the theme and the look of the lyrics as soon as they are changed
        // (it used to keep the old colours until the next song).
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            graph.settings.state
                .map { s ->
                    listOf(
                        s.themeMode, s.accent, s.customAccent, s.customBackground, s.customSurface, s.widget,
                        // font, weight, size, alignment, colours, word fill, animation: everything the lines show
                        s.widgetLyricLook(),
                        s.lineMarkStyle, s.lineMarkColor, s.lineMarkThickness,
                    )
                }
                .distinctUntilChanged()
                .drop(1)
                .collect { WidgetUpdater.requestUpdate(this@MalachiteApp) }
        }
        // The icon takes its colour when the app is left: switching it makes the launcher redraw
        // the home screen, which must not happen in the user's face.
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) = app.malachite.player.data.FreezeWatch.setOnScreen(true)

            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                app.malachite.player.data.FreezeWatch.setOnScreen(false)
                AppIcon.apply(this@MalachiteApp, graph.settings.value)
                // the widget did not follow the lyrics while the app was open: it catches up now
                WidgetUpdater.requestUpdate(this@MalachiteApp)
            }
        })
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            graph.settings.state.map { Triple(it.logEnabled, it.crashReports, it.logDetails) }.distinctUntilChanged()
                .collect { (log, crash, details) ->
                    AppLog.configure(log, crash)
                    app.malachite.player.data.FreezeWatch.setLog(log, details)
                    app.malachite.player.data.FrameWatch.setOn(log && details)
                }
        }
    }

    /** System dark / light mode changed: the widget takes its colours from the same theme. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        WidgetUpdater.requestUpdate(this)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_TASKS, tr("Исцеление и импорт"), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_NEW_MUSIC, tr("Новые песни"), NotificationManager.IMPORTANCE_DEFAULT))
    }

    companion object {
        const val CHANNEL_TASKS = "tasks"
        const val CHANNEL_NEW_MUSIC = "new_music"

        lateinit var instance: MalachiteApp
            private set

        val graph: AppGraph get() = instance.graph
    }
}
