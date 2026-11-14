package app.malachite.player.data

import app.malachite.player.MalachiteApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** What the statistics are made of, beyond the play counts every song already has. */
@Serializable
data class StatsData(
    /** When counting began (the first time anything was counted). */
    val since: Long = 0,
    /** Listening time per day, "2026-11-12" -> milliseconds. */
    val dayMs: Map<String, Long> = emptyMap(),
    /** Listening time by hour of the day, 24 values. */
    val hourMs: List<Long> = List(24) { 0L },
    /** What healing has fixed, by kind of work. */
    val healed: Map<String, Int> = emptyMap(),
) {
    val totalMs: Long get() = dayMs.values.sum()
}

/**
 * Listening statistics, kept in the main folder (`.malachite/stats.json`) – on the device only,
 * never sent anywhere. Written a few seconds after a change, not on every second of music.
 */
object Stats {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(StatsData())
    val state: StateFlow<StatsData> = _state.asStateFlow()

    @Volatile private var loadedFrom: String? = null
    private var pendingWrite: Job? = null

    private fun file(): File? = MalachiteApp.graph.library.root?.let { File(it, ".malachite/stats.json") }

    fun ensureLoaded() {
        val f = file() ?: return
        if (loadedFrom == f.path) return
        synchronized(this) {
            if (loadedFrom == f.path) return
            _state.value = try {
                if (f.isFile) json.decodeFromString(StatsData.serializer(), app.malachite.core.fs.Sealed.readText(f)) else StatsData()
            } catch (e: app.malachite.core.fs.Sealed.Locked) {
                return // protected and locked: nothing is counted (and nothing written over it)
            } catch (e: Exception) {
                StatsData()
            }
            loadedFrom = f.path
        }
    }

    private fun change(f: (StatsData) -> StatsData) {
        ensureLoaded()
        if (loadedFrom == null) return
        synchronized(this) {
            val cur = _state.value
            _state.value = f(if (cur.since == 0L) cur.copy(since = System.currentTimeMillis()) else cur)
        }
        pendingWrite?.cancel()
        pendingWrite = scope.launch {
            delay(5000)
            runCatching {
                val file = file() ?: return@launch
                file.parentFile?.mkdirs()
                app.malachite.core.library.LibraryStore.writeAtomicBytes(
                    file, app.malachite.core.fs.Sealed.encodeForWrite(json.encodeToString(StatsData.serializer(), _state.value)),
                )
            }
        }
    }

    /** [ms] of music were just listened to (counted while the player really plays). */
    fun addListening(ms: Long) {
        if (ms <= 0) return
        val day = LocalDate.now().toString()
        val hour = LocalTime.now().hour
        change { s ->
            val hours = s.hourMs.toMutableList().also { while (it.size < 24) it.add(0L) }
            hours[hour] += ms
            s.copy(dayMs = s.dayMs + (day to (s.dayMs[day] ?: 0L) + ms), hourMs = hours)
        }
    }

    fun addHealed(kind: String, count: Int) {
        if (count <= 0) return
        change { s -> s.copy(healed = s.healed + (kind to (s.healed[kind] ?: 0) + count)) }
    }
}
