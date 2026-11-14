package app.malachite.player.data

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import app.malachite.core.i18n.Tr
import app.malachite.player.MainActivity

/**
 * The language of the interface: Russian (the language the texts are written in) or English.
 *
 * The choice is kept apart from the other settings on purpose: it must be known before
 * anything else is created, because many texts are produced once, when their classes load.
 * For the same reason a change of language restarts the app.
 */
object Language {
    enum class Choice(val title: String) {
        // each name in its own language: it must be readable whatever the current language is
        AUTO("Авто / Auto"),
        RUSSIAN("Русский"),
        ENGLISH("English"),
    }

    private const val PREFS = "malachite_language"
    private const val KEY = "language"

    /**
     * "Auto": English, the language most people can read – unless the phone itself is set to
     * Russian, then Russian. Anyone else who prefers Russian switches it in the settings.
     */
    private val RUSSIAN_SYSTEM = setOf("ru")

    fun choice(context: Context): Choice {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        return Choice.entries.firstOrNull { it.name == saved } ?: Choice.AUTO
    }

    private fun english(context: Context): Boolean = when (choice(context)) {
        Choice.RUSSIAN -> false
        Choice.ENGLISH -> true
        Choice.AUTO -> {
            val system = runCatching { Resources.getSystem().configuration.locales[0].language }.getOrDefault("ru")
            system !in RUSSIAN_SYSTEM
        }
    }

    /** True when the interface is English now. */
    val isEnglish: Boolean get() = Tr.english

    /** Call first thing when the app starts. */
    fun apply(context: Context) = Tr.use(english(context))

    /** Saves the choice and restarts the app so that every text is produced anew. */
    fun change(context: Context, choice: Choice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, choice.name).commit()
        val app = context.applicationContext
        app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Runtime.getRuntime().exit(0)
    }
}
