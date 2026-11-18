// Language.kt
// Pocket Nomad
// SPDX-License-Identifier: Apache-2.0
//
// An in-app language choice (English, Spanish, French) that overrides the phone's language. The interface
// and menus are translated; the guides and books stay in English until a qualified person can translate
// first-aid and survival text. Strings use their English text as the key (see i18n/strings.json).

package org.nomadpocket.app.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class AppLanguage(val code: String?, val nativeName: String) {
    SYSTEM(null, "System"), EN("en", "English"), ES("es", "Español"), FR("fr", "Français");

    companion object {
        fun from(code: String?) = entries.firstOrNull { it.code == code } ?: SYSTEM
    }
}

object Language {
    /** The current choice. Compose reads this, so changing it redraws every translated string at once. */
    var current by mutableStateOf(AppLanguage.SYSTEM)
        private set

    fun load(context: Context) {
        current = AppLanguage.from(context.getSharedPreferences("language", Context.MODE_PRIVATE).getString("code", null))
    }

    fun set(context: Context, language: AppLanguage) {
        current = language
        context.getSharedPreferences("language", Context.MODE_PRIVATE).edit().apply {
            if (language.code == null) remove("code") else putString("code", language.code)
        }.apply()
    }

    /** The language code in effect: the choice, or the phone's language when following the system. */
    fun effectiveCode(): String = current.code ?: java.util.Locale.getDefault().language

    fun translate(text: String): String = when (effectiveCode()) {
        "es" -> GeneratedTranslations.es[text]
        "fr" -> GeneratedTranslations.fr[text]
        else -> null
    } ?: text
}

/** Translates an English UI string. Reading it in a composable makes the screen follow the language choice. */
@Composable
fun tr(text: String): String {
    @Suppress("UNUSED_VARIABLE") val dependsOnLanguage = Language.current
    return Language.translate(text)
}
