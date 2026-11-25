package app.malachite.core.lyrics

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class LyricWord(val startMs: Long, val endMs: Long? = null, val text: String)

@Serializable
data class LyricLine(
    val startMs: Long,
    /** When the line stops being sung (from a "release" in the sync editor or a blank LRC line). */
    val endMs: Long? = null,
    val text: String,
    val words: List<LyricWord> = emptyList(),
)

@Serializable
data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    /** Extra shift applied when displaying (user fine-tuning). */
    val offsetMs: Long = 0,
    val source: String = "",
) {
    val plainText: String get() = lines.joinToString("\n") { it.text }
    val hasWordTiming: Boolean get() = lines.any { it.words.isNotEmpty() }

    /** Index of the line that should be highlighted at [positionMs], or -1 (before first line / in a gap). */
    fun activeLine(positionMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        val p = positionMs - offsetMs
        // binary search for last line with start <= p
        var lo = 0
        var hi = lines.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= p) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        return idx
    }

    /**
     * Like [activeLine], but the next line counts as active [leadMs] before it starts (never
     * earlier than half-way through the current line). A highlight / scroll animation started
     * at that moment is finished when the line is actually sung, instead of running behind it.
     */
    fun activeLineAhead(positionMs: Long, leadMs: Long): Int {
        val i = activeLine(positionMs)
        if (!synced || leadMs <= 0) return i
        val at = switchMs(i, leadMs) ?: return i
        return if (positionMs - offsetMs >= at) i + 1 else i
    }

    /**
     * When (lyrics time, without the offset) the highlight moves on from line [i] to the next
     * one with [leadMs] of lead, or null for the last line. A line sung word by word keeps the
     * highlight until its last word has been sung (at least [LAST_WORD_MS] of it): the lead used
     * to move on while the last word was still being sung, and that word was never coloured.
     */
    fun switchMs(i: Int, leadMs: Long): Long? {
        val next = lines.getOrNull(i + 1) ?: return null
        val cur = if (i >= 0) lines[i].startMs else 0L
        var lead = minOf(leadMs.coerceAtLeast(0), (next.startMs - cur) / 2)
        lines.getOrNull(i)?.words?.lastOrNull()?.let { last ->
            val sung = minOf(last.endMs ?: (last.startMs + LAST_WORD_MS), next.startMs)
            lead = minOf(lead, (next.startMs - sung).coerceAtLeast(0))
        }
        return next.startMs - lead
    }

    /** True if the active line has already ended (instrumental gap) at [positionMs]. */
    fun isInGap(positionMs: Long): Boolean {
        val i = activeLine(positionMs)
        if (i < 0) return true
        val end = lines[i].endMs ?: return false
        return positionMs - offsetMs > end
    }

    fun withOffset(ms: Long) = copy(offsetMs = ms)

    /**
     * The lyrics running [tempo] times as fast (1.01: a hundredth faster): every time moves towards
     * the first line (or away from it), which stays where it is – for a text that is right at the
     * start of the song and drifts more and more towards its end.
     */
    fun retimed(tempo: Float): Lyrics {
        if (tempo == 1f || tempo <= 0f || !synced || lines.isEmpty()) return this
        val anchor = lines.first().let { it.words.firstOrNull()?.startMs ?: it.startMs }
        fun s(t: Long): Long = kotlin.math.round(anchor + (t - anchor) / tempo.toDouble()).toLong().coerceAtLeast(0)
        return copy(
            lines = lines.map { l ->
                l.copy(
                    startMs = s(l.startMs),
                    endMs = l.endMs?.let(::s),
                    words = l.words.map { w -> w.copy(startMs = s(w.startMs), endMs = w.endMs?.let(::s)) },
                )
            },
        )
    }

    /** Bakes the offset into every timestamp (used before saving to a file). */
    fun applyOffset(): Lyrics {
        if (offsetMs == 0L) return this
        fun s(t: Long) = (t + offsetMs).coerceAtLeast(0)
        return copy(
            offsetMs = 0,
            lines = lines.map { l ->
                l.copy(
                    startMs = s(l.startMs),
                    endMs = l.endMs?.let(::s),
                    words = l.words.map { w -> w.copy(startMs = s(w.startMs), endMs = w.endMs?.let(::s)) },
                )
            },
        )
    }

    companion object {
        /** The least time the last word of a line is shown being coloured before the next line takes over. */
        const val LAST_WORD_MS = 350L

        fun plain(text: String, source: String = "") = Lyrics(
            lines = text.lines().map { LyricLine(0, null, it.trimEnd()) },
            synced = false,
            source = source,
        )
    }
}

/** Parser / writer for LRC, including enhanced (word-level) LRC and our end-of-line markers. */
object Lrc {

    private val TIME = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val WORD_TIME = Regex("<(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?>")
    private val META = Regex("^\\[([a-zA-Z#]+):(.*)]\\s*$")

    fun looksSynced(text: String): Boolean {
        val content = text.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !(META.matches(it) && !TIME.containsMatchIn(it)) }.toList()
        if (content.isEmpty()) return false
        val timed = content.count { TIME.find(it)?.range?.first == 0 }
        return timed >= 1 && timed * 2 >= content.size
    }

    private fun toMs(min: String, sec: String, frac: String?): Long {
        val f = when (frac?.length) {
            null, 0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.substring(0, 3).toLong()
        }
        return min.toLong() * 60_000 + sec.toLong() * 1000 + f
    }

    fun parse(text: String, source: String = ""): Lyrics {
        if (!looksSynced(text)) return Lyrics.plain(LyricsCleaner.clean(text), source)
        var offset = 0L
        data class Raw(val t: Long, val text: String, val words: List<LyricWord>)
        val raws = ArrayList<Raw>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val meta = META.matchEntire(line)
            if (meta != null && !TIME.containsMatchIn(line)) {
                if (meta.groupValues[1].equals("offset", true)) offset = meta.groupValues[2].trim().toLongOrNull() ?: 0
                continue
            }
            val times = ArrayList<Long>()
            var rest = line
            while (true) {
                val m = TIME.find(rest) ?: break
                if (m.range.first != 0) break
                times += toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                rest = rest.substring(m.range.last + 1)
            }
            if (times.isEmpty()) continue
            // enhanced word timing
            val words = ArrayList<LyricWord>()
            val wm = WORD_TIME.findAll(rest).toList()
            var plain = rest
            if (wm.isNotEmpty()) {
                for ((i, m) in wm.withIndex()) {
                    val start = toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                    val endOfText = if (i + 1 < wm.size) wm[i + 1].range.first else rest.length
                    val w = rest.substring(m.range.last + 1, endOfText)
                    val next = if (i + 1 < wm.size) wm[i + 1] else null
                    if (w.isNotBlank()) {
                        val end = next?.let { toMs(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }
                        words += LyricWord(start, end, w.trim())
                    }
                }
                plain = WORD_TIME.replace(rest, "").replace(Regex("\\s+"), " ")
            }
            val cleaned = LyricsCleaner.cleanLine(plain)
            for (t in times) raws += Raw(t, cleaned ?: "", if (times.size == 1) words else emptyList())
        }
        raws.sortBy { it.t }
        // Empty timestamped lines mark the end of the previous line.
        val lines = ArrayList<LyricLine>()
        for ((i, r) in raws.withIndex()) {
            if (r.text.isBlank()) {
                if (lines.isNotEmpty() && lines.last().endMs == null) {
                    lines[lines.size - 1] = lines.last().copy(endMs = r.t)
                }
                continue
            }
            val nextStart = raws.getOrNull(i + 1)?.t
            val explicitEnd = r.words.lastOrNull()?.endMs
            lines += LyricLine(r.t, explicitEnd?.takeIf { nextStart == null || it <= nextStart }, r.text, r.words)
        }
        return Lyrics(lines, synced = true, offsetMs = offset, source = source)
    }

    fun format(ms: Long): String {
        val t = ms.coerceAtLeast(0)
        val min = t / 60_000
        val sec = (t % 60_000) / 1000
        val cs = (t % 1000) / 10
        return String.format(Locale.US, "%02d:%02d.%02d", min, sec, cs)
    }

    /**
     * Writes LRC. Line ends become empty timestamped lines when there is a real pause,
     * word timings become enhanced-LRC `<mm:ss.xx>` marks.
     */
    fun write(lyrics: Lyrics, title: String? = null, artist: String? = null, album: String? = null): String {
        val l = lyrics.applyOffset()
        val sb = StringBuilder()
        title?.takeIf { it.isNotBlank() }?.let { sb.append("[ti:").append(it).append("]\n") }
        artist?.takeIf { it.isNotBlank() }?.let { sb.append("[ar:").append(it).append("]\n") }
        album?.takeIf { it.isNotBlank() }?.let { sb.append("[al:").append(it).append("]\n") }
        sb.append("[re:Malachite]\n")
        if (!l.synced) {
            l.lines.forEach { sb.append(it.text).append('\n') }
            return sb.toString()
        }
        for ((i, line) in l.lines.withIndex()) {
            sb.append('[').append(format(line.startMs)).append(']')
            if (line.words.isNotEmpty()) {
                for (w in line.words) {
                    sb.append('<').append(format(w.startMs)).append('>').append(w.text).append(' ')
                }
                line.words.last().endMs?.let { sb.append('<').append(format(it)).append('>') }
            } else sb.append(line.text)
            sb.append('\n')
            val end = line.endMs
            val nextStart = l.lines.getOrNull(i + 1)?.startMs
            if (end != null && (nextStart == null || nextStart - end >= 250)) {
                sb.append('[').append(format(end)).append("]\n")
            }
        }
        return sb.toString()
    }
}

/**
 * What a favourite line is remembered by: its words, without case, signs and extra spaces, so
 * that a mark survives a re-sync, another source of the same lyrics and a fixed comma.
 */
fun markKey(text: String): String =
    text.lowercase().replace('ё', 'е').map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

/**
 * The mark of each line of a song: its words and which repeat of them it is ("words#0" the first
 * time they are sung, "words#1" the second…), so marking one chorus line does not mark every
 * chorus. Lines without words get "".
 */
fun lineMarkKeys(lines: List<String>): List<String> {
    val seen = HashMap<String, Int>()
    return lines.map { text ->
        val k = markKey(text)
        if (k.isEmpty()) "" else {
            val n = seen[k] ?: 0
            seen[k] = n + 1
            "$k#$n"
        }
    }
}

/**
 * Whether the line with [lineKey] (from [lineMarkKeys]) is marked. Marks made before repeats were
 * told apart hold only the words: they now belong to the first time those words are sung.
 */
fun isLineMarked(lineKey: String, marks: Set<String>): Boolean =
    lineKey.isNotEmpty() && (lineKey in marks || (lineKey.endsWith("#0") && lineKey.removeSuffix("#0") in marks))
