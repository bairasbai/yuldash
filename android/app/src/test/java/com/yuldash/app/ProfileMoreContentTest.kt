package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.yuldash.app.data.AdPackageDto
import com.yuldash.app.data.MyAdDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Ещё «content-композаблы» экрана ПРОФИЛЯ/рекламы: чистые (без сети/state/эффектов) под-компоненты
 * `CabinetMetric` (метрика кабинета), `AdField` (поле формы объявления), `MyAdCard` (карточка своего
 * объявления со статусами) и `AdsShowcase` (витрина тарифов) стали `internal` → покрываем их на JVM
 * через Robolectric. Проверяем двуязычие (RU/BA), состояния по статусу и клики-колбэки.
 *
 * Заголовок класса — как в ProfileScreenContentTest / AdminReviewsContentTest. Анимаций нет →
 * autoAdvance отключать не нужно. Компоненты с внутренним appText(ru, ba) оборачиваем в
 * CompositionLocalProvider(LocalAppLanguage). Тексты взяты дословно из ProfileScreen.kt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileMoreContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Фабрика объявления: меняем только нужные для сценария поля.
    private fun ad(
        id: String = "ad1",
        title: String = "Кафе «Юрта»",
        text: String = "Чай и выпечка для водителей",
        status: String = "draft",
        rejectReason: String = "",
        pkg: String = "",
        pkgTitle: String = "",
        budgetKop: Int = 0,
        periodDays: Int = 0,
        paid: Boolean = false,
    ) = MyAdDto(
        id = id, title = title, text = text, button = "", target = "",
        erid = "", status = status, rejectReason = rejectReason,
        pkg = pkg, pkgTitle = pkgTitle, budgetKop = budgetKop, periodDays = periodDays,
        placements = "", cities = "", paid = paid, submittedAt = null,
    )

    // --- CabinetMetric: метрика кабинета (значение сверху + подпись). Готовые String-параметры. ---

    @Test
    fun cabinetMetric_showsValueAndLabel() {
        composeRule.setContent { CabinetMetric(label = "Активные", value = "3") }
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithText("Активные").assertIsDisplayed()
    }

    @Test
    fun cabinetMetric_bashkirLabel_renders() {
        // Текст приходит готовым — компонент одинаково рисует любой язык.
        composeRule.setContent { CabinetMetric(label = "Рейтинг", value = "—") }
        composeRule.onNodeWithText("—").assertIsDisplayed()
        composeRule.onNodeWithText("Рейтинг").assertIsDisplayed()
    }

    // --- AdField: поле формы объявления (label + значение), правки уходят в onValueChange. ---

    @Test
    fun adField_showsLabelAndValue() {
        composeRule.setContent {
            AdField(label = "Заголовок", value = "Кафе «Юрта»", onValueChange = {})
        }
        composeRule.onNodeWithText("Заголовок").assertIsDisplayed()
        composeRule.onNodeWithText("Кафе «Юрта»").assertIsDisplayed()
    }

    @Test
    fun adField_typing_firesOnValueChange() {
        var typed = ""
        composeRule.setContent {
            AdField(label = "Заголовок", value = "", onValueChange = { typed = it })
        }
        composeRule.onNodeWithText("Заголовок").performTextInput("Пекарня")
        assertEquals("Пекарня", typed)
    }

    // --- MyAdCard: карточка своего объявления. Текст резолвится через appText → нужен провайдер. ---

    @Test
    fun myAdCard_draft_showsTitleTextAndActions() {
        // draft: заголовок, описание, бейдж «Черновик», кнопки «Изменить» + «На модерацию».
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MyAdCard(ad = ad(status = "draft"), submitting = false, onEdit = {}, onSubmit = {}, onPay = {})
            }
        }
        composeRule.onNodeWithText("Кафе «Юрта»").assertIsDisplayed()
        composeRule.onNodeWithText("Чай и выпечка для водителей").assertIsDisplayed()
        composeRule.onNodeWithText("Черновик").assertIsDisplayed()
        composeRule.onNodeWithText("Изменить").assertIsDisplayed()
        composeRule.onNodeWithText("На модерацию").assertIsDisplayed()
    }

    @Test
    fun myAdCard_draft_editClick_firesCallback() {
        var edited = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MyAdCard(ad = ad(status = "draft"), submitting = false, onEdit = { edited = true }, onSubmit = {}, onPay = {})
            }
        }
        composeRule.onNodeWithText("Изменить").performClick()
        assertTrue(edited)
    }

    @Test
    fun myAdCard_draft_submitDisabledWithoutPackage() {
        // «На модерацию» активна только когда выбран тариф (pkg не пуст).
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MyAdCard(ad = ad(status = "draft", pkg = ""), submitting = false, onEdit = {}, onSubmit = {}, onPay = {})
            }
        }
        composeRule.onNodeWithText("На модерацию").assertIsNotEnabled()
    }

    @Test
    fun myAdCard_draft_submitEnabledWithPackage_firesCallback() {
        var submitted = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MyAdCard(ad = ad(status = "draft", pkg = "week"), submitting = false, onEdit = {}, onSubmit = { submitted = true }, onPay = {})
            }
        }
        composeRule.onNodeWithText("На модерацию").assertIsEnabled()
        composeRule.onNodeWithText("На модерацию").performClick()
        assertTrue(submitted)
    }

    @Test
    fun myAdCard_activeUnpaid_showsPayButton_firesCallback() {
        // active + не оплачено: подсказка про оплату + кнопка «Оплатить размещение · N ₽».
        var paid = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MyAdCard(
                    ad = ad(status = "active", paid = false, budgetKop = 30000),
                    submitting = false, onEdit = {}, onSubmit = {}, onPay = { paid = true },
                )
            }
        }
        composeRule.onNodeWithText("Оплатить размещение · 300 ₽").assertIsDisplayed()
        composeRule.onNodeWithText("Оплатить размещение · 300 ₽").performClick()
        assertTrue(paid)
    }

    @Test
    fun myAdCard_activePaid_showsPaidNotice_bashkir() {
        // active + оплачено (башкирский): «Түләнде · иғлан күрһәтелә.»
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                MyAdCard(ad = ad(status = "active", paid = true), submitting = false, onEdit = {}, onSubmit = {}, onPay = {})
            }
        }
        composeRule.onNodeWithText("Түләнде · иғлан күрһәтелә.").assertIsDisplayed()
    }

    @Test
    fun myAdCard_rejected_showsReason() {
        // rejected: показываем причину отказа от модератора.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MyAdCard(
                    ad = ad(status = "rejected", rejectReason = "Нет фото товара"),
                    submitting = false, onEdit = {}, onSubmit = {}, onPay = {},
                )
            }
        }
        composeRule.onNodeWithText("Отклонено").assertIsDisplayed()
        composeRule.onNodeWithText("Причина отказа: Нет фото товара").assertIsDisplayed()
    }

    // --- AdsShowcase: витрина тарифов (когда своих объявлений ещё нет). appText → нужен провайдер. ---

    @Test
    fun adsShowcase_showsHeaderTariffsAndPackage() {
        val packages = listOf(
            AdPackageDto(code = "week", title = "Неделя", titleBa = "Аҙна", amountKop = 30000, periodDays = 7),
        )
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdsShowcase(packages = packages, modifier = Modifier, onCreate = {})
            }
        }
        composeRule.onNodeWithText("Реклама в Юлдаше").assertIsDisplayed()
        composeRule.onNodeWithText("Тарифы").assertIsDisplayed()
        composeRule.onNodeWithText("Неделя").assertIsDisplayed()
    }

    @Test
    fun adsShowcase_createButton_firesCallback() {
        var created = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdsShowcase(packages = emptyList(), modifier = Modifier, onCreate = { created = true })
            }
        }
        // Кнопка внизу списка — доскроллим до неё и жмём (список ленивый).
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Разместить рекламу"))
        composeRule.onNodeWithText("Разместить рекламу").performClick()
        assertTrue(created)
    }
}
