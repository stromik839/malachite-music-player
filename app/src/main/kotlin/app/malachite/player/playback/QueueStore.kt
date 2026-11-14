package app.malachite.player.playback

import android.content.Context
import android.media.audiofx.Equalizer
import app.malachite.core.library.LibraryStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Remembers the queue and position so playback resumes where it stopped. */
object QueueStore {
    @Serializable
    data class Saved(val paths: List<String>, val index: Int, val positionMs: Long, val shuffle: Boolean = false, val repeatMode: Int = 0)

    private val json = Json { ignoreUnknownKeys = true }
    private fun file(c: Context) = File(c.filesDir, "queue.json")

    fun save(c: Context, s: Saved) {
        runCatching { LibraryStore.writeAtomic(file(c), json.encodeToString(Saved.serializer(), s)) }
    }

    /**
     * One thread for the queue file: the write waits for the disk (on some phones for seconds),
     * which on the main thread froze the buttons, the notification and the widget. Of saves that
     * pile up only the newest is written.
     */
    val io: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "malachite-queue").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    }
    private val pending = java.util.concurrent.atomic.AtomicReference<Saved?>(null)

    fun saveSoon(c: Context, s: Saved) {
        val ctx = c.applicationContext
        if (pending.getAndSet(s) != null) return // a write is queued already: it takes this one
        io.execute { pending.getAndSet(null)?.let { save(ctx, it) } }
    }

    fun load(c: Context): Saved? = runCatching {
        file(c).takeIf { it.isFile }?.let { json.decodeFromString(Saved.serializer(), it.readText()) }
    }.getOrNull()

    /** Paths changed by healing (moves / renames). */
    fun remap(c: Context, moves: Map<String, String>) {
        val s = load(c) ?: return
        save(c, s.copy(paths = s.paths.map { moves[it] ?: it }))
    }
}

/**
 * System equalizer attached to the player's audio session. Some ROMs have no equalizer or
 * forbid it – then everything here silently does nothing.
 */
object AudioEffects {
    private var eq: Equalizer? = null
    private var prefs: android.content.SharedPreferences? = null

    val available get() = eq != null
    var enabled: Boolean
        get() = prefs?.getBoolean("eq_on", false) ?: false
        set(v) {
            prefs?.edit()?.putBoolean("eq_on", v)?.apply()
            runCatching { eq?.enabled = v }
        }

    fun attach(context: Context, sessionId: Int) {
        prefs = context.getSharedPreferences("equalizer", Context.MODE_PRIVATE)
        release()
        if (sessionId == 0) return
        eq = runCatching { Equalizer(0, sessionId) }.getOrNull()
        val e = eq ?: return
        runCatching {
            val p = prefs!!
            for (b in 0 until e.numberOfBands) {
                val lvl = p.getInt("band_$b", Int.MIN_VALUE)
                if (lvl != Int.MIN_VALUE) e.setBandLevel(b.toShort(), lvl.toShort())
            }
            e.enabled = enabled
        }
    }

    fun release() {
        runCatching { eq?.release() }
        eq = null
    }

    fun bands(): List<Pair<Int, Int>> {
        val e = eq ?: return emptyList()
        return runCatching {
            (0 until e.numberOfBands).map { b -> e.getCenterFreq(b.toShort()) / 1000 to e.getBandLevel(b.toShort()).toInt() }
        }.getOrDefault(emptyList())
    }

    fun range(): Pair<Int, Int> {
        val r = runCatching { eq?.bandLevelRange }.getOrNull() ?: return -1500 to 1500
        return r[0].toInt() to r[1].toInt()
    }

    fun setBand(band: Int, level: Int) {
        runCatching { eq?.setBandLevel(band.toShort(), level.toShort()) }
        prefs?.edit()?.putInt("band_$band", level)?.apply()
    }

    fun presets(): List<String> {
        val e = eq ?: return emptyList()
        return runCatching { (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) } }.getOrDefault(emptyList())
    }

    fun usePreset(i: Int) {
        runCatching {
            val e = eq ?: return
            e.usePreset(i.toShort())
            for (b in 0 until e.numberOfBands) prefs?.edit()?.putInt("band_$b", e.getBandLevel(b.toShort()).toInt())?.apply()
        }
    }

    fun reset() {
        val e = eq ?: return
        runCatching { for (b in 0 until e.numberOfBands) setBand(b, 0) }
    }
}
