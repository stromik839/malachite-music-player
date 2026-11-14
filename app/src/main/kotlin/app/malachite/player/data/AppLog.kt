package app.malachite.player.data

import android.content.Context
import android.os.Build
import android.os.Environment
import app.malachite.core.CoreLog
import app.malachite.player.Credits
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Diagnostics, both switched OFF until the user turns them on:
 *
 *  - the **log**: what the player did and what went wrong, appended to a text file in the
 *    Download folder, so it is easy to find and attach to a bug report;
 *  - the **crash report**: when the app dies from an error, the reason is kept in the app's
 *    private storage and shown at the next start, where the user decides what to do with it.
 *
 * Nothing is ever sent anywhere by the app itself.
 */
object AppLog {
    private const val LOG_NAME = "Malachite-log.txt"
    private const val OLD_NAME = "Malachite-log.old.txt"
    private const val CRASH_NAME = "last-crash.txt"
    private const val MAX_BYTES = 3_000_000L

    @Volatile var enabled = false
        private set
    @Volatile var crashReports = false
        private set

    private var app: Context? = null
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "malachite-log").apply { isDaemon = true } }
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context, settings: AppSettings) {
        app = context.applicationContext
        installCrashHandler()
        CoreLog.sink = { tag, message, error -> if (enabled) write("I", tag, message, error) }
        configure(settings.logEnabled, settings.crashReports)
    }

    fun configure(log: Boolean, crash: Boolean) {
        val was = enabled
        enabled = log
        crashReports = crash
        if (log && !was) i("log", "Журнал включён. " + deviceInfo())
    }

    fun i(tag: String, message: String) { if (enabled) write("I", tag, message, null) }
    fun w(tag: String, message: String, error: Throwable? = null) { if (enabled) write("W", tag, message, error) }
    fun e(tag: String, message: String, error: Throwable? = null) { if (enabled) write("E", tag, message, error) }

    fun deviceInfo(): String {
        val c = app
        val version = runCatching {
            val p = c!!.packageManager.getPackageInfo(c.packageName, 0)
            "${p.versionName} (${if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else 0})"
        }.getOrDefault("?")
        return "${Credits.APP_NAME} $version, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"
    }

    /** `Download/Malachite-log.txt`; the app's own folder if Download cannot be written. */
    fun logFile(): File {
        val downloads = runCatching { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }.getOrNull()
        if (downloads != null && (downloads.isDirectory || downloads.mkdirs()) && downloads.canWrite()) return File(downloads, LOG_NAME)
        val own = app?.getExternalFilesDir(null) ?: app?.filesDir ?: File(".")
        return File(own, LOG_NAME)
    }

    private fun write(level: String, tag: String, message: String, error: Throwable?) {
        val at = System.currentTimeMillis()
        val trace = error?.let { stackTrace(it) }
        // the time is written on the log's own thread: the date format must not be used by two threads at once
        io.execute {
            runCatching {
                append(buildString {
                    append(time.format(Date(at))).append(' ').append(level).append(' ').append(tag).append(": ").append(message).append('\n')
                    if (trace != null) append(trace).append('\n')
                })
            }
        }
    }

    private fun append(text: String) {
        val f = logFile()
        if (f.length() > MAX_BYTES) {
            val old = File(f.parentFile, OLD_NAME)
            old.delete()
            f.renameTo(old)
        }
        f.appendText(text)
    }

    /** The last [maxChars] of the log (for "share"), or null if there is no log. */
    fun tail(maxChars: Int = 12_000): String? = runCatching {
        val f = logFile()
        if (!f.isFile) return null
        val text = f.readText()
        if (text.length <= maxChars) text else "…\n" + text.takeLast(maxChars)
    }.getOrNull()

    fun clear() {
        io.execute {
            runCatching {
                val f = logFile()
                f.delete()
                File(f.parentFile, OLD_NAME).delete()
            }
        }
    }

    // ------------------------------------------------------------------ crashes

    private fun stackTrace(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString().trimEnd()

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val text = "${deviceInfo()}\n${time.format(Date())}, поток «${thread.name}»\n\n${stackTrace(error)}\n"
                if (crashReports) app?.let { File(it.filesDir, CRASH_NAME).writeText(text) }
                if (enabled) runCatching { logFile().appendText("\n===== ПЛЕЕР ЗАКРЫЛСЯ ИЗ-ЗА ОШИБКИ =====\n$text\n") }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The report of the last crash, if one was saved and not yet handled. */
    fun pendingCrash(): String? = runCatching { app?.let { File(it.filesDir, CRASH_NAME) }?.takeIf { it.isFile }?.readText() }.getOrNull()

    fun clearCrash() { runCatching { app?.let { File(it.filesDir, CRASH_NAME).delete() } } }

    /** Address of a new GitHub issue with the text already filled in (shortened to what a link can carry). */
    fun issueUrl(title: String, body: String): String {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        return Credits.ISSUES_URL + "/new?title=" + enc(title) + "&body=" + enc(body.take(5000))
    }
}
