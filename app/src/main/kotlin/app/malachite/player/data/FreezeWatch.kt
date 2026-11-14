package app.malachite.player.data

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * While the log is on and the app is on the screen: notes in the log when the main thread (the
 * one that draws the screen and answers taps) was stuck for a noticeable time, together with
 * what it was busy with. "It lags" in a sent log then says where. With the log off, or the app
 * not on the screen, nothing runs.
 */
object FreezeWatch {
    /** A stall shorter than this is not noted (a frame is 16 ms; a person notices ~0.3 s); half of it in the detailed log. */
    private val STUCK_MS get() = if (details) 150L else 300L
    private const val PAUSE_MS = 150L
    private val MAX_NOTES get() = if (details) 120 else 40
    @Volatile private var details = false

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var beat = 0L
    @Volatile private var logOn = false
    @Volatile private var onScreen = false
    private var thread: Thread? = null
    private var notes = 0

    fun setLog(on: Boolean, detailed: Boolean = false) { logOn = on; details = detailed; update() }
    fun setOnScreen(on: Boolean) { onScreen = on; update() }

    @Synchronized
    private fun update() {
        val want = logOn && onScreen && notes < MAX_NOTES
        if (want && thread == null) {
            thread = Thread(::watch, "malachite-freeze-watch").apply { isDaemon = true; start() }
        } else if (!want) {
            thread?.interrupt()
            thread = null
        }
    }

    private fun watch() {
        val me = Thread.currentThread()
        try {
            while (!me.isInterrupted) {
                val sent = SystemClock.uptimeMillis()
                val id = sent
                main.post { beat = id }
                var stack: Array<StackTraceElement>? = null
                while (beat != id) {
                    Thread.sleep(40)
                    if (stack == null && SystemClock.uptimeMillis() - sent >= STUCK_MS) stack = Looper.getMainLooper().thread.stackTrace
                }
                val took = SystemClock.uptimeMillis() - sent
                if (stack != null && took >= STUCK_MS) note(took, stack)
                Thread.sleep(PAUSE_MS)
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun note(took: Long, stack: Array<StackTraceElement>) {
        val frames = stack.take(18).joinToString("\n") { "    at $it" }
        AppLog.w("freeze", "Экран не отвечал $took мс. Главный поток в это время:\n$frames")
        synchronized(this) { notes++ }
        if (notes >= MAX_NOTES) {
            AppLog.i("freeze", "Записано $MAX_NOTES остановок, дальше не записываются до следующего запуска")
            update()
        }
    }
}
