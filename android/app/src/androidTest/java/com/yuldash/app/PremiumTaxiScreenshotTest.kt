package com.yuldash.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.InstantOrderDto
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Снимки премиальных экранов такси — чтобы их можно было ПОСМОТРЕТЬ, а не только проверить.
 *
 * Зачем отдельный тест. Тесты держат логику, но не показывают, как экран выглядит: как легли
 * отступы, не наехал ли текст, читается ли цена. Пять экранов, пришедших с премиальной
 * вёрсткой, до сих пор никто не видел живьём — до них не добраться без работающего сервера
 * такси, а он требует Redis. Здесь экран рисуется напрямую с готовым заказом, без сети.
 *
 * Куда кладутся снимки: внешняя папка приложения на устройстве,
 * `/sdcard/Android/data/com.yuldash.app/files/Pictures/premium/`. Забрать: `adb pull`.
 */
@RunWith(AndroidJUnit4::class)
class PremiumTaxiScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun passengerCompleted() = снять("01-пассажир-поездка-завершена") {
        TaxiPassengerCompletedScreen(
            order = заказ(status = "done", role = "passenger"),
            onClose = {}, onNewOrder = {}, onOpenReceipt = {}, onOpenChat = {},
            rateOrder = { _, _, _ -> Result.success(Unit) },
        )
    }

    @Test
    fun driverCompleted() = снять("02-водитель-поездка-завершена") {
        TaxiDriverCompletedScreen(
            order = заказ(status = "done", role = "driver"),
            onReturnToLine = {}, onShiftFinished = {}, onOpenReceipt = {},
            rateOrder = { _, _, _ -> Result.success(Unit) },
            finishShift = { Result.success(Unit) },
        )
    }

    @Test
    fun driverCancelled() = снять("03-водитель-заказ-отменён") {
        TaxiDriverCancelledScreen(
            order = заказ(status = "cancelled", role = "driver").copy(cancelBy = "passenger"),
            onReturnToLine = {}, onShiftFinished = {},
            finishShift = { Result.success(Unit) },
        )
    }

    @Test
    fun searching() = снять("04-поиск-машины") {
        TaxiSearchingScreen(
            order = заказ(status = "searching", role = "passenger"),
            onCancel = {},
            embedded = false,
        )
    }

    @Test
    fun driverCancelledScrolledDown() = снять("05-водитель-отмена-прокручено", прокрутить = true) {
        TaxiDriverCancelledScreen(
            order = заказ(status = "cancelled", role = "driver").copy(cancelBy = "passenger"),
            onReturnToLine = {}, onShiftFinished = {},
            finishShift = { Result.success(Unit) },
        )
    }

    @Test
    fun driverCompletedScrolledDown() = снять("06-водитель-завершено-прокручено", прокрутить = true) {
        TaxiDriverCompletedScreen(
            order = заказ(status = "done", role = "driver"),
            onReturnToLine = {}, onShiftFinished = {}, onOpenReceipt = {},
            rateOrder = { _, _, _ -> Result.success(Unit) },
            finishShift = { Result.success(Unit) },
        )
    }

    @Test
    fun экранОтменыДокручиваетсяДоДенег() {
        composeRule.setContent {
            YuldashTheme {
                TaxiDriverCancelledScreen(
                    order = заказ(status = "cancelled", role = "driver").copy(cancelBy = "passenger"),
                    onReturnToLine = {}, onShiftFinished = {},
                    finishShift = { Result.success(Unit) },
                )
            }
        }
        composeRule.waitForIdle()
        // Не жест, а прямая команда «доведи до видимой области». Упадёт — экран не прокручивается.
        // Берём НИЖНИЙ текст карточки, а не заголовок: заголовок виден и без прокрутки.
        composeRule.onNodeWithText("Отмена произошла до платного окна.")
            .performScrollTo().assertIsDisplayed()
        composeRule.waitForIdle()
        снимок("07-отмена-докручено-до-денег")
    }

    // ---------------------------------------------------------------- служебное

    private fun снять(
        имя: String,
        прокрутить: Boolean = false,
        экран: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        composeRule.setContent { YuldashTheme { экран() } }
        composeRule.waitForIdle()
        // Проверка «влезает ли всё»: гоним экран до низа и смотрим, что видно после прокрутки.
        if (прокрутить) {
            repeat(6) { composeRule.onRoot().performTouchInput { swipeUp() } }
            composeRule.waitForIdle()
        }
        снимок(имя)
    }

    private fun снимок(имя: String) {
        val bitmap: Bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val папка = File(
            InstrumentationRegistry.getInstrumentation().targetContext
                .getExternalFilesDir("Pictures"), "premium",
        ).apply { mkdirs() }
        FileOutputStream(File(папка, "$имя.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    /** Обычный городской заказ: Баймак, три с половиной километра, наличными. */
    private fun заказ(status: String, role: String) = InstantOrderDto(
        id = 90_001,
        status = status,
        role = role,
        fromLat = 52.5931,
        fromLng = 58.3186,
        toLat = 52.6076,
        toLng = 58.3338,
        fromText = "ул. Ленина, 12",
        toText = "ул. Гагарина, 8",
        category = "standard",
        paymentMethod = "cash",
        priceEstimate = 350,
        priceFinal = 350,
        distanceKm = 3.8,
        etaMin = 12.0,
        driverId = 77,
        offerExpiresAt = null,
        cancelBy = "",
        cancelReason = "",
        surgeK = 1.0,
        waitingStartedAt = null,
        waitingFeeKop = 0,
        cancelFeeKop = 0,
        noShow = false,
        waitFreeMin = 5,
        waitFeeRubPerMin = 5,
        noShowAt = null,
        cancelFeeNowKop = 0,
        passengerRating = 4.8,
        passengerTrips = 24,
        driverName = "Ильдар",
        driverCar = "Kia Rio",
        driverCarColor = "серебристый",
        driverVerified = true,
        driverRating = 4.9,
        driverPhone = "+7 927 000-00-00",
        passengerName = "Айгуль",
        passengerPhone = "+7 927 111-11-11",
        driverTrips = 312,
        driverFrom = "Баймак",
        driverPlate = "А123ВС 02",
    )
}
