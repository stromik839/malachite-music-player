package app.malachite.player.data

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window

/**
 * The detailed log ("how smooth is it really"): with it on, the log gets every few seconds what
 * the screen did – how many frames were drawn, how many frames a second while something moved,
 * at what refresh rate the screen ran, how many frames came late and what the worst one spent
 * its time on (measuring, drawing, the graphics chip). Also which screen was open, slow pieces of
 * work on the main thread, the display modes and the memory in use. Nothing runs while it is off.
 */
object FrameWatch {
    private const val REPORT_EVERY_MS = 6_000L
    /** A piece of main-thread work this long goes into the log (a frame at 90 Hz is 11 ms). */
    private const val SLOW_MESSAGE_MS = 40L
    private const val MAX_SLOW_MESSAGES = 3000
    private const val MEMORY_EVERY_MS = 60_000L
    /** While a piece of work runs longer than this, the main thread's stack is read again and again. */
    private const val SAMPLE_AFTER_MS = 12L
    private const val SAMPLE_EVERY_MS = 6L

    @Volatile private var on = false

    /** The detailed log is on: other parts add their own details (player events, timings). */
    val isOn: Boolean get() = on
    private var window: Window? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null

    /** The screen being shown (set by the app's frame), for the reports. */
    @Volatile var screen: String = ""
        set(value) {
            if (value == field) return
            field = value
            if (on) AppLog.i("ui", "экран: $value")
        }

    fun setOn(value: Boolean) {
        if (value == on) return
        on = value
        val w = window
        if (value) {
            AppLog.i("frames", "Подробный журнал включён: " + deviceLine())
            if (w != null) start(w)
            watchMainThread(true)
        } else {
            if (w != null) stop(w)
            watchMainThread(false)
        }
    }

    /** The app's window came on the screen. */
    fun attach(w: Window) {
        window = w
        if (on) start(w)
    }

    /** The app's window left the screen. */
    fun detach(w: Window) {
        if (window === w) { stop(w); window = null }
    }

    // ------------------------------------------------------------------ frames

    private class Period {
        var started = SystemClock.uptimeMillis()
        var frames = 0
        var late = 0
        var veryLate = 0
        var moving = 0
        var movingNs = 0L
        var lastVsync = 0L
        var worstNs = 0L
        var worst: LongArray? = null
        val intervals = ArrayList<Long>()
        var over50 = 0
        var over100 = 0
        /** The slowest frames of the period: [total, input, animation, layout, draw, sync+issue+swap, gpu, unknown delay]. */
        val slowest = ArrayList<LongArray>()
    }

    private var period = Period()
    private var lastMemory = 0L
    /** The screen's refresh rate when the window came (frames before Android 12 do not tell it). */
    @Volatile private var refreshHz = 60f
    private val ADDRESS = Regex("\\{[0-9a-f]+\\}|@[0-9a-f]+")

    @Synchronized
    private fun start(w: Window) {
        if (listener != null) return
        logDisplay(w)
        val t = HandlerThread("malachite-frames", android.os.Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        val h = Handler(t.looper)
        thread = t
        handler = h
        period = Period()
        val l = Window.OnFrameMetricsAvailableListener { _, metrics, _ -> runCatching { frame(metrics) } }
        listener = l
        runCatching { w.addOnFrameMetricsAvailableListener(l, h) }
    }

    @Synchronized
    private fun stop(w: Window) {
        val l = listener ?: return
        runCatching { w.removeOnFrameMetricsAvailableListener(l) }
        listener = null
        handler?.post { report(force = true) }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun frame(m: FrameMetrics) {
        if (m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) return
        val p = period
        val total = m.getMetric(FrameMetrics.TOTAL_DURATION)
        val vsync = m.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
        val budget = frameBudgetNs(m)
        p.frames++
        if (total > budget) p.late++
        if (total > budget * 2) p.veryLate++
        if (p.lastVsync > 0) {
            val gap = vsync - p.lastVsync
            // frames one after another: something is moving (a gap means the screen was still)
            if (gap in 1..(budget * 3)) {
                p.moving++
                p.movingNs += gap
                if (p.intervals.size < 2000) p.intervals += gap
            }
        }
        p.lastVsync = vsync
        if (total > 50_000_000L) p.over50++
        if (total > 100_000_000L) p.over100++
        if (total > budget * 2) {
            val parts = longArrayOf(
                total,
                m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION),
                m.getMetric(FrameMetrics.ANIMATION_DURATION),
                m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                m.getMetric(FrameMetrics.DRAW_DURATION),
                m.getMetric(FrameMetrics.SYNC_DURATION) + m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) + m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
                if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.GPU_DURATION) else -1L,
                m.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION),
                vsync,
            )
            p.slowest += parts
            p.slowest.sortByDescending { it[0] }
            if (p.slowest.size > 5) p.slowest.removeAt(p.slowest.size - 1)
        }
        if (total > p.worstNs) {
            p.worstNs = total
            p.worst = longArrayOf(
                m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION),
                m.getMetric(FrameMetrics.ANIMATION_DURATION),
                m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                m.getMetric(FrameMetrics.DRAW_DURATION),
                m.getMetric(FrameMetrics.SYNC_DURATION) + m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) + m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION),
                if (Build.VERSION.SDK_INT >= 31) m.getMetric(FrameMetrics.GPU_DURATION) else -1L,
                m.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION),
            )
        }
        report(force = false)
    }

    /** How long one frame may take: the screen's own interval (from the frame itself on Android 12+). */
    private fun frameBudgetNs(m: FrameMetrics): Long {
        if (Build.VERSION.SDK_INT >= 31) {
            val d = m.getMetric(FrameMetrics.DEADLINE)
            if (d in 4_000_000L..50_000_000L) return d
        }
        return (1_000_000_000.0 / refreshHz.coerceIn(30f, 240f)).toLong()
    }

    private fun report(force: Boolean) {
        val p = period
        val now = SystemClock.uptimeMillis()
        if (!force && now - p.started < REPORT_EVERY_MS) return
        period = Period()
        if (p.frames >= 5) {
            val secs = (now - p.started) / 1000.0
            val fps = if (p.movingNs > 0) p.moving * 1e9 / p.movingNs else 0.0
            val typical = p.intervals.sorted().let { if (it.isEmpty()) 0L else it[it.size / 2] }
            val hz = if (typical > 0) 1e9 / typical else 0.0
            val w = p.worst
            val parts = if (w == null) "" else listOfNotNull(
                "ввод ${ms(w[0])}", "анимация ${ms(w[1])}", "разметка ${ms(w[2])}", "рисование ${ms(w[3])}",
                "отправка ${ms(w[4])}", if (w[5] >= 0) "GPU ${ms(w[5])}" else null, "ожидание ${ms(w[6])}",
            ).joinToString(", ")
            val clock = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            val slow = if (p.slowest.isEmpty()) "" else "\n  самые долгие кадры:" + p.slowest.joinToString("") { f ->
                // the frame's own moment on the log's clock, to be found among the other lines
                val wall = System.currentTimeMillis() - (System.nanoTime() - f[8]) / 1_000_000
                "\n    ${clock.format(java.util.Date(wall))}: ${ms(f[0])} мс — " +
                    "ввод ${ms(f[1])}, анимация ${ms(f[2])}, разметка ${ms(f[3])}, рисование ${ms(f[4])}, отправка ${ms(f[5])}" +
                    (if (f[6] >= 0) ", GPU ${ms(f[6])}" else "") + ", ожидание ${ms(f[7])}"
            }
            AppLog.i(
                "frames",
                "«$screen»: ${p.frames} кадров за ${"%.1f".format(secs)} с; в движении ${"%.0f".format(fps)} к/с" +
                    (if (hz > 0) ", кадр обычно через ${"%.1f".format(typical / 1e6)} мс (≈${"%.0f".format(hz)} Гц)" else "") +
                    "; опоздали ${p.late} (${p.late * 100 / p.frames}%), сильно ${p.veryLate}, дольше 50 мс ${p.over50}, дольше 100 мс ${p.over100}" +
                    "; худший ${ms(p.worstNs)} мс" + (if (parts.isNotEmpty()) " ($parts)" else "") + slow,
            )
        }
        if (now - lastMemory >= MEMORY_EVERY_MS) {
            lastMemory = now
            val rt = Runtime.getRuntime()
            AppLog.i(
                "frames",
                "память: Java ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} из ${rt.maxMemory() / 1_048_576} МБ, " +
                    "native ${android.os.Debug.getNativeHeapAllocatedSize() / 1_048_576} МБ, потоков ${Thread.activeCount()}",
            )
        }
    }

    private fun ms(ns: Long) = "%.0f".format(ns / 1e6)

    /** The display modes and the one asked for: does the phone really give the app 90 / 120 Hz? */
    private fun logDisplay(w: Window) {
        runCatching {
            @Suppress("DEPRECATION")
            val d = w.decorView.display ?: w.windowManager.defaultDisplay ?: return
            refreshHz = d.refreshRate
            val modes = d.supportedModes.joinToString(", ") { "${it.modeId}: ${it.physicalWidth}×${it.physicalHeight} ${"%.0f".format(it.refreshRate)} Гц" }
            val asked = w.attributes.preferredDisplayModeId
            AppLog.i(
                "frames",
                "экран сейчас ${"%.0f".format(d.refreshRate)} Гц (режим ${d.mode.modeId}); режимы: $modes; " +
                    "приложение просит режим ${if (asked == 0) "по выбору системы" else asked.toString()}; Android ${Build.VERSION.SDK_INT}",
            )
        }
    }

    // ------------------------------------------------------------------ the main thread

    private var slowMessages = 0
    /** The main thread's piece of work now: its number, when it started (0: none), what it is. */
    @Volatile private var taskSeq = 0L
    @Volatile private var taskStart = 0L
    @Volatile private var taskWhat = ""
    private class Ended(val seq: Long, val took: Long, val what: String, val at: Long)
    private val ended = java.util.concurrent.ConcurrentLinkedQueue<Ended>()
    /** Stacks read while a piece of work ran long, by its number. */
    private val samples = java.util.concurrent.ConcurrentHashMap<Long, MutableList<Array<StackTraceElement>>>()
    private var sampler: Thread? = null

    /**
     * Each piece of work of the main thread is timed. While one runs long, another thread reads the
     * main thread's stack every few milliseconds; when it ends, the log gets how long it took, what
     * it was ("Choreographer" is a frame, "MediaSession" the notification…) and where the time went:
     * the stacks seen most often, with how many times each was seen. One stack read at a random
     * moment (as before) often showed only where the work happened to be, not what took the time.
     */
    @Synchronized
    private fun watchMainThread(enable: Boolean) {
        val looper = Looper.getMainLooper()
        if (!enable) {
            looper.setMessageLogging(null)
            sampler?.interrupt()
            sampler = null
            return
        }
        slowMessages = 0
        looper.setMessageLogging { line ->
            if (line.startsWith(">")) {
                taskSeq++
                taskWhat = line
                taskStart = SystemClock.uptimeMillis()
            } else if (line.startsWith("<") && taskStart > 0) {
                val now = SystemClock.uptimeMillis()
                val took = now - taskStart
                taskStart = 0
                if (took >= SLOW_MESSAGE_MS) ended.offer(Ended(taskSeq, took, taskWhat, now))
            }
        }
        if (sampler == null) {
            sampler = Thread(::sample, "malachite-main-sampler").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY - 1
                start()
            }
        }
    }

    private fun sample() {
        val me = Thread.currentThread()
        val main = Looper.getMainLooper().thread
        try {
            while (!me.isInterrupted) {
                val start = taskStart
                val seq = taskSeq
                if (start > 0 && SystemClock.uptimeMillis() - start >= SAMPLE_AFTER_MS) {
                    val stack = main.stackTrace
                    if (taskSeq == seq && taskStart == start) {
                        val list = samples.getOrPut(seq) { ArrayList() }
                        if (list.size < 400) list += stack
                    }
                    Thread.sleep(SAMPLE_EVERY_MS)
                } else {
                    Thread.sleep(3)
                }
                while (true) {
                    val e = ended.poll() ?: break
                    val got = samples.remove(e.seq)
                    if (slowMessages < MAX_SLOW_MESSAGES) {
                        slowMessages++
                        runCatching { reportTask(e, got.orEmpty()) }
                        if (slowMessages == MAX_SLOW_MESSAGES) AppLog.i("main", "записано $MAX_SLOW_MESSAGES долгих задач, дальше до следующего запуска не записываются")
                    }
                }
                // stacks of work that ended quickly enough are not needed
                if (samples.size > 4) samples.keys.filter { it < taskSeq - 2 }.forEach { samples.remove(it) }
            }
        } catch (_: InterruptedException) {
        }
    }

    /** Frames that say nothing about what the work was. */
    private fun boring(f: StackTraceElement): Boolean {
        val c = f.className
        return c.startsWith("android.os.Looper") || c.startsWith("android.app.ActivityThread") ||
            c.startsWith("com.android.internal.os.") || c.startsWith("java.lang.reflect.Method")
    }

    private fun reportTask(e: Ended, stacks: List<Array<StackTraceElement>>) {
        // ">>>>> Dispatching to Handler (android.view.Choreographer$FrameHandler) {…} android.view.Choreographer$FrameDisplayEventReceiver@…: 0"
        val what = e.what.substringAfter("Handler (", e.what).replace(ADDRESS, "").take(200)
        val sb = StringBuilder("главный поток занят ${e.took} мс: $what")
        if (stacks.isEmpty()) {
            AppLog.i("main", sb.toString())
            return
        }
        sb.append("; снимков стека ${stacks.size} (каждые $SAMPLE_EVERY_MS мс)")
        // the same place in the code: the top frames, the boring bottom cut off
        val trimmed = stacks.map { st -> st.takeWhile { !boring(it) }.ifEmpty { st.toList() } }
        val groups = trimmed.groupBy { st -> st.take(10).joinToString("|") { "${it.className}.${it.methodName}:${it.lineNumber}" } }
            .values.sortedByDescending { it.size }
        for (g in groups.take(4)) {
            val pct = g.size * 100 / stacks.size
            sb.append("\n  ${g.size}× ($pct%):")
            for (f in g.first().take(28)) sb.append("\n    at ").append(f)
        }
        if (groups.size > 4) sb.append("\n  …и ещё ${groups.size - 4} мест по ${groups.drop(4).sumOf { it.size }} снимков")
        // which of the app's own code was on the stack, and how often (anywhere in it)
        val own = HashMap<String, Int>()
        for (st in trimmed) {
            st.filter { it.className.startsWith("app.malachite") }
                .map { it.className.substringAfterLast('.').substringBefore('$') + "." + it.methodName }
                .distinct().forEach { own[it] = (own[it] ?: 0) + 1 }
        }
        if (own.isNotEmpty()) {
            sb.append("\n  свой код в снимках: ")
            sb.append(own.entries.sortedByDescending { it.value }.take(12).joinToString(", ") { "${it.key} ×${it.value}" })
        }
        AppLog.i("main", sb.toString())
    }

    /** The phone and its state, at the start of the detailed log. */
    private fun deviceLine(): String = runCatching {
        val ctx = app.malachite.player.MalachiteApp.graph.context
        val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mem = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val thermal = if (Build.VERSION.SDK_INT >= 29) ", нагрев ${pm.currentThermalStatus} (0 — норма)" else ""
        "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "${Build.SUPPORTED_ABIS.firstOrNull()}, ядер ${Runtime.getRuntime().availableProcessors()}, " +
            "память ${mem.availMem / 1_048_576} свободно из ${mem.totalMem / 1_048_576} МБ${if (mem.lowMemory) " (мало!)" else ""}, " +
            "экономия энергии ${if (pm.isPowerSaveMode) "включена" else "выключена"}$thermal"
    }.getOrDefault("")
}
