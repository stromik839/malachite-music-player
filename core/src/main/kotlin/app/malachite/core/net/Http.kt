package app.malachite.core.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Small HTTP helper shared by lyrics and cover search. No API keys are needed anywhere. */
class Http(
    val userAgent: String = "Malachite/0.1 (offline-first music player; https://github.com/stromik839/malachite)",
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Every request goes over HTTPS. Services sometimes answer with plain "http://" addresses
     * (covers): they are asked over HTTPS instead, which the same servers answer. Android forbids
     * plain HTTP for this app anyway.
     */
    private fun secure(url: String): String = if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    /**
     * GET returning the body, or null on 404. Throws on network errors. [browser] sends an
     * ordinary browser User-Agent (some sites refuse unknown clients).
     */
    fun get(
        url: String,
        browser: Boolean = false,
        accept: String = "application/json",
        headers: Map<String, String> = emptyMap(),
        optional: Boolean = false,
    ): String? {
        val b = Request.Builder().url(secure(url))
            .header("User-Agent", if (browser) BROWSER_UA else userAgent)
            .header("Accept", accept)
            .header("Accept-Language", "ru,en;q=0.8")
        headers.forEach { (k, v) -> b.header(k, v) }
        return execute(b.build(), optional)
    }

    /** POST of a JSON body, answer as text (null on 404). */
    fun postJson(url: String, body: String, browser: Boolean = false, optional: Boolean = false): JsonElement? {
        val req = Request.Builder().url(secure(url))
            .header("User-Agent", if (browser) BROWSER_UA else userAgent)
            .header("Accept", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return execute(req, optional)?.let { json.parseToJsonElement(it) }
    }

    /**
     * An *optional* service is an extra source that may be blocked in the user's country or
     * for their network. It gets short timeouts, and after a failure it is left alone for a
     * few minutes, so one dead service does not add ten seconds to every search.
     */
    private fun execute(req: Request, optional: Boolean): String? {
        val host = req.url.host
        if (optional) {
            val until = deadUntil[host]
            if (until != null) {
                if (System.currentTimeMillis() < until) throw IOException("$host did not answer recently, skipped")
                deadUntil.remove(host)
            }
        }
        throttle(host)
        try {
            (if (optional) quickClient else client).newCall(req).execute().use { r ->
                if (r.code == 404) return null
                if (!r.isSuccessful) {
                    if (optional && (r.code == 403 || r.code == 429 || r.code == 451 || r.code >= 500)) markDead(host)
                    throw IOException("HTTP ${r.code} for $host")
                }
                return r.body?.string()
            }
        } catch (e: IOException) {
            app.malachite.core.CoreLog.w("net", "$host: ${e.javaClass.simpleName}: ${e.message}")
            if (optional && (e is java.net.SocketTimeoutException || e is java.net.UnknownHostException ||
                    e is java.net.ConnectException || e is javax.net.ssl.SSLException || e is java.io.InterruptedIOException)
            ) markDead(host)
            throw e
        }
    }

    private val quickClient: OkHttpClient by lazy {
        client.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
    }
    private val deadUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun markDead(host: String) { deadUntil[host] = System.currentTimeMillis() + 5 * 60_000 }

    fun getJson(url: String, browser: Boolean = false, optional: Boolean = false): JsonElement? =
        get(url, browser, optional = optional)?.let { json.parseToJsonElement(it) }

    private val lastCall = HashMap<String, Long>()

    /** Keeps to the request rate the free services allow (MusicBrainz: 1 per second). */
    private fun throttle(host: String) {
        val gap = when {
            host.endsWith("musicbrainz.org") -> 1100L
            host.endsWith("itunes.apple.com") -> 400L
            host.endsWith("deezer.com") -> 150L
            else -> 0L
        }
        if (gap == 0L) return
        val wait = synchronized(lastCall) {
            val now = System.currentTimeMillis()
            val next = maxOf(now, (lastCall[host] ?: 0L) + gap)
            lastCall[host] = next
            next - now
        }
        if (wait > 0) Thread.sleep(wait)
    }

    /** Downloads binary data (images), limited to [maxBytes]. Null if it is not there or cannot be loaded. */
    fun download(url: String, maxBytes: Int = 8 * 1024 * 1024): ByteArray? = runCatching { downloadOrThrow(url, maxBytes) }.getOrNull()

    private fun downloadOrThrow(url: String, maxBytes: Int): ByteArray? {
        val req = Request.Builder().url(secure(url)).header("User-Agent", userAgent).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val body = r.body ?: return null
            val len = body.contentLength()
            if (len > maxBytes) return null
            val bytes = body.byteStream().readNBytesCompat(maxBytes + 1)
            return if (bytes.size > maxBytes) null else bytes
        }
    }

    /**
     * Downloads a file straight to disk (songs can be big). [onProgress] gets the bytes so far
     * and the total (-1 if unknown); returning false from it stops the download.
     * True when the whole file arrived.
     */
    fun downloadTo(url: String, target: java.io.File, maxBytes: Long = 400L * 1024 * 1024, onProgress: (Long, Long) -> Boolean = { _, _ -> true }): Boolean {
        val req = Request.Builder().url(secure(url)).header("User-Agent", userAgent).build()
        // a song takes longer than a search answer: no overall time limit, only "no data for a while"
        val patient = client.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).build()
        patient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for ${req.url.host}")
            val body = r.body ?: return false
            val total = body.contentLength()
            if (total > maxBytes) throw IOException("file is too big")
            target.parentFile?.mkdirs()
            var done = 0L
            body.byteStream().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done > maxBytes) throw IOException("file is too big")
                        if (!onProgress(done, total)) return false
                    }
                }
            }
            return total < 0 || done == total
        }
    }

    companion object {
        fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

        /** For a path segment ("/v1/Artist Name/Title"): spaces must be %20 there, not '+'. */
        fun encPath(s: String): String = enc(s).replace("+", "%20")

        const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0
    while (total < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}

// ---- tolerant JSON accessors

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.content?.toDoubleOrNull()
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
