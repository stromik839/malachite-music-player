package app.malachite.core

import app.malachite.core.text.NameMatch
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NameMatchTest {
    @Test
    fun eitherAlphabetFindsTheName() {
        assertTrue(NameMatch.matches("kino", "Кино"))
        assertTrue(NameMatch.matches("кино", "Kino"))
        assertTrue(NameMatch.matches("молчат", "Molchat Doma"))
        assertTrue(NameMatch.matches("molchat doma", "Молчат Дома (Molchat Doma)"))
        assertTrue(NameMatch.matches("Свидетельство", "Свидетельство О Смерти"))
        assertTrue(NameMatch.matches("svidetelstvo o smerti", "Свидетельство О Смерти"))
        assertTrue(NameMatch.matches("молчатдома", "Молчат Дома"))
        assertTrue(NameMatch.matches("tv girl", "TV Girl"))
    }

    @Test
    fun otherNamesDoNot() {
        assertFalse(NameMatch.matches("кино", "Земфира"))
        assertFalse(NameMatch.matches("molchat", "Кино"))
        assertFalse(NameMatch.matches("", "Кино"))
        assertFalse(NameMatch.matches("кино", ""))
    }
}
