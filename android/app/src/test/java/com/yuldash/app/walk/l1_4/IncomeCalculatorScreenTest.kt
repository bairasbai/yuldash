package com.yuldash.app.walk.l1_4

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.yuldash.app.AppLanguage
import com.yuldash.app.IncomeCalculatorScreen
import com.yuldash.app.LocalAppLanguage
import com.yuldash.app.rub
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.junit.runner.RunWith

/**
 * Калькулятор дохода (IncomeCalculatorScreen.kt) — админ-инструмент Александра, деньги
 * считаются на клиенте без сервера.
 *
 * R1 — `rub()` берёт единственный денежный форматтер приложения и не ломает минус на
 * отрицательных суммах, кратных тысяче (латентный баг: сейчас ни один ползунок не даёт
 * отрицательный доход, но функция обязана форматировать деньги правильно сама по себе —
 * `fmtRub` и `kopToRub` защищены точно так же).
 * R2 — «честная экономика водителя»: чистыми = валовый − бензин − комиссия, с реальными
 * числами из ползунков, а не только на дефолтных значениях экрана.
 * R3 — доход водителя никогда не уходит в минус отображением: если бензин+комиссия
 * превышают валовый (жадные настройки), экран показывает 0 ₽, а не «−1 234 567 ₽».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IncomeCalculatorScreenTest {
    @get:Rule val compose = createComposeRule()

    // ---- R1: fmtRub-делегирующий rub() не ломает минус (прямой вызов — чистая функция) ----

    @Test
    fun rub_negativeMultipleOfThousand_keepsMinusGlued() {
        // Было (своя реализация реверсом строки): "- 100 000 ₽" — пробел после минуса.
        assertEquals("-100 000 ₽", rub(-100000.0))
        assertEquals("-100 ₽", rub(-100.0))
    }

    @Test
    fun rub_roundsToNearestWholeRuble() {
        assertEquals("1 251 ₽", rub(1250.6))
        assertEquals("0 ₽", rub(0.0))
    }

    // ---- R2/R3: настоящий экран, настоящие ползунки ----

    private fun openWithTaxiOn() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                IncomeCalculatorScreen(onBack = {})
            }
        }
        // Тумблер «Включить такси» — шестой элемент LazyColumn (после пяти ползунков выше);
        // в тестовом окне он не композится сам по себе, пока список не докрутили до него.
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(isToggleable())
        compose.onNode(isToggleable()).performClick()   // «Включить такси»
    }

    private fun setSlider(tag: String, value: Float) {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    }

    @Test
    fun driverEconomy_subtractsFuelAndCommissionFromGross() {
        openWithTaxiOn()
        setSlider("calc_ridesPerDay", 100f)
        setSlider("calc_avgCheck", 1000f)
        setSlider("calc_commissionPct", 10f)
        setSlider("calc_kmPerRide", 100f)
        setSlider("calc_fuelPer100", 10f)
        setSlider("calc_fuelPrice", 50f)

        // Валовый = 100 поездок × 1000 ₽ × 30 дней × 1 маршрут = 3 000 000 ₽.
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("3 000 000 ₽"))
        compose.onNodeWithText("3 000 000 ₽").assertIsDisplayed()
        // Бензин/поездку = 100км/100 × 10л × 50₽ = 500 ₽; за месяц = 500 × 100 × 30 = 1 500 000 ₽.
        // Комиссия = 3 000 000 × 10% = 300 000 ₽.
        // Чистыми = 3 000 000 − 1 500 000 − 300 000 = 1 200 000 ₽.
        compose.onNodeWithText("1 200 000 ₽").assertIsDisplayed()
        val breakdown = "бензин 1 500 000 ₽ + комиссия 10% (300 000 ₽)."
        compose.onAllNodes(hasScrollToNodeAction()).onFirst()
            .performScrollToNode(hasText(breakdown, substring = true))
        compose.onNodeWithText(breakdown, substring = true).assertIsDisplayed()
    }

    @Test
    fun driverEconomy_neverShowsNegativeNet_clampsToZero() {
        openWithTaxiOn()
        // Расход бензина сознательно жадный: валовый при копеечном чеке не покрывает даже бензин.
        setSlider("calc_ridesPerDay", 100f)
        setSlider("calc_avgCheck", 200f)       // минимум шкалы
        setSlider("calc_commissionPct", 10f)
        setSlider("calc_kmPerRide", 300f)      // максимум шкалы
        setSlider("calc_fuelPer100", 15f)      // максимум шкалы
        setSlider("calc_fuelPrice", 80f)       // максимум шкалы

        // Валовый = 100 × 200 × 30 = 600 000 ₽; бензин/мес = (300/100×15×80) × 100 × 30 = 10 800 000 ₽
        // — больше валового. Чистыми обязаны остаться 0 ₽, а не уйти в минус.
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("600 000 ₽"))
        compose.onNodeWithText("600 000 ₽").assertIsDisplayed()
        // Если бы coerceAtLeast(0.0) пропал, тут стояло бы "-10 260 000 ₽" — ровно та
        // поломка, которую проверяет мутация на это правило.
        compose.onNodeWithText("0 ₽").assertIsDisplayed()
    }
}
