package app.malachite.core.text

import kotlin.math.max
import kotlin.math.min

/** How sure we are that several spellings mean the same thing. */
enum class MatchConfidence {
    /** Same after removing decoration, case, spaces, punctuation and Cyrillic/Latin script. */
    EXACT,
    /** Same after also ignoring transliteration-scheme differences (kh/h, ya/ja, ...). */
    STRONG,
    /** Differ by a letter or two – shown to the user, never applied without confirmation. */
    POSSIBLE;

    fun weakest(other: MatchConfidence) = if (other.ordinal > ordinal) other else this
}

object Similarity {
    /** Levenshtein distance with an upper bound (returns limit+1 when exceeded). */
    fun levenshtein(a: String, b: String, limit: Int = Int.MAX_VALUE): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
                rowMin = min(rowMin, cur[j])
            }
            if (rowMin > limit) return limit + 1
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /** 0..1 similarity of two strings. */
    fun ratio(a: String, b: String): Double {
        val m = max(a.length, b.length)
        if (m == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / m
    }

    /** Typos we tolerate for a key of the given length. */
    fun allowedTypos(length: Int): Int = when {
        length < 5 -> 0
        length < 10 -> 1
        length < 18 -> 2
        else -> 3
    }

    /**
     * Compares two raw names the way the healer does.
     * Returns null when they are different things.
     */
    fun sameName(a: String, b: String): MatchConfidence? {
        val sa = TextFold.strictKey(a)
        val sb = TextFold.strictKey(b)
        if (sa.isEmpty() || sb.isEmpty()) return null
        if (sa == sb) return MatchConfidence.EXACT
        if (TextFold.digitRuns(a) != TextFold.digitRuns(b)) return null
        val la = TextFold.loosen(sa)
        val lb = TextFold.loosen(sb)
        if (la == lb) return MatchConfidence.STRONG
        val allowed = allowedTypos(min(la.length, lb.length))
        if (allowed == 0) return null
        return if (levenshtein(la, lb, allowed) <= allowed) MatchConfidence.POSSIBLE else null
    }
}

/** One spelling of a name as found in the library. */
@kotlinx.serialization.Serializable
data class NameVariant(val raw: String, val count: Int)

/** Several spellings that (probably) mean the same artist / album / title. */
data class NameGroup(
    val variants: List<NameVariant>,
    val canonical: String,
    val confidence: MatchConfidence,
) {
    val totalCount get() = variants.sumOf { it.count }
    /** Spellings that will have to be rewritten to [canonical]. */
    val toRewrite get() = variants.filter { it.raw != canonical }
}

object NameGrouping {

    /**
     * Groups spellings of names. Only groups with 2+ different raw spellings, or with a
     * single decorated spelling that can be cleaned, are returned.
     */
    fun group(variants: List<NameVariant>, allowFuzzy: Boolean = true): List<NameGroup> {
        val list = variants.filter { it.raw.isNotBlank() }
            .groupBy { it.raw }
            .map { (raw, vs) -> NameVariant(raw, vs.sumOf { it.count }) }
        if (list.isEmpty()) return emptyList()
        val n = list.size
        val strict = list.map { TextFold.strictKey(it.raw) }
        val loose = strict.map { TextFold.loosen(it) }
        val digits = list.map { TextFold.digitRuns(it.raw) }

        val parent = IntArray(n) { it }
        val conf = Array(n) { MatchConfidence.EXACT }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val nx = parent[c]; parent[c] = r; c = nx }
            return r
        }
        fun union(a: Int, b: Int, c: MatchConfidence) {
            val ra = find(a); val rb = find(b)
            if (ra == rb) return
            parent[rb] = ra
            conf[ra] = conf[ra].weakest(conf[rb]).weakest(c)
        }

        // EXACT and STRONG: hash joins.
        joinByKey(strict, n) { a, b -> union(a, b, MatchConfidence.EXACT) }
        joinByKey(loose, n) { a, b -> if (digits[a] == digits[b]) union(a, b, MatchConfidence.STRONG) }

        // POSSIBLE: blocked fuzzy comparison (shared 3-char prefix or suffix) to stay fast.
        if (allowFuzzy) {
            val blocks = HashMap<String, MutableList<Int>>()
            for (i in 0 until n) {
                val k = loose[i]
                if (k.length < 5) continue
                blocks.getOrPut("p:" + k.take(3)) { mutableListOf() }.add(i)
                blocks.getOrPut("s:" + k.takeLast(3)) { mutableListOf() }.add(i)
            }
            for (members in blocks.values) {
                if (members.size < 2 || members.size > 400) continue
                for (x in members.indices) for (y in x + 1 until members.size) {
                    val a = members[x]; val b = members[y]
                    if (find(a) == find(b)) continue
                    if (digits[a] != digits[b]) continue
                    val allowed = Similarity.allowedTypos(min(loose[a].length, loose[b].length))
                    if (allowed > 0 && Similarity.levenshtein(loose[a], loose[b], allowed) <= allowed) {
                        union(a, b, MatchConfidence.POSSIBLE)
                    }
                }
            }
        }

        val byRoot = (0 until n).groupBy { find(it) }
        return byRoot.mapNotNull { (root, idx) ->
            val vs = idx.map { list[it] }.sortedByDescending { it.count }
            val canonical = chooseCanonical(vs)
            val needsWork = vs.size > 1 || canonical != vs[0].raw
            if (!needsWork) null else NameGroup(vs, canonical, if (vs.size == 1) MatchConfidence.EXACT else conf[root])
        }.sortedByDescending { it.totalCount }
    }

    private inline fun joinByKey(keys: List<String>, n: Int, join: (Int, Int) -> Unit) {
        val first = HashMap<String, Int>()
        for (i in 0 until n) {
            val k = keys[i]
            if (k.isEmpty()) continue
            val f = first[k]
            if (f == null) first[k] = i else join(f, i)
        }
    }

    /**
     * Picks the best spelling of a group: the most used one, preferring spellings without
     * decoration, with spaces between words, in the dominant script, and in normal case.
     * The chosen value is always passed through [TextFold.cleanDisplay].
     */
    fun chooseCanonical(variants: List<NameVariant>): String {
        val total = variants.sumOf { it.count }.coerceAtLeast(1)
        val cyrWeight = variants.filter { Translit.hasCyrillic(TextFold.cleanDisplay(it.raw)) }.sumOf { it.count }
        val dominantCyr = cyrWeight * 2 >= total
        val maxSpaces = variants.maxOf { TextFold.cleanDisplay(it.raw).count { c -> c == ' ' } }
        return variants.maxByOrNull { v ->
            val clean = TextFold.cleanDisplay(v.raw)
            var score = v.count.toDouble() / total * 10.0
            if (!TextFold.isDecorated(v.raw)) score += 3.0
            val spaces = clean.count { it == ' ' }
            if (spaces == maxSpaces) score += 2.0
            val isCyr = Translit.hasCyrillic(clean)
            if (isCyr == dominantCyr) score += 2.5
            val letters = clean.filter { it.isLetter() }
            if (letters.isNotEmpty()) {
                val allUpper = letters.all { it.isUpperCase() }
                val allLower = letters.all { it.isLowerCase() }
                if (!allUpper && !allLower) score += 1.0
                if (allUpper && letters.length > 4) score -= 0.5
            }
            if (Watermarks.contains(clean)) score -= 4.0
            score
        }!!.let { Watermarks.strip(TextFold.cleanDisplay(it.raw)) }
    }
}
