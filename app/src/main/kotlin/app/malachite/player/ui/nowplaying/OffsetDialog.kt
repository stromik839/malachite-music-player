package app.malachite.player.ui.nowplaying

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.malachite.core.i18n.tr
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** The longest shift that can be typed: ten minutes either way. */
private const val MAX_SHIFT_MS = 600_000L

/** "8.8", "12", "0.25": seconds without trailing zeros. */
internal fun secondsText(ms: Long): String =
    String.format(Locale.US, "%.3f", abs(ms) / 1000.0).trimEnd('0').trimEnd('.').ifEmpty { "0" }

/** Typed seconds ("8,8", "8.8", "12") to milliseconds; null when it is not a number or too big. */
internal fun parseSeconds(text: String): Long? {
    val v = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    val ms = (v * 1000).roundToLong()
    return ms.takeIf { it in 0..MAX_SHIFT_MS }
}

/**
 * The lyrics shift typed in seconds instead of tapping ±0.1 s many times. "Later" moves the
 * lines later than the song, "earlier" before it (a separate choice: number keyboards often
 * have no minus key).
 */
@Composable
fun OffsetDialog(
    currentMs: Long,
    onSet: (Long) -> Unit,
    onDismiss: () -> Unit,
    /** The lyrics' pace (1f: as they are) and how to change it at once (null: not offered). */
    tempo: Float = 1f,
    onTempo: ((Float) -> Unit)? = null,
) {
    var earlier by remember { mutableStateOf(currentMs < 0) }
    var text by remember { mutableStateOf(secondsText(currentMs)) }
    val typed = parseSeconds(text)
    val value = typed?.let { if (earlier) -it else it }
    fun set(ms: Long) {
        val v = ms.coerceIn(-MAX_SHIFT_MS, MAX_SHIFT_MS)
        earlier = v < 0
        text = secondsText(v)
    }
    fun done() { if (value != null) { onSet(value); onDismiss() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Сдвиг текста")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tr("На сколько секунд сдвинуть текст относительно песни."),
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!earlier, { earlier = false }, label = { Text(tr("Позже (+)")) })
                    FilterChip(earlier, { earlier = true }, label = { Text(tr("Раньше (−)")) })
                }
                OutlinedTextField(
                    text,
                    { t -> text = t.filter { it.isDigit() || it == '.' || it == ',' }.take(8) },
                    singleLine = true,
                    label = { Text(tr("Секунды")) },
                    isError = typed == null,
                    supportingText = if (typed == null) ({ Text(tr("Число от 0 до 600, например 8,8")) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { done() }),
                )
                // quick steps from what is typed now
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-1000L to tr("−1 с"), -100L to tr("−0.1 с"), 100L to tr("+0.1 с"), 1000L to tr("+1 с")).forEach { (d, label) ->
                        AssistChip(onClick = { set((value ?: 0L) + d) }, label = { Text(label) })
                    }
                    AssistChip(onClick = { set(0L) }, label = { Text(tr("Без сдвига")) })
                }
                if (onTempo != null) {
                    androidx.compose.material3.HorizontalDivider()
                    // the pace: for lyrics right at the start of the song that drift towards its end
                    Text(tr("Темп текста: %.1f %%").format(tempo * 100), style = MaterialTheme.typography.titleSmall)
                    Text(
                        tr("Если в начале песни текст совпадает, а к концу всё сильнее отстаёт — ускорьте его; если убегает вперёд — замедлите. Первая строка остаётся на месте, меняется сразу."),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        fun step(d: Float) = onTempo(((tempo + d) * 1000).let { kotlin.math.round(it) / 1000f }.coerceIn(MIN_TEMPO, MAX_TEMPO))
                        AssistChip(onClick = { step(-0.01f) }, label = { Text(tr("Медленнее 1 %")) })
                        AssistChip(onClick = { step(-0.002f) }, label = { Text(tr("−0,2 %")) })
                        AssistChip(onClick = { step(0.002f) }, label = { Text(tr("+0,2 %")) })
                        AssistChip(onClick = { step(0.01f) }, label = { Text(tr("Быстрее 1 %")) })
                        if (tempo != 1f) AssistChip(onClick = { onTempo(1f) }, label = { Text(tr("Как было")) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = ::done, enabled = value != null) { Text(tr("Готово")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена")) } },
    )
}

/** How far the lyrics' pace can go: a fifth slower or a quarter faster (a sped-up version has its own lyrics). */
private const val MIN_TEMPO = 0.8f
private const val MAX_TEMPO = 1.25f
