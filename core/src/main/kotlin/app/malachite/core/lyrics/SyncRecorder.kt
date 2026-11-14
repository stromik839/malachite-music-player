package app.malachite.core.lyrics

/**
 * Manual "press-and-hold" lyrics syncing.
 *
 * The user listens to the song and keeps one big button pressed while a line (or a word)
 * is being sung: **press = it starts, release = it ends**. Every press/release is stored
 * against the *media position* of the player.
 *
 * Speed handling: the song may be slowed down or sped up to make catching words easier.
 * The player position is always expressed on the song's own (1x) timeline, so the stored
 * values are already 1x values. The only thing that depends on the speed is the user's
 * reaction delay: a delay of `d` real milliseconds equals `d * speed` milliseconds of song
 * at the current speed. That is what [reactionMs] compensates, so the result is identical
 * whichever speed was used while recording.
 */
class SyncRecorder(
    lines: List<String>,
    val mode: Mode = Mode.LINES,
    /** Average human reaction delay in real (wall-clock) milliseconds. */
    var reactionMs: Long = 0,
) {
    enum class Mode { LINES, WORDS }

    private data class Unit_(val line: Int, val word: Int, val text: String)

    val lines: List<String> = lines.map { it.trim() }.filter { it.isNotEmpty() }

    /** What one press/release pair covers: a whole line or a single word. */
    private val units: List<Unit_> = when (mode) {
        Mode.LINES -> this.lines.mapIndexed { i, l -> Unit_(i, -1, l) }
        Mode.WORDS -> this.lines.flatMapIndexed { i, l ->
            l.split(Regex("\\s+")).filter { it.isNotEmpty() }.mapIndexed { w, t -> Unit_(i, w, t) }
        }
    }

    private val starts = LongArray(units.size) { -1 }
    private val ends = LongArray(units.size) { -1 }

    /**
     * "♪" lines (music without singing) mark themselves: see [autoPause]. A press while one
     * waits under the cursor is for the sung line after it, the "♪" is left out.
     */
    var autoPauses: Boolean = false

    /** A "♪" that started by itself and ends with the next press; -1 if there is none. */
    private var openPause = -1

    private fun isPause(i: Int) = i in units.indices && isPauseText(units[i].text)

    /** Index of the unit that the next press will start. */
    var cursor = 0
        private set

    /** True while the button is held. */
    var holding = false
        private set

    val total get() = units.size
    val isComplete get() = cursor >= units.size && !holding

    /** Line index the user is currently at (for highlighting in the editor). */
    val currentLine: Int get() = units.getOrNull(if (holding) cursor - 1 else cursor)?.line ?: lines.size
    val currentWord: Int get() = units.getOrNull(if (holding) cursor - 1 else cursor)?.word ?: -1
    val currentText: String get() = units.getOrNull(if (holding) cursor - 1 else cursor)?.text ?: ""

    private fun compensate(positionMs: Long, speed: Float): Long =
        (positionMs - (reactionMs * speed).toLong()).coerceAtLeast(0)

    /** Button pressed at media position [positionMs] while playing at [speed]. */
    fun press(positionMs: Long, speed: Float) {
        if (holding || cursor >= units.size) return
        // a "♪" that has not started by itself yet: the singing goes on, the press is for the next line
        if (autoPauses) while (cursor < units.size && isPause(cursor)) {
            starts[cursor] = -1
            ends[cursor] = -1
            cursor++
        }
        if (cursor >= units.size) return
        val t = compensate(positionMs, speed)
        // the music in between ends where the singing starts again
        if (openPause in units.indices) {
            ends[openPause] = maxOf(t, starts[openPause] + 50)
            openPause = -1
        }
        // Never go back in time relative to the previous unit.
        val prevEnd = if (cursor > 0) maxOf(starts[cursor - 1], ends[cursor - 1]) else 0
        starts[cursor] = maxOf(t, prevEnd)
        ends[cursor] = -1
        cursor++
        holding = true
    }

    /** Button released. */
    fun release(positionMs: Long, speed: Float) {
        if (!holding) return
        val i = cursor - 1
        val t = compensate(positionMs, speed)
        ends[i] = maxOf(t, starts[i] + 50)
        holding = false
    }

    /**
     * Called while the song plays. When the line under the cursor is a "♪" and nothing has been
     * pressed for [waitMs] of real time since the singing stopped, the "♪" starts by itself where
     * the singing stopped (at 0 for an intro) and lasts until the next press. Returns true when it
     * did. Pressing earlier never starts it, so short breaths between lines do not count.
     */
    fun autoPause(positionMs: Long, speed: Float, waitMs: Long): Boolean {
        if (!autoPauses || holding || waitMs <= 0 || cursor >= units.size || !isPause(cursor)) return false
        val stopped = lastMarkBefore(cursor)
        if ((positionMs - stopped) / speed.coerceAtLeast(0.1f) < waitMs) return false
        starts[cursor] = stopped
        ends[cursor] = -1
        openPause = cursor
        cursor++
        return true
    }

    /** Where the last recorded unit before [index] ended (0 when nothing is recorded yet). */
    private fun lastMarkBefore(index: Int): Long {
        for (i in index - 1 downTo 0) {
            val m = maxOf(starts[i], ends[i])
            if (m >= 0) return m
        }
        return 0
    }

    /** Undo the last press (and its release). Returns the media position to seek back to. */
    fun undo(): Long {
        if (holding) { holding = false }
        if (cursor == 0) return 0
        cursor--
        if (openPause == cursor) openPause = -1
        val back = starts[cursor]
        starts[cursor] = -1
        ends[cursor] = -1
        // the line after a "♪" is taken back: the music lasts again until the next press
        if (cursor > 0 && isPause(cursor - 1) && starts[cursor - 1] >= 0) {
            ends[cursor - 1] = -1
            openPause = cursor - 1
        }
        val anchor = if (back >= 0) back else (if (cursor > 0) starts[cursor - 1] else 0)
        return (anchor - 2500).coerceAtLeast(0)
    }

    /** Skip a line/word that is not sung (keeps it unsynced, it will be dropped). */
    fun skip() {
        if (holding || cursor >= units.size) return
        cursor++
    }

    /** Builds the result. Units that were skipped are left out. */
    fun result(source: String = "Malachite sync"): Lyrics {
        val out = ArrayList<LyricLine>()
        when (mode) {
            Mode.LINES -> for ((i, u) in units.withIndex()) {
                if (starts[i] < 0) continue
                out += LyricLine(starts[i], ends[i].takeIf { it >= 0 }, u.text)
            }
            Mode.WORDS -> {
                val byLine = units.indices.filter { starts[it] >= 0 }.groupBy { units[it].line }
                for ((line, idx) in byLine.toSortedMap()) {
                    val words = idx.map { LyricWord(starts[it], ends[it].takeIf { e -> e >= 0 }, units[it].text) }
                    out += LyricLine(words.first().startMs, words.last().endMs, lines[line], words)
                }
            }
        }
        out.sortBy { it.startMs }
        return Lyrics(out, synced = true, source = source)
    }

    // ------------------------------------------------------------------ continuing later

    /** What is needed to continue an unfinished recording: the marks made so far. */
    class Saved(val cursor: Int, val starts: List<Long>, val ends: List<Long>)

    fun save(): Saved = Saved(cursor, starts.toList(), ends.toList())

    /**
     * Continues from [saved]. False (and nothing changes) if the marks do not belong to these
     * lines – the text or the mode was changed since they were made.
     */
    fun restore(saved: Saved): Boolean {
        if (saved.starts.size != units.size || saved.ends.size != units.size) return false
        for (i in units.indices) {
            starts[i] = saved.starts[i]
            ends[i] = saved.ends[i]
        }
        cursor = saved.cursor.coerceIn(0, units.size)
        holding = false
        openPause = (cursor - 1).takeIf { it >= 0 && isPause(it) && starts[it] >= 0 && ends[it] < 0 } ?: -1
        return true
    }

    /** The position of the last mark made, to go on from; 0 if there is none. */
    val lastMarkMs: Long get() = maxOf(starts.maxOrNull() ?: 0L, ends.maxOrNull() ?: 0L).coerceAtLeast(0)

    companion object {
        private val PAUSE = Regex("^[\\s♪♫♬♩….·•\\-–—~*]*[♪♫♬♩][\\s♪♫♬♩….·•\\-–—~*]*$")
        // a sung line may be just "Музыка": a word counts only in brackets, or the plain "instrumental"
        private val PAUSE_WORDS = Regex("(?i)^(?:[\\[(]\\s*(?:instrumental|инструментал|проигрыш|music|музыка)\\s*[\\])]|instrumental|инструментал|проигрыш)$") // i18n:off

        /** "♪", "♫ ♫", "[Instrumental]", "(проигрыш)": a line of music without singing. */
        fun isPauseText(text: String): Boolean {
            val t = text.trim()
            return t.isNotEmpty() && (PAUSE.matches(t) || PAUSE_WORDS.matches(t))
        }
    }

    /** Raw timings for display: list of (start, end) per unit, -1 = not recorded. */
    fun timings(): List<Pair<Long, Long>> = units.indices.map { starts[it] to ends[it] }
}
