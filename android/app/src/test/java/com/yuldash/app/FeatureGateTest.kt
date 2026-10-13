package com.yuldash.app

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Запуск волнами (13.10.2026): закрытое направление показывает «Скоро в Юлдаше» на обоих
 * языках и выход «Найти попутку», открытое — само себя. Включили, пока человек смотрит
 * на заглушку, — содержимое приходит без перезапуска.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeatureGateTest {

    @get:Rule
    val compose = createComposeRule()

    private val closed = FeatureSet(emptySet(), known = true, fetchedAtMs = 0L)
    private val adsOnly = FeatureSet(setOf(Feature.Ads), known = true, fetchedAtMs = 0L)

    @Before fun setUp() { ApiClient.resetForTest() }
    @After fun tearDown() { ApiClient.resetForTest() }

    private fun gate(language: AppLanguage, onFind: () -> Unit = {}, onSecondary: (() -> Unit)? = null) {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                YuldashTheme {
                    FeatureGate(
                        open = { it.isOn(Feature.Ads) },
                        kind = if (onSecondary != null) ComingSoonKind.Delivery else ComingSoonKind.Ads,
                        onBack = {},
                        onFindRide = onFind,
                        embedded = true,
                        secondaryLabel = onSecondary?.let { becomeCourierEarlyLabel() },
                        onSecondary = onSecondary,
                    ) { Text("СОДЕРЖИМОЕ") }
                }
            }
        }
    }

    @Test
    fun `выключено — по-русски «скоро» и выход к попуткам`() {
        FeatureFlags.setForTest(closed)
        var found = 0
        gate(AppLanguage.Ru, onFind = { found++ })
        compose.onNodeWithText("Скоро в Юлдаше").assertIsDisplayed()
        compose.onNodeWithText("Реклама в Юлдаше скоро откроется. Готовим честную маркировку, как требует закон.")
            .assertIsDisplayed()
        compose.onNodeWithText("СОДЕРЖИМОЕ").assertDoesNotExist()
        compose.onNodeWithText("Найти попутку").performScrollTo().performClick()
        assertEquals(1, found)
    }

    @Test
    fun `выключено — по-башкирски`() {
        FeatureFlags.setForTest(closed)
        gate(AppLanguage.Ba)
        compose.onNodeWithText("Тиҙҙән Юлдашта").assertIsDisplayed()
        compose.onNodeWithText("Юлдаш табыу").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Скоро в Юлдаше").assertDoesNotExist()
    }

    @Test
    fun `доставка зовёт стать курьером заранее`() {
        FeatureFlags.setForTest(closed)
        var opened = 0
        gate(AppLanguage.Ru, onSecondary = { opened++ })
        compose.onNodeWithText("Доставка").assertIsDisplayed()
        compose.onNodeWithText("Стать курьером заранее").performScrollTo().performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `доставка по-башкирски`() {
        FeatureFlags.setForTest(closed)
        gate(AppLanguage.Ba, onSecondary = {})
        compose.onNodeWithText("Илтеү").assertIsDisplayed()
        compose.onNodeWithText("Алдан курьер булыу").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `включено — направление открывается как обычно`() {
        FeatureFlags.setForTest(adsOnly)
        gate(AppLanguage.Ru)
        compose.onNodeWithText("СОДЕРЖИМОЕ").assertIsDisplayed()
        compose.onNodeWithText("Скоро в Юлдаше").assertDoesNotExist()
    }

    @Test
    fun `включили, пока смотрят на заглушку, — содержимое приходит само`() {
        FeatureFlags.setForTest(closed)
        gate(AppLanguage.Ru)
        compose.onNodeWithText("Скоро в Юлдаше").assertIsDisplayed()
        compose.runOnIdle { FeatureFlags.setForTest(adsOnly) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTextCount("СОДЕРЖИМОЕ") > 0
        }
        compose.waitForIdle()
        compose.onNodeWithText("СОДЕРЖИМОЕ").assertIsDisplayed()
    }

    @Test
    fun `сервер ещё ни разу не ответил и не отвечает — «скоро», а не чужой экран`() {
        FeatureFlags.setForTest(FeatureSet.UNKNOWN)
        gate(AppLanguage.Ru)
        compose.onNodeWithText("Скоро в Юлдаше").assertIsDisplayed()
        compose.onNodeWithText("СОДЕРЖИМОЕ").assertDoesNotExist()
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
