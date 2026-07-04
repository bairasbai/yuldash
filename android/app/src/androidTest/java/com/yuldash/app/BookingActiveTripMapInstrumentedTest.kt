package com.yuldash.app

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yandex.mapkit.geometry.Point
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ИНСТРУМЕНТАЛЬНЫЙ тест ВТОРОЙ живой карты — маршрут активной поездки (`BookingRouteMapPreview`,
 * нативный `YandexMapView` внутри `BookingActiveTripScreen`). На JVM это 0% (MapKit не рендерится).
 * Здесь рендерим компонент на реальном устройстве (≤API-36): исполняется MapKit-инициализация,
 * `DisposableEffect` жизненного цикла (`onStart/onStop`), построение и отрисовка маршрута
 * `fromPoint → toPoint`. Ассертить внутренности нативной карты нельзя — ценность в том, что
 * MapKit-код исполняется НА УСТРОЙСТВЕ БЕЗ КРАША (рендер + жизненный цикл + маршрут).
 *
 * Гард `assumeTrue(SDK ≤ 36)`: на API 37 Espresso падает (`InputManager.getInstance` удалён) → пропуск.
 * Запуск: `gradlew :app:connectedDebugAndroidTest` на эмуляторе API ≤ 36.
 */
@RunWith(AndroidJUnit4::class)
class BookingActiveTripMapInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun skipOnNewApi() {
        assumeTrue("Инструментальные Compose-тесты карты — только API ≤ 36", Build.VERSION.SDK_INT <= 36)
    }

    // Реальные координаты Башкортостана: Баймак → Сибай (маршрут рисуется живой картой).
    private val baymak = Point(52.5931, 58.3186)
    private val sibay = Point(52.7078, 58.6686)

    @Test
    fun bookingRouteMap_rendersRoute_contactUnlocked_noCrash() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BookingRouteMapPreview(
                    from = "Баймаҡ",
                    to = "Сибай",
                    fromPoint = baymak,
                    toPoint = sibay,
                    contactUnlocked = true,
                )
            }
        }
        composeRule.waitForIdle()
        // Даём MapKit инициализироваться (onStart) + построить/отрисовать маршрут.
        Thread.sleep(3500)
        composeRule.waitForIdle()
        // Дошли сюда без исключения → нативная карта маршрута отрендерилась на устройстве.
    }

    @Test
    fun bookingRouteMap_contactLocked_hidesGeo_noCrash() {
        // contactUnlocked=false → ветка «геолокация скрыта до подтверждения» (приватность).
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                BookingRouteMapPreview(
                    from = "Өфө",
                    to = "Сибай",
                    fromPoint = Point(54.7388, 55.9721),   // Уфа
                    toPoint = sibay,
                    contactUnlocked = false,
                )
            }
        }
        composeRule.waitForIdle()
        Thread.sleep(3000)
        composeRule.waitForIdle()
    }
}
