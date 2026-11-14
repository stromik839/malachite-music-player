package app.malachite.core.lyrics

/**
 * Removes everything from downloaded lyrics that is *not sung* and would break syncing:
 * section labels ("[Куплет 1]", "(Припев)", "{Bridge}"), site headers
 * ("[Текст песни «Подводная лодка»]", "Song Lyrics", "12 Contributors"), "Embed" footers,
 * repeat marks ("(x2)"), etc.
 *
 * Square, curly and angle brackets are never sung and are always removed.
 * Round brackets are often backing vocals that *are* sung, so by default only
 * round-bracketed annotations are removed; [Options.removeAllParentheses] removes all.
 */
object LyricsCleaner {

    data class Options(
        val removeAllParentheses: Boolean = false,
    )

    /** Words that start a section label: "Куплет 2: Артист", "Pre-Chorus", "Припев (x2)". */
    private val SECTION_PREFIX = listOf(
        "куплет", "припев", "приспів", "приспiв", "пре-припев", "предприпев", "предприпів", "пост-припев",
        "бридж", "брідж", "интро", "інтро", "аутро", "вступление", "проигрыш", "програш", "переход",
        "перехід", "речитатив", "инструментал", "інструментал", "текст песни", "текст пісні",
        "verse", "chorus", "pre-chorus", "prechorus", "post-chorus", "bridge", "intro", "outro",
        "refrain", "interlude", "instrumental", "breakdown",
    )

    /** Words that are a label only when they are the whole bracket content (optionally with ":" or a number). */
    private val SECTION_EXACT = listOf(
        "кода", "хук", "связка", "вірш", "соло", "скит", "все", "оба", "партия", "часть", "перевод", "переклад",
        "hook", "solo", "drop", "part", "skit", "spoken", "speaking", "both", "all", "lyrics", "translation",
        "guitar", "piano", "beat", "sample", "repeat", "spoken word",
    )

    // NB: braces are always escaped – Android's regex engine (ICU) rejects a bare "}" that Java accepts.
    private val REPEAT_MARK = Regex("[(\\[\\{]?\\s*[xх×]\\s*\\d+\\s*[)\\]\\}]?|[(\\[\\{]\\s*\\d+\\s*[xх×]\\s*[)\\]\\}]", RegexOption.IGNORE_CASE)
    private val SQUARE = Regex("\\[[^\\]]*]")
    private val CURLY = Regex("\\{[^\\}]*\\}")
    private val ANGLE = Regex("<[^>]*>")
    private val ROUND = Regex("\\([^)]*\\)")
    private val GENIUS_CONTRIBUTORS = Regex("^\\d+\\s*(contributors?|участник\\p{L}*|translations?)\\b.*", RegexOption.IGNORE_CASE)
    private val HEADER_LYRICS = Regex("^.{1,120}\\s(lyrics|текст песни|слова песни)\\s*$", RegexOption.IGNORE_CASE)
    private val TEXT_OF_SONG = Regex("^\\s*(текст песни|текст пісні|слова песни)\\s*[«\"“].*[»\"”]\\s*$", RegexOption.IGNORE_CASE)
    private val EMBED_FOOTER = Regex("\\d*\\s*Embed\\s*$")
    private val JUNK_LINES = listOf(
        Regex("^you might also like.*", RegexOption.IGNORE_CASE),
        Regex("^see .* live$", RegexOption.IGNORE_CASE),
        Regex("^get tickets as low as.*", RegexOption.IGNORE_CASE),
        Regex("^read more\\s*$", RegexOption.IGNORE_CASE),
    )

    private fun isAnnotation(inner: String): Boolean {
        val t = inner.trim().lowercase().trim(':', '.', '-', ' ')
        if (t.isEmpty()) return true
        if (Regex("^[xх×]\\s*\\d+$|^\\d+\\s*[xх×]$|^\\d+\\s*раз\\p{L}*$").matches(t)) return true
        if (SECTION_PREFIX.any { w -> t == w || t.startsWith(w) && !t[w.length].isLetter() }) return true
        return SECTION_EXACT.any { w -> t == w || Regex("^" + Regex.escape(w) + "\\s*(\\d+|[ivx]+)?\\s*(:.*)?$").matches(t) }
    }

    /** Cleans one line; returns null if nothing sung remains. */
    fun cleanLine(line: String, options: Options = Options()): String? {
        var s = line.trim()
        if (s.isEmpty()) return null
        if (TEXT_OF_SONG.matches(s)) return null
        s = SQUARE.replace(s, " ")
        s = CURLY.replace(s, " ")
        s = ANGLE.replace(s, " ")
        s = ROUND.replace(s) { m ->
            val inner = m.value.substring(1, m.value.length - 1)
            if (options.removeAllParentheses || isAnnotation(inner)) " " else m.value
        }
        s = Regex("\\s+(?:[xх×]\\s*\\d+|\\d\\s*[xх×])\\s*$", RegexOption.IGNORE_CASE).replace(s, "")
        s = s.replace(Regex("\\s+"), " ").trim()
        // stray brackets left by broken markup
        if (s.all { it in "()[]{}<>:-–—*. " }) return null
        return s.ifEmpty { null }
    }

    // "x2", "(x2)", "2x", "×3", "(2 раза)", "[x2]"
    private val REPEAT_ONLY = Regex("^[(\\[]?\\s*(?:[xх×]\\s*(\\d)|(\\d)\\s*[xх×]|(\\d)\\s*раз\\p{L}*)\\s*[)\\]]?\\s*:?$", RegexOption.IGNORE_CASE)
    // a mark at the end of a line needs a space or a bracket before it: "Xbox 2" is not "x2"
    private val REPEAT_TAIL = Regex("(?:\\s*[(\\[]\\s*(?:[xх×]\\s*(\\d)|(\\d)\\s*[xх×]|(\\d)\\s*раз\\p{L}*)\\s*[)\\]]|\\s+(?:[xх×]\\s*(\\d)|(\\d)\\s*[xх×]))\\s*$", RegexOption.IGNORE_CASE)
    private val BRACKETED = Regex("^[\\[(]\\s*(.+?)\\s*[\\])]\\s*:?$")
    private val PLAIN_HEADER = Regex("^(припев|приспів|chorus|refrain|куплет\\s*\\d*|verse\\s*\\d*)\\s*(?:[(\\[]?\\s*(?:[xх×]\\s*\\d|\\d\\s*[xх×])\\s*[)\\]]?)?\\s*:?$", RegexOption.IGNORE_CASE)

    private fun repeatCount(m: MatchResult): Int =
        m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toIntOrNull()?.coerceIn(1, 4) ?: 1

    /** "[Припев x2]" -> ("припев", 2); "[Куплет 2: Артист]" -> ("куплет 2", 1); null if not a section label. */
    private fun header(t: String): Pair<String, Int>? {
        val inner = BRACKETED.matchEntire(t)?.groupValues?.get(1)
            ?: PLAIN_HEADER.matchEntire(t)?.value?.trimEnd(':', ' ')
            ?: return null
        if (!isAnnotation(inner) && PLAIN_HEADER.matchEntire(t) == null) return null
        var name = inner.substringBefore(':').lowercase().trim()
        var n = 1
        REPEAT_TAIL.find(name)?.let { m -> n = repeatCount(m); name = name.substring(0, m.range.first).trim() }
        Regex("\\(([^)]*)\\)").find(inner)?.let { m -> REPEAT_ONLY.matchEntire(m.groupValues[1].trim())?.let { n = maxOf(n, repeatCount(it)) } }
        name = name.replace(Regex("\\(.*\\)"), "").trim()
        if (name.isEmpty() || REPEAT_ONLY.matches(name)) return null
        return name to n
    }

    /**
     * Cleans a whole plain-text lyrics document. Repeats that sites only *mark* are written out,
     * because every sung line needs its own place for syncing:
     *  - "Line (x2)" -> the line twice;
     *  - "[Припев x2]" or a "(x2)" line after a stanza -> the stanza twice;
     *  - "[Припев]" with no lines under it -> the chorus written earlier.
     */
    fun clean(text: String, options: Options = Options()): String {
        val rawLines = text.replace("\r\n", "\n").replace('\r', '\n').lines().toMutableList()
        // Genius footer: "...грусть123Embed"
        if (rawLines.isNotEmpty()) rawLines[rawLines.size - 1] = EMBED_FOOTER.replace(rawLines.last(), "")
        val out = ArrayList<String>()
        val sections = HashMap<String, List<String>>()
        var key: String? = null
        var times = 1
        var cur = ArrayList<String>()
        var last: List<String> = emptyList()

        fun emit(lines: List<String>) {
            if (lines.isEmpty()) return
            if (out.isNotEmpty() && out.last().isNotEmpty()) out += ""
            out.addAll(lines)
        }
        fun flush() {
            val k = key
            if (cur.isEmpty() && k != null) sections[k]?.let { cur.addAll(it) } // "[Припев]" = the chorus again
            else if (cur.isNotEmpty() && k != null && k !in sections) sections[k] = cur.toList()
            if (cur.isNotEmpty()) {
                repeat(times) { emit(cur) }
                last = cur.toList()
            }
            cur = ArrayList()
            key = null
            times = 1
        }

        for ((i, raw) in rawLines.withIndex()) {
            val t = raw.trim()
            if (t.isEmpty()) { flush(); continue }
            if (i < 3 && (GENIUS_CONTRIBUTORS.matches(t) || HEADER_LYRICS.matches(t) || TEXT_OF_SONG.matches(t))) continue
            if (JUNK_LINES.any { it.matches(t) }) continue
            // "(x2)" on its own line: the stanza just above is sung again
            val rep = REPEAT_ONLY.matchEntire(t)
            if (rep != null) {
                val n = repeatCount(rep)
                if (cur.isNotEmpty()) times = maxOf(times, n)
                else if (last.isNotEmpty()) repeat(n - 1) { emit(last) }
                continue
            }
            val h = header(t)
            if (h != null) {
                flush()
                key = h.first
                times = h.second
                continue
            }
            val c = cleanLine(t, options) ?: run { flush(); null } ?: continue
            val n = REPEAT_TAIL.find(t)?.takeIf { it.range.first > 0 }?.let { repeatCount(it) } ?: 1
            repeat(n) { cur += c }
        }
        flush()
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        while (out.isNotEmpty() && out.first().isEmpty()) out.removeAt(0)
        return out.joinToString("\n")
    }

    /** Lines that must be synced (sung lines only, no blank separators). */
    fun singableLines(text: String, options: Options = Options()): List<String> =
        clean(text, options).lines().map { it.trim() }.filter { it.isNotEmpty() }
}
