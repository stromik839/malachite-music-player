package app.malachite.core.soundid

import app.malachite.core.library.TrackInfo
import app.malachite.core.net.Http
import app.malachite.core.net.arr
import app.malachite.core.net.get
import app.malachite.core.net.num
import app.malachite.core.net.obj
import app.malachite.core.net.str
import okhttp3.FormBody
import okhttp3.Request

/**
 * Asks the AcoustID service which recording a sound print belongs to. The service wants a key
 * of the application (free; each user enters their own in the settings). What goes out is the
 * print and the length of the song – not the sound, not the file name.
 */
class AcoustId(private val http: Http, private val endpoint: String = "https://api.acoustid.org/v2/lookup") {

    enum class KeyTrouble { MISSING, INVALID, NOT_ALLOWED }

    /** A recording the print belongs to; [score] 0…1 is how sure the service is. */
    data class Match(
        val score: Double,
        val title: String,
        val artist: String,
        val album: String?,
        val durationSec: Int?,
        val recordingId: String,
    )

    sealed interface Answer {
        /** The service answered; [matches] is empty when it does not know the song. */
        data class Found(val matches: List<Match>) : Answer
        /** The key is missing, wrong or no longer accepted: nothing can be asked until it is fixed. */
        data class KeyProblem(val trouble: KeyTrouble, val detail: String) : Answer
        /** No answer this time (no internet, the service busy). */
        data class Failed(val detail: String) : Answer
    }

    private var lastCall = 0L

    fun lookup(key: String, print: String, durationSec: Int): Answer {
        if (key.isBlank()) return Answer.KeyProblem(KeyTrouble.MISSING, "no key")
        // the service allows 3 requests a second
        synchronized(this) {
            val wait = lastCall + 350 - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastCall = System.currentTimeMillis()
        }
        val body = FormBody.Builder()
            .add("client", key.trim())
            .add("duration", durationSec.toString())
            .add("fingerprint", print)
            .add("meta", "recordings releasegroups compress")
            .add("format", "json")
            .build()
        val req = Request.Builder().url(endpoint).header("User-Agent", http.userAgent).post(body).build()
        val (code, text) = try {
            http.client.newCall(req).execute().use { r -> r.code to r.body?.string().orEmpty() }
        } catch (e: java.io.IOException) {
            app.malachite.core.CoreLog.w("net", "api.acoustid.org: ${e.javaClass.simpleName}: ${e.message}")
            return Answer.Failed(e.message ?: e.javaClass.simpleName)
        }
        val answer = parse(text)
        if (answer is Answer.Failed && code >= 400) return Answer.Failed("HTTP $code" + if (answer.detail.isNotEmpty()) ": ${answer.detail}" else "")
        return answer
    }

    companion object {
        // the service's error numbers
        private const val INVALID_KEY = 4
        private const val NOT_ALLOWED = 12
        private const val UNKNOWN_APPLICATION = 17
        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }

        /** The service's answer read; also used by the tests. */
        fun parse(text: String): Answer {
            val root = runCatching { json.parseToJsonElement(text) }.getOrNull()
                ?: return Answer.Failed(text.take(120))
            if (root["status"].str() != "ok") {
                val err = root["error"]
                val code = err["code"].num()?.toInt() ?: -1
                val message = err["message"].str().orEmpty()
                return when {
                    code == INVALID_KEY || code == UNKNOWN_APPLICATION || message.contains("api key", true) ->
                        Answer.KeyProblem(KeyTrouble.INVALID, message)
                    code == NOT_ALLOWED -> Answer.KeyProblem(KeyTrouble.NOT_ALLOWED, message)
                    else -> Answer.Failed(message.ifEmpty { "error $code" })
                }
            }
            val matches = ArrayList<Match>()
            for (result in root["results"].arr().orEmpty()) {
                val score = result["score"].num() ?: continue
                for (rec in result["recordings"].arr().orEmpty()) {
                    val title = rec["title"].str()?.trim().orEmpty()
                    val artist = rec["artists"].arr().orEmpty().joinToString("") { a ->
                        a["name"].str().orEmpty() + (a["joinphrase"].str() ?: "")
                    }.trim()
                    if (title.isEmpty() || artist.isEmpty()) continue
                    matches += Match(
                        score = score,
                        title = title,
                        artist = artist,
                        album = album(rec["releasegroups"].arr().orEmpty().mapNotNull { it.obj() }),
                        durationSec = rec["duration"].num()?.toInt(),
                        recordingId = rec["id"].str().orEmpty(),
                    )
                }
            }
            // a recording known under several prints: the best score, then the one with an album
            return Answer.Found(
                matches.sortedWith(compareByDescending<Match> { it.score }.thenByDescending { it.album != null })
                    .distinctBy { it.title.lowercase() to it.artist.lowercase() },
            )
        }

        /** The album of the recording: a real album before an EP before the single; never a compilation, live or soundtrack. */
        private fun album(groups: List<kotlinx.serialization.json.JsonObject>): String? {
            fun rank(g: kotlinx.serialization.json.JsonObject): Int {
                if (g["secondarytypes"].arr().orEmpty().isNotEmpty()) return -1
                return when (g["type"].str()?.lowercase()) {
                    "album" -> 3
                    "ep" -> 2
                    "single" -> 1
                    else -> 0
                }
            }
            return groups.filter { rank(it) > 0 }.maxByOrNull { rank(it) }?.let { it["title"].str()?.trim() }?.takeIf { it.isNotEmpty() }
        }

        private val JUNK_TITLE = listOf(
            Regex("^\\d{6,}$"), // "115620301" (Telegram), "20250227"; "1979" is a song
            Regex("(?i)^tmp[a-z0-9_]{4,}$"), // "tmpeicn5cwv"
            Regex("(?i)^(audio|aud|track|трек|song|запись|record|recording|voice|rec)[\\s_-]*\\d*$"), // i18n:off
            Regex("(?i)^(aud|audio|ptt|vid)[-_]\\d{6,}.*$"), // "AUD-20240101-WA0001"
            Regex("(?i)^(unknown|untitled|без названия|неизвестно|no title)$"), // i18n:off
            Regex("(?i)^youtube video #.+$"),
            Regex("(?i)^[0-9a-f]{12,}$"), // a hash
        )

        /** A title that says nothing about the song ("115620301", "tmpeicn5cwv", "Track 3"). */
        fun meaninglessTitle(title: String): Boolean {
            val t = title.trim()
            return t.isEmpty() || JUNK_TITLE.any { it.matches(t) }
        }

        /** Whether a song is worth recognising by its sound: its title or its artist is missing or meaningless. */
        fun worthListening(t: TrackInfo, artistIsJunk: Boolean): Boolean =
            meaninglessTitle(t.displayTitle) || (t.artist.isBlank() && t.albumArtist.isBlank()) || artistIsJunk
    }
}
