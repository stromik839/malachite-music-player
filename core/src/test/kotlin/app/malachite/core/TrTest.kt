package app.malachite.core

import app.malachite.core.i18n.Tr
import app.malachite.core.i18n.tr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrTest {
    private fun english(block: () -> Unit) {
        Tr.use(true)
        try { block() } finally { Tr.use(false) }
    }

    @Test
    fun russianIsLeftAsItIs() {
        assertEquals("Песни", tr("Песни"))
        assertEquals("Найдено: 5", tr("Найдено: 5"))
    }

    @Test
    fun theBuiltInDictionaryIsFound() = english {
        assertEquals("Songs", tr("Песни"))
        assertEquals("Settings", tr("Настройки"))
        // a text with a line break in it
        assertTrue(tr("Отпустите,\nкогда закончится").startsWith("Release\n"))
    }

    @Test
    fun valuesAreCarriedIntoTheTranslation() = english {
        assertEquals("Downloaded to the main folder: Кукла колдуна", tr("Скачано в главную папку: Кукла колдуна"))
        assertEquals("Reading tags: 12 of 428", tr("Чтение тегов 12 из 428"))
        assertEquals("a.mp3 → trash (video)", tr("a.mp3 → корзина (video)"))
        // the more specific template wins over the general one
        assertEquals("x.mp3: lyrics not found", tr("x.mp3: текст не найден"))
        assertEquals("names: fixed 2, skipped 10, errors 2", tr("names: исправлено 2, пропущено 10, ошибок 2"))
    }

    @Test
    fun unknownTextIsShownUnchanged() = english {
        assertEquals("Группа крови", tr("Группа крови"))
        assertEquals("", tr(""))
    }

    @Test
    fun templatesWithNumbersAndOwnDictionary() {
        Tr.useDictionary("{} из {}\n{2} total, {1} done\n\nДа\nYes\n")
        try {
            assertEquals("10 total, 3 done", tr("3 из 10"))
            assertEquals("Yes", tr("Да"))
        } finally {
            Tr.use(false)
        }
    }
}
