package com.yuldash.app

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ИНСТРУМЕНТАЛЬНЫЙ тест ЖИВОЙ карты (на устройстве/эмуляторе ≤API-36, где Espresso работает).
 * JVM/Robolectric не может отрендерить нативный `YandexMapView` (MapKit) — это единственный
 * крупный непокрытый кусок. Здесь рендерим весь умный `MapScreen` на реальном устройстве:
 * `YuldashApplication` при старте процесса делает `MapKitFactory.setApiKey`+`initialize`, поэтому
 * `nativeMapVisible` (после короткой задержки) поднимает НАСТОЯЩИЙ `YandexMapCard` — исполняется
 * код инициализации карты, маркеров, жизненного цикла `onStart/onStop`, чего на JVM не было.
 *
 * Проверяем: экран композится и рендерит хром поверх живой карты БЕЗ КРАША (главная ценность —
 * исполнение MapKit-веток на устройстве + краш-фри), заголовок/кнопки на двух языках.
 * Запуск: `gradlew :app:connectedDebugAndroidTest` на эмуляторе API ≤36.
 *
 * Сеть (`ApiClient.getNearbyRidesPaged` в LaunchedEffect) под эмулятором может не дойти до прод-API
 * (ТСПУ) → `onFailure`, экран показывает состояние ошибки «Ближайших». Карте это не мешает —
 * MapKit-тайлы грузятся с Яндекса независимо. Тест не зависит от сети: бьём по всегда-видимому хрому.
 */
@RunWith(AndroidJUnit4::class)
class MapScreenInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun skipOnNewApi() {
        // Espresso (idle-sync под createAndroidComposeRule) падает на API 37
        // (NoSuchMethodException InputManager.getInstance — метод удалён, AndroidX не догнал).
        // На ≤API-36 — работает. Гард делает тест безопасным на любом эмуляторе: тут исполняется,
        // на API 37+ — ПРОПУСК (assumeTrue), а не провал всего connectedCheck.
        assumeTrue("Инструментальные Compose-тесты карты — только API ≤ 36", Build.VERSION.SDK_INT <= 36)
    }

    /** Рендер MapScreen; данные/язык/колбэки переопределяем под тест. */
    private fun renderMap(
        language: AppLanguage = AppLanguage.Ru,
        activeTrip: Ride? = null,
        rides: List<Ride> = emptyList(),
        ads: List<PartnerAd> = emptyList(),
        onDriver: () -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides language) {
                MapScreen(
                    rides = rides,
                    activeTrip = activeTrip,
                    ads = ads,
                    adStats = ads.associate { it.id to AdStats() },
                    onBookRide = {},
                    onShareRide = {},
                    onAdImpression = {},
                    onAdClick = {},
                    onSos = {},
                    onOpenPopular = {},
                    onDriver = onDriver,
                    onBoost = {},
                )
            }
        }
    }

    @Test
    fun mapScreen_rendersHeadlineAndChrome_russian() {
        renderMap(AppLanguage.Ru)
        // Заголовок над картой всегда виден (хром поверх живого MapKit).
        composeRule.onNodeWithText("Куда поедем?").assertIsDisplayed()
        // Кнопки действий под картой (рендерятся вместе с картой).
        composeRule.onNodeWithText("Найти поездку").assertIsDisplayed()
        composeRule.onNodeWithText("Я водитель").assertIsDisplayed()
    }

    @Test
    fun mapScreen_rendersHeadline_bashkir() {
        renderMap(AppLanguage.Ba)
        // Башкирский заголовок главного экрана карты.
        composeRule.onNodeWithText("Ҡайҙа барабыҙ?").assertIsDisplayed()
    }

    @Test
    fun mapScreen_liveMapAppears_noCrash() {
        // `nativeMapVisible` поднимается после короткой задержки → живой YandexMapCard.
        // Даём карте инициализироваться (MapKit onStart/тайлы), затем проверяем, что экран
        // ЖИВ и хром на месте — значит нативная карта отрисовалась без краша на устройстве.
        renderMap(AppLanguage.Ru)
        composeRule.onNodeWithText("Куда поедем?").assertIsDisplayed()
        // Ждём флип nativeMapVisible + инициализацию MapKit (реальные часы устройства).
        Thread.sleep(3500)
        composeRule.waitForIdle()
        // Экран пережил появление живой карты — хром по-прежнему рендерится.
        composeRule.onNodeWithText("Найти поездку").assertIsDisplayed()
    }

    @Test
    fun mapScreen_withActiveTrip_rendersFocusedRoute_noCrash() {
        // Активная поездка → карта фокусируется на её маршруте (другая ветка MapKit-рендера).
        val trip = Ride(
            id = "t1",
            from = "Баймаҡ",
            to = "Сибай",
            time = "08:30",
            driver = "Ринат",
            car = "Lada Vesta",
            price = 350,
            seats = 3,
            rating = 4.8,
            verified = true,
            boosted = false,
        )
        renderMap(AppLanguage.Ru, activeTrip = trip)
        composeRule.onNodeWithText("Куда поедем?").assertIsDisplayed()
        Thread.sleep(2500)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Найти поездку").assertIsDisplayed()
    }

    @Test
    fun mapScreen_withFullData_rendersMarkersAndAds_noCrash() {
        // Демо-поездки + реклама → карта рисует маркеры поездок (MapPins) и карточки рекламы,
        // активная поездка → фокус маршрута. Максимум веток живого рендера MapKit за один тест.
        val trip = Ride(
            id = "t2", from = "Уфа", to = "Сибай", time = "10:00",
            driver = "Айдар", car = "Kia Rio", price = 500, seats = 2,
            rating = 4.9, verified = true, boosted = false,
        )
        renderMap(
            language = AppLanguage.Ru,
            activeTrip = trip,
            rides = demoRides,
            ads = demoPartnerAds,
        )
        composeRule.onNodeWithText("Куда поедем?").assertIsDisplayed()
        // Даём карте нарисовать маркеры/маршрут по данным + инициализироваться.
        Thread.sleep(3500)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Найти поездку").assertIsDisplayed()
    }

    @Test
    fun mapScreen_iAmDriver_click_firesCallback() {
        var driver = false
        renderMap(AppLanguage.Ru, onDriver = { driver = true })
        composeRule.onNodeWithText("Я водитель").performClick()
        org.junit.Assert.assertTrue(driver)
    }
}
