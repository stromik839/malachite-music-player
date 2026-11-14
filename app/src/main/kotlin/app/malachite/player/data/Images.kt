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
    private val missing = LruCache<String, Boolean>(2000)

    fun key(file: File, size: Int) = "${file.absolutePath}|${file.lastModified()}|$size"

    fun cached(file: File, size: Int): Bitmap? = cache.get(key(file, size))

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
        cache.get(k)?.let { return it }
        val mk = "${file.absolutePath}|${file.lastModified()}"
        if (missing.get(mk) == true) return null
        val bytes = embeddedBytes(file)
        if (bytes == null) { missing.put(mk, true); return null }
        val b = decode(bytes, size) ?: run { missing.put(mk, true); return null }
        cache.put(k, b)
        return b
    }

    fun decode(bytes: ByteArray, size: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= size) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Cover of a song played from the internet (downloaded once, then kept in memory). Call off the main thread. */
    fun loadUrl(url: String, size: Int): Bitmap? {
        val k = "url|$url|$size"
        cache.get(k)?.let { return it }
        if (missing.get(k) == true) return null
        val b = runCatching { app.malachite.player.MalachiteApp.graph.http.download(url)?.let { decode(it, size) } }.getOrNull()
        if (b == null) missing.put(k, true) else cache.put(k, b)
        return b
    }

    fun cachedUrl(url: String, size: Int): Bitmap? = cache.get("url|$url|$size")

    fun invalidate() {
        cache.evictAll()
        missing.evictAll()
    }
}

/**
 * Loads the covers (and their colours) of the songs next to the one playing, so that "next" and
 * "previous" change the cover and the background together with the title. Before, the new cover
 * was read from the file only after the switch, and for a moment (up to two seconds with a large
 * picture) the screen showed the new title over the old cover and colours.
 */
object CoverPrefetch {
    /** The sizes the song screen, the app's background and the mini player ask for. */
    private val SIZES = intArrayOf(900, 400, 200)

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
        var big: Bitmap? = null
        for (size in SIZES) {
            val b = when {
                url != null -> CoverLoader.loadUrl(url, size)
                file != null -> CoverLoader.load(file, size)
                else -> null
            } ?: return
            if (big == null) big = b
        }
        if (colours && big != null) app.malachite.player.ui.theme.coverSchemeCached(big, shade)
    }
}
