package app.malachite.player.data

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager

/**
 * The phone's accessibility services, as far as the app's speed is concerned. While any service is
 * switched on – a screen reader, but just as well a key mapper, a password manager, a "floating
 * ball" or an automation app – Compose keeps a copy of the whole screen's structure and builds it
 * anew after every change of the screen: on a mid-range phone 50–700 ms of the main thread each
 * time (the detailed log showed it every half second, even with the app in the background). The
 * app therefore avoids changes nobody needs: a value that changes every second (the time of the
 * song, the progress line) is told only to a screen reader, the one service that reads it out.
 */
object A11y {
    /** A screen reader (touch exploration, as with TalkBack) is on. Read it in composition. */
    val screenReader = androidx.compose.runtime.mutableStateOf(false)

    @Volatile private var started = false

    /** Follows the services from now on (main thread). */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val am = app.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return
        screenReader.value = am.isEnabled && am.isTouchExplorationEnabled
        am.addTouchExplorationStateChangeListener { on ->
            screenReader.value = on
            changed(app)
        }
        am.addAccessibilityStateChangeListener { changed(app) }
    }

    private fun changed(context: Context) {
        if (FrameWatch.isOn) AppLog.i("a11y", "службы специальных возможностей изменились: " + describe(context)) // i18n:off
    }

    /** For the detailed log: which services are on and what they do. */
    fun describe(context: Context): String = runCatching {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (!am.isEnabled) return@runCatching "выключены (интерфейс ничего лишнего не делает)" // i18n:off
        val list = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val services = list.joinToString("; ") { s ->
            val id = s.id ?: s.resolveInfo?.serviceInfo?.let { "${it.packageName}/${it.name}" } ?: "?"
            val reads = s.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0
            id + (if (reads) " (читает содержимое экрана)" else "") + ", отклик " + // i18n:off
                AccessibilityServiceInfo.feedbackTypeToString(s.feedbackType)
        }
        val reader = if (am.isTouchExplorationEnabled) "; исследование касанием включено" else "; исследование касанием выключено" // i18n:off
        if (list.isEmpty()) "включены без служб (проверка или автоматизация)$reader" // i18n:off
        else "включено ${list.size}: $services$reader — из-за них каждое изменение экрана стоит дороже" // i18n:off
    }.getOrDefault("?")
}
