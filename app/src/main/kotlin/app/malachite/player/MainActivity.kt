package app.malachite.player

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import app.malachite.player.data.Storage
import app.malachite.player.ui.LocalNavigator
import app.malachite.player.ui.MainScaffold
import app.malachite.player.ui.Navigator
import app.malachite.player.ui.screens.FolderProblemScreen
import app.malachite.player.ui.screens.OnboardingScreen
import app.malachite.player.ui.theme.MalachiteTheme
import app.malachite.player.ui.theme.shade

class MainActivity : ComponentActivity() {

    private var navigator: Navigator? = null

    /**
     * The fastest mode of the screen at its present resolution. Many phones keep an app at 60 Hz
     * unless it asks, even with 90 or 120 Hz chosen in the system settings: every animation then
     * looked slower than the rest of the phone.
     */
    private fun preferRefreshRate(fastest: Boolean) {
        runCatching {
            @Suppress("DEPRECATION")
            val screen: android.view.Display = (if (android.os.Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay) ?: return
            val now = screen.mode
            val best = screen.supportedModes
                .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight }
                .maxByOrNull { it.refreshRate }
            val id = if (fastest && best != null && best.refreshRate > 61f) best.modeId else 0
            val attrs = window.attributes
            if (attrs.preferredDisplayModeId != id) {
                attrs.preferredDisplayModeId = id
                window.attributes = attrs
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = MalachiteApp.graph
        graph.player.connect()
        val openPlayer = intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true
        setContent {
            val settings by graph.settings.state.collectAsState()
            val root by graph.library.openRoot.collectAsState()
            val lockedRoot by graph.lockedRootState.collectAsState()
            val nav = remember { Navigator(settings.tabs.firstOrNull() ?: app.malachite.player.data.Tab.SONGS).also { navigator = it } }
            LaunchedEffect(Unit) { if (openPlayer) nav.playerOpen = true }
            // "colours from the cover everywhere": the whole app takes the colours of the song playing
            val playing by graph.player.state.collectAsState()
            val playingTrack = playing.currentPath?.let { graph.library.track(it) }
            val coverColours = app.malachite.player.ui.theme.rememberCoverScheme(
                playingTrack, settings.shade(androidx.compose.foundation.isSystemInDarkTheme()), settings.coverColorsEverywhere,
            )
            MalachiteTheme(settings, coverColours) {
                // the window behind the app (seen during transitions, behind the keyboard) follows the theme too
                val windowBg = MaterialTheme.colorScheme.background.toArgb()
                // dark icons of the status / navigation bar on a light theme and the other way round
                // (they used to follow the system mode even when the app theme was different)
                val lightTheme = androidx.core.graphics.ColorUtils.calculateLuminance(windowBg) > 0.5
                LaunchedEffect(settings.highRefreshRate) { preferRefreshRate(settings.highRefreshRate) }
                SideEffect {
                    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(windowBg))
                    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightTheme
                        isAppearanceLightNavigationBars = lightTheme
                    }
                }
                CompositionLocalProvider(LocalNavigator provides nav) {
                    // Surface sets the default text/icon colour from the theme (otherwise it is black).
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    Box(Modifier.fillMaxSize()) {
                        when {
                            // before anything else: what this app is, who made it, and the agreement
                            settings.termsAccepted < app.malachite.player.ui.screens.Terms.VERSION -> app.malachite.player.ui.screens.FirstRunFlow()
                            !settings.onboardingDone -> OnboardingScreen()
                            // a main folder protected with a password waits for it
                            root == null && lockedRoot != null ->
                                app.malachite.player.ui.screens.UnlockScreen()
                            root == null -> FolderProblemScreen()
                            else -> {
                                BackHandler(enabled = nav.playerOpen || nav.stack.size > 1 || nav.dialog != null) { nav.back() }
                                MainScaffold()
                                // the previous run crashed and the user allowed keeping the report
                                app.malachite.player.ui.screens.CrashReportDialog()
                                // a short tour on the first start (and whenever it is asked for again)
                                if (!settings.tourShown) app.malachite.player.ui.help.TourOverlay { graph.settings.update { it.copy(tourShown = true) } }
                                // once after an update that brought something new
                                else if (settings.whatsNewSeen < app.malachite.player.ui.help.WhatsNew.ID) app.malachite.player.ui.help.WhatsNewDialog {
                                    graph.settings.update { it.copy(whatsNewSeen = app.malachite.player.ui.help.WhatsNew.ID) }
                                }
                            }
                        }
                    }
                    }
                }
            }
        }
    }

    /**
     * A hardware keyboard (Chromebook, tablet with a keyboard, desktop mode): space plays / pauses,
     * Ctrl+→ / Ctrl+← switch songs, Ctrl+F opens the search. Keys typed into a text field never
     * get here – the field takes them first.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val g = MalachiteApp.graph
        val ready = g.settings.value.onboardingDone && g.settings.value.termsAccepted >= app.malachite.player.ui.screens.Terms.VERSION
        if (ready && event.repeatCount == 0) {
            when {
                keyCode == android.view.KeyEvent.KEYCODE_SPACE -> { g.player.toggle(); return true }
                event.isCtrlPressed && keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { g.player.next(); return true }
                event.isCtrlPressed && keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { g.player.previous(); return true }
                event.isCtrlPressed && keyCode == android.view.KeyEvent.KEYCODE_F -> { navigator?.push(app.malachite.player.ui.Screen.Search); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        app.malachite.player.data.FrameWatch.detach(window)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        app.malachite.player.data.FrameWatch.attach(window)
        val graph = MalachiteApp.graph
        // Permission may have been granted in system settings meanwhile.
        if (!graph.library.isOpen && Storage.hasAllFilesAccess(this)) graph.openLibraryIfPossible()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) navigator?.playerOpen = true
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}

@Composable
fun rememberGraph() = remember { MalachiteApp.graph }
