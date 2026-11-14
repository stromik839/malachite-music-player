package app.malachite.player.ui.help

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.malachite.core.i18n.tr
import app.malachite.core.text.TextFold
import app.malachite.player.data.Tab
import app.malachite.player.ui.LocalNavigator
import app.malachite.player.ui.Navigator
import app.malachite.player.ui.Screen
import app.malachite.player.ui.settings.SettingsPage

/** One question of the help: the answer and, where it is a place in the app, a button to go there. */
class HelpItem(val question: String, val answer: String, val go: ((Navigator) -> Unit)? = null, val goLabel: String = "")

private fun screen(s: Screen): (Navigator) -> Unit = { it.push(s) }
private fun tab(t: Tab): (Navigator) -> Unit = { it.openTab(t) }

/** The song screen if something plays, else the list of songs (to start something). */
private fun player(): (Navigator) -> Unit = { n ->
    if (app.malachite.player.MalachiteApp.graph.player.state.value.currentPath != null) n.playerOpen = true else n.openTab(Tab.SONGS)
}

/** The questions people ask most, each answered by where to tap. */
fun helpItems(): List<HelpItem> = listOf(
    HelpItem(
        tr("Где настроить вид текста песни: шрифт, размер, цвет, выравнивание?"),
        tr("Настройки → «Тексты песен». Там же выделение слов в такт, анимация, яркость строк и любимые строки. Быстрый путь: на экране песни откройте текст и нажмите «Вид текста» внизу."),
        screen(Screen.SettingsLyrics), tr("Открыть «Тексты песен»"),
    ),
    HelpItem(
        tr("Как найти текст песни, если его нет?"),
        tr("Откройте песню (нажмите на мини-плеер внизу), нажмите кнопку текста и «Найти текст». Если найдено не то — меню ⋮ → «Найти текст»: можно исправить название и исполнителя, выбрать вариант или вставить свой текст."),
        player(), tr("Открыть экран песни"),
    ),
    HelpItem(
        tr("Как синхронизировать текст самому или исправить одну строку?"),
        tr("На экране песни: меню ⋮ → «Синхронизировать текст вручную» (держите кнопку, пока поётся строка или слово) или «Исправить строки текста» — поправить слова, добавить или удалить строку, синхронизировать заново только с нужного места. Незаконченная работа сохраняется сама — продолжить можно в «Процессах»."),
        player(), tr("Открыть экран песни"),
    ),
    HelpItem(
        tr("Как отметить любимую строку?"),
        tr("В тексте песни удерживайте нужную строку. Вид отметки (подчёркивание, маркер, цвет, толщина) — Настройки → «Тексты песен» → «Любимые строки». Отметка видна и в виджете."),
        player(), tr("Открыть экран песни"),
    ),
    HelpItem(
        tr("Где изменить название песни, исполнителя или альбом?"),
        tr("Удерживайте песню в списке (или нажмите ⋮ справа от неё) → «Изменить теги». На экране песни то же самое — в меню ⋮. Изменения записываются в сам файл, и он переезжает в папку нового исполнителя и альбома."),
        tab(Tab.SONGS), tr("Открыть песни"),
    ),
    HelpItem(
        tr("Как поменять или обрезать обложку?"),
        tr("Песня: удерживайте её → «Заменить обложку» — найти в интернете, выбрать свою картинку или обрезать текущую. Альбом: удерживайте плитку альбома → «Заменить или обрезать обложку». Плейлист: откройте его и нажмите на картинку."),
        tab(Tab.SONGS), tr("Открыть песни"),
    ),
    HelpItem(
        tr("Как переименовать, закрепить или добавить альбом в избранное?"),
        tr("Во вкладке «Альбомы» удерживайте плитку альбома — появится меню. Те же кнопки есть вверху открытого альбома. Сортировка и фильтр «Избранные» — над сеткой альбомов."),
        tab(Tab.ALBUMS), tr("Открыть альбомы"),
    ),
    HelpItem(
        tr("Где песни без альбома?"),
        tr("Во вкладке «Альбомы» первая плитка «Без альбома». Если у таких песен на самом деле есть альбом, его поможет найти «Исцеление» → «Пустые альбомы и исполнители»."),
        screen(Screen.NoAlbum), tr("Открыть «Без альбома»"),
    ),
    HelpItem(
        tr("Как поменять вид меню: где панель вкладок, какие вкладки и в каком порядке?"),
        tr("Настройки → «Меню и вкладки». Там положение панели (снизу, сверху, сбоку), список вкладок с порядком, мини-плеер, цвет, прозрачность и размытие панелей."),
        screen(Screen.SettingsMenu), tr("Открыть «Меню и вкладки»"),
    ),
    HelpItem(
        tr("Как сменить тему, цвет, язык или значок приложения?"),
        tr("Настройки → «Тема, цвета и язык»: тёмная или светлая тема, цвет акцента, свои цвета, шрифт меню, язык интерфейса и цвет значка на рабочем столе."),
        screen(Screen.SettingsAppearance), tr("Открыть «Тема, цвета и язык»"),
    ),
    HelpItem(
        tr("Что такое главная папка и где лежит моя музыка?"),
        tr("Все песни плеера лежат в одной главной папке, разложенными по папкам «Исполнитель / Альбом». Там же плейлисты и данные плеера, поэтому её легко перенести на другой телефон. Где она и как искать новые песни — Настройки → «Главная папка и новые песни»."),
        screen(Screen.SettingsLibrary), tr("Открыть «Главная папка и новые песни»"),
    ),
    HelpItem(
        tr("Почему не видно новых песен и как их добавить?"),
        tr("Плеер показывает только песни из главной папки. Новые песни на телефоне он находит сам по расписанию; найти сразу — Настройки → «Главная папка и новые песни» → «Найти сейчас». Голосовые, записи и рингтоны не добавляются никогда."),
        screen(Screen.SettingsLibrary), tr("Открыть «Главная папка и новые песни»"),
    ),
    HelpItem(
        tr("Что делает «Исцеление» и не испортит ли оно файлы?"),
        tr("Оно наводит порядок: исправляет названия и альбомы, вшивает обложки, убирает клоны и «не музыку», раскладывает файлы по папкам. Каждое изменение делается на копии и проверяется, а любое исцеление можно отменить на том же экране. Сначала нажмите «Проверить» — плеер покажет, что собирается изменить."),
        tab(Tab.HEAL), tr("Открыть исцеление"),
    ),
    HelpItem(
        tr("Как настроить виджет?"),
        tr("Добавьте виджет Malachite на рабочий стол и растяните его: чем больше, тем больше в нём видно. Цвета, текст песни и его вид — Настройки → «Виджет на рабочем столе»."),
        screen(Screen.SettingsWidgets), tr("Открыть «Виджет на рабочем столе»"),
    ),
    HelpItem(
        tr("Где скорость, питч, эквалайзер и таймер сна?"),
        tr("Скорость и питч — кнопка с числом (например, 1,00x) внизу экрана песни, таймер сна — кнопка с луной. Эквалайзер и значения по умолчанию — Настройки → «Воспроизведение и звук»."),
        screen(Screen.SettingsPlayback), tr("Открыть «Воспроизведение и звук»"),
    ),
    HelpItem(
        tr("Почему одни песни громче других и как это выровнять?"),
        tr("Песни записаны с разной громкостью. «Исцеление» → «Громкость» → «Проверить» → «Измерить»: плеер один раз измерит каждую песню и дальше играет все одинаково громко. Выключить — Настройки → «Воспроизведение и звук» → «Выравнивать громкость песен»."),
        tab(Tab.HEAL), tr("Открыть исцеление"),
    ),
    HelpItem(
        tr("Как защитить данные плеера паролем?"),
        tr("«Процессы» → «Защита данных плеера». Шифруются история прослушиваний, плейлисты, статистика, избранное и любимые строки. Забытый пароль восстановить нельзя, но музыка от него не зависит."),
        screen(Screen.Processes), tr("Открыть «Процессы»"),
    ),
    HelpItem(
        tr("Как перенести музыку на другой телефон?"),
        tr("Настройки → «Перенос и резервные копии» → «Экспорт в ZIP». На новом телефоне распакуйте архив или откройте его через «Импорт ZIP» — песни, плейлисты, тексты, избранное и настройки переедут вместе."),
        screen(Screen.SettingsBackup), tr("Открыть «Перенос и резервные копии»"),
    ),
    HelpItem(
        tr("Как слушать и скачивать музыку из интернета?"),
        tr("Плеер работает без интернета, онлайн-раздел выключен. Включите его: Настройки → «Музыка и тексты» → «Поиск и скачивание музыки в интернете» — появится поиск по открытым каталогам. Скачанные песни ложатся в главную папку как обычные."),
        screen(Screen.Settings), tr("Открыть настройки"),
    ),
    HelpItem(
        tr("Где статистика прослушиваний?"),
        tr("Во вкладке «Статистика»: время прослушивания по дням и часам, самые слушаемые песни, альбомы и исполнители, что исправило исцеление. Если вкладки нет на панели — добавьте её в «Меню и вкладки»."),
        tab(Tab.STATS), tr("Открыть статистику"),
    ),
    HelpItem(
        tr("Как выбрать сразу несколько песен, альбомов или плейлистов?"),
        tr("Нажмите кнопку-галочку рядом с «Слушать» (или «Выбрать» над альбомами, исполнителями и плейлистами), либо зажмите песню и выберите «Выбрать несколько». Дальше нажатия выбирают, сверху появляется панель: «Выбрать все», «Снять все», «Наоборот» и быстрый выбор — «Последнее пополнение» (все песни последней закачки или импорта), «Появились за последний час», «За сутки», «Без обложки», «Без текста» и другие. Меню ⋮ на панели или долгое нажатие на любую выбранную песню — что сделать со всеми сразу: слушать, в очередь, в плейлист, в избранное, теги, исцелить, удалить. Чтобы выбрать подряд, зажмите песню и ведите пальцем — выбираются все песни под пальцем, у края список листается сам; отпустите и повторите в другом месте. Если зажать уже выбранную и повести — выбор снимается. «По имени…» выбирает песни исполнителя, альбома или с названием (можно латиницей: kino = кино), или все, кроме них. Выбор действует только в открытом списке; «Назад» или ✕ его заканчивают."),
        tab(Tab.SONGS), tr("Открыть песни"),
    ),
    HelpItem(
        tr("Как исцелить только некоторые песни, а не всю главную папку?"),
        tr("Выберите эти песни (см. вопрос о выборе нескольких) и в меню выбранного нажмите «Исцелить выбранные…». Откроется список исцелений: отметьте только нужные (например, одни «Тексты» или одно «Качество обложек») или «Всё сразу». Плеер запоминает ваш выбор до следующего раза, «Как обычно» возвращает обычный набор. Ход виден во вкладке «Исцеление», там же всё можно отменить."),
        tab(Tab.SONGS), tr("Открыть песни"),
    ),
    HelpItem(
        tr("Можно исцелить не всё, а только что-то одно?"),
        tr("Да. Во вкладке «Исцеление» кнопка «Исцелить всё или выбрать, что именно…» открывает список: отметьте нужные исцеления по отдельности или «Всё сразу». То же самое — в «Исцелить выбранные…» для выбранных песен. «Регистр букв» включается только отдельной галочкой: многие исполнители пишут имя капсом нарочно. Кроме того, у каждого раздела ниже есть своя кнопка «Проверить» — там видно, что будет исправлено, и можно снять галочки."),
        tab(Tab.HEAL), tr("Открыть исцеление"),
    ),
    HelpItem(
        tr("Почему текст русской песни написан латиницей («I dve tysyachi let voyna»)?"),
        tr("В открытых базах текстов кто-то выложил транслит (или перевод) рядом с настоящим текстом, и раньше он мог совпасть с песней на 100%. Теперь текст другим алфавитом сам не ставится: в окне «Текст песни» он помечен «другим алфавитом» и стоит ниже, а исцеление «Тексты» находит такие сохранённые тексты и заменяет их настоящими, если нашлись. Тексты, которые вы выбрали, вставили или синхронизировали сами, исцеление не трогает."),
        tab(Tab.HEAL), tr("Открыть исцеление"),
    ),
    HelpItem(
        tr("Как отсортировать песни в плейлисте (по альбомам, названию, году)?"),
        tr("Откройте плейлист и нажмите кнопку сортировки рядом с «Слушать» и «Вперемешку». По альбомам — песни одного альбома вместе и по порядку треков, альбомы по году; ещё по исполнителям, названию, году, недавно добавленным, длине или наоборот. Новый порядок записывается в плейлист; «Вернуть порядок до сортировки» там же. Песню можно и перенести вручную: зажать и вести вверх или вниз."),
        tab(Tab.PLAYLISTS), tr("Открыть плейлисты"),
    ),
    HelpItem(
        tr("Как убрать песню или исполнителя из перемешивания, не удаляя?"),
        tr("В меню песни — «Не нравится» (или для выбранных песен сразу), для исполнителя — кнопка 👎 на его странице. Такие песни не играют во «Вперемешку» и не попадают в умные плейлисты («Часто слушаю», «Недавно добавленные»…), но файлы остаются, и нажатие на песню играет её как обычно. Список отмеченного и «Снова нравится» — Настройки → «Воспроизведение и звук»."),
        screen(Screen.SettingsPlayback), tr("Открыть «Воспроизведение и звук»"),
    ),
    HelpItem(
        tr("Что за черта на полосе перемотки?"),
        tr("Это лучший момент песни. Если у песни есть синхронизированный текст, плеер сам находит припев (строки, которые поются снова) и ставит там черту. Свой момент можно отметить в меню ⋮ на экране песни: «Отметить лучший момент здесь». Ведите ползунок к черте — рядом с ней он сам встаёт точно на неё (с лёгкой отдачей), и песня продолжится с этого места."),
        player(), tr("Открыть экран песни"),
    ),
    HelpItem(
        tr("Как сделать плавный переход между песнями (кроссфейд)?"),
        tr("Настройки → «Воспроизведение и звук» → «Плавный переход»: от 1 до 12 секунд. Конец песни затихает, следующая в это время набирает громкость. Работает для песен на телефоне; на паузе, при перемотке или выборе другой песни старая сразу замолкает."),
        screen(Screen.SettingsPlayback), tr("Открыть «Воспроизведение и звук»"),
    ),
    HelpItem(
        tr("Можно ли слушать онлайн-песни без интернета?"),
        tr("Да: песня из раздела «Онлайн», которую дослушали, остаётся на телефоне, и потом играет без интернета — список «Слушали — играют без интернета» в разделе «Онлайн». Сколько места на это отдать (или не хранить вовсе) — в настройках под переключателем онлайн-раздела. Чтобы песня стала обычным файлом главной папки — «Скачать»."),
        screen(Screen.Settings), tr("Открыть настройки"),
    ),
    HelpItem(
        tr("Почему у всех альбомов исполнителя одна и та же обложка?"),
        tr("Так бывает, когда вся дискография скачана одним архивом с одной картинкой (например, из Internet Archive) или сайт вшил свой логотип в каждый файл. Исцеление «Качество обложек» (оно входит и в «Исцелить всё») находит одну картинку на разных альбомах и ищет для каждого альбома его собственную обложку — тем же поиском, что и ручная «Заменить обложку». Обложки, которые вы выбрали сами, не трогаются. Новые скачивания из Internet Archive берут картинку из папки своего альбома."),
        tab(Tab.HEAL), tr("Открыть исцеление"),
    ),
    HelpItem(
        tr("У песен названия из цифр («115620301») — как узнать, что это за песни?"),
        tr("Плеер может узнать песню по звуку: он слушает её начало и спрашивает открытую базу AcoustID. Для этого нужен бесплатный ключ: Настройки → «Узнавание песен по звуку» — там пошаговая инструкция, поле для ключа и кнопка «Проверить ключ». После этого «Исцелить всё», «Исцелить выбранные» или раздел «Узнать песни по звуку» во вкладке «Исцеление» запишут найденные название, исполнителя и альбом. Без ключа это просто пропускается, остальное исцеление работает."),
        screen(Screen.SettingsSoundId), tr("Открыть настройку ключа"),
    ),
    HelpItem(
        tr("Почему «AyuGram» или «Telegram» стоят вместо альбома, а «Разные исполнители» — вместо исполнителя?"),
        tr("Так получается у песен, скачанных из Telegram-клиентов и загрузчиков: имя их папки принималось за альбом. Теперь плеер никогда не считает такие папки альбомом или исполнителем. Уже испорченные песни исправит «Исцеление» → «Пустые альбомы и исполнители» → «Проверить странные альбомы и исполнителей» или «Исцелить выбранные» с первой галочкой. Песни без названий узнаются по звуку (вопрос выше)."),
        tab(Tab.HEAL), tr("Открыть исцеление"),
    ),
    HelpItem(
        tr("Как быстро переключать вкладки, не попадая пальцем?"),
        tr("Зажмите палец на панели вкладок — подсветка-«таблетка» поднимется и поедет за пальцем, вкладка под ней подсвечивается. Отпустите — таблетка сама доедет до ближайшей вкладки и откроет её. Обычное нажатие работает как раньше. Сколько держать палец и как таблетка доезжает (пружина, плавно, быстро, без анимации) — в «Меню и вкладки»."),
        screen(Screen.SettingsMenu), tr("Открыть «Меню и вкладки»"),
    ),
    HelpItem(
        tr("Плеер помнит, где я был в другой вкладке?"),
        tr("Да: у каждой вкладки свой путь. Открытый плейлист, альбом или исполнитель и место, до которого вы пролистали, остаются, пока вы смотрите другую вкладку. Повторное нажатие на уже открытую вкладку возвращает в её начало."),
    ),
    HelpItem(
        tr("Анимации кажутся медленнее, чем в остальном телефоне (90/120 Гц)"),
        tr("Плеер просит у телефона самую высокую частоту экрана — «Меню и вкладки» → «Самая высокая частота экрана». Если всё равно тормозит, включите в «Ошибки и журнал» журнал и «Подробный журнал: плавность и задержки», повторите то, что тормозит, и пришлите файл журнала — в нём видно, сколько кадров в секунду было на каждом экране и что их задерживало."),
        screen(Screen.SettingsDiagnostics), tr("Открыть «Ошибки и журнал»"),
    ),
    HelpItem(
        tr("Как настроить, как уходит спетая строка и как закрашиваются слова?"),
        tr("Настройки → «Тексты песен»: «Как уходит спетая строка» — уменьшается и тускнеет, уезжает вверх, гаснет или размывается (размытие — на Android 12 и новее). «Выделение слов в такт песне» — сразу, быстро, плавно, пока слово поётся, или «по буквам, с мягким краем» — цвет бежит по строке буква за буквой."),
        screen(Screen.SettingsLyrics), tr("Открыть «Тексты песен»"),
    ),
    HelpItem(
        tr("Как скачать сразу все песни исполнителя или альбома из «Онлайн»?"),
        tr("В разделе «Онлайн» найдите исполнителя, альбом или плейлист, откройте его и нажмите «Скачать все» — плеер покажет примерный размер и спросит подтверждение. Ход скачивания виден полосой с отрезками; остановить можно в любой момент. Раздел «Онлайн» включается в настройках «Музыка и тексты»."),
        screen(Screen.Online()), tr("Открыть «Онлайн»"),
    ),
    HelpItem(
        tr("При синхронизации неудобно попадать в круглую кнопку, а строки «♪» приходится нажимать"),
        tr("Настройки → «Тексты песен» → «Ручная синхронизация»: кнопку «Держите, пока поётся» можно сделать шире (вплоть до всей ширины экрана), выше и с любым скруглением углов — от прямоугольника до круга. Там же «Строки «♪» отмечаются сами»: если после строки вы не нажимаете кнопку выбранное число секунд, «♪» (проигрыш) отмечается сам и длится до следующего нажатия; нажали раньше — «♪» пропускается."),
        screen(Screen.SettingsLyrics), tr("Открыть «Тексты песен»"),
    ),
    HelpItem(
        tr("Как поменять порядок песен в плейлисте?"),
        tr("Откройте плейлист, зажмите песню и, не отпуская, ведите её вверх или вниз — она поднимется и поедет за пальцем, остальные расступятся, у края список листается сам. Отпустите там, где она должна стоять. Если зажать и не вести — откроется меню песни."),
        tab(Tab.PLAYLISTS), tr("Открыть плейлисты"),
    ),
    HelpItem(
        tr("Что-то работает не так — как сообщить об ошибке?"),
        tr("Настройки → «Ошибки и журнал»: включите журнал, повторите ошибку и нажмите «Создать сообщение на GitHub», приложив файл журнала из папки Download."),
        screen(Screen.SettingsDiagnostics), tr("Открыть «Ошибки и журнал»"),
    ),
)

/** A place in the settings that the search can find. [keys] are extra words to match (both languages). */
class SettingEntry(val title: String, val where: String, val go: (Navigator) -> Unit, val keys: String = "")

fun settingEntries(): List<SettingEntry> {
    val look = tr("Тема, цвета и язык")
    val menu = tr("Меню и вкладки")
    val lyrics = tr("Тексты песен")
    val play = tr("Воспроизведение и звук")
    val lib = tr("Главная папка и новые песни")
    val widget = tr("Виджет на рабочем столе")
    val processes = tr("Процессы")
    val backup = tr("Перенос и резервные копии")
    return listOf(
        SettingEntry(tr("Язык интерфейса"), look, screen(Screen.SettingsAppearance), "language язык english русский"),
        SettingEntry(tr("Тема меню"), look, screen(Screen.SettingsAppearance), "тёмная светлая amoled dark light theme"),
        SettingEntry(tr("Цвет акцента"), look, screen(Screen.SettingsAppearance), "цвет colour color accent"),
        SettingEntry(tr("Значок приложения"), look, screen(Screen.SettingsAppearance), "иконка icon"),
        SettingEntry(tr("Шрифт меню"), look, screen(Screen.SettingsAppearance), "font размер текста"),
        SettingEntry(tr("Экран открытой песни"), look, screen(Screen.SettingsAppearance), "цвета из обложки cover colours player"),
        SettingEntry(tr("Положение панели вкладок"), menu, screen(Screen.SettingsMenu), "панель снизу сверху сбоку navigation bar"),
        SettingEntry(tr("Вкладки и их порядок"), menu, screen(Screen.SettingsMenu), "tabs вкладки порядок"),
        SettingEntry(tr("Мини-плеер"), menu, screen(Screen.SettingsMenu), "mini player"),
        SettingEntry(tr("Колонок в сетке"), menu, screen(Screen.SettingsMenu), "альбомы albums grid"),
        SettingEntry(tr("Шрифт текстов песен"), lyrics, screen(Screen.SettingsLyrics), "font lyrics шрифт размер жирный"),
        SettingEntry(tr("Вид текста песни"), lyrics, screen(Screen.SettingsLyrics), "выравнивание выделение слов анимация цвет lyrics look"),
        SettingEntry(tr("Любимые строки"), lyrics, screen(Screen.SettingsLyrics), "подчёркивание маркер underline highlight"),
        SettingEntry(tr("Положение текста на экране песни"), lyrics, screen(Screen.SettingsLyrics), "середина центр выше ниже сетка position centre middle grid"),
        SettingEntry(tr("Поиск текстов"), lyrics, screen(Screen.SettingsLyrics), "lyrics search lrclib"),
        SettingEntry(tr("Ручная синхронизация"), lyrics, screen(Screen.SettingsLyrics), "sync задержка автосохранение"),
        SettingEntry(tr("Скорость и питч по умолчанию"), play, screen(Screen.SettingsPlayback), "speed pitch nightcore slowed"),
        SettingEntry(tr("Эквалайзер"), play, screen(Screen.SettingsPlayback), "equalizer eq басы"),
        SettingEntry(tr("Пропускать тишину"), play, screen(Screen.SettingsPlayback), "silence"),
        SettingEntry(tr("Одинаковая громкость"), play, screen(Screen.SettingsPlayback), "громкость loudness replaygain выравнивать"),
        SettingEntry(tr("Цвета обложки в уведомлении"), play, screen(Screen.SettingsPlayback), "notification уведомление"),
        SettingEntry(tr("Плавный переход между песнями"), play, screen(Screen.SettingsPlayback), "кроссфейд crossfade fade затухание переход"), // i18n:off
        SettingEntry(tr("Визуализатор"), play, screen(Screen.SettingsPlayback), "полоски эквалайзер анимация visualizer spectrum bars"), // i18n:off
        SettingEntry(tr("Не нравится"), play, screen(Screen.SettingsPlayback), "дизлайк скрыть перемешивание dislike hide shuffle"), // i18n:off
        SettingEntry(tr("Онлайн-песни без интернета"), tr("Настройки"), screen(Screen.Settings), "кэш офлайн сохранять cache offline"), // i18n:off
        SettingEntry(tr("Главная папка"), lib, screen(Screen.SettingsLibrary), "папка folder путь path"),
        SettingEntry(tr("Новые песни на устройстве"), lib, screen(Screen.SettingsLibrary), "поиск scan новые песни"),
        SettingEntry(tr("Что не считать музыкой"), lib, screen(Screen.SettingsLibrary), "голосовые мессенджеры telegram voice"),
        SettingEntry(tr("Удалять песни вне главной папки"), lib, screen(Screen.SettingsLibrary), "удаление оригиналов полный переход delete originals"),
        SettingEntry(tr("Виджет Malachite"), widget, screen(Screen.SettingsWidgets), "widget"),
        SettingEntry(tr("Текст песни в виджете"), widget, screen(Screen.SettingsWidgets), "widget lyrics"),
        SettingEntry(tr("Защита данных плеера"), processes, screen(Screen.Processes), "пароль шифрование password encryption"),
        SettingEntry(tr("Синхронизация текстов"), processes, screen(Screen.Processes), "незаконченные продолжить drafts"),
        SettingEntry(tr("Экспорт в ZIP"), backup, screen(Screen.SettingsBackup), "перенос другой телефон zip backup"),
        SettingEntry(tr("Резервные копии и корзина"), backup, screen(Screen.SettingsBackup), "корзина trash копии"),
        SettingEntry(tr("Онлайн-раздел"), tr("Настройки"), screen(Screen.Settings), "интернет online audius"),
        SettingEntry(tr("Исцеление"), tr("Вкладка"), tab(Tab.HEAL), "heal порядок названия обложки клоны отменить undo"),
        SettingEntry(tr("Статистика"), tr("Вкладка"), tab(Tab.STATS), "stats statistics"),
        SettingEntry(tr("Root и Shizuku"), tr("Настройки"), screen(Screen.SettingsAdvanced), "root shizuku"),
        SettingEntry(tr("Ошибки и журнал"), tr("Настройки"), screen(Screen.SettingsDiagnostics), "log ошибка bug журнал"),
        SettingEntry(tr("Подробный журнал: плавность и задержки"), tr("Ошибки и журнал"), screen(Screen.SettingsDiagnostics), "fps кадры лаги тормозит lag frames"), // i18n:off
        SettingEntry(tr("Узнавание песен по звуку"), tr("Настройки"), screen(Screen.SettingsSoundId), "acoustid ключ key звук shazam распознать recognize"), // i18n:off
        SettingEntry(tr("Переключать вкладки, ведя пальцем"), menu, screen(Screen.SettingsMenu), "жест свайп таблетка gesture swipe pill"), // i18n:off
        SettingEntry(tr("Самая высокая частота экрана"), menu, screen(Screen.SettingsMenu), "90 120 гц hz герц плавность refresh"), // i18n:off
    )
}

private fun matches(query: String, vararg texts: String): Boolean {
    val words = TextFold.strictKey(query).split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return false
    val hay = texts.joinToString(" ") { TextFold.strictKey(it) }
    return words.all { it in hay }
}

/** What the settings search finds: settings and help questions. */
@Composable
fun SettingsSearchResults(query: String) {
    val nav = LocalNavigator.current
    val entries = remember { settingEntries() }
    val help = remember { helpItems() }
    val found = entries.filter { matches(query, it.title, it.where, it.keys) }
    val questions = help.filter { matches(query, it.question, it.answer) }
    if (found.isEmpty() && questions.isEmpty()) {
        Text(tr("Ничего не найдено. Попробуйте другое слово или откройте «Вопросы и ответы»."), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { nav.push(Screen.Help) }) { Text(tr("Вопросы и ответы")) }
        return
    }
    found.forEach { e ->
        Column(Modifier.fillMaxWidth().clickable { e.go(nav) }.padding(vertical = 8.dp)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(e.where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (questions.isNotEmpty()) {
        Text(tr("Вопросы и ответы"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
        questions.forEach { HelpCard(it) }
    }
}

@Composable
private fun HelpCard(item: HelpItem, startOpen: Boolean = false) {
    val nav = LocalNavigator.current
    var open by rememberSaveable(item.question) { mutableStateOf(startOpen) }
    Card(
        Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.clickable { open = !open }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.question, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            }
            if (open) {
                Text(item.answer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                item.go?.let { go ->
                    androidx.compose.material3.Button(onClick = { go(nav) }) {
                        Text(item.goLabel.ifBlank { tr("Открыть") })
                    }
                }
            }
        }
    }
}

/** "Вопросы и ответы": what people ask, with a search and a button to go to the place. */
@Composable
fun HelpScreen() {
    val nav = LocalNavigator.current
    var query by rememberSaveable { mutableStateOf("") }
    val items = remember { helpItems() }
    SettingsPage {
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(),
            placeholder = { Text(tr("Что вы ищете? Например: обложка, текст, тема")) },
            leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true,
        )
        if (query.isBlank()) {
            items.forEach { HelpCard(it) }
            OutlinedButton(
                onClick = {
                    // the tour points at the main screen's buttons, so it starts from there
                    val g = app.malachite.player.MalachiteApp.graph
                    nav.openTab(g.settings.value.tabs.firstOrNull() ?: app.malachite.player.data.Tab.SONGS)
                    g.settings.update { it.copy(tourShown = false) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("Показать обучение ещё раз")) }
        } else {
            SettingsSearchResults(query)
        }
    }
}
