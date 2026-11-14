package app.malachite.player.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.malachite.core.i18n.tr
import java.io.ByteArrayOutputStream

/**
 * Lets the user choose which square part of a picture becomes the cover: drag to move, pinch
 * to zoom. [onDone] gets the cut-out picture as JPEG; [onUseWhole] – "take it as it is".
 */
@Composable
fun CropDialog(bytes: ByteArray, onDismiss: () -> Unit, onUseWhole: (() -> Unit)? = null, onDone: (ByteArray) -> Unit) {
    val bitmap = remember(bytes) { decodeForCrop(bytes) }
    if (bitmap == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            text = { Text(tr("Этот файл не читается как картинка")) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Закрыть")) } },
        )
        return
    }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var box by remember { mutableIntStateOf(0) }

    // the picture always covers the whole square: it cannot be dragged or shrunk off it
    fun shown(): Float = if (box == 0) 1f else box.toFloat() / minOf(bitmap.width, bitmap.height) * zoom
    fun clamp() {
        val s = shown()
        val maxX = ((bitmap.width * s - box) / 2f).coerceAtLeast(0f)
        val maxY = ((bitmap.height * s - box) / 2f).coerceAtLeast(0f)
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Обрезка обложки")) },
        text = {
            Column {
                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                        .clipToBounds()
                        .onSizeChanged { box = minOf(it.width, it.height); clamp() }
                        .pointerInput(bitmap) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                zoom = (zoom * gestureZoom).coerceIn(1f, 6f)
                                offsetX += pan.x
                                offsetY += pan.y
                                clamp()
                            }
                        },
                ) {
                    // the whole picture is drawn (not only its middle), so every part of it can be reached
                    val image = remember(bitmap) { bitmap.asImageBitmap() }
                    Canvas(Modifier.fillMaxSize()) {
                        val s = shown()
                        val w = (bitmap.width * s).toInt()
                        val h = (bitmap.height * s).toInt()
                        drawImage(
                            image,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(bitmap.width, bitmap.height),
                            dstOffset = IntOffset((box / 2f + offsetX - w / 2f).toInt(), (box / 2f + offsetY - h / 2f).toInt()),
                            dstSize = IntSize(w, h),
                        )
                    }
                }
                Text(
                    tr("Двигайте картинку пальцем, сводите и разводите два пальца, чтобы приблизить. В обложку попадёт то, что видно в квадрате."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = box > 0, onClick = {
                val s = shown()
                val side = (box / s).toInt().coerceIn(1, minOf(bitmap.width, bitmap.height))
                val left = (((bitmap.width * s - box) / 2f - offsetX) / s).toInt().coerceIn(0, bitmap.width - side)
                val top = (((bitmap.height * s - box) / 2f - offsetY) / s).toInt().coerceIn(0, bitmap.height - side)
                val cut = runCatching { Bitmap.createBitmap(bitmap, left, top, side, side) }.getOrNull()
                val out = ByteArrayOutputStream()
                if (cut != null && cut.compress(Bitmap.CompressFormat.JPEG, 93, out)) onDone(out.toByteArray()) else onDismiss()
            }) { Text(tr("Готово")) }
        },
        dismissButton = {
            if (onUseWhole != null) TextButton(onClick = onUseWhole) { Text(tr("Без обрезки")) }
            TextButton(onClick = onDismiss) { Text(tr("Отмена")) }
        },
    )
}

/** The picture for the crop screen: turned the right way up and not larger than a cover needs. */
private fun decodeForCrop(bytes: ByteArray): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
    val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    // photos from a camera are stored lying on their side with a note how to turn them
    val degrees = runCatching {
        when (android.media.ExifInterface(java.io.ByteArrayInputStream(bytes)).getAttributeInt(
            android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL,
        )) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    if (degrees == 0f) raw
    else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, android.graphics.Matrix().apply { postRotate(degrees) }, true)
}.getOrNull()
