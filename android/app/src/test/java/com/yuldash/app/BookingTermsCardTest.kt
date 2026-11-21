package com.yuldash.app

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w320dp-h700dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingTermsCardTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var server: MockWebServer

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>())
        server = MockWebServer().apply { start() }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }

    @After fun teardown() {
        ApiClient.resetForTest()
        server.shutdown()
    }

    @Test fun russianLightShowsServerTermsAndZeroPrice() {
        render(AppLanguage.Ru, dark = false, policy())
        compose.onNodeWithText("Цена по договорённости с водителем").performScrollTo().assertIsDisplayed()
        open("Подробнее об отмене")
        awaitText("за 37 мин до выезда")
        awaitText("через 8 мин")
        display("водитель сначала отмечает «Подъезжаю»")
        compose.onNodeWithText("Свернуть").performScrollTo().performClick()
        compose.onNodeWithText("за 37 мин до выезда", substring = true).assertDoesNotExist()
    }

    @Test fun russianDarkExplainsTermsDuringServerFailure() {
        render(AppLanguage.Ru, dark = true, MockResponse().setResponseCode(503).setBody("{}"))
        open("Подробнее об отмене")
        display("незадолго до выезда")
        display("выезда, подъезда и выбранной остановки")
        compose.onNodeWithText("Обновить сроки").performScrollTo().assertIsDisplayed()
    }

    @Test fun bashkirLightExplainsTermsOnAnOlderServer() {
        render(AppLanguage.Ba, dark = false, MockResponse().setBody("{}"))
        open("Кире алыу тураһында тулыраҡ")
        display("Сығыу алдынан")
        display("һайланған туҡталышты иҫәпкә алып")
    }

    @Test fun bashkirDarkShowsServerTerms() {
        render(AppLanguage.Ba, dark = true, policy())
        open("Кире алыу тураһында тулыраҡ")
        awaitText("Сығыуға 37 минут")
        awaitText("8 минут үткәндән һуң")
        display("йөрөтөүсе башта «Яҡынлашам»")
    }

    private fun policy() = MockResponse().setBody("""{"late_cancel_before_min":37,"no_show_wait_min":8}""")

    @Test fun configuredDriverMethodsDoNotSuggestUnacceptedMethods() {
        render(AppLanguage.Ru, dark = false, policy(), hasDriverPayMethods = true)
        display("Оплата напрямую водителю.")
        compose.onNodeWithText("Договорись с водителем", substring = true).assertDoesNotExist()
    }

    @Test fun guestCanDismissScamTipExplicitly() {
        val active = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashTheme(darkTheme = false) {
                    val holder = rememberSaveableStateHolder()
                    if (active.value) holder.SaveableStateProvider("guest-booking") { BookingScamTip(guest = true) }
                }
            }
        }
        compose.onNodeWithText("Береги себя и деньги").assertIsDisplayed()
        compose.onNodeWithText("Понятно").performClick()
        compose.onNodeWithText("Береги себя и деньги").assertDoesNotExist()
        compose.runOnIdle { active.value = false }
        compose.waitForIdle()
        compose.runOnIdle { active.value = true }
        compose.onNodeWithText("Береги себя и деньги").assertDoesNotExist()
    }

    private fun render(language: AppLanguage, dark: Boolean, response: MockResponse, hasDriverPayMethods: Boolean = false) {
        server.enqueue(response)
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language, LocalNightRide provides dark) {
                YuldashTheme(darkTheme = dark) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        BookingSeatCount(available = 1, selected = 1, price = 0, enabled = true, onChange = {})
                        BookingTermsCard(hasDriverPayMethods)
                    }
                }
            }
        }
        val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/safety/policy", request.path)
    }

    private fun open(label: String) {
        compose.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
    }

    private fun display(text: String) {
        compose.onNodeWithText(text, substring = true).performScrollTo().assertIsDisplayed()
    }

    private fun awaitText(text: String) {
        compose.waitUntil(8_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size == 1
        }
        display(text)
    }
}
