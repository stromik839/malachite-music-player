package app.malachite.player.ui.screens

import app.malachite.core.i18n.tr
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.malachite.player.Credits
import app.malachite.player.MalachiteApp
import app.malachite.player.data.AppLog
import app.malachite.player.ui.LocalNavigator
import app.malachite.player.ui.Screen
import app.malachite.player.ui.settings.Group
import app.malachite.player.ui.settings.Hint
import app.malachite.player.ui.settings.NavRow
import app.malachite.player.ui.settings.SettingsPage
import app.malachite.player.ui.settings.SwitchRow

/**
 * The agreement every user sees once (and again whenever [VERSION] grows).
 * Plain language on purpose: people should be able to actually read it.
 */
object Terms {
    /** Raise this number when the text changes in a way people must agree to again. */
    const val VERSION = 2
    const val DATE = "11.11.2026"

    val TITLE get() = tr("Пользовательское соглашение и конфиденциальность")

    val INTRO get() = tr("Malachite — бесплатный офлайн-плеер с открытым исходным кодом. Пользуясь им, вы соглашаетесь с " +
        "условиями ниже. Если вы с ними не согласны, нажмите «Отклонить» в конце текста — приложение закроется.")

    val SECTIONS: List<Pair<String, String>> = listOf(
        tr("1. Вы пользуетесь приложением по своему желанию и на свой риск") to
            tr("Плеер бесплатный. Вы устанавливаете его и пользуетесь им только по собственному желанию и на " +
            "собственный риск. Приложение даётся «как есть», без каких-либо гарантий: никто не обещает, что оно " +
            "подойдёт для ваших целей, будет работать без ошибок или вообще продолжит работать.\n\n" +
            "Автор идеи и создатели кода не несут никакой ответственности за использование приложения и за любые " +
            "его последствия: сбои, потерю или порчу файлов и данных, любой прямой или косвенный ущерб. Всю " +
            "ответственность за то, как вы используете приложение и что из этого выйдет, несёте вы сами.\n\n" +
            "Код написан искусственным интеллектом и может содержать ошибки. Обновления и исправления не гарантируются."),
        tr("2. Что плеер делает с вашими файлами") to
            tr("Плеер создан, чтобы наводить порядок в музыке. Поэтому по вашей команде он копирует песни в главную папку, " +
            "переименовывает и перемещает файлы внутри неё, записывает в сами файлы теги, обложки и тексты, исправляет " +
            "расширения, а клоны и «не музыку» отправляет в корзину главной папки.\n\n" +
            "Оригиналы вне главной папки удаляются, только если вы сами включили «Удалять песни вне главной папки».\n\n" +
            "Перед каждым изменением делается копия и проверяется, что звук остался прежним. Но ни одна программа не " +
            "застрахована от ошибок, севшей батареи или сбоя накопителя. Храните резервную копию важной музыки: " +
            "за сохранность своих файлов отвечаете вы."),
        tr("3. Интернет") to
            tr("Плеер полностью работает без интернета. В сеть он выходит, только когда вы сами запускаете поиск текста " +
            "песни, обложки или альбома (в том числе через «Исцеление»).\n\n" +
            "Чтобы найти совпадение, на сторонние сервисы отправляются исполнитель, название песни, альбом и " +
            "длительность. Сервисы: LRCLIB, NetEase Cloud Music, Genius, lyrics.ovh, iTunes (Apple), Deezer, MusicBrainz " +
            "и Cover Art Archive, Яндекс Музыка, Bandcamp, а также YouTube — только картинка по ссылке, которая уже " +
            "записана в вашем файле.\n\n" +
            "Отдельно есть онлайн-раздел. Он выключен, пока вы сами не включите его в настройках. В нём можно искать " +
            "музыку в открытых каталогах Audius и Internet Archive, слушать её и скачивать в главную папку, а также " +
            "слушать 30-секундные отрывки песен из каталога Deezer (их скачать нельзя); этим каталогам отправляется " +
            "только ваш поисковый запрос.\n\n" +
            "Как и любой сайт, эти сервисы видят ваш IP-адрес и действуют по собственным правилам. Сами музыкальные " +
            "файлы и список вашей библиотеки никуда не отправляются."),
        tr("4. Ваши данные") to
            tr("В приложении нет аккаунтов, рекламы, аналитики и трекеров. Список песен, избранное, плейлисты, тексты и " +
            "настройки хранятся только на вашем устройстве — в главной папке и в памяти приложения. " +
            "Автору ничего не передаётся автоматически. По желанию эти данные можно защитить паролем (раздел «Процессы»)."),
        tr("5. Журнал и отчёты об ошибках") to
            tr("Журнал работы выключен. Если вы включите его в настройках, плеер начнёт записывать свои действия и ошибки " +
            "в текстовый файл в папке Download.\n\n" +
            "Отчёт о сбое сохраняется, только если вы это разрешили. При следующем запуске плеер покажет его и спросит, " +
            "что с ним сделать.\n\n" +
            "В журнале и отчётах могут встречаться названия ваших файлов и папок. Отправлять ли их кому-нибудь, " +
            "решаете только вы: сам плеер этого не делает."),
        tr("6. Музыка, тексты и обложки") to
            tr("Плеер не содержит и не распространяет музыку. За то, что файлы на вашем устройстве получены законно, " +
            "отвечаете вы. Тексты песен и обложки принадлежат их правообладателям; плеер лишь помогает найти их " +
            "для личного использования.\n\n" +
            "Онлайн-раздел показывает только то, что открытые каталоги сами предлагают слушать и скачивать. Права на " +
            "эти записи принадлежат их авторам; как вы используете скачанное, решаете и отвечаете вы."),
        tr("7. Root и Shizuku") to
            tr("Дополнительные возможности с root или Shizuku необязательны и включаются только вами. Они дают приложению " +
            "расширенный доступ к системе; пользуйтесь ими на свой риск."),
        tr("8. Исходный код и авторство") to
            tr("Идея и замысел приложения: ${Credits.CREATOR}. Код написан ${Credits.CODE_BY}.\n\n" +
            "Исходный код открыт по лицензии GNU GPL версии 3 (или более поздней) с дополнительными условиями. " +
            "Код можно изучать, изменять и распространять, но изменённые версии должны оставаться открытыми на тех же " +
            "условиях, быть явно помечены как изменённые и сохранять сведения о создателе в разделе «О приложении». " +
            "Выдавать приложение или его код за собственную разработку нельзя. Полный текст — в файлах LICENSE и " +
            "NOTICE.md в репозитории проекта."),
        tr("9. Изменение условий") to
            tr("Если условия изменятся, приложение покажет их снова и попросит вашего согласия."),
        tr("10. Связь") to
            tr("Вопросы и сообщения об ошибках — в разделе Issues репозитория проекта. Ссылка есть в настройках, " +
            "в разделе «О приложении»."),
    )

    val OUTRO get() = tr("Нажимая «Принять», вы подтверждаете, что прочитали эти условия, согласны с ними и " +
        "пользуетесь приложением по собственному желанию и на свой риск.")
}

fun openLink(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun shareText(context: Context, subject: String, text: String) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** The creator's mark: white on red in a light theme, red on white in a dark one (as in "Розклад"). */
@Composable
fun CreatorLogo(size: Dp, modifier: Modifier = Modifier) {
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val tile = if (light) Color(0xFFFF0000) else Color.White
    val mark = if (light) Color.White else Color(0xFFFF0000)
    val image = remember(mark) {
        ImageVector.Builder(defaultWidth = 100.dp, defaultHeight = 100.dp, viewportWidth = 100f, viewportHeight = 100f)
            .addPath(pathData = addPathNodes(Credits.LOGO_PATH), fill = SolidColor(mark))
            .build()
    }
    Image(image, tr("Логотип создателя"), modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(tile))
}

@Composable
private fun TermsText() {
    Text(Terms.INTRO, style = MaterialTheme.typography.bodyMedium)
    Terms.SECTIONS.forEach { (title, body) ->
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Shown before anything else: first what this app is and who made it, then the agreement.
 * "Accept" and "Decline" are at the very end of the text, so they can only be reached by
 * scrolling through all of it.
 */
@Composable
fun FirstRunFlow() {
    val g = MalachiteApp.graph
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf(0) }
    var crashReports by rememberSaveable { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.TopCenter) {
            // not wider than a comfortable line of text, also on tablets and laptops
            Column(Modifier.widthIn(max = 640.dp).fillMaxHeight().padding(horizontal = 22.dp, vertical = 12.dp)) {
                if (page == 0) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                    ) {
                        CreatorLogo(96.dp)
                        Text(Credits.APP_NAME, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                        Text(tr("Офлайн-плеер, который приводит музыку в порядок"), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Fact(tr("Код полностью создан ИИ Claude."))
                        Fact(tr("Все идеи: ${Credits.CREATOR.lowercase()}."))
                        Fact(tr("Дальнейшие обновления могут оказаться невозможными в будущем."))
                        Fact(tr("Данные плеера (история прослушиваний, плейлисты, статистика) по умолчанию не защищены паролем. Защиту можно включить в разделе «Процессы»."))
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = { page = 1 }, modifier = Modifier.fillMaxWidth()) { Text(tr("Далее — пользовательское соглашение")) }
                        // the other language, one tap away, before anything has to be read
                        val english = app.malachite.player.data.Language.isEnglish
                        androidx.compose.material3.TextButton(onClick = {
                            app.malachite.player.data.Language.change(
                                context,
                                if (english) app.malachite.player.data.Language.Choice.RUSSIAN else app.malachite.player.data.Language.Choice.ENGLISH,
                            )
                        }) { Text(if (english) "На русском" else "In English") } // i18n:off – each name in its own language
                    }
                } else {
                    val scroll = rememberScrollState()
                    Text(Terms.TITLE, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        tr("Версия ${Terms.VERSION} от ${Terms.DATE}. Прочитайте до конца: кнопки «Отклонить» и «Принять» — в самом низу."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    LinearProgressIndicator(
                        progress = { if (scroll.maxValue > 0) scroll.value / scroll.maxValue.toFloat() else 1f },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                    )
                    Column(Modifier.weight(1f).verticalScroll(scroll).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        TermsText()
                        Spacer(Modifier.height(12.dp))
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                                .clickable { crashReports = !crashReports }.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(crashReports, { crashReports = it })
                            Column {
                                Text(tr("Помогать находить ошибки"), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    tr("Необязательно. Если плеер закроется из-за ошибки, он сохранит её описание у себя и при " +
                                        "следующем запуске спросит, отправить ли его. Сам он ничего не отправляет. Можно изменить в настройках."),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(Terms.OUTRO, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { (context as? Activity)?.finishAffinity() }, modifier = Modifier.weight(1f)) { Text(tr("Отклонить")) }
                            Button(
                                onClick = {
                                    g.settings.update { it.copy(termsAccepted = Terms.VERSION, crashReports = crashReports) }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text(tr("Принять")) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Fact(text: String) {
    Text(
        text,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp),
        style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
    )
}

/** The agreement again, for reading (Settings → About). */
@Composable
fun TermsScreen() {
    SettingsPage {
        Text(Terms.TITLE, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Hint(tr("Версия ${Terms.VERSION} от ${Terms.DATE}. Вы приняли эти условия при первом запуске."))
        TermsText()
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun AboutScreen() {
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    SettingsPage {
        Group(tr("Создатель")) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { openLink(context, Credits.CREATOR_LINK) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CreatorLogo(64.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Главный создатель этого плеера"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Credits.CREATOR_DISPLAY, style = MaterialTheme.typography.titleLarge)
                    Text(Credits.CREATOR_LINK_LABEL, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Hint(tr("Все идеи: ${Credits.CREATOR.lowercase()}. Код полностью создан ${Credits.CODE_BY}."))
        }
        Group(tr("Изменили код / модифицировали")) {
            if (Credits.MODIFIED_BY.isEmpty()) {
                Hint(tr("Это оригинальная версия — её никто не изменял."))
            } else {
                Credits.MODIFIED_BY.forEach { m ->
                    Column(Modifier.fillMaxWidth().clickable(enabled = m.link.isNotBlank()) { openLink(context, m.link) }.padding(vertical = 4.dp)) {
                        Text(m.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        if (m.what.isNotBlank()) Text(m.what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Hint(tr("Меняете код и выпускаете свою версию? Лицензия просит пометить её как изменённую — проще всего добавить себя в этот список (файл Credits.kt)."))
        }
        Group("${Credits.APP_NAME} $version") {
            Hint(tr("Без рекламы, без аккаунтов, без слежки. Все данные — в главной папке. Обновления могут оказаться невозможными в будущем."))
            NavRow(tr("Пользовательское соглашение"), tr("Условия и конфиденциальность, версия ${Terms.VERSION}")) { nav.push(Screen.Terms) }
            NavRow(tr("Лицензия"), Credits.LICENSE_NAME) { openLink(context, Credits.SOURCE_URL + "/blob/HEAD/NOTICE.md") }
            NavRow(tr("Исходный код"), Credits.SOURCE_URL.removePrefix("https://")) { openLink(context, Credits.SOURCE_URL) }
            NavRow(tr("Сообщить об ошибке"), tr("Откроется раздел Issues проекта")) { openLink(context, Credits.ISSUES_URL) }
            NavRow(tr("Ошибки и журнал"), tr("Журнал в файл, отчёт о сбое")) { nav.push(Screen.SettingsDiagnostics) }
            if (Credits.DONATE_URL.isNotBlank() || Credits.DONATE_WALLETS.isNotEmpty()) {
                NavRow(tr("Поддержать автора"), tr("Только по желанию — спасибо!")) { nav.push(Screen.Support) }
            }
        }
    }
}

/** Voluntary donations: addresses with their coin and network, each with a copy button. */
@Composable
fun SupportScreen() {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    SettingsPage {
        Group(tr("Поддержать автора")) {
            Hint(tr("Плеер бесплатный и таким останется. Если он вам пригодился, автора можно поблагодарить — только по желанию."))
            if (Credits.DONATE_URL.isNotBlank()) {
                Button(onClick = { openLink(context, Credits.DONATE_URL) }) { Text(tr("Открыть страницу для пожертвований")) }
            }
        }
        if (Credits.DONATE_WALLETS.isNotEmpty()) {
            Text(
                tr("Важно: на каждый адрес отправляйте только указанную монету и только через указанную сеть. " +
                    "Перевод другой монетой или через другую сеть будет потерян безвозвратно."),
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Credits.DONATE_WALLETS.forEach { w ->
                Group(tr("${w.coin} — сеть ${w.network}")) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(w.address, style = MaterialTheme.typography.bodyMedium, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    }
                    Hint(tr("Минимальная сумма: ${w.minimum}. Меньший перевод может не дойти."))
                    OutlinedButton(onClick = {
                        runCatching {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("${w.coin} ${w.network}", w.address))
                        }
                        nav.toast(tr("Адрес ${w.coin} (${w.network}) скопирован"))
                    }) { Text(tr("Скопировать адрес")) }
                }
            }
        }
    }
}

@Composable
fun DiagnosticsScreen() {
    val g = MalachiteApp.graph
    val s by g.settings.state.collectAsState()
    val context = LocalContext.current
    val nav = LocalNavigator.current
    SettingsPage {
        Group(tr("Журнал работы")) {
            SwitchRow(
                tr("Вести журнал в файл"), s.logEnabled,
                tr("Выключено по умолчанию. Файл: ${AppLog.logFile().absolutePath.removePrefix("/storage/emulated/0/")}"),
            ) { v -> g.settings.update { it.copy(logEnabled = v) } }
            if (s.logEnabled) SwitchRow(
                tr("Подробный журнал: плавность и задержки"), s.logDetails,
                tr("Каждые несколько секунд: сколько кадров в секунду было на каждом экране, с какой частотой работал экран, какие кадры опоздали и на что ушло время. Ещё долгие задачи главного потока и память. Журнал растёт быстрее — включайте, когда что-то тормозит"),
            ) { v -> g.settings.update { it.copy(logDetails = v) } }
            Hint(
                tr("В журнал попадает, что делал плеер и что не получилось: поиск песен, исцеление, поиск текстов и обложек, " +
                    "ошибки воспроизведения. В нём могут быть названия ваших файлов. Плеер никуда его не отправляет — " +
                    "вы сами прикладываете файл к сообщению об ошибке."),
            )
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = {
                    val tail = AppLog.tail()
                    if (tail == null) nav.toast(tr("Журнала пока нет")) else shareText(context, tr("Журнал Malachite"), tail)
                }) { Text(tr("Поделиться концом журнала")) }
                TextButton(onClick = { AppLog.clear(); nav.toast(tr("Журнал очищен")) }) { Text(tr("Очистить")) }
            }
        }
        Group(tr("Сбои")) {
            SwitchRow(
                tr("Сохранять отчёт о сбое"), s.crashReports,
                tr("Если плеер закроется из-за ошибки, при следующем запуске он покажет отчёт и спросит, что с ним сделать"),
            ) { v -> g.settings.update { it.copy(crashReports = v) } }
            Hint(tr("Отчёт хранится только в памяти приложения, пока вы не решите его отправить или удалить."))
        }
        Group(tr("Сообщить об ошибке")) {
            Hint(tr("Лучше всего: включите журнал, повторите ошибку, затем создайте сообщение и приложите файл журнала."))
            Button(onClick = {
                openLink(context, AppLog.issueUrl("Ошибка: ", "**Что произошло**\n\n\n**Как повторить**\n\n\n**Устройство**\n${AppLog.deviceInfo()}\n"))
            }) { Text(tr("Создать сообщение на GitHub")) }
        }
    }
}

/** At start, when the previous run ended with a crash and the user allowed keeping the report. */
@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(AppLog.pendingCrash()) }
    val text = report ?: return
    fun done() { AppLog.clearCrash(); report = null }
    AlertDialog(
        onDismissRequest = { report = null },
        title = { Text(tr("В прошлый раз плеер закрылся из-за ошибки")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("Вот что сохранилось. Здесь могут быть названия ваших файлов — посмотрите, прежде чем отправлять."), style = MaterialTheme.typography.bodySmall)
                Text(
                    text.lineSequence().take(14).joinToString("\n"),
                    Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .verticalScroll(rememberScrollState()).padding(8.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                openLink(context, AppLog.issueUrl("Сбой: " + text.lineSequence().drop(3).firstOrNull().orEmpty().take(80), "```\n$text\n```"))
                done()
            }) { Text(tr("Отправить на GitHub")) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { shareText(context, tr("Отчёт о сбое Malachite"), text); done() }) { Text(tr("Поделиться")) }
                TextButton(onClick = { done() }) { Text(tr("Удалить")) }
            }
        },
    )
}
