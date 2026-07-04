package com.yuldash.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Общее ядро (`MainActivity.kt`): чистые `@Composable`-хелперы двуязычных подписей доменных моделей —
 * `LocalizedText.text()`, `seatsText`, `Ride.timeText/carText`, `PopularRoute.minutesText/labelText`,
 * `TrustedContact.relationText`, `FrequentTrip.titleText/timeHintText`. Каждый возвращает `String` по
 * `LocalAppLanguage`, поэтому рендерим результат в `Text(...)` и проверяем видимость. Ветки RU/BA и
 * фолбэк `xxBa ?: xx` (когда башкирского варианта нет) — то, что раньше было 0%. `onCreate`/Activity
 * и импур-часть (`SbpTransferSheet`/`SberPayBlock`: буфер/контекст/QR) НЕ трогаем — они не чистые.
 *
 * Заголовок класса — как в SosVerifyDeepContentTest. Анимаций в этих хелперах нет → без autoAdvance.
 * Высокое окно (`w411dp-h2600dp`) — на всякий случай, скролл тут не нужен (по одному `Text`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Показать строку, вычисленную @Composable-хелпером, в узле Text → можно ассертить по тексту.
    private fun show(lang: AppLanguage, content: @Composable () -> String) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides lang) {
                Text(content())
            }
        }
    }

    private fun ride(
        time: String = "10:00",
        timeBa: String? = null,
        car: String = "Kia Rio",
        carBa: String? = null,
    ) = Ride(
        id = "1", from = "Сибай", to = "Уфа", time = time, timeBa = timeBa,
        driver = "Азат", car = car, carBa = carBa, price = 500, seats = 3,
        rating = 4.8, verified = true, boosted = false,
    )

    private fun route(
        minutes: String = "4 ч",
        minutesBa: String? = null,
        label: String = "Хит",
        labelBa: String? = null,
    ) = PopularRoute(
        from = "Темясово", to = "Уфа", minutes = minutes, minutesBa = minutesBa,
        distance = "250 км", nearbyCount = 3, label = label, labelBa = labelBa,
    )

    // ==================== LocalizedText.text() ====================

    @Test
    fun localizedText_russian_showsRu() {
        show(AppLanguage.Ru) { LocalizedText("Полиция", "Полиция ба").text() }
        composeRule.onNodeWithText("Полиция").assertIsDisplayed()
    }

    @Test
    fun localizedText_bashkir_showsBa() {
        show(AppLanguage.Ba) { LocalizedText("Скорая", "Тиҙ ярҙам").text() }
        composeRule.onNodeWithText("Тиҙ ярҙам").assertIsDisplayed()
    }

    // ==================== seatsText: русский плюрал + башкирское «урын» ====================

    @Test
    fun seatsText_russianPlural_one() {
        show(AppLanguage.Ru) { seatsText(1) }
        composeRule.onNodeWithText("1 место").assertIsDisplayed()
    }

    @Test
    fun seatsText_russianPlural_few() {
        show(AppLanguage.Ru) { seatsText(3) }
        composeRule.onNodeWithText("3 места").assertIsDisplayed()
    }

    @Test
    fun seatsText_russianPlural_many() {
        show(AppLanguage.Ru) { seatsText(5) }
        composeRule.onNodeWithText("5 мест").assertIsDisplayed()
    }

    @Test
    fun seatsText_russianPlural_teens_useMany() {
        // 11..14 → всегда «мест» (11 мест), несмотря на m10==1.
        show(AppLanguage.Ru) { seatsText(11) }
        composeRule.onNodeWithText("11 мест").assertIsDisplayed()
    }

    @Test
    fun seatsText_russianPlural_twentyOne_usesOne() {
        // 21 → m100=21 не в 11..14, m10==1 → «место».
        show(AppLanguage.Ru) { seatsText(21) }
        composeRule.onNodeWithText("21 место").assertIsDisplayed()
    }

    @Test
    fun seatsText_bashkir_usesUryn() {
        show(AppLanguage.Ba) { seatsText(3) }
        composeRule.onNodeWithText("3 урын").assertIsDisplayed()
    }

    // ==================== Ride.timeText(): фолбэк timeBa ?: time ====================

    @Test
    fun rideTimeText_bashkir_usesTimeBaWhenPresent() {
        show(AppLanguage.Ba) { ride(time = "10:00", timeBa = "10:00 иртә").timeText() }
        composeRule.onNodeWithText("10:00 иртә").assertIsDisplayed()
    }

    @Test
    fun rideTimeText_bashkir_fallsBackToTimeWhenBaNull() {
        // timeBa == null → башкирская ветка appText получает сам time (фолбэк).
        show(AppLanguage.Ba) { ride(time = "12:30", timeBa = null).timeText() }
        composeRule.onNodeWithText("12:30").assertIsDisplayed()
    }

    @Test
    fun rideTimeText_russian_usesTime() {
        show(AppLanguage.Ru) { ride(time = "09:15", timeBa = "иртә").timeText() }
        composeRule.onNodeWithText("09:15").assertIsDisplayed()
    }

    // ==================== Ride.carText(): фолбэк carBa ?: car ====================

    @Test
    fun rideCarText_bashkir_usesCarBaWhenPresent() {
        show(AppLanguage.Ba) { ride(car = "Kia Rio", carBa = "Kia Rio аҡ").carText() }
        composeRule.onNodeWithText("Kia Rio аҡ").assertIsDisplayed()
    }

    @Test
    fun rideCarText_bashkir_fallsBackToCarWhenBaNull() {
        show(AppLanguage.Ba) { ride(car = "Lada Vesta", carBa = null).carText() }
        composeRule.onNodeWithText("Lada Vesta").assertIsDisplayed()
    }

    // ==================== PopularRoute.minutesText() / labelText(): фолбэки ====================

    @Test
    fun routeMinutesText_bashkir_usesMinutesBaWhenPresent() {
        show(AppLanguage.Ba) { route(minutes = "4 ч", minutesBa = "4 сәғәт").minutesText() }
        composeRule.onNodeWithText("4 сәғәт").assertIsDisplayed()
    }

    @Test
    fun routeMinutesText_bashkir_fallsBackToMinutesWhenBaNull() {
        show(AppLanguage.Ba) { route(minutes = "2 ч", minutesBa = null).minutesText() }
        composeRule.onNodeWithText("2 ч").assertIsDisplayed()
    }

    @Test
    fun routeLabelText_bashkir_usesLabelBaWhenPresent() {
        show(AppLanguage.Ba) { route(label = "Хит", labelBa = "Хит ба").labelText() }
        composeRule.onNodeWithText("Хит ба").assertIsDisplayed()
    }

    @Test
    fun routeLabelText_russian_usesLabel() {
        show(AppLanguage.Ru) { route(label = "Популярно", labelBa = "Популяр").labelText() }
        composeRule.onNodeWithText("Популярно").assertIsDisplayed()
    }

    // ==================== TrustedContact.relationText(): фолбэк relationBa ?: relation ====================

    @Test
    fun trustedContactRelationText_bashkir_usesRelationBaWhenPresent() {
        show(AppLanguage.Ba) {
            TrustedContact(name = "Марат", relation = "Брат", phone = "+79990000000",
                notifyByDefault = true, relationBa = "Ағай").relationText()
        }
        composeRule.onNodeWithText("Ағай").assertIsDisplayed()
    }

    @Test
    fun trustedContactRelationText_bashkir_fallsBackToRelationWhenBaNull() {
        show(AppLanguage.Ba) {
            TrustedContact(name = "Марат", relation = "Брат", phone = "+79990000000",
                notifyByDefault = true, relationBa = null).relationText()
        }
        composeRule.onNodeWithText("Брат").assertIsDisplayed()
    }

    // ==================== FrequentTrip.titleText() / timeHintText(): обе ветки RU/BA ====================

    private fun trip() = FrequentTrip(
        title = "На работу", titleBa = "Эшкә", from = "Сибай", to = "Баймак",
        timeHint = "Утро", timeHintBa = "Иртә", categoryKey = "regular",
    )

    @Test
    fun frequentTripTitleText_russian_usesTitle() {
        show(AppLanguage.Ru) { trip().titleText() }
        composeRule.onNodeWithText("На работу").assertIsDisplayed()
    }

    @Test
    fun frequentTripTitleText_bashkir_usesTitleBa() {
        show(AppLanguage.Ba) { trip().titleText() }
        composeRule.onNodeWithText("Эшкә").assertIsDisplayed()
    }

    @Test
    fun frequentTripTimeHintText_russian_usesTimeHint() {
        show(AppLanguage.Ru) { trip().timeHintText() }
        composeRule.onNodeWithText("Утро").assertIsDisplayed()
    }

    @Test
    fun frequentTripTimeHintText_bashkir_usesTimeHintBa() {
        show(AppLanguage.Ba) { trip().timeHintText() }
        composeRule.onNodeWithText("Иртә").assertIsDisplayed()
    }
}
