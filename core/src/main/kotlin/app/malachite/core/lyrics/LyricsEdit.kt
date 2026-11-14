package app.malachite.core.lyrics

/**
 * Small corrections of synced lyrics without syncing the whole song again: other words in a
 * line, a line added or removed, and syncing again only from a chosen line onward.
 */
object LyricsEdit {
    private val SPACES = Regex("\\s+")

    fun words(text: String): List<String> = text.trim().split(SPACES).filter { it.isNotEmpty() }

    /**
     * [line] with the words of [text]. When the number of words stays the same (a typo, a word
     * replaced by another, two words swapped) every word keeps its time. Otherwise the words get
     * times spread over the line by their length – close, but worth syncing again.
     */
    fun retext(line: LyricLine, text: String, nextStartMs: Long?): LyricLine {
        val newWords = words(text)
        val clean = newWords.joinToString(" ")
        if (line.words.isEmpty() || newWords.isEmpty()) return line.copy(text = clean, words = emptyList())
        if (newWords.size == line.words.size) {
            return line.copy(text = clean, words = line.words.mapIndexed { k, w -> w.copy(text = newWords[k]) })
        }
        val end = line.endMs ?: line.words.last().endMs ?: nextStartMs ?: (line.words.last().startMs + 600)
        return line.copy(text = clean, words = spread(newWords, line.startMs, maxOf(end, line.startMs + 100L * newWords.size)))
    }

    /** True when [retext] has to guess the times of the words of [line] for [text]. */
    fun timingGuessed(line: LyricLine, text: String): Boolean =
        line.words.isNotEmpty() && words(text).size != line.words.size

    /** Words over [start]..[end], each getting time by its length (a space counts as a letter). */
    fun spread(words: List<String>, start: Long, end: Long): List<LyricWord> {
        val weights = words.map { it.length + 1 }
        val total = weights.sum().coerceAtLeast(1)
        val span = (end - start).coerceAtLeast(1)
        var at = start
        var acc = 0
        return words.mapIndexed { k, w ->
            acc += weights[k]
            val next = start + span * acc / total
            LyricWord(at, next, w).also { at = next }
        }
    }

    /** A new line after [index] (-1 = at the very beginning), timed between its neighbours. */
    fun insertAfter(lines: List<LyricLine>, index: Int, text: String): List<LyricLine> {
        val prev = lines.getOrNull(index)
        val next = lines.getOrNull(index + 1)
        val start = when {
            prev == null -> ((next?.startMs ?: 2000L) - 1000L).coerceAtLeast(0)
            next == null -> (prev.endMs ?: prev.startMs) + 1000L
            else -> (maxOf(prev.endMs ?: prev.startMs, prev.startMs) + next.startMs) / 2
        }
        val line = LyricLine(start, null, words(text).joinToString(" "))
        return lines.toMutableList().also { it.add(index + 1, line) }
    }

    fun delete(lines: List<LyricLine>, index: Int): List<LyricLine> = lines.filterIndexed { i, _ -> i != index }

    /** What the recorder needs to sync again from line [from]: the lines and the marks kept before it. */
    class Resync(val lines: List<String>, val wordMode: Boolean, val saved: SyncRecorder.Saved)

    /**
     * Everything before line [from] keeps its times (moved by [offsetMs] onto the song's own
     * timeline, where the recorder works); line [from] and the rest are recorded again.
     */
    fun resyncFrom(lines: List<LyricLine>, offsetMs: Long, from: Int, wordMode: Boolean): Resync {
        val kept = lines.filter { it.text.isNotBlank() }
        val texts = kept.map { it.text.trim() }
        val starts = ArrayList<Long>()
        val ends = ArrayList<Long>()
        var cursor = 0
        for ((i, line) in kept.withIndex()) {
            val units = if (wordMode) words(line.text) else listOf(line.text)
            val before = i < from
            if (!before) {
                repeat(units.size) { starts += -1L; ends += -1L }
                continue
            }
            val next = kept.getOrNull(i + 1)?.startMs
            if (!wordMode) {
                starts += line.startMs + offsetMs
                ends += (line.endMs ?: next?.let { it - 50 } ?: (line.startMs + 2000)) + offsetMs
            } else {
                val timed = if (line.words.size == units.size) line.words
                else spread(units, line.startMs, line.endMs ?: line.words.lastOrNull()?.endMs ?: next ?: (line.startMs + 400L * units.size))
                for (w in timed) {
                    starts += w.startMs + offsetMs
                    ends += (w.endMs ?: (w.startMs + 300)) + offsetMs
                }
            }
            cursor += units.size
        }
        return Resync(texts, wordMode, SyncRecorder.Saved(cursor, starts, ends))
    }
}
