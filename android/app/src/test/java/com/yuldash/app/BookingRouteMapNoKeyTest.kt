package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.yandex.mapkit.geometry.Point
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Экран брони без ключа Яндекс MapKit не падает. Сборка в чистом worktree и в CI собирается без
 * `local.properties` (ключ карты там и не должен лежать), и `MapKitFactory.initialize` на экране
 * брони бросал `AssertionError: You need to set the API key before using MapKit!` — приложение
 * закрывалось ровно на «Забронировать» (поймано на эмуляторе 2026-10-14). Теперь без ключа вместо
 * живой карты — заглушка маршрута.
 *
 * Тест имеет смысл только в сборке без ключа (CI, чистый worktree); на машине с ключом в
 * `local.properties` берётся настоящая карта, которую JVM не рисует, — тест пропускается.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingRouteMapNoKeyTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun routePreview_withoutMapKey_showsPlaceholderInsteadOfCrashing() {
        assumeTrue("в сборке есть ключ MapKit — живая карта", BuildConfig.YANDEX_MAPKIT_KEY.isBlank())
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BookingRouteMapPreview(
                    modifier = Modifier,
                    from = "Баймак",
                    to = "Уфа",
                    fromPoint = Point(51.7, 58.3),
                    toPoint = Point(54.7, 55.9),
                    contactUnlocked = false,
                )
            }
        }
        composeRule.onNodeWithText("Карта маршрута загружается").assertIsDisplayed()
    }
}
