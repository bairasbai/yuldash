package com.yuldash.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yandex.mapkit.geometry.Point
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Все места, где создаётся живая карта, не роняют приложение без ключа Яндекс MapKit.
 * Сборка в чистом worktree и в CI идёт без `local.properties` (ключа там и не должно быть), а
 * `MapKitFactory.initialize` тогда бросает «You need to set the API key before using MapKit!».
 * На эмуляторе 14.10.2026 так закрывалось приложение на «Отметить на карте» и на карте такси.
 *
 * Экран брони закрывает `BookingRouteMapNoKeyTest`; здесь — остальные. Тесты имеют смысл только в
 * сборке без ключа; на машине с ключом в `local.properties` рисуется настоящая карта, которую JVM
 * не умеет, — тесты пропускаются.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapKitNoKeyScreensTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun onlyWithoutKey() {
        assumeTrue("в сборке есть ключ MapKit — живая карта", BuildConfig.YANDEX_MAPKIT_KEY.isBlank())
    }

    @Test
    fun pickupPicker_withoutMapKey_showsPlaceholderAndBackWorks() {
        var dismissed = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                PickupPickerOverlay(initial = null, onConfirm = { _, _ -> }, onDismiss = { dismissed++ })
            }
        }
        composeRule.onNodeWithText("Карта сейчас недоступна").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Назад").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun pickupPicker_withoutMapKey_isBilingual() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                PickupPickerOverlay(initial = null, onConfirm = { _, _ -> }, onDismiss = {})
            }
        }
        composeRule.onNodeWithText("Карта хәҙер юҡ").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Артҡа").assertIsDisplayed()
    }

    @Test
    fun taxiRouteMap_withoutMapKey_showsPlaceholderInsteadOfCrashing() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                InstantRouteMap(
                    from = Point(53.58, 58.31),
                    to = Point(54.72, 55.94),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        composeRule.onNodeWithText("Карта сейчас недоступна").assertIsDisplayed()
    }
}
