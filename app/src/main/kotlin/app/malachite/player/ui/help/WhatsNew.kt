package app.malachite.player.ui.help

import app.malachite.core.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.malachite.player.MalachiteApp
import app.malachite.player.ui.LocalNavigator
import app.malachite.player.ui.Navigator
import app.malachite.player.ui.Screen
import app.malachite.player.data.Tab

/**
 * What the last updates brought, shown once after an update (and from Settings → «Что нового»):
 * new things are easy to miss when nothing on the screen points at them.
 */
object WhatsNew {
    /** Raised whenever the list below changes: the window is shown once more after such an update. */
    const val ID = 6

    class Item(val title: String, val text: String, val open: ((Navigator) -> Unit)? = null, val openLabel: String = "")

    private fun playerOrSongs(): (Navigator) -> Unit = { n ->
        if (MalachiteApp.graph.player.state.value.currentPath != null) n.playerOpen = true else n.openTab(Tab.SONGS)
    }

    fun items(): List<Item> = listOf(
        Item(
            tr("Экран песни открывается ровно"),
            tr("Обложка песни и её цвета готовятся заранее, пока она играет: экран песни выезжает плавно и сразу с обложкой — без «В файле нет обложки» на миг и без рывка. При смене песни обложка и цвета перетекают мягко. И сообщения с экрана песни («Лучший момент отмечен»…) теперь видны."),
            playerOrSongs(), tr("Открыть песню"),
        ),
        Item(
            tr("Цвета из обложки везде"),
            tr("Новый переключатель в Оформление → «Эффекты»: акцент, фон и панели всего приложения берут оттенки обложки играющей песни — тема подходит к размытому фону. Выключен, пока не включите."),
            { it.push(Screen.SettingsAppearance) }, tr("К эффектам"),
        ),
        Item(
            tr("Обложки в FLAC и OGG"),
            tr("«Найти и вшить в файл», исцеление обложек и перевод песни в FLAC теперь вшивают обложку и в файлы FLAC и OGG. Раньше на телефоне это не получалось из-за ошибки в библиотеке тегов."),
        ),
        Item(
            tr("Размытие легче и на любом Android"),
            tr("Обложка размывается заранее, один раз на песню, а не в каждом кадре: слабым телефонам так легче. И размытие теперь есть на Android 8–11."),
            { it.push(Screen.SettingsAppearance) }, tr("К эффектам"),
        ),
        Item(
            tr("Визуализатор точно в такт"),
            tr("Удар на полосках теперь виден ровно тогда, когда он слышен: визуализатор берёт время у самого звукового выхода телефона, а не угадывает его. Новые виды: «Горы», «Точки», «Осциллограф» (сама звуковая волна) и «Пульс» (громкость последних секунд)."),
            { it.push(Screen.SettingsPlayback) }, tr("Выбрать вид"),
        ),
        Item(
            tr("Размытие по-новому"),
            tr("Размытая обложка теперь лежит под всем окном, а панели — прозрачные слои поверх неё: нет резкой середины между размытыми краями. Оформление → Эффекты → «Размытие»."),
            { it.push(Screen.SettingsAppearance) }, tr("К эффектам"),
        ),
        Item(
            tr("Вкладка «Добавить»"),
            tr("Песню, которую плеер не заметил, можно добавить самому: путь к файлу или к папке, или выбор в файловом менеджере. Там же песню можно перевести в M4A, FLAC или WAV, или просто исправить неправильное расширение."),
            { it.openTab(Tab.ADD) }, tr("Открыть «Добавить»"),
        ),
        Item(
            tr("Визуализатор: расположение и плавность"),
            tr("Новые расположения полос: басы в центре, или по силе — самые громкие в середине, слева или справа. Скорость появления и исчезания настраивается, по умолчанию — «Волна»."),
            { it.push(Screen.SettingsPlayback) }, tr("Выбрать вид"),
        ),
        Item(
            tr("История поиска и эффекты"),
            tr("Под пустым полем поиска — недавние запросы (и в «Онлайн»). В «Оформлении» — «Эффекты»: анимации интерфейса, тени-градиенты и размытие (выключено, пока не включите)."),
            { it.push(Screen.SettingsAppearance) }, tr("К эффектам"),
        ),
        Item(
            tr("Лучший момент и вкладки"),
            tr("Ползунок встаёт на черту лучшего момента, только если задержать его рядом на пару секунд. Таблетка вкладок больше не возвращается назад и не догоняет вкладку, когда появляется клавиатура."),
        ),
        Item(
            tr("DJ-микшер"),
            tr("В «Эквалайзер и микшер» (меню ⋮ на экране песни): ручки TRIM, HI, MID и LOW — до упора влево они убирают басы, середину или верх целиком (KILL), FILTER, BAL и индикатор уровня L/R."),
            { it.push(Screen.Equalizer) }, tr("Открыть микшер"),
        ),
        Item(
            tr("Новый визуализатор"),
            tr("Двигаются все полосы, а не только басы: чувствительность подстраивается под песню. Свечение, отражение и «шапочки» пиков; три вида — полосы, светодиоды и волна (Настройки → «Воспроизведение и звук» → «Визуализатор»)."),
            { it.push(Screen.SettingsPlayback) }, tr("Выбрать вид"),
        ),
        Item(
            tr("Песни из Telegram находятся сами"),
            tr("Песни из папок Telegram и других мессенджеров — с названием и исполнителем или с именем «Исполнитель - Название», а также сохранённые в «Музыку» и «Загрузки» — теперь добавляются как обычная музыка. Если поиск ничего не нашёл, он объясняет почему и что сделать; «Готово» после добавления ведёт к песням."),
            { it.push(Screen.ImportScreen) }, tr("Найти музыку"),
        ),
        Item(
            tr("Свой альбом и плавные экраны"),
            tr("Выберите песни → «Собрать в свой альбом…» — и они станут альбомом. Экраны теперь сменяются плавно: следующий мягко выезжает, предыдущий уходит вправо."),
            { it.openTab(Tab.SONGS) }, tr("К песням"),
        ),
        Item(
            tr("Сортировка плейлиста"),
            tr("В плейлисте рядом с «Слушать» и «Вперемешку» — кнопка сортировки: по альбомам, исполнителям, названию, году и другие. Песню, которую несёте пальцем, больше не теряет у края экрана."),
            { it.openTab(Tab.PLAYLISTS) }, tr("К плейлистам"),
        ),
        Item(
            tr("Плавный переход между песнями"),
            tr("Конец песни затихает, следующая набирает громкость. Включается в Настройки → «Воспроизведение и звук» → «Плавный переход» (1–12 с)."),
            { it.push(Screen.SettingsPlayback) }, tr("Включить"),
        ),
        Item(
            tr("Лучший момент"),
            tr("Черта на полосе перемотки: припев, найденный по синхронизированному тексту, или своя отметка (меню ⋮ на экране песни → «Отметить лучший момент здесь»). Задержите ползунок у черты на пару секунд — он встанет точно на неё; рядом с ней можно выбрать любое место."),
            playerOrSongs(), tr("К экрану песни"),
        ),
        Item(
            tr("Не нравится"),
            tr("Песня (меню песни) или исполнитель (👎 на его странице) не играет вперемешку и не попадает в умные плейлисты. Файлы остаются."),
            { it.push(Screen.SettingsPlayback) }, tr("Список"),
        ),
        Item(
            tr("Онлайн-песни без интернета"),
            tr("Дослушанная песня из раздела «Онлайн» остаётся на телефоне и потом играет без интернета: список «Слушали — играют без интернета» в разделе «Онлайн»."),
        ),
        Item(
            tr("Исцеление: выбор, что исцелять"),
            tr("«Исцелить всё или выбрать, что именно…» и «Исцелить выбранные…» открывают список исцелений с галочками. Тексты латиницей заменяются настоящими, одна обложка на разные альбомы — своими."),
            { it.openTab(Tab.HEAL) }, tr("К исцелению"),
        ),
    )
}

/** The window itself; [onClose] marks it as seen. */
@Composable
fun WhatsNewDialog(onClose: () -> Unit) {
    val nav = LocalNavigator.current
    val items = androidx.compose.runtime.remember { WhatsNew.items() }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(tr("Что нового")) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr("Сборка ${appVersion()}"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                items.forEach { item ->
                    Column {
                        Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(item.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val open = item.open
                        if (open != null) TextButton(onClick = { onClose(); open(nav) }, modifier = Modifier.padding(start = 0.dp)) { Text(item.openLabel) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(tr("Понятно")) } },
    )
}

/** The installed build as people see it ("0.2.0-46cc06c"). */
fun appVersion(): String {
    val context = MalachiteApp.graph.context
    return runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
}
