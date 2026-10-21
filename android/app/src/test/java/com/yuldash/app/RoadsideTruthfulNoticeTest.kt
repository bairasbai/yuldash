package com.yuldash.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onAllNodesWithText
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoadsideTruthfulNoticeTest : ShareHelpBoundaryHarness() {
    private var notified = 0
    private var contacts = 0
    override fun customResponse(request: RecordedRequest, path: String): MockResponse? =
        if (path.endsWith("/stuck")) MockResponse().setBody("""{"contacts_notified":$notified,"contacts_total":$contacts}""") else null
    private fun verify(language: AppLanguage, scheduled: Int, total: Int) {
        notified = scheduled; contacts = total
        LocationPrefs.lastLat = null; LocationPrefs.lastLng = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                YuldashTheme { RoadsideHelpAction(key = 91) { lat, lng, owner -> ApiClient.roadsideHelp(91, lat, lng, "", owner) } }
            }
        }
        pump()
        val ba = language == AppLanguage.Ba
        click(if (ba) "Юлда ҡалдым" else "Застрял на трассе")
        val before = if (ba) "Юлдашҡа сигнал ебәрәбеҙ. Урын билдәле булһа, уны ла өҫтәйбеҙ. Хәүеф янаһа — 112-гә шылтырат."
            else "Отправим сигнал в Юлдаш. Если место доступно, приложим его. Если угрожает опасность — звони 112."
        await { compose.onAllNodesWithText(before).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("Missing location cannot be promised before sending", compose.onAllNodesWithText(before).fetchSemanticsNodes().isNotEmpty())
        click(if (ba) "Ярҙам саҡырыу" else "Позвать помощь")
        val saved = if (ba) "Сигнал Юлдашта һаҡланды." else "Сигнал сохранён в Юлдаш."
        await { compose.onAllNodesWithText(saved, substring = true).fetchSemanticsNodes().isNotEmpty() }
        for (claim in listOf("координат", "получил", "координаталарыңды алды")) {
            assertTrue("A saved signal is not proof of delivered coordinates", compose.onAllNodesWithText(claim, substring = true, ignoreCase = true).fetchSemanticsNodes().isEmpty())
        }
        val body = JSONObject(records.single { it[0] == "POST" && it[1]!!.endsWith("/stuck") }[3]!!)
        assertFalse(body.has("lat")); assertFalse(body.has("lng"))
        assertEquals(1, count("POST", "/stuck"))
    }
    @Test fun russianNoContacts() = verify(AppLanguage.Ru, 0, 0)
    @Test fun bashkirNoContacts() = verify(AppLanguage.Ba, 0, 0)
    @Test fun russianUnavailableChannel() = verify(AppLanguage.Ru, 0, 2)
    @Test fun bashkirUnavailableChannel() = verify(AppLanguage.Ba, 0, 2)
    @Test fun russianScheduledIsNotDelivered() = verify(AppLanguage.Ru, 2, 2)
    @Test fun bashkirScheduledIsNotDelivered() = verify(AppLanguage.Ba, 2, 2)
}
