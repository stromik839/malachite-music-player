package app.malachite.player.ui.settings

import app.malachite.core.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils

@Composable
fun SettingsPage(content: @Composable ColumnScope.() -> Unit) {
    // centred and not wider than a comfortable line of text – on tablets and laptops too
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

@Composable
fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Hint(subtitle)
        }
        Switch(checked, onChange)
    }
}

@Composable
fun <T> RadioRow(title: String, selected: T, value: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onSelect(value) }, verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected == value, { onSelect(value) })
        Text(title)
    }
}

@Composable
fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, label: (Float) -> String, steps: Int = 0, onChange: (Float) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value) }
    Column {
        Text("$title: ${label(v)}", style = MaterialTheme.typography.bodyLarge)
        Slider(v, { v = it }, valueRange = range, steps = steps, onValueChangeFinished = { onChange(v) })
    }
}

@Composable
fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Hint(subtitle)
    }
}

@Composable
fun ColorDot(color: Color?, selected: Boolean, size: Int = 36, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape)
            .background(color ?: MaterialTheme.colorScheme.surfaceVariant)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (color == null) Text("A", style = MaterialTheme.typography.labelSmall)
    }
}

/** Colour row: shows the colour and opens a picker. null = "from theme". */
@Composable
fun ColorSetting(title: String, value: Long?, allowDefault: Boolean = true, onChange: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        // a colour of one's own stays when the theme changes – say so right here
        if (allowDefault) Text(
            if (value == null) tr("как в теме") else tr("свой цвет"),
            Modifier.padding(end = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )
        ColorDot(value?.let { Color(it.toInt()) }, false, 28) { open = true }
    }
    if (open) ColorPickerDialog(value, allowDefault, { open = false }) { onChange(it); open = false }
}

private val SWATCHES = listOf(
    0xFF2E8B57, 0xFF1B5E20, 0xFF0B3D2E, 0xFF43A047, 0xFF00897B, 0xFF29B6F6, 0xFF1E88E5, 0xFF3949AB, 0xFF8E24AA,
    0xFFD81B60, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF6D4C41, 0xFF546E7A, 0xFF000000, 0xFF101512, 0xFF1E1E1E,
    0xFF2B2B2B, 0xFFFFFFFF, 0xFFF1F8F3,
)

/** Simple HSV picker: hue / saturation / brightness sliders plus ready-made swatches. */
@Composable
fun ColorPickerDialog(initial: Long?, allowDefault: Boolean, onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    // Starts from the colour of the current theme. It used to start from green, so opening the
    // picker and pressing "choose" pinned green there for good, whatever theme came later.
    val themeColor = MaterialTheme.colorScheme.primary.toArgb()
    val hsv = remember {
        FloatArray(3).also { android.graphics.Color.colorToHSV(initial?.toInt() ?: themeColor, it) }
    }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    val color = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Цвет")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(color))
                Text(tr("Оттенок")); Slider(h, { h = it }, valueRange = 0f..360f)
                Text(tr("Насыщенность")); Slider(s, { s = it })
                Text(tr("Яркость")); Slider(v, { v = it })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SWATCHES.forEach { sw ->
                        ColorDot(Color(sw.toInt()), false, 30) {
                            val arr = FloatArray(3)
                            android.graphics.Color.colorToHSV(sw.toInt(), arr)
                            h = arr[0]; s = arr[1]; v = arr[2]
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                val lum = ColorUtils.calculateLuminance(color.toArgb())
                Hint(if (lum > 0.6) tr("Светлый цвет") else if (lum < 0.08) tr("Очень тёмный цвет") else "")
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color.toArgb().toLong() and 0xFFFFFFFFL) }) { Text(tr("Выбрать")) } },
        dismissButton = {
            Row {
                if (allowDefault) TextButton(onClick = { onPick(null) }) { Text(tr("Как в теме")) }
                TextButton(onClick = onDismiss) { Text(tr("Отмена")) }
            }
        },
    )
}
