package app.malachite.core.lyrics


/**
 * "Letter by letter, with a soft edge": the sung colour runs through the whole line at an even
 * pace, from the moment the line starts until it has been sung — whatever the timing of the
 * single words says. (Following each word's own timing made a quickly sung word flash and a held
 * one crawl.) Vowels get a little more of the time than consonants, being what a singer holds;
 * spaces and signs almost none. It needs only the time of the line, so it works for lyrics timed
 * by lines as well.
 */
object LineSweep {
    /** The slowest pace (ms per unit of weight): a line followed by a long pause is not stretched over the pause. */
    private const val SLOWEST_MS = 115f
    /** The line is fully coloured a moment before the next one takes over. */
    private const val DONE_BEFORE_MS = 120L
    private const val SHORTEST_MS = 250L

    private const val VOWELS = "aeiouyаеёиоуыэюяіїєáàâäãåéèêëíìîïóòôöõúùûüýÿæøœ" // i18n:off – vowels, not interface text

    private fun weight(c: Char): Float = when {
        c.isWhitespace() -> 0.35f
        !c.isLetterOrDigit() -> 0.25f
        c.lowercaseChar() in VOWELS -> 2.2f
        else -> 1f
    }

    /**
     * How far the colour has come through [text] (the line as shown) at [posMs] on the lyrics'
     * own timeline: in characters, 0 … text.length, with the fraction of the character at the edge.
     */
    fun reach(text: String, line: LyricLine, posMs: Long, nextStartMs: Long?, switchAtMs: Long?): Float {
        val n = text.length
        if (n == 0) return 0f
        val start = line.words.firstOrNull()?.startMs ?: line.startMs
        if (posMs <= start) return 0f
        val cum = FloatArray(n + 1)
        for (i in 0 until n) cum[i + 1] = cum[i] + weight(text[i])
        val total = cum[n]
        val slowest = (total * SLOWEST_MS).toLong().coerceAtLeast(SHORTEST_MS)
        var end = switchAtMs ?: nextStartMs ?: line.endMs ?: (start + slowest)
        // with timed words: done when the last word is
        line.words.lastOrNull()?.endMs?.let { end = minOf(end, it) }
        line.endMs?.let { end = minOf(end, it) }
        val span = (end - DONE_BEFORE_MS - start).coerceIn(SHORTEST_MS, slowest)
        val tau = (posMs - start).toFloat() / span
        if (tau >= 1f) return n.toFloat()
        val target = tau * total
        // the character the edge is in
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (cum[mid] <= target) lo = mid else hi = mid - 1
        }
        val w = cum[lo + 1] - cum[lo]
        return lo + if (w > 0f) ((target - cum[lo]) / w).coerceIn(0f, 1f) else 0f
    }

    /**
     * The colour mix (0 waiting … 1 sung) of character [i] of [n] with the edge at [reach],
     * blended over [soft] characters: nothing is coloured at 0, everything at [n].
     */
    fun mix(i: Int, n: Int, reach: Float, soft: Float = 1.6f): Float {
        if (n <= 0) return 0f
        val edge = (0.5f - soft / 2) + (n - 1 + soft) * (reach / n)
        return ((edge - (i + 0.5f)) / soft + 0.5f).coerceIn(0f, 1f)
    }
}
