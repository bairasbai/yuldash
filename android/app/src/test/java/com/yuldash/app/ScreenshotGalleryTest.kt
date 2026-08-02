package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import com.yuldash.app.data.InstantEstimateDto
import com.yuldash.app.data.InstantPriceFactorDto
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Галерея скриншотов: рисует ключевые куски интерфейса в PNG **без устройства и эмулятора**.
 *
 * Зачем. Эмулятор есть только у Александра, а среда агента запускать его не может (нет
 * аппаратного ускорения и закрыт `dl.google.com`). При этом «выглядит правильно» на слово
 * принимать нельзя: на тёмной теме мы уже дважды ловили невидимый текст, который все проверки
 * проходил — тесты умеют читать надписи, но не видят, что надпись цвета фона.
 *
 * Robolectric в режиме NATIVE рисует настоящие пиксели, `captureToImage()` их забирает.
 * Картинки складываются в `build/reports/screenshots/`, CI отдаёт их отдельным артефактом.
 *
 * Это НЕ тест сравнения с эталоном: он ничего не утверждает и упасть не может. Его продукт —
 * изображения для живого просмотра человеком (или агентом). Утверждения про контраст живут
 * отдельно и считаются числами: `tools/contrast.py`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")   // обычный телефон, иначе окно узкое и картинка обрезана
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotGalleryTest {

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun resetTheme() {
        ThemePrefs.darkOverride = null   // тема глобальная — не тащим её в соседние тесты
    }

    /** Отрисовать блок и положить PNG рядом с отчётами Gradle. */
    private fun shot(
        name: String,
        dark: Boolean = false,
        lang: AppLanguage = AppLanguage.Ru,
        content: @Composable () -> Unit,
    ) {
        ThemePrefs.darkOverride = dark
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides lang) {
                Surface(color = CanonBg) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) { content() }
                }
            }
        }
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/reports/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun estimate() = InstantEstimateDto(
        price = 340, distanceKm = 8.4, etaMin = 17.0, pickupEtaMin = 4,
        zone = "baymak", category = "econom", tariffId = 1,
        surgeK = 1.3, dynamicK = 1.3, pricingCapK = 1.5, basePrice = 260,
        pricingVersion = "v2",   // без этого TaxiPricingBreakdown ничего не рисует
        priceFactors = listOf(
            InstantPriceFactorDto(
                code = "demand", kind = "multiplier", k = 1.2, active = true,
                titleRu = "Много заказов рядом", titleBa = "Яҡында заказдар күп",
                descriptionRu = "Свободных машин меньше обычного",
                descriptionBa = "Буш машиналар ғәҙәттәгенән аҙыраҡ",
            ),
            InstantPriceFactorDto(
                code = "weather", kind = "multiplier", k = 1.1, active = true,
                titleRu = "Дождь", titleBa = "Ямғыр",
                descriptionRu = "В непогоду ехать дольше и сложнее",
                descriptionBa = "Насар һауала барыу оҙоныраҡ һәм ауырыраҡ",
            ),
            InstantPriceFactorDto(
                code = "cap", kind = "cap", k = 1.5, active = true,
                titleRu = "Потолок наценки", titleBa = "Өҫтәмә хаҡ сиге",
                descriptionRu = "Выше этого не поднимаем никогда",
                descriptionBa = "Быныһынан юғары бер ҡасан да күтәрмәйбеҙ",
            ),
        ),
    )

    // ---------- Такси: разбор цены. Здесь жил невидимый в тёмной теме текст ----------

    @Test
    fun taxiPricingBreakdown_dark() = shot("taxi-pricing-breakdown-dark", dark = true) {
        TaxiPricingBreakdown(estimate())
    }

    @Test
    fun taxiPricingBreakdown_light() = shot("taxi-pricing-breakdown-light") {
        TaxiPricingBreakdown(estimate())
    }

    @Test
    fun taxiPricingBreakdown_bashkir_dark() =
        shot("taxi-pricing-breakdown-ba-dark", dark = true, lang = AppLanguage.Ba) {
            TaxiPricingBreakdown(estimate())
        }

    // ---------- Такси: выбор тарифа (галочка выбранного) и сводка цены ----------

    @Test
    fun taxiClassTiles_dark() = shot("taxi-class-tiles-dark", dark = true) {
        TaxiServiceClassTile(
            title = appText("Эконом", "Эконом"),
            subtitle = appText("обычная машина", "ғәҙәти машина"),
            price = 340, selected = true, onClick = {}, modifier = Modifier.fillMaxWidth(),
        )
        TaxiServiceClassTile(
            title = appText("Комфорт", "Комфорт"),
            subtitle = appText("новее и просторнее", "яңыраҡ һәм иркенерәк"),
            price = 480, selected = false, onClick = {}, modifier = Modifier.fillMaxWidth(),
        )
    }

    @Test
    fun taxiFareSummary_dark() = shot("taxi-fare-summary-dark", dark = true) {
        TaxiFareSummary(
            price = 340,
            distance = appText("≈ 8 км", "≈ 8 км"),
            duration = appText("≈ 17 мин", "≈ 17 мин"),
            pickup = appText("Машина будет через ≈4 мин", "Машина ≈4 минуттан була"),
            loading = false, error = null,
        )
    }

    @Test
    fun taxiFareSummary_light() = shot("taxi-fare-summary-light") {
        TaxiFareSummary(
            price = 340,
            distance = appText("≈ 8 км", "≈ 8 км"),
            duration = appText("≈ 17 мин", "≈ 17 мин"),
            pickup = appText("Машина будет через ≈4 мин", "Машина ≈4 минуттан была"),
            loading = false, error = null,
        )
    }

    // ---------- Курьер: подпись над суммой (у бесплатной посылки дохода нет) ----------

    @Test
    fun courierOffer_paid_light() = shot("courier-offer-paid-light") {
        CourierOfferCard(
            from = appText("Баймак", "Баймаҡ"),
            to = appText("Сибай", "Сибай"),
            sizeLabel = appText("Средняя", "Уртаса"),
            deliveryLabel = appText("Курьер", "Курьер"),
            priceCaption = appText("Твой доход", "Һинең килем"),
            priceLabel = "≈ 620 ₽",
            description = appText("Документы в плотном конверте.", "Документтар ҡалын конвертта."),
        ) { Text(appText("Цена доставки: 680 ₽", "Илтеү хаҡы: 680 ₽")) }
    }

    @Test
    fun courierOffer_free_dark() = shot("courier-offer-free-dark", dark = true) {
        CourierOfferCard(
            from = appText("Темясово", "Темәс"),
            to = appText("Сибай", "Сибай"),
            sizeLabel = appText("Малая", "Бәләкәй"),
            deliveryLabel = appText("По пути", "Юл ыңғайы"),
            priceCaption = appText("Без оплаты", "Түләүһеҙ"),
            priceLabel = appText("По-соседски", "Күрше хаҡы"),
            description = appText("Лекарства для бабушки.", "Өләсәйгә дарыу."),
        ) {}
    }

    @Test
    fun courierOffer_free_bashkir() =
        shot("courier-offer-free-ba", lang = AppLanguage.Ba) {
            CourierOfferCard(
                from = appText("Темясово", "Темәс"),
                to = appText("Сибай", "Сибай"),
                sizeLabel = appText("Малая", "Бәләкәй"),
                deliveryLabel = appText("По пути", "Юл ыңғайы"),
                priceCaption = appText("Без оплаты", "Түләүһеҙ"),
                priceLabel = appText("По-соседски", "Күрше хаҡы"),
                description = appText("Лекарства для бабушки.", "Өләсәйгә дарыу."),
            ) {}
        }

    // ---------- Шапки режимов: такси и курьер, светлая и тёмная ----------

    @Test
    fun mobilityIntros_dark() = shot("mobility-intros-dark", dark = true) {
        MobilityScreenIntro(
            mode = MobilityMode.Taxi,
            title = appText("Ищем машину", "Машина эҙләйбеҙ"),
            subtitle = appText("Предложение видят ближайшие проверенные водители",
                "Тәҡдимде яҡындағы тикшерелгән водителдәр күрә"),
            badge = appText("в эфире", "эфирҙа"),
        )
        MobilityScreenIntro(
            mode = MobilityMode.Courier,
            title = appText("Заказы рядом", "Яҡындағы заказдар"),
            subtitle = appText("Маршрут и твой доход видны до принятия",
                "Маршрут һәм килем заказды алғансы күренә"),
        )
    }

    @Test
    fun courierProgress_light() = shot("courier-progress-light") {
        CourierDeliveryProgress(status = "in_transit", modifier = Modifier.fillMaxWidth())
        CourierDeliveryProgress(status = "returning", modifier = Modifier.fillMaxWidth())
    }
}
