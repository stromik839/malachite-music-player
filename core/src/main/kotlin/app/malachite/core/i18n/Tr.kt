package app.malachite.core.i18n

/**
 * The language of the interface.
 *
 * The texts are written in the code in Russian, exactly as they are shown. In English mode
 * every text passes through [tr], which finds its translation in `i18n/en.txt` (the gettext
 * way: the source text is the key). A text with values inside ("Найдено: 12") is matched
 * against templates ("Найдено: {}") and the values are carried over.
 *
 * A text without a translation is shown as it is – never an empty line, never a crash.
 * `tools/i18n.py check` (run by CI) fails the build when a text has no translation.
 */
object Tr {
    @Volatile
    var english: Boolean = false
        private set

    private class Template(val chunks: List<String>, val translation: String) {
        val weight = chunks.sumOf { it.length }
    }

    /** True while the dictionary in memory is the one built into the app. */
    @Volatile private var builtIn = false
    @Volatile private var exact: Map<String, String> = emptyMap()
    @Volatile private var templates: List<Template> = emptyList()
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 800
    }

    /** Chooses the language. Call before anything is shown; the dictionary is read once. */
    fun use(english: Boolean) {
        if (english && !builtIn) load()
        this.english = english
        synchronized(cache) { cache.clear() }
    }

    private fun load() {
        val text = runCatching {
            Tr::class.java.getResourceAsStream("/i18n/en.txt")?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull() ?: return
        val (pairs, patterns) = parse(text)
        exact = pairs
        templates = patterns
        builtIn = true
    }

    /** For tests and tools: the dictionary given as text instead of the built-in file. */
    fun useDictionary(text: String) {
        val (pairs, patterns) = parse(text)
        exact = pairs
        templates = patterns
        builtIn = false
        english = true
        synchronized(cache) { cache.clear() }
    }

    private fun parse(text: String): Pair<Map<String, String>, List<Template>> {
        val pairs = HashMap<String, String>()
        val patterns = ArrayList<Template>()
        val block = ArrayList<String>(2)
        fun flush() {
            if (block.size == 2) {
                val key = unescape(block[0])
                val value = unescape(block[1])
                if ("{}" in key) patterns += Template(key.split("{}"), value) else pairs[key] = value
            }
            block.clear()
        }
        for (line in text.split('\n')) {
            val l = line.trimEnd('\r')
            when {
                l.startsWith("#") -> Unit
                l.isBlank() -> flush()
                else -> block += l
            }
        }
        flush()
        // the most specific template first: "Найдено {} из {}" before "{} из {}"
        patterns.sortByDescending { it.weight }
        return pairs to patterns
    }

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                out.append(if (s[i + 1] == 'n') '\n' else s[i + 1])
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    fun translate(text: String): String {
        exact[text]?.let { return it }
        if (text.isEmpty() || templates.isEmpty()) return text
        synchronized(cache) { cache[text] }?.let { return it }
        var result = text
        for (t in templates) {
            val values = match(t.chunks, text) ?: continue
            result = fill(t.translation, values)
            break
        }
        synchronized(cache) { cache[text] = result }
        return result
    }

    /** The values of [text] if it has the fixed parts [chunks] in this order, else null. */
    private fun match(chunks: List<String>, text: String): List<String>? {
        val first = chunks.first()
        val last = chunks.last()
        if (text.length < first.length + last.length || !text.startsWith(first) || !text.endsWith(last)) return null
        val values = ArrayList<String>(chunks.size - 1)
        var pos = first.length
        val limit = text.length - last.length
        for (i in 1 until chunks.size - 1) {
            val at = text.indexOf(chunks[i], pos)
            if (at < 0 || at + chunks[i].length > limit) return null
            values += text.substring(pos, at)
            pos = at + chunks[i].length
        }
        if (pos > limit) return null
        values += text.substring(pos, limit)
        return values
    }

    /** Puts [values] into `{}` (in order) or `{1}`, `{2}`… (by number) of [translation]. */
    private fun fill(translation: String, values: List<String>): String {
        val out = StringBuilder(translation.length + 16)
        var next = 0
        var i = 0
        while (i < translation.length) {
            val c = translation[i]
            if (c == '{') {
                val close = translation.indexOf('}', i)
                val inside = if (close > i) translation.substring(i + 1, close) else "x"
                if (inside.isEmpty()) {
                    out.append(values.getOrElse(next++) { "" })
                    i = close + 1
                    continue
                }
                val number = inside.toIntOrNull()
                if (number != null) {
                    out.append(values.getOrElse(number - 1) { "" })
                    i = close + 1
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }
}

/** The text in the language of the interface. [text] is the Russian original. */
fun tr(text: String): String = if (Tr.english) Tr.translate(text) else text
