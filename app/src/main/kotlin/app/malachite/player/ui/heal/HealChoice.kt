package app.malachite.player.ui.heal

import app.malachite.core.i18n.tr
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.malachite.player.MalachiteApp
import app.malachite.player.data.SoundIdKey
import app.malachite.player.heal.HealStep
import app.malachite.player.ui.LocalNavigator

private class StepInfo(val step: HealStep, val icon: ImageVector, val title: String, val hint: String)

/** The steps as the chooser shows them, in the order they are done. */
private fun stepInfos(): List<StepInfo> = listOf(
    StepInfo(HealStep.JUNK, Icons.Filled.Block, tr("Не музыка"), tr("видео, кэш мессенджеров, голосовые — в корзину")),
    StepInfo(HealStep.FORMATS, Icons.Filled.SwapHoriz, tr("Форматы файлов"), tr("расширение по настоящему формату")),
    StepInfo(HealStep.ODD_TAGS, Icons.Filled.Language, tr("Странные альбомы и исполнители"), tr("«AyuGram», сайты, «Разные исполнители» — проверка в интернете")),
    StepInfo(HealStep.SOUND_ID, Icons.Filled.Hearing, tr("Узнать песни по звуку"), tr("непонятные названия; нужен ключ AcoustID")),
    StepInfo(HealStep.TITLE_NUMBERS, Icons.Filled.Tag, tr("Номера в названиях"), tr("«01 - Песня» → «Песня»")),
    StepInfo(HealStep.MISSING, Icons.Filled.Album, tr("Пустые альбомы и исполнители"), tr("из тегов Android и папок")),
    StepInfo(HealStep.NAMES, Icons.Filled.Spellcheck, tr("Написание названий"), tr("опечатки, кириллица и латиница, украшения")),
    StepInfo(HealStep.FILENAMES, Icons.Filled.DriveFileRenameOutline, tr("Имена файлов"), tr("«2_3049492992.mp3» → название песни")),
    StepInfo(HealStep.DUPLICATES, Icons.Filled.ContentCopy, tr("Клоны песен"), tr("лишние копии с тем же звуком — в корзину")),
    StepInfo(HealStep.BROKEN, Icons.Filled.BrokenImage, tr("Повреждённые файлы"), tr("пустые и без звука — в корзину")),
    StepInfo(HealStep.COVERS, Icons.Filled.Image, tr("Обложки"), tr("для песен без обложки")),
    StepInfo(HealStep.COVER_QUALITY, Icons.Filled.HighQuality, tr("Качество обложек"), tr("мелкие, тяжёлые и одна картинка на разные альбомы")),
    StepInfo(HealStep.TRACK_NUMBERS, Icons.Filled.FormatListNumbered, tr("Номера треков"), tr("по списку песен альбома в интернете")),
    StepInfo(HealStep.COMPILATIONS, Icons.Filled.LibraryMusic, tr("Сборники"), tr("целый альбом разных исполнителей")),
    StepInfo(HealStep.LAYOUT, Icons.Filled.DriveFileMove, tr("Раскладка по папкам"), tr("Исполнитель / Альбом / Песня")),
    StepInfo(HealStep.LYRICS, Icons.Filled.Lyrics, tr("Тексты"), tr("найти недостающие; заменить тексты другим алфавитом")),
    StepInfo(HealStep.LOUDNESS, Icons.Filled.GraphicEq, tr("Громкость"), tr("измерить, чтобы песни звучали одинаково громко")),
    StepInfo(HealStep.LETTER_CASE, Icons.Filled.TextFields, tr("Регистр букв"), tr("«НАЗВАНИЕ КАПСОМ» → «Название капсом» — меняет всё найденное; многие пишут имена так нарочно")),
)

/**
 * What to heal: each step by itself or all at once. [paths] are the chosen songs; null is the
 * whole library. The choice is remembered for the next time.
 */
@Composable
fun HealChoiceDialog(paths: List<String>?, close: () -> Unit) {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val songs = paths != null
    val usual = if (songs) HealStep.USUAL_SONGS else HealStep.USUAL_LIBRARY
    var steps by remember {
        val s = g.settings.state.value
        mutableStateOf(HealStep.parse(if (songs) s.healStepsSongs else s.healSteps, usual))
    }
    val infos = remember { stepInfos() }
    val allOn = HealStep.ALL.all { it in steps }
    val someOn = HealStep.ALL.any { it in steps }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(if (paths != null) tr("Исцелить выбранные (${paths.size})") else tr("Что исцелить")) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text(
                    tr("Отметьте, что сделать — по отдельности или всё сразу. Делаются только уверенные исправления; сомнительные остаются во вкладке «Исцеление», их можно разобрать вручную."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth().clickable { steps = if (allOn) steps - HealStep.ALL else steps + HealStep.ALL }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TriStateCheckbox(
                        when {
                            allOn -> ToggleableState.On
                            someOn -> ToggleableState.Indeterminate
                            else -> ToggleableState.Off
                        },
                        onClick = null,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(tr("Всё сразу"), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { steps = usual }) { Text(tr("Как обычно")) }
                }
                HorizontalDivider()
                infos.filter { it.step !in HealStep.ONLY_BY_HAND }.forEach { info ->
                    StepRow(info, info.step in steps) { on -> steps = if (on) steps + info.step else steps - info.step }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text(tr("Только если отметить отдельно:"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                infos.filter { it.step in HealStep.ONLY_BY_HAND }.forEach { info ->
                    StepRow(info, info.step in steps) { on -> steps = if (on) steps + info.step else steps - info.step }
                }
                if (HealStep.SOUND_ID in steps && !SoundIdKey.present) Text(
                    tr("Ключа AcoustID нет в настройках — узнавание по звуку будет пропущено, остальное сделается."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                )
                Text(
                    tr("Ход виден во вкладке «Исцеление». Всё сделанное можно отменить там же."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = steps.isNotEmpty(), onClick = {
                val names = steps.map { it.name }
                g.settings.update { if (songs) it.copy(healStepsSongs = names) else it.copy(healSteps = names) }
                g.healing.heal(steps, paths)
                nav.toast(if (songs) tr("Исцеление выбранных началось") else tr("Исцеление началось"))
                close()
            }) { Text(tr("Исцелить (${steps.size})")) }
        },
        dismissButton = { TextButton(onClick = close) { Text(tr("Отмена")) } },
    )
}

@Composable
private fun StepRow(info: StepInfo, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Icon(info.icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(info.title, style = MaterialTheme.typography.bodyMedium)
            Text(info.hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
