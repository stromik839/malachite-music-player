package app.malachite.player.ui

import app.malachite.core.i18n.tr
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.QuestionMark
import app.malachite.player.ui.help.tourTarget
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.malachite.player.MalachiteApp
import app.malachite.player.data.NavPosition
import app.malachite.player.data.PartStyle
import app.malachite.player.data.Tab
import app.malachite.player.ui.components.CoverImage
import app.malachite.player.ui.components.rememberCover
import app.malachite.player.ui.dialogs.DialogHost
import app.malachite.player.ui.heal.HealScreen
import app.malachite.player.ui.nowplaying.NowPlayingScreen
import app.malachite.player.ui.screens.AlbumDetailScreen
import app.malachite.player.ui.screens.AlbumsScreen
import app.malachite.player.ui.screens.ArtistDetailScreen
import app.malachite.player.ui.screens.ArtistsScreen
import app.malachite.player.ui.screens.FolderScreen
import app.malachite.player.ui.screens.ImportScreen
import app.malachite.player.ui.screens.PlaylistDetailScreen
import app.malachite.player.ui.screens.PlaylistsScreen
import app.malachite.player.ui.screens.SearchScreen
import app.malachite.player.ui.screens.SmartPlaylistScreen
import app.malachite.player.ui.screens.SongsScreen
import app.malachite.player.ui.settings.EqualizerScreen
import app.malachite.player.ui.settings.SettingsAdvancedScreen
import app.malachite.player.ui.settings.SettingsAppearanceScreen
import app.malachite.player.ui.settings.SettingsBackupScreen
import app.malachite.player.ui.settings.SettingsLibraryScreen
import app.malachite.player.ui.settings.SettingsLyricsScreen
import app.malachite.player.ui.settings.SettingsMenuScreen
import app.malachite.player.ui.settings.SettingsPlaybackScreen
import app.malachite.player.ui.settings.SettingsScreen
import app.malachite.player.ui.settings.SettingsWidgetsScreen
import app.malachite.player.ui.sync.LyricsSyncScreen
import kotlinx.coroutines.delay

fun Tab.icon(): ImageVector = when (this) {
    Tab.SONGS -> Icons.Filled.MusicNote
    Tab.ALBUMS -> Icons.Filled.Album
    Tab.ARTISTS -> Icons.Filled.Person
    Tab.PLAYLISTS -> Icons.Filled.QueueMusic
    Tab.FAVORITES -> Icons.Filled.Favorite
    Tab.FOLDERS -> Icons.Filled.Folder
    Tab.HEAL -> Icons.Filled.AutoFixHigh
    Tab.SEARCH -> Icons.Filled.Search
    Tab.ONLINE -> Icons.Filled.Public
    Tab.PROCESSES -> Icons.Filled.HourglassBottom
    Tab.STATS -> Icons.Filled.BarChart
    Tab.ADD -> Icons.Filled.LibraryAdd
}

/** Background of a menu part with the user's colour, transparency and optional frosted cover. */
@Composable
fun PartBackground(style: PartStyle, default: Color, cover: Bitmap?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val fx by MalachiteApp.graph.settings.state.collectAsState()
    val base = style.color?.let { Color(it.toInt()) } ?: default
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && cover != null
    // "Размытие" for everything (Look → Effects): one frosted picture lies behind the whole window
    // (see [FrostedBackdrop]) and the parts are clear layers over it, the same glass as the rest.
    val glass = fx.blur && canBlur
    // the part's own frosted look (Menu and tabs) when the one for everything is off
    val own = style.blur && !fx.blur && canBlur
    Box(modifier) {
        if (own) Image(cover!!.asImageBitmap(), null, Modifier.matchParentSize().blur(28.dp), contentScale = ContentScale.Crop)
        val alpha = style.alpha.coerceIn(0f, 1f).let { a -> if (glass) kotlin.math.min(a, 0.5f) else if (own) kotlin.math.min(a, 0.72f) else a }
        Box(Modifier.matchParentSize().background(base.copy(alpha = alpha)))
        // a faint sheen from above
        if (fx.gradients) Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.04f), Color.Transparent))))
        content()
    }
}

@Composable
fun MainScaffold() {
    val graph = MalachiteApp.graph
    val nav = LocalNavigator.current
    val settings by graph.settings.state.collectAsState()
    val player by graph.player.state.collectAsState()
    val current = player.currentPath?.let { graph.library.track(it) }
    val cover = rememberCover(current, 400)
    val heal by graph.healing.state.collectAsState()

    LaunchedEffect(nav.snackbar) {
        if (nav.snackbar != null) { delay(3500); nav.snackbar = null }
    }
    LaunchedEffect(heal.message) {
        heal.message?.let { nav.toast(it); graph.healing.clearMessage() }
    }
    // healing was cut short by closing or updating the app: say where to go on with it
    LaunchedEffect(heal.interrupted.isNotEmpty()) {
        if (heal.interrupted.isNotEmpty()) nav.toast(tr("Исцеление не закончилось — продолжить можно во вкладке «Исцеление»"))
    }

    // Tablets, laptop windows and phones turned on their side: the tab panel goes to the side
    // (a bottom bar would eat the little height there is). The user's own "left / right" stays.
    val window = androidx.compose.ui.platform.LocalConfiguration.current
    val roomy = window.screenWidthDp >= 600 || (window.screenWidthDp > window.screenHeightDp && window.screenHeightDp < 480)
    val navPosition = if (settings.navAuto && roomy && (settings.navPosition == NavPosition.BOTTOM || settings.navPosition == NavPosition.TOP)) {
        NavPosition.LEFT
    } else settings.navPosition
    val navBar: @Composable () -> Unit = {
        // the "online" tab exists only while the online section is switched on
        val tabs = settings.tabs.filter { it != Tab.ONLINE || settings.onlineEnabled }
        TabBar(
            tabs, nav.currentTab, navPosition, settings.navStyle, settings.showTabLabels, cover,
            settings.tabSlide, settings.tabSlideDelayMs, if (settings.animations) settings.tabSlideAnim else app.malachite.player.data.TabSlideAnim.NONE,
        ) { nav.openTab(it) }
    }
    val mini: @Composable () -> Unit = {
        if (current != null) Box(Modifier.tourTarget("mini")) { MiniPlayer(settings.miniPlayerStyle, cover) }
    }
    // the AcoustID key failed during healing: one window, wherever the user is
    app.malachite.player.ui.heal.SoundKeyProblemDialog()
    // what each open screen keeps (the scroll position above all) while another tab is looked at
    val kept = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    androidx.compose.runtime.DisposableEffect(nav, kept) {
        nav.onForget = { kept.removeState(it); app.malachite.player.ui.components.Selections.forget(it) }
        onDispose { nav.onForget = null }
    }
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize()) {
            TopBar(settings.topBarStyle, cover)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val key = nav.currentKey
                // which screen the detailed log reports frames for
                LaunchedEffect(nav) {
                    androidx.compose.runtime.snapshotFlow { titleOf(nav.current) + if (nav.playerOpen) " + плеер" else "" } // i18n:off
                        .collect { app.malachite.player.data.FrameWatch.screen = it }
                }
                // A screen comes in softly: deeper screens glide in from the right, going back the
                // screen glides away to the right, another tab fades in.
                androidx.compose.animation.AnimatedContent(
                    targetState = Place(key, nav.currentTab, nav.stack.size, nav.current),
                    modifier = Modifier.fillMaxSize(),
                    contentKey = { it.key },
                    transitionSpec = {
                        val sameTab = initialState.tab == targetState.tab
                        val glide = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(
                            280, easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        )
                        when {
                            // the animations switch is off: the new screen is simply there
                            !settings.animations -> androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                            sameTab && targetState.depth > initialState.depth ->
                                (androidx.compose.animation.slideInHorizontally(glide) { it / 8 } +
                                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 30))) togetherWith
                                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150))
                            sameTab && targetState.depth < initialState.depth ->
                                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 30)) togetherWith
                                    (androidx.compose.animation.slideOutHorizontally(glide) { it / 8 } +
                                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(200)))).apply { targetContentZIndex = -1f }
                            else -> androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220, delayMillis = 50)) togetherWith
                                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150))
                        }
                    },
                    label = "screen",
                ) { place ->
                    kept.SaveableStateProvider(place.key) {
                        androidx.compose.runtime.CompositionLocalProvider(app.malachite.player.ui.components.LocalScreenKey provides place.key) { ScreenContent(place.screen) }
                    }
                }
                // soft shadows where the screen meets the bars and the tab panel
                if (settings.gradients) EdgeShadows(navPosition)
            }
        }
    }

    // the frosted glass behind the whole window, the screens and the panels alike
    if (settings.blur) FrostedBackdrop(cover)
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        when (navPosition) {
            NavPosition.BOTTOM -> Column(Modifier.fillMaxSize()) {
                if (settings.miniPlayerOnTop) mini()
                Box(Modifier.weight(1f)) { content() }
                if (!settings.miniPlayerOnTop) mini()
                navBar()
            }
            NavPosition.TOP -> Column(Modifier.fillMaxSize()) {
                navBar()
                if (settings.miniPlayerOnTop) mini()
                Box(Modifier.weight(1f)) { content() }
                if (!settings.miniPlayerOnTop) mini()
            }
            NavPosition.LEFT -> Row(Modifier.fillMaxSize()) {
                navBar()
                Column(Modifier.weight(1f)) {
                    if (settings.miniPlayerOnTop) mini()
                    Box(Modifier.weight(1f)) { content() }
                    if (!settings.miniPlayerOnTop) mini()
                }
            }
            NavPosition.RIGHT -> Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f)) {
                    if (settings.miniPlayerOnTop) mini()
                    Box(Modifier.weight(1f)) { content() }
                    if (!settings.miniPlayerOnTop) mini()
                }
                navBar()
            }
        }
        nav.snackbar?.let { msg ->
            Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp).padding(bottom = 120.dp)) { Text(msg) }
        }
    }
    AnimatedVisibility(
        visible = nav.playerOpen && current != null,
        enter = if (settings.animations) slideInVertically { it } else androidx.compose.animation.EnterTransition.None,
        exit = if (settings.animations) slideOutVertically { it } else androidx.compose.animation.ExitTransition.None,
    ) {
        NowPlayingScreen()
    }
    DialogHost()
}

/**
 * The playing song's cover, blurred and dimmed, behind the whole window (Android 12+): screens and
 * panels lie on the same frosted glass instead of blurred panels beside a sharp screen.
 */
@Composable
private fun FrostedBackdrop(cover: Bitmap?) {
    if (cover == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    androidx.compose.animation.Crossfade(cover, label = "backdrop") { c ->
        Image(c.asImageBitmap(), null, Modifier.fillMaxSize().blur(48.dp).alpha(0.32f), contentScale = ContentScale.Crop)
    }
}

/** Gradient shadows along the edges of the screen area: under the top bar, above the bottom bars, beside a side panel. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.EdgeShadows(navPosition: NavPosition) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shade = Color.Black.copy(alpha = if (dark) 0.3f else 0.12f)
    val depth = 10.dp
    Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(depth).background(Brush.verticalGradient(listOf(shade, Color.Transparent))))
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(depth).background(Brush.verticalGradient(listOf(Color.Transparent, shade))))
    when (navPosition) {
        NavPosition.RIGHT -> Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(depth).background(Brush.horizontalGradient(listOf(Color.Transparent, shade))))
        NavPosition.LEFT -> Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(depth).background(Brush.horizontalGradient(listOf(shade, Color.Transparent))))
        else -> {}
    }
}

/** Where the navigation stands: the screen shown, its tab and how deep in the tab it is (for the direction of a transition). */
private data class Place(val key: String, val tab: Tab, val depth: Int, val screen: Screen)

@Composable
private fun ScreenContent(screen: Screen) {
    when (screen) {
        is Screen.TabRoot -> when (screen.tab) {
            Tab.SONGS -> SongsScreen()
            Tab.ALBUMS -> AlbumsScreen()
            Tab.ARTISTS -> ArtistsScreen()
            Tab.PLAYLISTS -> PlaylistsScreen()
            Tab.FAVORITES -> SmartPlaylistScreen(SmartKind.FAVORITES)
            Tab.FOLDERS -> FolderScreen("")
            Tab.HEAL -> HealScreen()
            Tab.SEARCH -> SearchScreen()
            Tab.ONLINE -> app.malachite.player.ui.screens.OnlineScreen()
            Tab.PROCESSES -> app.malachite.player.ui.screens.ProcessesScreen()
            Tab.STATS -> app.malachite.player.ui.screens.StatsScreen()
            Tab.ADD -> app.malachite.player.ui.screens.AddScreen()
        }
        is Screen.ArtistDetail -> ArtistDetailScreen(screen.key)
        is Screen.AlbumDetail -> AlbumDetailScreen(screen.key)
        Screen.NoAlbum -> app.malachite.player.ui.screens.NoAlbumScreen()
        is Screen.PlaylistDetail -> PlaylistDetailScreen(screen.name)
        is Screen.SmartPlaylist -> SmartPlaylistScreen(screen.kind)
        is Screen.Folder -> FolderScreen(screen.relative)
        is Screen.LyricsSync -> LyricsSyncScreen(screen.path, screen.resume)
        Screen.Processes -> app.malachite.player.ui.screens.ProcessesScreen()
        Screen.Help -> app.malachite.player.ui.help.HelpScreen()
        is Screen.LyricsEdit -> app.malachite.player.ui.sync.LyricsEditScreen(screen.path)
        Screen.Search -> SearchScreen()
        Screen.Settings -> SettingsScreen()
        Screen.SettingsAppearance -> SettingsAppearanceScreen()
        Screen.SettingsMenu -> SettingsMenuScreen()
        Screen.SettingsLibrary -> SettingsLibraryScreen()
        Screen.SettingsLyrics -> SettingsLyricsScreen()
        Screen.SettingsPlayback -> SettingsPlaybackScreen()
        Screen.SettingsWidgets -> SettingsWidgetsScreen()
        Screen.SettingsBackup -> SettingsBackupScreen()
        Screen.Equalizer -> EqualizerScreen()
        Screen.SettingsAdvanced -> SettingsAdvancedScreen()
        Screen.SettingsDiagnostics -> app.malachite.player.ui.screens.DiagnosticsScreen()
        Screen.SettingsSoundId -> app.malachite.player.ui.settings.SoundIdSettingsScreen()
        Screen.About -> app.malachite.player.ui.screens.AboutScreen()
        Screen.Terms -> app.malachite.player.ui.screens.TermsScreen()
        Screen.Support -> app.malachite.player.ui.screens.SupportScreen()
        is Screen.Online -> app.malachite.player.ui.screens.OnlineScreen(screen.query)
        Screen.ImportScreen -> ImportScreen()
    }
}

private fun titleOf(s: Screen): String = when (s) {
    is Screen.TabRoot -> s.tab.title
    is Screen.ArtistDetail -> tr("Исполнитель")
    is Screen.AlbumDetail -> tr("Альбом")
    Screen.NoAlbum -> tr("Без альбома")
    is Screen.PlaylistDetail -> s.name
    is Screen.SmartPlaylist -> s.kind.title
    is Screen.Folder -> if (s.relative.isEmpty()) tr("Папки") else s.relative.substringAfterLast('/')
    is Screen.LyricsSync -> tr("Синхронизация текста")
    Screen.Processes -> tr("Процессы")
    Screen.Help -> tr("Вопросы и ответы")
    is Screen.LyricsEdit -> tr("Правка текста")
    Screen.Search -> tr("Поиск")
    Screen.Settings -> tr("Настройки")
    Screen.SettingsAppearance -> tr("Оформление")
    Screen.SettingsMenu -> tr("Меню и панели")
    Screen.SettingsLibrary -> tr("Библиотека и главная папка")
    Screen.SettingsLyrics -> tr("Тексты песен")
    Screen.SettingsPlayback -> tr("Воспроизведение")
    Screen.SettingsWidgets -> tr("Виджеты")
    Screen.SettingsBackup -> tr("Экспорт, резервные копии")
    Screen.Equalizer -> tr("Эквалайзер и микшер")
    Screen.SettingsAdvanced -> tr("Root и Shizuku")
    Screen.SettingsDiagnostics -> tr("Ошибки и журнал")
    Screen.SettingsSoundId -> tr("Узнавание по звуку")
    Screen.About -> tr("О приложении")
    Screen.Terms -> tr("Соглашение")
    Screen.Support -> tr("Поддержать автора")
    is Screen.Online -> tr("Онлайн")
    Screen.ImportScreen -> tr("Новые песни на устройстве")
}

@Composable
private fun TopBar(style: PartStyle, cover: Bitmap?) {
    val nav = LocalNavigator.current
    val scale = style.scale.coerceIn(0.7f, 1.6f)
    PartBackground(style, MaterialTheme.colorScheme.background, cover, Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(56.dp * scale).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (nav.stack.size > 1) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Назад")) }
            } else Spacer(Modifier.width(12.dp))
            Text(
                titleOf(nav.current),
                Modifier.weight(1f),
                style = if (scale > 1.15f) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // help is always one tap away
            IconButton(onClick = { nav.push(Screen.Help) }, modifier = Modifier.tourTarget("help")) { Icon(Icons.Filled.QuestionMark, tr("Вопросы и ответы")) }
            IconButton(onClick = { nav.push(Screen.Search) }, modifier = Modifier.tourTarget("search")) { Icon(Icons.Filled.Search, tr("Поиск")) }
            IconButton(onClick = { nav.push(Screen.Settings) }, modifier = Modifier.tourTarget("settings")) { Icon(Icons.Filled.Settings, tr("Настройки")) }
        }
    }
}


@Composable
private fun MiniPlayer(style: PartStyle, cover: Bitmap?) {
    val graph = MalachiteApp.graph
    val nav = LocalNavigator.current
    val st by graph.player.state.collectAsState()
    val t = st.currentPath?.let { graph.library.track(it) } ?: return
    val scale = style.scale.coerceIn(0.7f, 1.6f)
    val pos = remember { mutableLongStateOf(0L) }
    LaunchedEffect(st.isPlaying, st.currentPath) {
        while (true) {
            pos.longValue = graph.player.positionMs()
            delay(500)
        }
    }
    PartBackground(style, MaterialTheme.colorScheme.surfaceContainerHigh, cover, Modifier.fillMaxWidth().clickable { nav.playerOpen = true }) {
        Column {
            LinearProgressIndicator(
                progress = { if (st.durationMs > 0) (pos.longValue.toFloat() / st.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
            )
            Row(Modifier.fillMaxWidth().height(62.dp * scale).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                CoverImage(t, size = 46.dp * scale, corner = 10.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(t.displayArtist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { graph.player.toggle() }) {
                    Icon(if (st.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (st.isPlaying) tr("Пауза") else tr("Играть"), Modifier.size(30.dp * scale))
                }
                IconButton(onClick = { graph.player.next() }) { Icon(Icons.Filled.SkipNext, tr("Дальше")) }
            }
        }
    }
}
