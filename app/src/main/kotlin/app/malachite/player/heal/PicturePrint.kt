package app.malachite.player.heal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * A 64-bit print of what a picture looks like: shrunk to 8×8 grey squares, each one lighter or
 * darker than their average. The same picture gives (nearly) the same print however it was resized
 * or recompressed – so a photo lying in the main folder is recognised inside the songs it was
 * embedded into. 0 when the picture cannot be read.
 */
object PicturePrint {
    fun of(bytes: ByteArray, width: Int, height: Int): Long = runCatching {
        val o = BitmapFactory.Options().apply { inSampleSize = sample(width, height) }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)?.let(::print) ?: 0L
    }.getOrDefault(0L)

    fun of(file: File): Long = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0) return 0L
        val o = BitmapFactory.Options().apply { inSampleSize = sample(bounds.outWidth, bounds.outHeight) }
        BitmapFactory.decodeFile(file.absolutePath, o)?.let(::print) ?: 0L
    }.getOrDefault(0L)

    /** Decoded small (about 64 px) – enough for 8×8 squares, cheap for a big photo. */
    private fun sample(w: Int, h: Int): Int {
        var s = 1
        while (maxOf(w, h) / (s * 2) >= 64) s *= 2
        return s
    }

    private fun print(b: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(b, 8, 8, true)
        if (small !== b) b.recycle()
        val grey = IntArray(64)
        for (y in 0 until 8) for (x in 0 until 8) {
            val c = small.getPixel(x, y)
            grey[y * 8 + x] = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        small.recycle()
        val mean = grey.average()
        var bits = 0L
        for (i in 0 until 64) if (grey[i] > mean) bits = bits or (1L shl i)
        // a picture of one colour all over prints as 0: no likeness can be judged from it
        return bits
    }
}
