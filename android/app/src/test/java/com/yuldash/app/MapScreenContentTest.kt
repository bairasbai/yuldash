package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.RequestNearDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Вкладка «Карта» — это MapKit (нативная карта, гео, камера, ценники-плейсмарки), её тестировать на JVM нельзя.
 * Но НЕ-карточные части экрана чистые: карта-заглушка [MapPreview] (Compose Canvas, до загрузки MapKit),
 * ярлык города [MapLabel], кнопки зума [MapZoomControls] (сам зум делает вызывающий через колбэки),
 * карточка «Крупные кнопки» [SeniorAccessCard] и базовый рендер карточки заявки [RequestPreviewCard]
 * (сеть ApiClient — только в диалоге-отклике, тесты его не открывают). Их и покрываем через Robolectric.
 *
 * Заголовок класса — как в SecondaryScreensContentTest / AdminReviewsContentTest. Анимаций нет → autoAdvance не нужен.
 * appText читает LocalAppLanguage → двуязычие проверяем через CompositionLocalProvider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MapScreenContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun request(
        id: Int = 1,
        name: String = "Айгуль",
        fromCity: String = "Баймаҡ",
        toCity: String = "Сибай",
        seats: Int = 2,
        comment: String = "Еду к родным, возьму попутчика",
        distanceKm: Double? = 3.0,
    ) = RequestNearDto(
        id = id,
        passengerName = name,
        fromCity = fromCity,
        toCity = toCity,
        fromLat = null,
        fromLng = null,
        seats = seats,
        comment = comment,
        distanceKm = distanceKm,
    )

    // --- MapPreview: карта-заглушка (Canvas-маршрут + ярлыки + чип расстояния + плашка приватности) ---

    @Test
    fun mapPreview_showsCitiesDistanceAndPrivacyNotice_russian() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MapPreview(from = "Уфа", to = "Сибай", distance = "43 км")
            }
        }
        composeRule.onNodeWithText("Уфа").assertIsDisplayed()
        composeRule.onNodeWithText("Сибай").assertIsDisplayed()
        composeRule.onNodeWithText("43 км").assertIsDisplayed()
        composeRule.onNodeWithText("Геолокация откроется после подтверждения поездки").assertIsDisplayed()
    }

    @Test
    fun mapPreview_privacyNotice_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                MapPreview(from = "Баймаҡ", to = "Сибай", distance = "43 км")
            }
        }
        composeRule.onNodeWithText("Геолокация сәфәр раҫланғас асыла").assertIsDisplayed()
    }

    @Test
    fun mapPreview_nullDistance_hidesChipButKeepsCities() {
        // distance=null → чип расстояния не рисуется, но названия городов остаются.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MapPreview(from = "Учалы", to = "Белорецк", distance = null)
            }
        }
        composeRule.onNodeWithText("Учалы").assertIsDisplayed()
        composeRule.onNodeWithText("Белорецк").assertIsDisplayed()
        composeRule.onNodeWithText("43 км").assertDoesNotExist()
    }

    // --- MapLabel: ярлык города поверх карты ---

    @Test
    fun mapLabel_showsText() {
        composeRule.setContent { MapLabel("Магнитогорск", Modifier) }
        composeRule.onNodeWithText("Магнитогорск").assertIsDisplayed()
    }

    // --- MapZoomControls: кнопки ＋/−, зум выполняет вызывающий через колбэки ---

    @Test
    fun mapZoomControls_showsBothButtons_russian() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MapZoomControls(onZoomIn = {}, onZoomOut = {})
            }
        }
        composeRule.onNodeWithContentDescription("Приблизить").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Отдалить").assertIsDisplayed()
    }

    @Test
    fun mapZoomControls_zoomInClick_firesCallback() {
        var zoomedIn = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MapZoomControls(onZoomIn = { zoomedIn = true }, onZoomOut = {})
            }
        }
        assertFalse(zoomedIn)
        composeRule.onNodeWithContentDescription("Приблизить").performClick()
        assertTrue(zoomedIn)
    }

    @Test
    fun mapZoomControls_zoomOutClick_firesCallback() {
        var zoomedOut = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MapZoomControls(onZoomIn = {}, onZoomOut = { zoomedOut = true })
            }
        }
        composeRule.onNodeWithContentDescription("Отдалить").performClick()
        assertTrue(zoomedOut)
    }

    @Test
    fun mapZoomControls_bashkirContentDescriptions() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                MapZoomControls(onZoomIn = {}, onZoomOut = {})
            }
        }
        composeRule.onNodeWithContentDescription("Яҡынайтыу").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Йыраҡлаштырыу").assertIsDisplayed()
    }

    // --- SeniorAccessCard: карточка «Крупные кнопки» (тап по карточке/тумблеру включает режим) ---

    @Test
    fun seniorAccessCard_showsTitleAndSubtitle_russian() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SeniorAccessCard(onSimpleMode = {})
            }
        }
        composeRule.onNodeWithText("Крупные кнопки").assertIsDisplayed()
        composeRule.onNodeWithText("Крупный текст и голос").assertIsDisplayed()
    }

    @Test
    fun seniorAccessCard_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                SeniorAccessCard(onSimpleMode = {})
            }
        }
        composeRule.onNodeWithText("Ҙур төймәләр").assertIsDisplayed()
    }

    @Test
    fun seniorAccessCard_clickingCard_firesOnSimpleMode() {
        var enabled = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SeniorAccessCard(onSimpleMode = { enabled = true })
            }
        }
        assertFalse(enabled)
        composeRule.onNodeWithText("Крупные кнопки").performClick()
        assertTrue(enabled)
    }

    // --- RequestPreviewCard: карточка заявки попутчика (имя, маршрут, места, комментарий, действия) ---

    @Test
    fun requestPreviewCard_showsNameRouteAndActions_russian() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RequestPreviewCard(req = request(), onClose = {})
            }
        }
        composeRule.onNodeWithText("Айгуль").assertIsDisplayed()
        composeRule.onNodeWithText("ищет попутку").assertIsDisplayed()
        composeRule.onNodeWithText("Баймаҡ  →  Сибай").assertIsDisplayed()
        composeRule.onNodeWithText("Откликнуться").assertIsDisplayed()
        composeRule.onNodeWithText("Закрыть").assertIsDisplayed()
    }

    @Test
    fun requestPreviewCard_showsSeatsAndDistanceMeta_russian() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RequestPreviewCard(req = request(seats = 2, distanceKm = 3.0), onClose = {})
            }
        }
        // meta склеивается через "  ·  " → "2 места  ·  ≈ 3 км рядом"
        composeRule.onNodeWithText("2 места  ·  ≈ 3 км рядом").assertIsDisplayed()
    }

    @Test
    fun requestPreviewCard_showsComment() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RequestPreviewCard(req = request(comment = "Возьму до вокзала"), onClose = {})
            }
        }
        composeRule.onNodeWithText("Возьму до вокзала").assertIsDisplayed()
    }

    @Test
    fun requestPreviewCard_bashkir_showsSeeksRideLabel() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                RequestPreviewCard(req = request(), onClose = {})
            }
        }
        composeRule.onNodeWithText("юлдаш эҙләй").assertIsDisplayed()
        composeRule.onNodeWithText("Яуап бирергә").assertIsDisplayed()
    }

    @Test
    fun requestPreviewCard_closeClick_firesOnClose() {
        var closed = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RequestPreviewCard(req = request(), onClose = { closed = true })
            }
        }
        assertFalse(closed)
        composeRule.onNodeWithText("Закрыть").performClick()
        assertTrue(closed)
    }
}
