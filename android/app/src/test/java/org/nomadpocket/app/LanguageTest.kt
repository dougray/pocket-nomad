package org.nomadpocket.app

import org.junit.Assert.*
import org.junit.Test
import org.nomadpocket.app.i18n.AppLanguage
import org.nomadpocket.app.i18n.GeneratedTranslations
import org.nomadpocket.app.i18n.Language

class LanguageTest {
    @Test fun parsesCodes() {
        assertEquals(AppLanguage.ES, AppLanguage.from("es")); assertEquals(AppLanguage.SYSTEM, AppLanguage.from(null)); assertEquals(AppLanguage.SYSTEM, AppLanguage.from("xx"))
    }

    @Test fun everyEnglishKeyHasBothTranslations() {
        assertEquals(GeneratedTranslations.es.keys, GeneratedTranslations.fr.keys)
        assertTrue(GeneratedTranslations.es.size > 100)
        assertTrue(GeneratedTranslations.es.all { (k, v) -> v.isNotBlank() && k.isNotBlank() })
    }

    @Test fun translatedAndFallback() {
        assertEquals("Ajustes", GeneratedTranslations.es["Settings"])
        assertEquals("Réglages", GeneratedTranslations.fr["Settings"])
        assertEquals("Not translated yet", Language.translate("Not translated yet"))
    }
}
