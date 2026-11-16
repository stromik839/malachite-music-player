package app.malachite.player.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import app.malachite.core.audio.TagEdit
import app.malachite.core.audio.TagIO
import app.malachite.core.heal.ImagePrep
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * Prepares a downloaded picture for embedding: decodes it, limits the size to 1000×1000 and
 * re-encodes as JPEG (the format every player, phone, PC and messenger can show).
 */
object AndroidImagePrep : ImagePrep {
    private const val MAX = 1000

    override fun prepare(bytes: ByteArray): TagEdit.Artwork? = prepare(bytes, square = false)

    /**
     * [square] cuts the picture to a centred square – for the user's own pictures (photos,
     * screenshots), so the cover looks the same in lists, the player and the widget. Photos are
     * also turned the right way up (the camera only notes the rotation in the file).
     */
    override fun prepare(bytes: ByteArray, square: Boolean): TagEdit.Artwork? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val isJpeg = bounds.outMimeType == "image/jpeg"
        val rotation = if (isJpeg) exifRotation(bytes) else 0
        val needsCrop = square && bounds.outWidth != bounds.outHeight
        // Already a reasonable JPEG: embed as is (no recompression loss).
        if (isJpeg && rotation == 0 && !needsCrop && bounds.outWidth <= MAX && bounds.outHeight <= MAX && bytes.size <= 600_000) {
            return TagEdit.Artwork(bytes, "image/jpeg", bounds.outWidth, bounds.outHeight)
        }
        // when cropping, the shorter side is what must stay at least MAX
        fun side(sample: Int) = if (needsCrop) minOf(bounds.outWidth, bounds.outHeight) / sample else maxOf(bounds.outWidth, bounds.outHeight) / sample
        var sample = 1
        while (side(sample * 2) >= MAX) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        fun replace(next: Bitmap) { if (next !== bmp) { bmp.recycle(); bmp = next } }
        if (rotation != 0) {
            replace(Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }, true))
        }
        if (needsCrop) {
            val s = minOf(bmp.width, bmp.height)
            replace(Bitmap.createBitmap(bmp, (bmp.width - s) / 2, (bmp.height - s) / 2, s, s))
        }
        val scale = minOf(1f, MAX.toFloat() / maxOf(bmp.width, bmp.height))
        if (scale < 1f) replace(Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        val w = bmp.width
        val h = bmp.height
        bmp.recycle()
        return TagEdit.Artwork(out.toByteArray(), "image/jpeg", w, h)
    }

    private fun exifRotation(bytes: ByteArray): Int = runCatching {
        val exif = android.media.ExifInterface(java.io.ByteArrayInputStream(bytes))
        when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)
}

/** Loads covers embedded in the song files themselves (with an in-memory cache). */
object CoverLoader {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /**
     * The big covers (the song screen's) are kept apart, a few of them: browsing long lists of
     * small covers used to push out the one of the song playing, and the song screen then opened
     * with "no cover" until the file was read again.
     */
    private val big = LruCache<String, Bitmap>(4)
    private const val BIG = 600
    private fun store(size: Int) = if (size >= BIG) big else cache

    /** The sizes the app asks for: lists, the mini player, the widget, album tiles, the song screen. */
    private val SIZES = intArrayOf(200, 220, 256, 300, 400, 512, 600, 900)

    private val missing = LruCache<String, Boolean>(2000)

    fun key(file: File, size: Int) = "${file.absolutePath}|${file.lastModified()}|$size"

    fun cached(file: File, size: Int): Bitmap? = store(size).get(key(file, size))

    /** The cover of [file] in [size] when it is in memory, otherwise in the nearest size that is (bigger ones first). */
    fun cachedAny(file: File, size: Int): Bitmap? {
        val base = "${file.absolutePath}|${file.lastModified()}|"
        return nearest(size) { s -> store(s).get(base + s) }
    }

    fun cachedUrlAny(url: String, size: Int): Bitmap? = nearest(size) { s -> store(s).get("url|$url|$s") }

    private inline fun nearest(size: Int, get: (Int) -> Bitmap?): Bitmap? {
        get(size)?.let { return it }
        for (s in SIZES) if (s > size) get(s)?.let { return it }
        for (i in SIZES.indices.reversed()) if (SIZES[i] < size) get(SIZES[i])?.let { return it }
        return null
    }

    fun isKnownMissing(file: File) = missing.get("${file.absolutePath}|${file.lastModified()}") == true

    /** Raw embedded picture bytes (Android's own reader first, our tag reader as fallback). */
    fun embeddedBytes(file: File): ByteArray? {
        val viaAndroid = runCatching {
            MediaMetadataRetriever().let { r ->
                try {
                    r.setDataSource(file.absolutePath)
                    r.embeddedPicture
                } finally {
                    runCatching { r.release() }
                }
            }
        }.getOrNull()
        return viaAndroid ?: runCatching { TagIO.readArtwork(file) }.getOrNull()
    }

    /** Decodes the cover scaled to about [size] px. Call off the main thread. */
    fun load(file: File, size: Int): Bitmap? {
        val k = key(file, size)
        store(size).get(k)?.let { return it }
        val mk = "${file.absolutePath}|${file.lastModified()}"
        if (missing.get(mk) == true) return null
        val bytes = embeddedBytes(file)
        if (bytes == null) { missing.put(mk, true); return null }
        val b = decode(bytes, size) ?: run { missing.put(mk, true); return null }
        store(size).put(k, b)
        return b
    }

    fun decode(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        // halving only comes near the size asked for (a 3000 px cover asked at 900 gave 1500 px, four
        // times the memory): the rest of the way by scaling, so more covers stay in memory and a
        // big one is quicker to put on the screen
        val side = minOf(b.width, b.height)
        if (side <= size * 5 / 4) return b
        val f = size.toFloat() / side
        val scaled = Bitmap.createScaledBitmap(b, (b.width * f).roundToInt().coerceAtLeast(1), (b.height * f).roundToInt().coerceAtLeast(1), true)
        if (scaled !== b) b.recycle()
        return scaled
    }

    /** Cover of a song played from the internet (downloaded once, then kept in memory). Call off the main thread. */
    fun loadUrl(url: String, size: Int): Bitmap? {
        val k = "url|$url|$size"
        store(size).get(k)?.let { return it }
        if (missing.get(k) == true) return null
        val b = runCatching { app.malachite.player.MalachiteApp.graph.http.download(url)?.let { decode(it, size) } }.getOrNull()
        if (b == null) missing.put(k, true) else store(size).put(k, b)
        return b
    }

    fun cachedUrl(url: String, size: Int): Bitmap? = store(size).get("url|$url|$size")

    /** Known to have no cover (the file was read, or the picture could not be downloaded). */
    fun isKnownMissingUrl(url: String, size: Int) = missing.get("url|$url|$size") == true

    fun invalidate() {
        cache.evictAll()
        big.evictAll()
        missing.evictAll()
        Frost.invalidate()
    }
}

/**
 * The cover blurred beforehand into a small picture (72 px). Stretched over the screen it looks like
 * frosted glass, yet costs nothing to draw: the blur used to be worked out in every frame (and the
 * screen of the song, the panels and the backdrop each had their own), which weak phones felt as
 * jerks. It also looks the same on every Android version, not only from Android 12.
 */
object Frost {
    private const val SIDE = 72
    private val cache = LruCache<String, Bitmap>(12)

    /** [key] names the song and the state of its file (see [coverKey]). */
    fun cached(key: String): Bitmap? = cache.get(key)

    /** Call off the main thread. */
    fun of(key: String, src: Bitmap): Bitmap {
        cache.get(key)?.let { return it }
        // the middle square of the picture, as the screen shows it
        val s = minOf(src.width, src.height).coerceAtLeast(1)
        val square = if (src.width == src.height) src else Bitmap.createBitmap(src, (src.width - s) / 2, (src.height - s) / 2, s, s)
        val small = Bitmap.createScaledBitmap(square, SIDE, SIDE, true)
        if (square !== src && square !== small) square.recycle()
        val px = IntArray(SIDE * SIDE)
        small.getPixels(px, 0, SIDE, 0, 0, SIDE, SIDE)
        if (small !== src) small.recycle()
        // three box blurs one after another come very close to a Gaussian one
        val tmp = IntArray(px.size)
        repeat(3) {
            box(px, tmp, SIDE, SIDE, 2, horizontal = true)
            box(tmp, px, SIDE, SIDE, 2, horizontal = false)
        }
        val out = Bitmap.createBitmap(px, SIDE, SIDE, Bitmap.Config.ARGB_8888)
        cache.put(key, out)
        return out
    }

    /** A running box average of radius [r] along rows or columns; the edges repeat their last pixel. */
    private fun box(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val n = 2 * r + 1
        for (line in 0 until lines) {
            val start = if (horizontal) line * w else line
            val step = if (horizontal) 1 else w
            fun px(i: Int) = src[start + i.coerceIn(0, len - 1) * step]
            var a = 0; var red = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val p = px(i)
                a += p ushr 24; red += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            for (i in 0 until len) {
                dst[start + i * step] = ((a / n) shl 24) or ((red / n) shl 16) or ((g / n) shl 8) or (b / n)
                val gone = px(i - r)
                val come = px(i + r + 1)
                a += (come ushr 24) - (gone ushr 24)
                red += ((come shr 16) and 0xFF) - ((gone shr 16) and 0xFF)
                g += ((come shr 8) and 0xFF) - ((gone shr 8) and 0xFF)
                b += (come and 0xFF) - (gone and 0xFF)
            }
        }
    }

    fun invalidate() = cache.evictAll()
}

/** What names a song's cover in the caches of the colours and of [Frost]: the song and the state of its file. */
fun coverKey(path: String, lastModified: Long) = "$path|$lastModified"

/**
 * Loads the covers (and their colours) of the songs next to the one playing, so that "next" and
 * "previous" change the cover and the background together with the title. Before, the new cover
 * was read from the file only after the switch, and for a moment (up to two seconds with a large
 * picture) the screen showed the new title over the old cover and colours.
 */
object CoverPrefetch {
    /** The sizes the song screen and the mini player (and from it the frosted glass and the colours) ask for. */
    private val SIZES = intArrayOf(900, 200)

    private val io = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "malachite-covers-ahead").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    @Volatile private var wanted: List<String> = emptyList()

    /** Songs ([paths]) that may come next; a newer call replaces what an older one still had to do. */
    fun around(paths: List<String>, shade: app.malachite.player.ui.theme.Shade, colours: Boolean) {
        if (paths.isEmpty() || paths == wanted) return
        wanted = paths
        io.execute {
            for (p in paths) {
                if (wanted !== paths) return@execute
                runCatching { warm(p, shade, colours) }
            }
        }
    }

    private fun warm(path: String, shade: app.malachite.player.ui.theme.Shade, colours: Boolean) {
        val lib = app.malachite.player.MalachiteApp.graph.library
        val url = lib.onlineItem(path)?.coverUrl
        val file = if (url == null) lib.file(path) else null
        var small: Bitmap? = null
        for (size in SIZES) {
            small = when {
                url != null -> CoverLoader.loadUrl(url, size)
                file != null -> CoverLoader.load(file, size)
                else -> null
            } ?: return
        }
        val pic = small ?: return
        // the frosted glass and the colours come from the small cover, the same one the screen uses
        val key = coverKey(path, lib.track(path)?.lastModified ?: 0L)
        Frost.of(key, pic)
        if (colours) app.malachite.player.ui.theme.CoverColors.compute(key, pic, shade)
    }
}
