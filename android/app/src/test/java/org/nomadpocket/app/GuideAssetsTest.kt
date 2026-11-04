package org.nomadpocket.app

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.nomadpocket.app.guides.GuideLibrary
import org.nomadpocket.app.guides.GuidePage
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Gradle copy task put the shared guides into the APK's assets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GuideAssetsTest {
    @Test fun guidesAreInTheAssets() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val library = GuideLibrary.get(context)
        assertEquals(69, library.allGuides.size)
        assertEquals(8, library.checklists.size)
        val cpr = library.guideById("cpr")!!
        val html = GuideLibrary.html(context, cpr)
        assertTrue(html.contains("<h2>"))
        val page = GuidePage.page(cpr.title, html, dark = true)
        assertTrue(page.contains("--bg:#000000"))
        assertTrue(page.contains(".callout.danger"))
        assertTrue(page.contains("<h1>CPR</h1>"))
    }
}
