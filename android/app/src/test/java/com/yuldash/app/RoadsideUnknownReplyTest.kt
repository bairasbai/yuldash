package com.yuldash.app

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoadsideUnknownReplyTest : ShareHelpBoundaryHarness() {
    override fun customResponse(request: RecordedRequest, path: String): MockResponse? =
        if (path.endsWith("/stuck")) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else null
    private fun verify(language: AppLanguage) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                YuldashTheme {
                    RoadsideHelpAction(key = 91) { lat, lng, owner -> ApiClient.roadsideHelp(91, lat, lng, "", owner) }
                }
            }
        }
        pump()
        val ba = language == AppLanguage.Ba
        click(if (ba) "Юлда ҡалдым" else "Застрял на трассе")
        click(if (ba) "Ярҙам саҡырыу" else "Позвать помощь")
        await { ShadowToast.getTextOfLatestToast() != null }
        assertEquals(if (ba)
            "Ебәрелеүҙе раҫлап булманы. Бәйләнеште тикшер, сигналды ҡабатла йәки, мөмкин булһа, 112-гә һәм яҡындарыңа шылтырат."
        else "Не удалось подтвердить отправку. Проверь связь, повтори сигнал или позвони 112 и близким, если можешь.",
            ShadowToast.getTextOfLatestToast())
        assertEquals(1, count("POST", "/stuck"))
    }
    @Test fun russianLostReplyIsUnknown() = verify(AppLanguage.Ru)
    @Test fun bashkirLostReplyIsUnknown() = verify(AppLanguage.Ba)
}
