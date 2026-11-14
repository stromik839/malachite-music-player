package app.malachite.core

import app.malachite.core.online.OnlineCatalog.Kind
import app.malachite.core.cover.AlbumFinder
import app.malachite.core.cover.CoverFinder
import app.malachite.core.lyrics.LyricsFinder
import app.malachite.core.net.Http
import java.io.File
import kotlin.test.Test

/**
 * Real requests to the lyrics / album / cover services, run only in CI (where the internet
 * is reachable) when the file "live-probe.on" exists. It never fails the build: it prints
 * what each service answers for songs from the real library.
 */
class LiveProbeTest {
    private val songs = listOf(
        Triple("Алёна Апина", "Алена Апина - \"Лёха\" (Official Video)", 237_000L),
        Triple("Свидетельство О Смерти", "Вернувшиеся с войны", 271_000L),
        Triple("Свидетельство О Смерти", "7-я пятница", 226_000L),
        Triple("Свидетельство О Смерти", "Когда я ответил да (Sefon.Pro)", 200_000L),
        Triple("Свидетельство О Смерти", "Когда я ответил да", 269_000L),
        Triple("Imagine Dragons", "Believer", 204_000L),
        Triple("Кино", "Группа крови", 285_000L),
        Triple("Король и Шут", "Лесник", 0L),
        Triple("Сплин", "Выхода нет", 0L),
        Triple("Queen", "Bohemian Rhapsody", 355_000L),
    )

    @Test
    fun probe() {
        if (!File("live-probe.on").exists()) return
        val out = StringBuilder()
        fun log(s: String) { println(s); out.append(s).append('\n') }
        val http = Http()
        for (u in listOf(
            "https://lrclib.net/api/search?q=" + Http.enc("Кино Группа крови"),
            "https://music.163.com/api/cloudsearch/pc?s=" + Http.enc("Queen Bohemian Rhapsody") + "&type=1&limit=3",
            "https://music.163.com/api/search/pc?s=" + Http.enc("Queen Bohemian Rhapsody") + "&type=1&limit=3",
            "https://genius.com/api/search/song?q=" + Http.enc("Алёна Апина Лёха") + "&per_page=3",
            "https://api.lyrics.ovh/v1/Queen/Bohemian%20Rhapsody",
            "https://api.deezer.com/search?q=" + Http.enc("Алёна Апина Лёха"),
            "https://itunes.apple.com/search?term=" + Http.enc("Алёна Апина Лёха") + "&entity=song&limit=3&country=RU",
            "https://musicbrainz.org/ws/2/recording/?query=" + Http.enc("recording:\"Лёха\" AND artist:\"Апина\"") + "&fmt=json&limit=3",
            "https://api.music.yandex.net/search?text=" + Http.enc("Свидетельство о смерти Всем смертям назло") + "&type=album&page=0&nocorrect=false",
            "https://api.music.yandex.net/search?text=" + Http.enc("Вещие Ослы Ботинки") + "&type=track&page=0&nocorrect=false",
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/maxresdefault.jpg",
            "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq720.jpg",
        )) {
            val r = runCatching { http.get(u, browser = u.contains("genius") || u.contains("163")) }
            log("RAW ${u.substringBefore('?')}: " + r.fold({ "ok ${it?.length ?: -1} chars: ${it?.take(700)?.replace('\n', ' ')}" }, { "FAIL $it" }))
        }
        for (filter in listOf("a", "t")) {
            val body = "{\"search_text\":\"Вещие Ослы Безнадёжность\",\"search_filter\":\"$filter\",\"full_page\":false,\"fan_id\":null}"
            val r = runCatching { http.postJson("https://bandcamp.com/api/bcsearch_public_api/1/autocomplete_elastic", body) }
            log("RAW bandcamp [$filter]: " + r.fold({ it.toString().take(900) }, { "FAIL $it" }))
        }
        for ((artist, album, title) in listOf(
            Triple("Вещие Ослы", "Безнадёжность", ""),
            Triple("Вещие Ослы", "", "Ботинки"),
            Triple("Борис Кузьмин", "Время выткало нить", ""),
            Triple("Свидетельство О Смерти", "Кто плачет, тот живёт", ""),
            Triple("мой друг магнитофон", "", "обосранный герой"),
            Triple("Оргазм Нострадамуса", "", "Сердешная"),
            Triple("FRESH HOOBS", "", "icarus"),
        )) {
            val t0 = System.currentTimeMillis()
            val cv = runCatching { CoverFinder(http).search(artist, album, title) }.getOrElse { log("cover FAIL $it"); emptyList() }
            log("COVER $artist | $album | $title (${System.currentTimeMillis() - t0} ms): " +
                cv.take(5).joinToString(" | ") { "${it.source} ${it.artist} — ${it.album} ${"%.2f".format(it.score)}" })
        }
        for (u in listOf(
            "https://api.audius.co",
            "https://discoveryprovider.audius.co/v1/tracks/search?query=" + Http.enc("lofi") + "&app_name=Malachite",
            "https://archive.org/advancedsearch.php?q=" + Http.enc("(Кино) AND mediatype:(audio)") + "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&rows=3&output=json",
        )) {
            val r = runCatching { http.get(u) }
            log("RAW ${u.substringBefore('?')}: " + r.fold({ "ok ${it?.length ?: -1} chars: ${it?.take(900)?.replace('\n', ' ')}" }, { "FAIL $it" }))
        }
        val online = app.malachite.core.online.OnlineCatalog(http)
        // whole names, a part of a title, names with special signs, a name typed without them
        for (q in listOf("lofi", "Кино", "Свидетельство о смерти", "Bach", "звезда по имени", "P!nk", "AC/DC", "ACDC thunder", "Король и Шут")) {
            val found = runCatching { online.search(q) }.getOrElse { log("ONLINE FAIL $it"); emptyList() }
            val bySource = found.groupingBy { it.source + (if (it.isRelease) " releases" else "") + if (it.preview) " (30 s)" else "" }.eachCount()
            log("ONLINE «$q»: ${found.size} $bySource | " + (found.filter { !it.preview && !it.isRelease }.take(6) + found.filter { it.isRelease }.take(1) + found.filter { it.preview }.take(2)).joinToString(" | ") {
                "${it.source}${if (it.isRelease) "[release]" else ""}${if (it.preview) "[30s]" else ""} ${it.artist} — ${it.title} ${it.durationSec}s dl=${it.downloadUrl != null} cover=${it.coverUrl != null}"
            })
            found.firstOrNull { it.isRelease }?.let { rel ->
                val tr = runCatching { online.tracksOf(rel) }.getOrElse { log("ONLINE tracks FAIL $it"); emptyList() }
                log("ONLINE tracks of ${rel.id}: ${tr.size} | " + tr.take(4).joinToString(" | ") { "${it.title} .${it.extension} ${it.durationSec}s ${it.streamUrl.takeLast(60)}" })
            }
        }
        // searching by artist / album / song, with the parts of a query in any order
        for ((q, kind) in listOf(
            "molchat doma" to Kind.ALL, "молчат дома, судно" to Kind.SONGS, "судно, molchat doma" to Kind.SONGS,
            "molchat doma" to Kind.ARTISTS, "молчат дома" to Kind.ARTISTS, "Этажи, Молчат Дома" to Kind.ALBUMS, "kino gruppa krovi" to Kind.SONGS,
        )) {
            val r = runCatching { online.find(q, kind) }.getOrElse { log("FIND FAIL «$q» $it"); null } ?: continue
            log("FIND «$q» $kind")
            log("   artists: " + r.artists.take(5).joinToString(" | ") { "${it.source} ${it.title}${if (it.verified) " ✓" else ""} fans=${it.popularity} songs=${it.count}" })
            log("   albums: " + r.albums.take(5).joinToString(" | ") { "${it.source} ${it.artist} — ${it.title} [${it.releaseType}] ${it.count}" })
            log("   songs: " + r.songs.take(10).joinToString(" | ") { "${it.source}${if (it.preview) "[30s]" else ""} ${it.artist} — ${it.title}" })
            r.artists.firstOrNull()?.let { a ->
                val t = runCatching { online.tracksOf(a) }.getOrElse { log("   artist tracks FAIL $it"); emptyList() }
                log("   songs of artist ${a.id}: ${t.size}, whole ${t.count { !it.preview }}, downloadable ${t.count { it.downloadUrl != null }} | " + t.take(5).joinToString(" | ") { "${it.source} ${it.artist} — ${it.title}${if (it.preview) " [30s]" else ""}" })
            }
            r.albums.firstOrNull()?.let { a ->
                val t = runCatching { online.tracksOf(a) }.getOrElse { log("   album tracks FAIL $it"); emptyList() }
                log("   songs of album ${a.id}: ${t.size}, whole ${t.count { !it.preview }} | " + t.take(5).joinToString(" | ") { "${it.source} ${it.title}${if (it.preview) " [30s]" else ""}" })
            }
        }
        val lyrics = LyricsFinder(http)
        val albums = AlbumFinder(http)
        // the artist by the song and its album (for an artist tag that is a site or a placeholder)
        for ((song, album) in listOf("Группа крови" to "Группа крови", "Судно (Борис Рыжий)" to "Этажи", "Bohemian Rhapsody" to "A Night at the Opera")) {
            val a = runCatching { albums.findArtist(song, album) }.getOrElse { log("ARTIST FAIL $it"); emptyList() }
            log("ARTIST of «$song» on «$album»: " + a.take(3).joinToString(" | ") { "${it.artist} ${it.source} x${it.sources} ${"%.2f".format(it.score)}${if (it.confident) " ✓" else ""}" })
        }
        // recognition by sound: with a made-up key the service must say the key is wrong (the form of the
        // request and of its answer are right); no real key is ever kept in the repository
        run {
            val tone = ShortArray(app.malachite.core.soundid.SoundPrint.SAMPLE_RATE * 20) { i -> (8000 * kotlin.math.sin(2 * Math.PI * (220 + i / 2000) * i / app.malachite.core.soundid.SoundPrint.SAMPLE_RATE)).toInt().toShort() }
            val a = app.malachite.core.soundid.AcoustId(http).lookup("not-a-real-key", app.malachite.core.soundid.SoundPrint.encoded(tone), 20)
            log("ACOUSTID with a made-up key: $a")
        }
        val covers = CoverFinder(http)
        for ((artist, title, dur) in songs) {
            log("\n=== $artist — $title")
            val t0 = System.currentTimeMillis()
            val c = runCatching { lyrics.search(title, artist, "", dur) }.getOrElse { log("lyrics FAIL $it"); emptyList() }
            log("lyrics (${System.currentTimeMillis() - t0} ms): picked = " + LyricsFinder.pick(c)?.let { "${it.source} synced=${it.synced} ${"%.2f".format(it.score)} ${it.artist} — ${it.title} (${it.lyrics.lines.size} lines)" })
            c.take(5).forEach { log("   ${it.source} synced=${it.synced} ${"%.2f".format(it.score)} ${it.artist} — ${it.title} [${it.album}] ${it.durationSec.toInt()}s lines=${it.lyrics.lines.size} first='${it.lyrics.lines.firstOrNull()?.text}'") }
            val a = runCatching { albums.find(artist, title, dur) }.getOrElse { log("album FAIL $it"); emptyList() }
            log("album: " + a.take(4).joinToString(" | ") { "${it.album} (${it.year}) ${it.source} x${it.sources} ${"%.2f".format(it.score)}${if (it.confident) " ✓" else ""}" })
            val cv = runCatching { covers.search(artist, "", title, durationMs = dur) }.getOrElse { log("cover FAIL $it"); emptyList() }
            log("cover: " + cv.take(3).joinToString(" | ") { "${it.source} ${it.artist} — ${it.album} ${"%.2f".format(it.score)} ${it.note}" })
        }
        File("build").mkdirs()
        File("build/live-probe.txt").writeText(out.toString())
    }
}
