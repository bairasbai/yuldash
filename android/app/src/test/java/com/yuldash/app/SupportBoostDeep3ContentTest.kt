package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.BoostPlanDto
import com.yuldash.app.data.BoostResultDto
import com.yuldash.app.data.RideDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину-3» по экрану Boost/поднятие. Добираем то, что осталось непокрытым после
 * SupportBoostContentTest / SupportBoostDeepContentTest / SupportBoostDeep2ContentTest:
 *
 *  1. [BoostResultContent] — чистый рендер результата оплаты (вынесен из `BoostResultCard`;
 *     импур-часть — буфер обмена через `onCopyPhone` и QR-блок через слот `sberPaySlot`).
 *     Покрываем все 3 ветки (succeeded / ручной СБП с реквизитами и без / переход к ЮKassa)
 *     на двух языках, клик «Скопировать» и вызов QR-слота.
 *  2. Непокрытые ветки `BoostPlanCard` (tier=day/urgent/неизвестный → разные подзаголовки).
 *  3. Ветка `BoostRideRow` с `boosted=true` на русском («уже поднята»).
 *
 * Заголовок класса — как в SupportBoostContentTest. Карточки Boost анимируются
 * (`animateColorAsState` рамки + `bounceClick`) → в их тестах первой строкой гасим авто-часы:
 * `composeRule.mainClock.autoAdvance = false`, иначе бесконечный idle-sync.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SupportBoostDeep3ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun result(
        status: String = "pending",
        method: String = "sbp_manual",
        amount: Int = 149,
        payeePhone: String? = null,
        payeeBank: String? = null,
        payeeName: String? = null,
    ) = BoostResultDto(
        status = status,
        method = method,
        paymentId = 1,
        amount = amount,
        confirmationUrl = null,
        payeePhone = payeePhone,
        payeeBank = payeeBank,
        payeeName = payeeName,
    )

    private fun ride(boosted: Boolean = false) = RideDto(
        id = 1,
        fromCity = "Уфа",
        toCity = "Казань",
        departAt = "2026-07-03T10:00",
        seatsTotal = 4,
        seatsLeft = 3,
        price = 500,
        category = "people",
        driverName = "Азат",
        driverRating = 4.9,
        driverVerified = true,
        driverCar = "Kia Rio",
        boosted = boosted,
    )

    // ==================== BoostResultContent ====================

    // --- Ветка «succeeded» → InfoCard «Объявление поднято», QR-слот не зовётся ---

    @Test
    fun result_succeeded_russian_showsBoostedCard() {
        var sberSlotCalls = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BoostResultContent(
                    res = result(status = "succeeded", method = "yookassa"),
                    onCopyPhone = {},
                    sberPaySlot = { sberSlotCalls++ },
                )
            }
        }
        composeRule.onNodeWithText("Объявление поднято").assertIsDisplayed()
        composeRule.onNodeWithText("Поднятие включено. Спасибо!").assertIsDisplayed()
        assertEquals(0, sberSlotCalls)   // QR только в ручном СБП с телефоном
    }

    @Test
    fun result_succeeded_bashkir_showsBoostedCard() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                BoostResultContent(
                    res = result(status = "succeeded", method = "sbp_manual"),
                    onCopyPhone = {},
                    sberPaySlot = {},
                )
            }
        }
        composeRule.onNodeWithText("Иғлан күтәрелде").assertIsDisplayed()
    }

    // --- Ветка «sbp_manual» с реквизитами: сумма + телефон + банк·имя + «Скопировать» + QR-слот ---

    @Test
    fun result_sbpManual_withPhone_russian_showsAmountPhoneAndCopy() {
        var copied: String? = null
        var sberPhone: String? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BoostResultContent(
                    res = result(amount = 149, payeePhone = "+79990001122", payeeBank = "Сбербанк", payeeName = "Азат А."),
                    onCopyPhone = { copied = it },
                    sberPaySlot = { sberPhone = it },
                )
            }
        }
        // Сумма (RU-ветка appText) + сам номер + банк·имя.
        composeRule.onNodeWithText("Переведи 149 ₽ по СБП").assertIsDisplayed()
        composeRule.onNodeWithText("+79990001122").assertIsDisplayed()
        composeRule.onNodeWithText("Сбербанк · Азат А.").assertIsDisplayed()
        // QR-слот получил телефон (импур-часть вынесена в слот).
        assertEquals("+79990001122", sberPhone)
        // Клик «Скопировать» → onCopyPhone с номером.
        assertNull(copied)
        composeRule.onNodeWithText("Скопировать").performClick()
        assertEquals("+79990001122", copied)
    }

    @Test
    fun result_sbpManual_withPhone_bashkir_showsAmountAndCopyLabel() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                BoostResultContent(
                    res = result(amount = 149, payeePhone = "+79990001122", payeeBank = "Сбербанк"),
                    onCopyPhone = {},
                    sberPaySlot = {},
                )
            }
        }
        composeRule.onNodeWithText("СБП аша 149 ₽ күсер").assertIsDisplayed()
        composeRule.onNodeWithText("Күсереп алыу").assertIsDisplayed()
    }

    // --- Ветка «sbp_manual» БЕЗ реквизитов: фолбэк-текст вместо пустоты, QR-слот не зовётся ---

    @Test
    fun result_sbpManual_noPhone_russian_showsFallbackHint() {
        var sberSlotCalls = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BoostResultContent(
                    res = result(payeePhone = null),
                    onCopyPhone = {},
                    sberPaySlot = { sberSlotCalls++ },
                )
            }
        }
        composeRule.onNodeWithText(
            "Реквизиты для перевода ещё не подгрузились — напиши в поддержку, поможем перевести."
        ).assertIsDisplayed()
        // Без телефона QR-блок и кнопка «Скопировать» не показываются.
        composeRule.onNodeWithText("Скопировать").assertDoesNotExist()
        assertEquals(0, sberSlotCalls)
    }

    @Test
    fun result_sbpManual_blankPhone_treatedAsNoPhone() {
        // Пустая строка телефона (isBlank) → тот же фолбэк, что и null.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BoostResultContent(
                    res = result(payeePhone = "   "),
                    onCopyPhone = {},
                    sberPaySlot = {},
                )
            }
        }
        composeRule.onNodeWithText(
            "Реквизиты для перевода ещё не подгрузились — напиши в поддержку, поможем перевести."
        ).assertIsDisplayed()
    }

    // --- Ветка «else» (не succeeded, не sbp_manual) → InfoCard «Переходим к оплате» (ЮKassa) ---

    @Test
    fun result_yookassaPending_russian_showsRedirectCard() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BoostResultContent(
                    res = result(status = "pending", method = "yookassa"),
                    onCopyPhone = {},
                    sberPaySlot = {},
                )
            }
        }
        composeRule.onNodeWithText("Переходим к оплате").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Заверши оплату в открывшемся окне. После оплаты поднятие включится."
        ).assertIsDisplayed()
    }

    @Test
    fun result_yookassaPending_bashkir_showsRedirectCard() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                BoostResultContent(
                    res = result(status = "pending", method = "yookassa"),
                    onCopyPhone = {},
                    sberPaySlot = {},
                )
            }
        }
        composeRule.onNodeWithText("Түләүгә күсәбеҙ").assertIsDisplayed()
    }

    // ==================== BoostPlanCard: непокрытые tier-ветки подзаголовка ====================

    @Test
    fun boostPlanCard_dayTier_russian_showsMapHighlightSub() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BoostPlanCard(
                    BoostPlanDto(tier = "day", title = "На день", price = 149, hours = 24),
                    selected = true, onClick = {},
                )
            }
        }
        // tier=day, RU → "24 часа выше + выделение на карте"
        composeRule.onNodeWithText("24 часа выше + выделение на карте").assertIsDisplayed()
    }

    @Test
    fun boostPlanCard_urgentTier_russian_showsUrgentSub() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BoostPlanCard(
                    BoostPlanDto(tier = "urgent", title = "Срочно", price = 299, hours = 6),
                    selected = false, onClick = {},
                )
            }
        }
        // tier=urgent, RU → "6 часов выше, выделение, метка срочно"
        composeRule.onNodeWithText("6 часов выше, выделение, метка срочно").assertIsDisplayed()
    }

    @Test
    fun boostPlanCard_unknownTier_bashkir_showsFallbackSub() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                BoostPlanCard(
                    BoostPlanDto(tier = "mega", title = "Мега", price = 999, hours = 48),
                    selected = false, onClick = {},
                )
            }
        }
        // else-ветка (неизвестный tier), BA → "48 сәғәт өҫтәрәк"
        composeRule.onNodeWithText("48 сәғәт өҫтәрәк").assertIsDisplayed()
    }

    // ==================== BoostRideRow: boosted-бейдж на русском ====================

    @Test
    fun boostRideRow_boosted_russian_showsAlreadyBoostedBadge() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BoostRideRow(ride(boosted = true), selected = false, onClick = {})
            }
        }
        composeRule.onNodeWithText("уже поднята").assertIsDisplayed()
    }

    @Test
    fun boostRideRow_notBoosted_hidesBadge() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                BoostRideRow(ride(boosted = false), selected = false, onClick = {})
            }
        }
        composeRule.onNodeWithText("уже поднята").assertDoesNotExist()
    }
}
