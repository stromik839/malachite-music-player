package app.malachite.player.ui.settings

import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.saveable.rememberSaveable
import app.malachite.core.i18n.tr
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.malachite.core.library.LibraryStore
import app.malachite.core.library.ZipTransfer
import app.malachite.player.MalachiteApp
import app.malachite.player.data.AccentPreset
import app.malachite.player.data.FontChoice
import app.malachite.player.data.LyricsAlign
import app.malachite.player.data.NavPosition
import app.malachite.player.data.PartStyle
import app.malachite.player.data.Privileged
import app.malachite.player.data.Storage
import app.malachite.player.data.Tab
import app.malachite.player.data.ThemeMode
import app.malachite.player.playback.AudioEffects
import app.malachite.player.service.LongTaskService
import app.malachite.player.ui.LocalNavigator
import app.malachite.player.ui.Screen
import app.malachite.player.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SettingsScreen() {
    val nav = LocalNavigator.current
    val g = MalachiteApp.graph
    val st by g.settings.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    SettingsPage {
        // a search over the settings and the help: write what you need – "обложка", "тема", "текст"
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(),
            placeholder = { Text(tr("Найти настройку или ответ…")) },
            leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true,
        )
        if (query.isNotBlank()) {
            app.malachite.player.ui.help.SettingsSearchResults(query)
            return@SettingsPage
        }
        Group(tr("Помощь")) {
            NavRow(tr("Вопросы и ответы"), tr("Где настроить текст, как изменить название, обложку, вид меню…")) { nav.push(Screen.Help) }
            NavRow(tr("Показать обучение ещё раз"), tr("Короткое знакомство с плеером")) { g.settings.update { it.copy(tourShown = false) } }
        }
        Group(tr("Внешний вид")) {
            NavRow(tr("Тема, цвета и язык"), tr("Тёмная или светлая тема, цвет акцента, значок, шрифт меню, язык интерфейса")) { nav.push(Screen.SettingsAppearance) }
            NavRow(tr("Меню и вкладки"), tr("Где панель вкладок, какие вкладки и в каком порядке, мини-плеер, прозрачность")) { nav.push(Screen.SettingsMenu) }
            NavRow(tr("Виджет на рабочем столе"), tr("Цвета, текст песни в виджете и его вид, перемотка")) { nav.push(Screen.SettingsWidgets) }
        }
        Group(tr("Музыка и тексты")) {
            NavRow(tr("Тексты песен"), tr("Шрифт и вид текста, любимые строки, поиск текстов, синхронизация")) { nav.push(Screen.SettingsLyrics) }
            NavRow(tr("Воспроизведение и звук"), tr("Скорость, питч, эквалайзер, пропуск тишины, уведомление")) { nav.push(Screen.SettingsPlayback) }
            SwitchRow(
                tr("Поиск и скачивание музыки в интернете"), st.onlineEnabled,
                tr("Выключено — плеер полностью офлайн. Включите, и появится раздел «Онлайн»: открытые каталоги Audius и Internet Archive, а для остальных песен — отрывки по 30 секунд"),
            ) { v -> g.settings.update { it.copy(onlineEnabled = v, tabs = if (v) it.tabs else it.tabs - Tab.ONLINE) } }
            if (st.onlineEnabled) {
                NavRow(tr("Открыть раздел «Онлайн»"), tr("Его можно добавить и на панель вкладок: «Меню и вкладки» → «Вкладки и их порядок»")) { nav.push(Screen.Online()) }
                OnlineCacheRows()
            }
        }
        Group(tr("Библиотека")) {
            NavRow(tr("Главная папка и новые песни"), tr("Где лежит музыка, поиск новых песен, что не считать музыкой, удаление оригиналов")) { nav.push(Screen.SettingsLibrary) }
            NavRow(tr("Исцеление"), tr("Порядок в названиях, альбомах и обложках; отмена исцеления")) { nav.openTab(Tab.HEAL) }
            NavRow(tr("Узнавание песен по звуку"), tr("Ключ AcoustID: песни «115620301» и без исполнителя узнаются по звуку при исцелении")) { nav.push(Screen.SettingsSoundId) }
            NavRow(tr("Процессы и защита данных"), tr("Пароль на данные плеера, незаконченные синхронизации, что выполняется сейчас")) { nav.push(Screen.Processes) }
            NavRow(tr("Перенос и резервные копии"), tr("ZIP главной папки для другого телефона, корзина, копии файлов")) { nav.push(Screen.SettingsBackup) }
        }
        Group(tr("Дополнительно")) {
            NavRow(tr("Root и Shizuku"), tr("Закрытые папки приложений, защита от выгрузки, разрешения")) { nav.push(Screen.SettingsAdvanced) }
            NavRow(tr("Ошибки и журнал"), tr("Журнал в файл (выключен), отчёт о сбое, сообщить об ошибке")) { nav.push(Screen.SettingsDiagnostics) }
        }
        Group(tr("О приложении")) {
            var whatsNew by remember { mutableStateOf(false) }
            if (whatsNew) app.malachite.player.ui.help.WhatsNewDialog { whatsNew = false }
            NavRow(tr("Что нового"), tr("Сборка ${app.malachite.player.ui.help.appVersion()} — что появилось в последних обновлениях")) { whatsNew = true }
            NavRow(tr("О приложении"), tr("Автор, лицензия, соглашение, исходный код")) { nav.push(Screen.About) }
            NavRow(tr("Поддержать автора"), tr("Только по желанию — спасибо!")) { nav.push(Screen.Support) }
        }
        Hint(tr("Malachite • без рекламы, без аккаунтов, без слежки. Все данные — в главной папке."))
        Hint(tr("Установлена сборка ${app.malachite.player.ui.help.appVersion()}"))
    }
}

// ---------------------------------------------------------------------- appearance

@Composable
fun SettingsAppearanceScreen() {
    val g = MalachiteApp.graph
    val nav = LocalNavigator.current
    val s by g.settings.state.collectAsState()
    val context = LocalContext.current
    fun up(f: (app.malachite.player.data.AppSettings) -> app.malachite.player.data.AppSettings) = g.settings.update(f)
    var fontTarget by remember { mutableStateOf("menu") }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val dir = File(context.filesDir, "fonts").apply { mkdirs() }
            val out = File(dir, "${fontTarget}_${System.currentTimeMillis()}.ttf")
            runCatching { context.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } } }
            if (out.isFile) {
                val problem = app.malachite.player.ui.theme.fontProblem(out)
                if (problem != null) android.widget.Toast.makeText(context, problem, android.widget.Toast.LENGTH_LONG).show()
                if (fontTarget == "menu") up { it.copy(menuFont = FontChoice.CUSTOM, menuFontFile = out.absolutePath) }
                else up { it.copy(lyricsFont = FontChoice.CUSTOM, lyricsFontFile = out.absolutePath) }
            }
        }
    }
    SettingsPage {
        Group(tr("Язык интерфейса") + " · Interface language") {
            val language = remember { app.malachite.player.data.Language.choice(context) }
            app.malachite.player.data.Language.Choice.entries.forEach { c ->
                RadioRow(c.title, language, c) { v -> if (v != language) app.malachite.player.data.Language.change(context, v) }
            }
            Hint(tr("Авто: русский, если язык телефона русский, иначе английский. После смены языка плеер перезапустится (музыка остановится)."))
        }
        Group(tr("Эффекты")) {
            SwitchRow(
                tr("Анимации интерфейса"), s.animations,
                tr("Экраны плавно сменяются, таблетка вкладок скользит, экран песни выезжает. Выключено — всё меняется сразу. У визуализатора и текста песни свои настройки, этот переключатель их не трогает"),
            ) { v -> up { it.copy(animations = v) } }
            SwitchRow(
                tr("Тени-градиенты"), s.gradients,
                tr("Мягкие тени у краёв верхней панели, мини-плеера и панели вкладок и лёгкий блеск на самих панелях"),
            ) { v -> up { it.copy(gradients = v) } }
            SwitchRow(
                tr("Размытие"), s.blur,
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) tr("Матовое стекло: размытая обложка играющей песни за верхней панелью, панелью вкладок и мини-плеером. Чуть больше работы для телефона")
                else tr("Нужен Android 12 или новее: на этом телефоне размытие не видно"),
            ) { v -> up { it.copy(blur = v) } }
        }
        Group(tr("Тема меню")) {
            RadioRow(tr("Тёмная (по умолчанию)"), s.themeMode, ThemeMode.DARK) { v -> up { it.copy(themeMode = v) } }
            RadioRow(tr("Тёмная AMOLED (чёрный фон)"), s.themeMode, ThemeMode.AMOLED) { v -> up { it.copy(themeMode = v) } }
            RadioRow(tr("Светлая"), s.themeMode, ThemeMode.LIGHT) { v -> up { it.copy(themeMode = v) } }
            RadioRow(tr("Как в системе"), s.themeMode, ThemeMode.SYSTEM) { v -> up { it.copy(themeMode = v) } }
        }
        Group(tr("Цвет акцента")) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AccentPreset.entries.filter { it != AccentPreset.CUSTOM }.forEach { p ->
                    ColorDot(Color(p.argb.toInt()), s.accent == p) { up { it.copy(accent = p) } }
                }
            }
            Text(tr("Выбрано: ${s.accent.title}"), style = MaterialTheme.typography.bodySmall)
            ColorSetting(tr("Свой цвет акцента"), if (s.accent == AccentPreset.CUSTOM) s.customAccent else null, allowDefault = false) { c ->
                if (c != null) up { it.copy(accent = AccentPreset.CUSTOM, customAccent = c) }
            }
            ColorSetting(tr("Свой цвет фона"), s.customBackground) { c -> up { it.copy(customBackground = c) } }
            ColorSetting(tr("Свой цвет поверхностей"), s.customSurface) { c -> up { it.copy(customSurface = c) } }
            Hint(tr("Тема применяется к меню плеера. Экран открытой песни может брать цвета из обложки."))
            // Parts with a colour of their own do not follow the theme: show them and offer to let go.
            val pinned = buildList {
                if (s.customBackground != null) add(tr("фон меню"))
                if (s.customSurface != null) add(tr("поверхности"))
                if (s.navStyle.color != null) add(tr("панель вкладок"))
                if (s.miniPlayerStyle.color != null) add(tr("мини-плеер"))
                if (s.topBarStyle.color != null) add(tr("верхняя строка"))
                if (s.widget.backgroundColor != null && !s.widget.useCoverColors) add(tr("виджет"))
                if (s.lyricsSungColor != null || s.lyricsActiveColor != null) add(tr("текст песни"))
            }
            if (pinned.isNotEmpty()) {
                Hint(tr("Своим цветом закреплены: ${pinned.joinToString(", ")}. Эти части не меняются, когда вы меняете тему."))
                androidx.compose.material3.OutlinedButton(onClick = {
                    up {
                        it.copy(
                            customBackground = null, customSurface = null,
                            navStyle = it.navStyle.copy(color = null),
                            miniPlayerStyle = it.miniPlayerStyle.copy(color = null),
                            topBarStyle = it.topBarStyle.copy(color = null),
                            widget = it.widget.copy(backgroundColor = null),
                            lyricsSungColor = null, lyricsActiveColor = null,
                        )
                    }
                }) { Text(tr("Сбросить свои цвета — пусть всё следует теме")) }
            }
        }
        Group(tr("Значок приложения")) {
            app.malachite.player.data.IconColor.entries.forEach { c ->
                if (c != app.malachite.player.data.IconColor.SYSTEM || app.malachite.player.data.AppIcon.systemColorsAvailable) {
                    RadioRow(c.title, s.iconColor, c) { v -> up { it.copy(iconColor = v) } }
                }
            }
            val now = app.malachite.player.data.AppIcon.wanted(context, s)
            Hint(
                tr("Значок есть в восьми цветах — как готовые цвета акцента; для своего цвета берётся ближайший. Сейчас: ${now.title.substringBefore(" (")}. " +
                    "Значок меняется, когда вы сворачиваете плеер. Некоторые рабочие столы при этом переставляют его в конец экрана " +
                    "или убирают плеер из списка недавних — так устроена смена значка в Android."),
            )
            Hint(
                tr("Отдельно от этого: на Android 13 и новее при включённых «тематических значках» в настройках рабочего стола " +
                    "значок сам окрашивается в цвета обоев — для этого у него есть одноцветный вариант."),
            )
        }
        Group(tr("Экран открытой песни")) {
            SwitchRow(tr("Цвета из обложки"), s.coverColorsInPlayer, tr("Выключите, чтобы и там была тема из настроек")) { v -> up { it.copy(coverColorsInPlayer = v) } }
        }
        Group(tr("Шрифт меню")) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FontChoice.entries.forEach { f ->
                    FilterChip(s.menuFont == f, {
                        if (f == FontChoice.CUSTOM) { fontTarget = "menu"; fontPicker.launch(arrayOf("*/*")) } else up { it.copy(menuFont = f) }
                    }, label = { Text(f.title) })
                }
            }
            SliderRow(tr("Размер текста меню"), s.fontScale, 0.8f..1.4f, { "%.0f%%".format(it * 100) }) { v -> up { it.copy(fontScale = v) } }
        }
        Group(tr("Текст песни")) {
            NavRow(tr("Шрифт и вид текста песни"), tr("Всё о тексте песни — в разделе «Тексты песен»")) { nav.push(Screen.SettingsLyrics) }
        }
    }
}


// ---------------------------------------------------------------------- menu layout

/** Look of the lyrics: alignment, word highlight, animation, colours. Shown in "Тексты песен" and "Оформление". */
@Composable
fun SyncedLyricsLook(s: app.malachite.player.data.AppSettings, up: ((app.malachite.player.data.AppSettings) -> app.malachite.player.data.AppSettings) -> Unit) {
    Group(tr("Вид текста песни")) {
        Hint(tr("Строки"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LyricsAlign.entries.forEach { a -> FilterChip(s.lyricsAlign == a, { up { it.copy(lyricsAlign = a) } }, label = { Text(a.title) }) }
        }
        Hint(tr("Выделение слов в такт песне"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            app.malachite.player.data.WordFill.entries.forEach { w -> FilterChip(s.lyricsWordFill == w, { up { it.copy(lyricsWordFill = w) } }, label = { Text(w.title) }) }
        }
        Hint(tr("Прокрутка и смена строки"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            app.malachite.player.data.LyricsAnim.entries.forEach { a -> FilterChip(s.lyricsAnim == a, { up { it.copy(lyricsAnim = a) } }, label = { Text(a.title) }) }
        }
        Hint(tr("Как уходит спетая строка"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            app.malachite.player.data.LyricsPass.entries.forEach { p -> FilterChip(s.lyricsPass == p, { up { it.copy(lyricsPass = p) } }, label = { Text(p.title) }) }
        }
        if (s.lyricsPass == app.malachite.player.data.LyricsPass.BLUR && android.os.Build.VERSION.SDK_INT < 31) {
            Hint(tr("Размытие есть на Android 12 и новее; здесь строки просто тускнеют."))
        }
        SwitchRow(tr("Увеличивать текущую строку"), s.lyricsScaleActive) { v -> up { it.copy(lyricsScaleActive = v) } }
        SliderRow(tr("Яркость остальных строк"), s.lyricsInactiveAlpha, 0.15f..0.9f, { "%.0f%%".format(it * 100) }) { v -> up { it.copy(lyricsInactiveAlpha = v) } }
        ColorSetting(tr("Цвет спетых слов (по умолчанию — акцент)"), s.lyricsSungColor) { c -> up { it.copy(lyricsSungColor = c) } }
        ColorSetting(tr("Цвет текущей строки (по умолчанию — цвет текста)"), s.lyricsActiveColor) { c -> up { it.copy(lyricsActiveColor = c) } }
    }
    Group(tr("Любимые строки")) {
        Hint(tr("Удерживайте строку в тексте песни, чтобы отметить её как любимую; ещё раз — чтобы снять отметку. Отметка видна и в виджете."))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            app.malachite.player.data.LineMarkStyle.entries.forEach { m -> FilterChip(s.lineMarkStyle == m, { up { it.copy(lineMarkStyle = m) } }, label = { Text(m.title) }) }
        }
        if (s.lineMarkStyle != app.malachite.player.data.LineMarkStyle.MARKER) {
            SliderRow(tr("Толщина линии"), s.lineMarkThickness, 1f..8f, { tr("${it.toInt()} dp") }, steps = 6) { v -> up { it.copy(lineMarkThickness = v) } }
        }
        ColorSetting(tr("Цвет отметки (по умолчанию — акцент)"), s.lineMarkColor) { c -> up { it.copy(lineMarkColor = c) } }
    }
}

@Composable
fun SettingsMenuScreen() {
    val g = MalachiteApp.graph
    val s by g.settings.state.collectAsState()
    fun up(f: (app.malachite.player.data.AppSettings) -> app.malachite.player.data.AppSettings) = g.settings.update(f)
    SettingsPage {
        Group(tr("Положение панели вкладок")) {
            NavPosition.entries.forEach { p -> RadioRow(p.title, s.navPosition, p) { v -> up { it.copy(navPosition = v) } } }
            SwitchRow(tr("Подписи под значками"), s.showTabLabels) { v -> up { it.copy(showTabLabels = v) } }
            SwitchRow(tr("Мини-плеер над содержимым"), s.miniPlayerOnTop, tr("Иначе — под содержимым, рядом с панелью")) { v -> up { it.copy(miniPlayerOnTop = v) } }
            SwitchRow(
                tr("На широком экране — сбоку сама"), s.navAuto,
                tr("Планшет, ноутбук или телефон на боку: панель встаёт слева, чтобы не отнимать высоту"),
            ) { v -> up { it.copy(navAuto = v) } }
            SwitchRow(
                tr("Переключать вкладки, ведя пальцем"), s.tabSlide,
                tr("Задержите палец на панели вкладок — подсветка поднимется и поедет за пальцем; отпустите над нужной вкладкой. Обычное нажатие работает как раньше"),
            ) { v -> up { it.copy(tabSlide = v) } }
            if (s.tabSlide) {
                SliderRow(tr("Сколько держать палец"), s.tabSlideDelayMs.toFloat(), 150f..1000f, { tr("${it.toInt()} мс") }, steps = 16) { v ->
                    up { it.copy(tabSlideDelayMs = Math.round(v / 50f) * 50) }
                }
                Hint(tr("Как подсветка доезжает до вкладки"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    app.malachite.player.data.TabSlideAnim.entries.forEach { a -> FilterChip(s.tabSlideAnim == a, { up { it.copy(tabSlideAnim = a) } }, label = { Text(a.title) }) }
                }
            }
            SwitchRow(
                tr("Самая высокая частота экрана"), s.highRefreshRate,
                tr("Просить у телефона 90 или 120 Гц, пока плеер открыт: анимации и текст плавнее. Некоторые телефоны иначе держат приложения на 60 Гц"),
            ) { v -> up { it.copy(highRefreshRate = v) } }
        }
        Group(tr("Вкладки и их порядок")) {
            val all = (s.tabs + Tab.entries.filter { it !in s.tabs }).filter { it != Tab.ONLINE || s.onlineEnabled }
            all.forEach { t ->
                val on = t in s.tabs
                val idx = s.tabs.indexOf(t)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, { v ->
                        up { st -> st.copy(tabs = if (v) st.tabs + t else (st.tabs - t).ifEmpty { listOf(Tab.SONGS) }) }
                    })
                    Text(t.title, Modifier.weight(1f))
                    IconButton(enabled = on && idx > 0, onClick = {
                        up { st -> st.copy(tabs = st.tabs.toMutableList().also { java.util.Collections.swap(it, idx, idx - 1) }) }
                    }) { Icon(Icons.Filled.ArrowUpward, tr("Выше")) }
                    IconButton(enabled = on && idx < s.tabs.size - 1, onClick = {
                        up { st -> st.copy(tabs = st.tabs.toMutableList().also { java.util.Collections.swap(it, idx, idx + 1) }) }
                    }) { Icon(Icons.Filled.ArrowDownward, tr("Ниже")) }
                }
            }
        }
        PartEditor(tr("Панель вкладок"), s.navStyle) { v -> up { it.copy(navStyle = v) } }
        PartEditor(tr("Мини-плеер"), s.miniPlayerStyle) { v -> up { it.copy(miniPlayerStyle = v) } }
        PartEditor(tr("Верхняя строка"), s.topBarStyle) { v -> up { it.copy(topBarStyle = v) } }
        Group(tr("Альбомы")) {
            SliderRow(tr("Колонок в сетке"), s.albumGridColumns.toFloat(), 1f..4f, { it.toInt().toString() }, steps = 2) { v -> up { it.copy(albumGridColumns = v.toInt()) } }
        }
    }
}

@Composable
private fun PartEditor(title: String, style: PartStyle, onChange: (PartStyle) -> Unit) {
    Group(title) {
        ColorSetting(tr("Цвет"), style.color) { c -> onChange(style.copy(color = c)) }
        SliderRow(tr("Непрозрачность"), style.alpha, 0f..1f, { "%.0f%%".format(it * 100) }) { v -> onChange(style.copy(alpha = v)) }
        SliderRow(tr("Размер"), style.scale, 0.7f..1.6f, { "%.0f%%".format(it * 100) }) { v -> onChange(style.copy(scale = v)) }
        SwitchRow(tr("Размытие (обложка за панелью)"), style.blur, tr("Матовое стекло из обложки текущей песни; Android 12+")) { v -> onChange(style.copy(blur = v)) }
    }
}

// ---------------------------------------------------------------------- library

@Composable
fun SettingsLibraryScreen() {
    val g = MalachiteApp.graph
    val s by g.settings.state.collectAsState()
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val last by g.scanScheduler.lastResult.collectAsState()
    fun up(f: (app.malachite.player.data.AppSettings) -> app.malachite.player.data.AppSettings) = g.settings.update(f)
    var newPath by remember { mutableStateOf(s.mainFolder) }
    var msg by remember { mutableStateOf<String?>(null) }
    var excluded by remember { mutableStateOf(s.importRules.excludedFolders.joinToString("\n")) }
    SettingsPage {
        Group(tr("Главная папка")) {
            Text(s.mainFolder, fontWeight = FontWeight.Medium)
            Hint(tr("Все песни плеера живут только здесь: Исполнитель / Альбом / Песня. Плейлисты — в _Playlists, данные (тексты, избранное, синхронизация) — в .malachite."))
            OutlinedTextField(newPath, { newPath = it; msg = null }, Modifier.fillMaxWidth(), label = { Text(tr("Другая папка (полный или неполный путь)")) }, singleLine = true)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = {
                    val f = Storage.resolveUserPath(newPath)
                    val st = Storage.check(context, f.absolutePath)
                    msg = when {
                        st != Storage.FolderStatus.OK -> "${f.absolutePath}: ${st.message}"
                        !LibraryStore.isLibraryRoot(f) -> tr("Это не главная папка Malachite. Используйте «Перенести сюда».")
                        else -> { g.settings.restoreFrom(f); g.openLibrary(f); tr("Открыта ${f.absolutePath}") }
                    }
                }) { Text(tr("Открыть")) }
                OutlinedButton(onClick = {
                    val from = g.library.root
                    val to = Storage.resolveUserPath(newPath)
                    msg = when {
                        from == null -> tr("Нет текущей папки")
                        to.exists() && (to.list()?.isNotEmpty() == true) -> tr("Папка ${to.absolutePath} не пуста")
                        else -> {
                            to.parentFile?.mkdirs()
                            if (to.exists()) to.delete()
                            if (from.renameTo(to)) { g.openLibrary(to); tr("Главная папка перенесена в ${to.absolutePath}") }
                            else tr("Перенос возможен только в пределах одного носителя. Для переноса на другой — используйте экспорт ZIP.")
                        }
                    }
                }) { Text(tr("Перенести сюда")) }
            }
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
        Group(tr("Новые песни на устройстве")) {
            SliderRow(tr("Проверять каждые"), s.scanIntervalMin.toFloat(), 1f..120f, { tr("${it.toInt()} мин") }) { v ->
                up { it.copy(scanIntervalMin = v.toInt()) }
                g.scanScheduler.restartTimer(); g.scanScheduler.schedulePeriodic()
            }
            Hint(tr("Когда плеер закрыт, Android разрешает фоновую проверку не чаще раза в 15 минут. Пока плеер открыт или играет — как указано, плюс мгновенно при появлении новых файлов."))
            SwitchRow(tr("Сразу добавлять найденное в главную папку"), s.autoImport, tr("Иначе — только уведомление")) { v -> up { it.copy(autoImport = v) } }
            SwitchRow(tr("Глубокий поиск по файлам"), s.deepScan, tr("Находит и песни с неправильным расширением, и в папках, скрытых от медиатеки")) { v -> up { it.copy(deepScan = v) } }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { nav.push(Screen.ImportScreen) }) { Text(tr("Найти сейчас")) }
                OutlinedButton(onClick = { scope.launch { withContext(Dispatchers.IO) { g.scanScheduler.scanNow(auto = true) } } }) { Text(tr("Проверить в фоне")) }
            }
            last?.let { Hint(tr("Последняя проверка: $it")) }
        }
        Group(tr("Что не считать музыкой")) {
            SliderRow(tr("Минимальная длительность"), s.importRules.minDurationSec.toFloat(), 0f..180f, { tr("${it.toInt()} с") }) { v ->
                up { it.copy(importRules = it.importRules.copy(minDurationSec = v.toInt())) }
            }
            Hint(tr("Голосовые сообщения (Telegram, WhatsApp, Viber, VK…), видео, записи звонков и диктофона, рингтоны, будильники и звуки уведомлений не добавляются никогда."))
            SwitchRow(
                tr("Всё аудио из мессенджеров"), s.importRules.importFromMessengers,
                tr("Telegram, WhatsApp, VK и др. Выключено — берутся только песни: сохранённые вами («Сохранить в музыку», «в загрузки»), с названием и исполнителем или с именем «Исполнитель - Название», не короче минуты. Остальное там — в основном кэш, мемы и пересланные файлы"),
            ) { v -> up { it.copy(importRules = it.importRules.copy(importFromMessengers = v)) } }
            SwitchRow(tr("Добавлять подкасты и аудиокниги"), s.importRules.importPodcasts) { v -> up { it.copy(importRules = it.importRules.copy(importPodcasts = v, importAudiobooks = v)) } }
            OutlinedTextField(excluded, { excluded = it }, Modifier.fillMaxWidth(), label = { Text(tr("Исключённые папки (по одной в строке)")) })
            OutlinedButton(onClick = {
                up { it.copy(importRules = it.importRules.copy(excludedFolders = excluded.lines().map { l -> l.trim() }.filter { l -> l.isNotEmpty() })) }
            }) { Text(tr("Сохранить список")) }
        }
        Group(tr("Полный переход")) { DeleteOriginalsControl() }
        Group(tr("Файлы")) {
            SwitchRow(tr("Альбом из названия папки"), s.albumFromFolders, tr("Если в файле нет альбома — взять его из папки, где песня лежит (как делают Mi Music и медиатека Android). В сам файл альбом записывается только через «Исцеление»")) { v -> up { it.copy(albumFromFolders = v) } }
            SwitchRow(tr("Год в названии папки альбома"), s.yearInAlbumFolder, tr("«Безнадёжность (2022)». Год берётся из тегов; даты скачивания с YouTube (20260813) не считаются годом выпуска")) { v -> up { it.copy(yearInAlbumFolder = v) } }
            var singles by remember(s.singlesFolder) { mutableStateOf(s.singlesFolder) }
            OutlinedTextField(singles, { singles = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(tr("Папка для синглов и песен без альбома")) })
            if (singles != s.singlesFolder && singles.isNotBlank()) {
                OutlinedButton(onClick = { up { it.copy(singlesFolder = singles.trim()) } }) { Text(tr("Сохранить имя папки")) }
            }
            Hint(tr("Новые правила применяются к новым песням сразу, а к уже собранным — через Исцеление → «Раскладка по папкам»."))
            SwitchRow(tr("Номер трека в имени файла"), s.trackNumberPrefix, tr("«01 - Песня.mp3» вместо «Песня.mp3»")) { v -> up { it.copy(trackNumberPrefix = v) } }
            SwitchRow(tr("Хранить копии изменённых файлов"), s.keepBackups, tr("При исцелении оригинал уходит в .malachite/backup до очистки")) { v -> up { it.copy(keepBackups = v) } }
            OutlinedButton(onClick = { g.library.rescanAsync() }) { Text(tr("Перечитать главную папку")) }
        }
    }
}

// ---------------------------------------------------------------------- lyrics

@Composable
fun SettingsLyricsScreen() {
    val g = MalachiteApp.graph
    val s by g.settings.state.collectAsState()
    fun up(f: (app.malachite.player.data.AppSettings) -> app.malachite.player.data.AppSettings) = g.settings.update(f)
    val context = LocalContext.current
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val dir = File(context.filesDir, "fonts").apply { mkdirs() }
            val out = File(dir, "lyrics_${System.currentTimeMillis()}.ttf")
            runCatching { context.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } } }
            if (out.isFile) {
                val problem = app.malachite.player.ui.theme.fontProblem(out)
                if (problem != null) android.widget.Toast.makeText(context, problem, android.widget.Toast.LENGTH_LONG).show()
                up { it.copy(lyricsFont = FontChoice.CUSTOM, lyricsFontFile = out.absolutePath) }
            }
        }
    }
    SettingsPage {
        Group(tr("Шрифт текстов песен")) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FontChoice.entries.forEach { f ->
                    FilterChip(s.lyricsFont == f, {
                        if (f == FontChoice.CUSTOM) fontPicker.launch(arrayOf("*/*")) else up { it.copy(lyricsFont = f) }
                    }, label = { Text(f.title) })
                }
            }
            SliderRow(tr("Размер"), s.lyricsFontSize.toFloat(), 14f..40f, { "${it.toInt()} sp" }) { v -> up { it.copy(lyricsFontSize = v.toInt()) } }
            SwitchRow(tr("Жирный текст"), s.lyricsBold) { v -> up { it.copy(lyricsBold = v) } }
        }
        SyncedLyricsLook(s) { f -> up(f) }
        Group(tr("Положение текста на экране песни")) {
            val nav = LocalNavigator.current
            Hint(tr("Строка, которая поётся, стоит посередине рамки: от названия альбома вверху до «таблетки» сдвига внизу. Её можно поставить выше или ниже."))
            SliderRow(tr("Строка, которая поётся"), s.lyricsAnchorShift * 100f, -40f..40f, { v ->
                val pct = kotlin.math.round(v).toInt()
                when {
                    pct == 0 -> tr("ровно посередине")
                    pct < 0 -> tr("выше на ${-pct}%")
                    else -> tr("ниже на ${pct}%")
                }
            }) { v -> up { it.copy(lyricsAnchorShift = kotlin.math.round(v) / 100f) } }
            Button(onClick = {
                if (g.player.state.value.currentPath != null) {
                    app.malachite.player.ui.nowplaying.LyricsGuide.adjusting = true
                    nav.playerOpen = true
                } else nav.toast(tr("Включите любую песню — положение настраивается прямо в плеере, по сетке"))
            }) { Text(tr("Настроить по сетке в плеере")) }
            Hint(tr("В плеере появится полупрозрачная сетка и ползунок. Если у песни нет текста, для настройки покажется пример."))
        }
        Group(tr("Поиск текстов")) {
            SwitchRow(tr("Искать при открытии текста"), s.lyricsAutoSearch, tr("Только когда вы нажали на «текст» — не в фоне")) { v -> up { it.copy(lyricsAutoSearch = v) } }
            Hint(tr("Сначала ищется синхронизированный текст (LRCLIB, NetEase), и только если его нет — обычный (LRCLIB, Genius, lyrics.ovh). Поиск учитывает длительность песни, чтобы не взять текст от другой версии."))
        }
        Group(tr("Очистка")) {
            Hint(tr("Квадратные, фигурные и угловые скобки ([Куплет 1], {Bridge}, <Аутро>) и заголовки вроде «Текст песни «…»» убираются всегда."))
            SwitchRow(tr("Убирать любой текст в круглых скобках"), s.lyricsRemoveAllParentheses, tr("Иначе убираются только пометки «(Припев)», «(x2)»; подпевки остаются")) { v -> up { it.copy(lyricsRemoveAllParentheses = v) } }
        }
        Group(tr("Хранение")) {
            Hint(tr("Тексты сохраняются в главной папке (.malachite/lyrics) в формате LRC и с точными отметками в JSON."))
            SwitchRow(tr("Также вшивать текст в файл песни"), s.lyricsEmbedInFile, tr("Тогда текст увидят и другие плееры")) { v -> up { it.copy(lyricsEmbedInFile = v) } }
        }
        Group(tr("Ручная синхронизация")) {
            SliderRow(tr("Задержка реакции"), s.syncReactionMs.toFloat(), 0f..400f, { tr("${it.toInt()} мс") }) { v -> up { it.copy(syncReactionMs = v.toInt()) } }
            Hint(tr("Вычитается из каждой отметки с поправкой на скорость, с которой вы записывали."))
            SliderRow(tr("Сохранять незаконченную синхронизацию каждые"), s.syncAutosaveSec.toFloat(), 5f..120f, { tr("${it.toInt()} с") }) { v ->
                up { it.copy(syncAutosaveSec = v.toInt()) }
            }
            Hint(tr("Кроме того, она сохраняется, когда вы уходите с экрана или сворачиваете плеер. Продолжить можно в разделе «Процессы»."))
            SliderRow(
                tr("Строки «♪» отмечаются сами"), s.syncPauseSec.toFloat(), 0f..15f,
                { if (it < 1f) tr("нет, нажимать вручную") else tr("через ${it.toInt()} с тишины") }, steps = 14,
            ) { v -> up { it.copy(syncPauseSec = v.toInt()) } }
            Hint(tr("«♪» — проигрыш без слов. Если после строки вы не нажимаете кнопку столько секунд, «♪» начинается сам с того места, где закончилось пение, и длится до следующего нажатия. Нажали раньше — «♪» пропускается, нажатие идёт следующей строке."))
            Text(tr("Кнопка «Держите, пока поётся»"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            SliderRow(tr("Ширина"), s.syncButtonWidthDp.toFloat(), 100f..600f, { if (it >= 590f) tr("во всю ширину") else tr("${it.toInt()} dp") }) { v ->
                up { it.copy(syncButtonWidthDp = v.toInt()) }
            }
            SliderRow(tr("Высота"), s.syncButtonHeightDp.toFloat(), 80f..320f, { tr("${it.toInt()} dp") }) { v -> up { it.copy(syncButtonHeightDp = v.toInt()) } }
            SliderRow(tr("Скругление углов"), s.syncButtonCorner.toFloat(), 0f..50f, { if (it >= 49.5f) tr("полностью круглые") else "${it.toInt()}%" }) { v ->
                up { it.copy(syncButtonCorner = v.toInt()) }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                app.malachite.player.ui.sync.SyncButtonPreview(s.syncButtonWidthDp, s.syncButtonHeightDp, s.syncButtonCorner)
            }
            TextButton(onClick = { up { it.copy(syncButtonWidthDp = 150, syncButtonHeightDp = 150, syncButtonCorner = 50) } }) { Text(tr("Вернуть круг")) }
        }
    }
}

// ---------------------------------------------------------------------- playback

@Composable
fun SettingsPlaybackScreen() {
    val g = MalachiteApp.graph
    val s by g.settings.state.collectAsState()
    val nav = LocalNavigator.current
    fun up(f: (app.malachite.player.data.AppSettings) -> app.malachite.player.data.AppSettings) = g.settings.update(f)
    SettingsPage {
        Group(tr("Скорость и питч по умолчанию")) {
            SliderRow(tr("Скорость"), s.speed, 0.25f..3f, { "%.2fx".format(it) }) { v -> up { it.copy(speed = v) }; g.player.restoreSpeed() }
            SliderRow(tr("Питч"), s.pitchSemitones, -12f..12f, { tr("%+.1f полутона").format(it) }) { v -> up { it.copy(pitchSemitones = v) }; g.player.restoreSpeed() }
            SwitchRow(tr("Питч следует за скоростью"), s.linkPitchToSpeed, tr("Как пластинка: быстрее — выше («nightcore»), медленнее — ниже («slowed»)")) { v -> up { it.copy(linkPitchToSpeed = v) } }
            SwitchRow(tr("Запоминать скорость/питч для каждой песни"), s.rememberSpeedPerSong) { v -> up { it.copy(rememberSpeedPerSong = v) } }
        }
        Group(tr("Одинаковая громкость")) {
            SwitchRow(
                tr("Выравнивать громкость песен"), s.levelLoudness,
                tr("Тихие и громкие песни звучат одинаково. Громкость измеряет исцеление «Громкость»; не измеренные песни играют как есть"),
            ) { v -> up { it.copy(levelLoudness = v) } }
        }
        Group(tr("Переход между песнями")) {
            SliderRow(
                tr("Плавный переход"), s.crossfadeSec.toFloat(), 0f..12f,
                { if (it < 0.5f) tr("выключен") else tr("${it.toInt()} с") }, steps = 11,
            ) { v -> up { it.copy(crossfadeSec = v.toInt()) } }
            Hint(tr("Конец песни затихает, а следующая в это время набирает громкость. Работает для песен на телефоне; онлайн-песни и «Повтор одной» сменяются как обычно."))
        }
        Group(tr("Визуализатор")) {
            SwitchRow(
                tr("Полоски под обложкой"), s.visualizer,
                tr("Двигаются вместе с музыкой: слева басы, справа высокие. Берутся из самого звука плеера — микрофон и разрешения не нужны"),
            ) { v -> up { it.copy(visualizer = v) } }
            if (s.visualizer) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    app.malachite.player.data.VisualizerStyle.entries.forEach { v ->
                        FilterChip(s.visualizerStyle == v, { up { it.copy(visualizerStyle = v) } }, label = { Text(v.title) })
                    }
                }
                Hint(tr("Полосы — со свечением, отражением и «шапочками», которые ненадолго задерживаются на самой высокой точке. Светодиоды — как индикатор на пульте. Волна — плавная, отражённая от середины."))
                Text(tr("Расположение"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    app.malachite.player.data.VisualizerOrder.entries.forEach { o ->
                        FilterChip(s.visualizerOrder == o, { up { it.copy(visualizerOrder = o) } }, label = { Text(o.title) })
                    }
                }
                Hint(tr("«Сильные…» расставляют полосы не по частоте, а по силе звука в эту секунду: самые громкие собираются в середине или с одного края."))
                SliderRow(
                    tr("Появление и исчезание"), s.visualizerFadeMs / 1000f, 0.1f..3f, { tr("%.1f с").format(it) },
                ) { v -> up { it.copy(visualizerFadeMs = (v * 1000).toInt()) } }
            }
        }
        DislikedGroup()
        Group(tr("Прочее")) {
            SwitchRow(tr("Пропускать тишину"), s.skipSilence) { v -> g.player.setSkipSilence(v) }
            SwitchRow(tr("Продолжать с того же места после перезапуска"), s.resumeLastSession) { v -> up { it.copy(resumeLastSession = v) } }
            SwitchRow(
                tr("Цвета обложки в уведомлении"), s.notificationCoverColors,
                tr("Там, где это умеет система. Изменение видно со следующей песни"),
            ) { v -> up { it.copy(notificationCoverColors = v) } }
            NavRow(tr("Эквалайзер и DJ-микшер"), tr("Ручки TRIM, HI, MID, LOW с KILL, фильтр, баланс и эквалайзер телефона")) { nav.push(Screen.Equalizer) }
        }
    }
}

/** How much of the heard online music is kept for listening without internet. */
@Composable
private fun OnlineCacheRows() {
    val g = MalachiteApp.graph
    val st by g.settings.state.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val nav = LocalNavigator.current
    var used by remember { mutableStateOf(-1L) }
    var asked by remember { mutableStateOf(0) }
    LaunchedEffect(asked) {
        used = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.malachite.player.playback.OnlineCache.usedBytes(context) }
    }
    val steps = listOf(0, 256, 512, 1024, 2048, 4096, 8192)
    val index = steps.indexOfFirst { it >= st.onlineCacheMb }.let { if (it < 0) steps.size - 1 else it }
    SliderRow(
        tr("Хранить прослушанные онлайн-песни"), index.toFloat(), 0f..(steps.size - 1).toFloat(),
        { i -> steps[i.toInt().coerceIn(0, steps.size - 1)].let { mb -> if (mb == 0) tr("не хранить") else if (mb < 1024) tr("до $mb МБ") else tr("до ${mb / 1024} ГБ") } },
        steps = steps.size - 2,
    ) { v -> g.settings.update { it.copy(onlineCacheMb = steps[v.toInt().coerceIn(0, steps.size - 1)]) } }
    Hint(
        tr("Песня, которую дослушали, играет потом и без интернета (раздел «Онлайн» → «Слушали — играют без интернета»). Когда место кончается, первыми уходят те, что давно не слушали. Новый размер — со следующего запуска плеера.") +
            if (used > 0) tr(" Сейчас занято: ${"%.1f".format(used / 1_048_576.0)} МБ.") else "",
    )
    if (used > 0) TextButton(onClick = {
        app.malachite.player.playback.OnlineCache.clear(context)
        asked++
        nav.toast(tr("Сохранённые онлайн-песни удалены"))
    }) { Text(tr("Удалить сохранённые онлайн-песни")) }
}

/** What the user does not like: shown here so that it can be liked again. */
@Composable
private fun DislikedGroup() {
    val g = MalachiteApp.graph
    val data by g.library.data.collectAsState()
    val tracks by g.library.tracks.collectAsState()
    val songs = remember(data, tracks) { tracks.filter { data.user(it.path).disliked } }
    Group(tr("Не нравится")) {
        Hint(tr("Песни и исполнители с отметкой «Не нравится» не играют вперемешку и не попадают в умные плейлисты. Файлы остаются, нажатие на песню играет её как обычно. Отметить — в меню песни или на странице исполнителя (👎)."))
        if (songs.isEmpty() && data.dislikedArtists.isEmpty()) Hint(tr("Пока ничего не отмечено."))
        data.dislikedArtists.values.sorted().forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Исполнитель: $name"), Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                TextButton(onClick = { g.library.setArtistDisliked(name, false) }) { Text(tr("Снова нравится")) }
            }
        }
        songs.take(100).forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${t.displayArtist} — ${t.displayTitle}", Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                TextButton(onClick = { g.library.setDisliked(listOf(t.path), false) }) { Text(tr("Снова нравится")) }
            }
        }
        if (songs.size > 100) Hint(tr("… и ещё ${songs.size - 100}"))
        if (songs.size > 1) TextButton(onClick = { g.library.setDisliked(songs.map { it.path }, false) }) { Text(tr("Вернуть все песни (${songs.size})")) }
    }
}

@Composable
fun EqualizerScreen() {
    var enabled by remember { mutableStateOf(AudioEffects.enabled) }
    var bands by remember { mutableStateOf(AudioEffects.bands()) }
    val (min, max) = AudioEffects.range()
    SettingsPage {
        MixerGroup()
        if (!AudioEffects.available) {
            Group(tr("Эквалайзер телефона")) { Hint(tr("Эквалайзер станет доступен, когда начнёт играть песня (на некоторых прошивках его нет вовсе).")) }
            return@SettingsPage
        }
        Group(tr("Эквалайзер телефона")) {
            SwitchRow(tr("Включён"), enabled) { v -> AudioEffects.enabled = v; enabled = v }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AudioEffects.presets().forEachIndexed { i, p ->
                    FilterChip(false, { AudioEffects.usePreset(i); bands = AudioEffects.bands() }, label = { Text(p) })
                }
            }
            bands.forEachIndexed { i, (hz, level) ->
                SliderRow(if (hz >= 1000) tr("${hz / 1000} кГц") else tr("$hz Гц"), level.toFloat(), min.toFloat()..max.toFloat(), { tr("%+.1f дБ").format(it / 100f) }) { v ->
                    AudioEffects.setBand(i, v.toInt()); bands = AudioEffects.bands()
                }
            }
            OutlinedButton(onClick = { AudioEffects.reset(); bands = AudioEffects.bands() }) { Text(tr("Сбросить")) }
        }
    }
}

// ---------------------------------------------------------------------- widgets

@Composable
fun SettingsWidgetsScreen() {
    val g = MalachiteApp.graph
    val s by g.settings.state.collectAsState()
    val context = LocalContext.current
    val nav = LocalNavigator.current
    fun up(f: (app.malachite.player.data.WidgetSettings) -> app.malachite.player.data.WidgetSettings) {
        g.settings.update { it.copy(widget = f(it.widget)) }
        WidgetUpdater.requestUpdate(context)
    }
    val w = s.widget
    /** The widget's own look filled in with what the player uses now. */
    fun fromPlayer(it: app.malachite.player.data.WidgetSettings) = it.copy(
        font = s.lyricsFont, fontFile = s.lyricsFontFile, bold = s.lyricsBold, align = s.lyricsAlign,
        fill = s.lyricsWordFill, fade = s.lyricsAnim != app.malachite.player.data.LyricsAnim.OFF,
        scaleActive = s.lyricsScaleActive, inactiveAlpha = s.lyricsInactiveAlpha,
        sungColor = s.lyricsSungColor, activeColor = s.lyricsActiveColor,
        textScale = (s.lyricsFontSize / 24f).coerceIn(0.75f, 1.35f),
    )
    // a font file of the widget's own
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val dir = File(context.filesDir, "fonts").apply { mkdirs() }
            val out = File(dir, "widget_${System.currentTimeMillis()}.ttf")
            runCatching { context.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } } }
            if (out.isFile) {
                val problem = app.malachite.player.ui.theme.fontProblem(out)
                if (problem != null) android.widget.Toast.makeText(context, problem, android.widget.Toast.LENGTH_LONG).show()
                up { it.copy(font = FontChoice.CUSTOM, fontFile = out.absolutePath) }
            }
        }
    }
    SettingsPage {
        Group(tr("Виджет Malachite")) {
            Hint(tr("Один виджет любого размера: растяните его на рабочем столе. Маленький — обложка и кнопка, средний — кнопки, большой — строки синхронизированного текста, самый большой — ещё и полоса перемотки (нажмите в нужное место)."))
            SwitchRow(tr("Цвета из обложки (как в плеере)"), w.useCoverColors) { v -> up { it.copy(useCoverColors = v) } }
            if (!w.useCoverColors) ColorSetting(tr("Цвет фона"), w.backgroundColor) { c -> up { it.copy(backgroundColor = c) } }
            SliderRow(tr("Непрозрачность фона"), w.backgroundAlpha, 0f..1f, { "%.0f%%".format(it * 100) }) { v -> up { it.copy(backgroundAlpha = v) } }
            Hint(tr("Цвета виджета берутся из темы приложения (или из обложки, как в плеере); цвет текста подбирается сам."))
            SwitchRow(tr("Показывать обложку"), w.showCover) { v -> up { it.copy(showCover = v) } }
            SwitchRow(tr("Полоса перемотки (самые большие)"), w.showSeekBar) { v -> up { it.copy(showSeekBar = v) } }
        }
        Group(tr("Текст песни в виджете")) {
            SwitchRow(tr("Строки текста песни (большие виджеты)"), w.showLyrics) { v -> up { it.copy(showLyrics = v) } }
            SwitchRow(
                tr("Точно как в плеере"), w.exactText,
                tr("Строки рисуются тем же шрифтом, что и в плеере, — подходит и шрифт из файла. Выключите, если на вашем рабочем столе строки выглядят размыто: тогда текст покажет сам рабочий стол, но только системными шрифтами"),
            ) { v -> up { it.copy(exactText = v) } }
            SwitchRow(
                tr("Закрашивать слова по мере пения"), w.wordFill,
                tr("Как в плеере; нужен текст с отметками слов"),
            ) { v -> up { it.copy(wordFill = v) } }
            SwitchRow(
                tr("Текущая строка цветом акцента"), w.accentCurrentLine,
                tr("Для текстов без отметок слов. Выключено — как в плеере: обычным цветом текста"),
            ) { v -> up { it.copy(accentCurrentLine = v) } }
        }
        Group(tr("Вид текста")) {
            SwitchRow(
                tr("Свой вид для виджета"), w.ownLook,
                tr("Выключено — виджет повторяет плеер: шрифт, жирность, размер, выравнивание, цвета, яркость строк, закрашивание слов и анимацию"),
            ) { v ->
                up {
                    // an untouched own look starts from what the player has now, not from factory values
                    val untouched = it.copy(ownLook = false) == app.malachite.player.data.WidgetSettings().copy(
                        backgroundColor = it.backgroundColor, backgroundAlpha = it.backgroundAlpha, useCoverColors = it.useCoverColors,
                        showLyrics = it.showLyrics, showSeekBar = it.showSeekBar, showCover = it.showCover, lightText = it.lightText,
                        accentCurrentLine = it.accentCurrentLine, wordFill = it.wordFill, exactText = it.exactText,
                    )
                    if (v && untouched) fromPlayer(it).copy(ownLook = true) else it.copy(ownLook = v)
                }
            }
            if (!w.ownLook) {
                NavRow(tr("Изменить вид текста песни"), tr("Общий для плеера и виджета: «Оформление» → шрифт и вид текста песни")) { nav.push(Screen.SettingsAppearance) }
            } else {
                Hint(tr("Шрифт"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FontChoice.entries.forEach { f ->
                        FilterChip(w.font == f, {
                            if (f == FontChoice.CUSTOM) fontPicker.launch(arrayOf("*/*")) else up { it.copy(font = f) }
                        }, label = { Text(f.title) })
                    }
                }
                if (w.font == FontChoice.CUSTOM && !w.exactText) Hint(tr("Шрифт из файла виден только при включённом «Точно как в плеере»."))
                SwitchRow(tr("Жирный текст"), w.bold) { v -> up { it.copy(bold = v) } }
                SliderRow(tr("Размер строк"), w.textScale, 0.7f..1.4f, { "%.0f%%".format(it * 100) }) { v -> up { it.copy(textScale = v) } }
                Hint(tr("Строки"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LyricsAlign.entries.forEach { a -> FilterChip(w.align == a, { up { it.copy(align = a) } }, label = { Text(a.title) }) }
                }
                Hint(tr("Выделение слов в такт песне"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    app.malachite.player.data.WordFill.entries.forEach { f -> FilterChip(w.fill == f, { up { it.copy(fill = f) } }, label = { Text(f.title) }) }
                }
                SwitchRow(tr("Плавная смена строки"), w.fade, tr("Старая строка гаснет, новая проявляется")) { v -> up { it.copy(fade = v) } }
                SwitchRow(tr("Увеличивать текущую строку"), w.scaleActive) { v -> up { it.copy(scaleActive = v) } }
                SliderRow(tr("Яркость остальных строк"), w.inactiveAlpha, 0.15f..0.9f, { "%.0f%%".format(it * 100) }) { v -> up { it.copy(inactiveAlpha = v) } }
                ColorSetting(tr("Цвет спетых слов (по умолчанию — акцент)"), w.sungColor) { c -> up { it.copy(sungColor = c) } }
                ColorSetting(tr("Цвет текущей строки (по умолчанию — цвет текста)"), w.activeColor) { c -> up { it.copy(activeColor = c) } }
                androidx.compose.material3.OutlinedButton(onClick = { up { fromPlayer(it) } }) { Text(tr("Взять вид из плеера")) }
            }
            Hint(tr("Чего виджет не может: рабочий стол сам рисует виджеты и не даёт прокручивать строки, как в плеере, — поэтому строка сменяется проявлением, а плавная заливка слова идёт шагами, несколько раз в секунду."))
        }
    }
}

// ---------------------------------------------------------------------- export / backups

@Composable
fun SettingsBackupScreen() {
    val g = MalachiteApp.graph
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var msg by remember { mutableStateOf<String?>(null) }
    var sizes by remember { mutableStateOf(g.library.safe?.backupSize() to g.library.safe?.trashSize()) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val root = g.library.root
        if (uri == null || root == null) return@rememberLauncherForActivityResult
        busy = true; msg = null
        LongTaskService.start(context, tr("Экспорт главной папки в ZIP"))
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        ZipTransfer.export(root, out, { d, t -> progress = if (t > 0) d.toFloat() / t else 0f; LongTaskService.progress(context, tr("Экспорт"), (progress * 100).toInt(), 100) })
                    } ?: error(tr("не удалось открыть файл"))
                }
            }
            LongTaskService.stop(context)
            busy = false
            msg = r.fold({ tr("Готово. Перенесите ZIP на другой телефон и распакуйте (или используйте «Импорт ZIP»).") }, { tr("Ошибка: ${it.message}") })
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true; msg = null
        LongTaskService.start(context, tr("Импорт ZIP"))
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input -> ZipTransfer.import(input, Storage.primaryRoot()) }
                }
            }
            LongTaskService.stop(context)
            busy = false
            val root = r.getOrNull()
            msg = if (root != null && LibraryStore.isLibraryRoot(root)) {
                g.settings.restoreFrom(root); g.openLibrary(root)
                tr("Распаковано и открыто: ${root.absolutePath}")
            } else tr("Не получилось: ${r.exceptionOrNull()?.message ?: tr("в архиве нет главной папки Malachite")}")
        }
    }
    SettingsPage {
        Group(tr("Перенос на другое устройство")) {
            Hint(tr("Главная папка упаковывается в ZIP без сжатия: файлы внутри не меняются. Внутри — все песни, плейлисты, тексты, синхронизация, избранное и настройки."))
            Button(enabled = !busy && g.library.root != null, onClick = { exportLauncher.launch((g.library.root?.name ?: "Malachite") + ".zip") }) { Text(tr("Экспорт в ZIP")) }
            OutlinedButton(enabled = !busy, onClick = { importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) }) { Text(tr("Импорт ZIP (распаковать во внутреннюю память)")) }
            if (busy) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            msg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
        Group(tr("Резервные копии и корзина")) {
            Text(tr("Копии оригиналов после исцеления: %.1f МБ").format((sizes.first ?: 0L) / 1048576.0))
            Text(tr("Корзина (удалённые клоны и песни): %.1f МБ").format((sizes.second ?: 0L) / 1048576.0))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = { g.library.safe?.clearBackups(); sizes = g.library.safe?.backupSize() to g.library.safe?.trashSize() }) { Text(tr("Очистить копии")) }
                OutlinedButton(onClick = { g.library.safe?.clearTrash(); sizes = g.library.safe?.backupSize() to g.library.safe?.trashSize() }) { Text(tr("Очистить корзину")) }
            }
            Hint(tr("Пока не очищено, любой изменённый или удалённый файл можно вернуть из .malachite/backup или .malachite/trash."))
        }
    }
}

// ---------------------------------------------------------------------- root / Shizuku

@Composable
fun SettingsAdvancedScreen() {
    val g = MalachiteApp.graph
    val s by g.settings.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    fun up(f: (app.malachite.player.data.AppSettings) -> app.malachite.player.data.AppSettings) = g.settings.update(f)
    SettingsPage {
        Group(tr("Режим")) {
            Hint(tr("Дополнительные возможности для телефонов с root или Shizuku. Плеер работает только со своими файлами и музыкой — ничего другого не трогает."))
            Privileged.Mode.entries.forEach { m -> RadioRow(m.title, s.privilegedMode, m) { v -> up { it.copy(privilegedMode = v) } } }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { Privileged.checkRoot() }
                        status = if (ok) tr("Root доступен") else tr("Root недоступен (или запрос отклонён)")
                    }
                }) { Text(tr("Проверить root")) }
                OutlinedButton(onClick = {
                    status = when {
                        !Privileged.shizukuRunning() -> tr("Shizuku не запущен")
                        Privileged.shizukuGranted() -> tr("Shizuku: разрешение есть")
                        else -> { Privileged.requestShizuku(); tr("Подтвердите запрос Shizuku") }
                    }
                }) { Text("Shizuku") }
            }
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
        val usable = s.privilegedMode != Privileged.Mode.NONE
        Group(tr("Возможности")) {
            SwitchRow(
                tr("Искать музыку в закрытых папках приложений"), s.scanAppData,
                tr("Android/data, например «Telegram Audio» в новом Telegram (без root/Shizuku туда не попасть). Голосовые всё равно отсеиваются."),
            ) { v -> up { it.copy(scanAppData = v) } }
            OutlinedButton(enabled = usable, onClick = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Privileged.keepAlive(s.privilegedMode, context.packageName) }
                    status = if (r.ok) tr("Плеер добавлен в исключения энергосбережения") else tr("Не удалось: ${r.err.take(200)}")
                }
            }) { Text(tr("Не давать системе выгружать плеер")) }
            OutlinedButton(enabled = usable, onClick = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Privileged.grantOwnPermissions(s.privilegedMode, context.packageName) }
                    status = tr("Разрешения выданы") + if (r.err.isNotBlank()) tr(" (часть команд не поддерживается этой версией Android — это нормально)") else ""
                }
            }) { Text(tr("Выдать плееру разрешения автоматически")) }
        }
    }
}

// ---------------------------------------------------------------------- deleting songs outside the main folder

/**
 * The one switch that decides whether the player may delete songs outside the main folder.
 * Turning it on asks for confirmation; turning it off takes effect immediately – nothing
 * outside the main folder is deleted afterwards, not even by an import that is already running.
 */
@Composable
fun DeleteOriginalsControl(compact: Boolean = false) {
    val g = MalachiteApp.graph
    val s by g.settings.state.collectAsState()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    var alsoExisting by remember { mutableStateOf(false) }
    var existing by remember { mutableStateOf<Int?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(confirm) {
        if (confirm) existing = withContext(Dispatchers.IO) { runCatching { g.deviceScanner.countImportedOriginals() }.getOrNull() }
    }
    androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SwitchRow(
            tr("Удалять песни вне главной папки"),
            s.deleteOriginals,
            if (s.deleteOriginals) tr("ВКЛЮЧЕНО: после проверенного копирования в главную папку оригинал удаляется — и при каждой автоматической проверке новых песен")
            else tr("ВЫКЛЮЧЕНО: плеер только копирует музыку и ничего вне главной папки не удаляет"),
        ) { v ->
            if (v) { alsoExisting = false; confirm = true } else {
                g.settings.update { it.copy(deleteOriginals = false) }
                msg = tr("Удаление отключено. Вне главной папки больше ничего не удаляется.")
            }
        }
        if (!compact) Hint(tr("Разрешение на удаление можно выдать и отозвать в любой момент. Оригинал удаляется только после того, как копия в главной папке сверена байт-в-байт. Голосовые, записи и рингтоны плеер не трогает никогда."))
        msg?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(tr("Разрешить удаление песен вне главной папки?")) },
            text = {
                androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Каждая песня, которая скопирована в главную папку и проверена, будет удаляться из старого места. Это касается и новых песен, которые плеер находит сам каждые ${s.scanIntervalMin} мин."))
                    Text(tr("Отозвать разрешение можно в любой момент этим же переключателем — удаление прекратится сразу."), style = MaterialTheme.typography.bodySmall)
                    val n = existing
                    if (n != null && n > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(alsoExisting, { alsoExisting = it })
                            Text(tr("Также удалить оригиналы уже добавленных песен ($n)"))
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirm = false
                    g.settings.update { it.copy(deleteOriginals = true) }
                    if (alsoExisting) {
                        msg = tr("Удаляю оригиналы…")
                        scope.launch {
                            val removed = withContext(Dispatchers.IO) { g.deviceScanner.deleteImportedOriginals() }
                            msg = tr("Удалено оригиналов: $removed")
                        }
                    } else msg = tr("Удаление включено для новых песен.")
                }) { Text(tr("Разрешить")) }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text(tr("Отмена")) } },
        )
    }
}
