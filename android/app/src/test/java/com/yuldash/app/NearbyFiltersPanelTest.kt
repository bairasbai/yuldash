package com.yuldash.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.yuldash.app.data.FilterCity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * Ряд фильтров «Ближайших»: Откуда · Куда · Когда · Фильтры (16.10.2026).
 *
 * Условия переехали из ряда чипов в шторку — значит, их легко «забыть включёнными». Поэтому
 * проверяем главное: число выбранных видно на чипе, галочка правда меняет условия, пояснение
 * «только проверенные» показано, когда оно режет ленту, а кнопка шторки говорит, сколько поездок осталось.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NearbyFiltersPanelTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `чип показывает, сколько условий включено`() {
        compose.setContent { Row { NearbyConditionsChip(setOf("pets", "child"), {}, false, 5, Modifier) } }
        compose.onNodeWithText("Фильтры · 2").assertIsDisplayed()
    }

    @Test
    fun `без условий чип — просто «Фильтры»`() {
        compose.setContent { Row { NearbyConditionsChip(emptySet(), {}, false, 5, Modifier) } }
        compose.onNodeWithText("Фильтры").assertIsDisplayed()
    }

    @Test
    fun `шторка говорит, сколько поездок покажет`() {
        compose.setContent { Row { NearbyConditionsChip(setOf("child"), {}, false, 5, Modifier) } }
        compose.onNodeWithText("Фильтры · 1").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Показать 5 поездок").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Детское кресло").assertIsOn()
        compose.onNodeWithText("С животным").assertIsOff()
    }

    @Test
    fun `галочка добавляет и снимает условие`() {
        var chosen by mutableStateOf(setOf("pets"))   // состояние — чтобы шторка перерисовалась, как на экране
        compose.setContent { Column { NearbyConditionsList(chosen, { chosen = it }, verifiedOnly = false) } }
        compose.onNodeWithText("Детское кресло").performClick()
        assertEquals(setOf("pets", "child"), chosen)
        compose.onNodeWithText("С животным").performClick()
        assertEquals(setOf("child"), chosen)
    }

    @Test
    fun `все семь условий на месте`() {
        compose.setContent { Column { NearbyConditionsList(emptySet(), {}, verifiedOnly = false) } }
        listOf("Только женщины", "Детское кресло", "С животным", "Багаж", "Кондиционер", "Некурящий", "Тихая поездка")
            .forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        assertEquals(7, NEARBY_CONDITION_KEYS.size)
    }

    @Test
    fun `«только проверенные» объяснены, когда режут ленту`() {
        compose.setContent { Column { NearbyConditionsList(emptySet(), {}, verifiedOnly = true) } }
        compose.onNodeWithText("Показываем только проверенных водителей — так включено в «Безопасности».").assertIsDisplayed()
    }

    @Test
    fun `без «только проверенных» пояснения нет`() {
        compose.setContent { Column { NearbyConditionsList(emptySet(), {}, verifiedOnly = false) } }
        assertEquals(
            0,
            compose.onAllNodesWithText("Показываем только проверенных водителей — так включено в «Безопасности».")
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `«только женщины» поясняет, что это и женщины за рулём`() {
        compose.setContent { Column { NearbyConditionsList(setOf("women"), {}, verifiedOnly = false) } }
        compose.onNodeWithText("Женщины за рулём и поездки «только для женщин».").assertIsDisplayed()
    }

    @Test
    fun `день выбирается одним нажатием, «Все дни» снимает выбор`() {
        val today = LocalDate.of(2026, 10, 16)
        var picked: String? = "unset"
        compose.setContent { Column { NearbyDayOptions(null, today) { picked = it } } }
        compose.onNodeWithText("Все дни").assertIsSelected()
        compose.onNodeWithText("Завтра").performClick()
        assertEquals("2026-10-17", picked)
        compose.onNodeWithText("Все дни").performClick()
        assertEquals(null, picked)
    }

    @Test
    fun `чип «Когда» называет выбранный день`() {
        val tomorrow = LocalDate.now().plusDays(1).toString()
        compose.setContent { NearbyWhenChip(tomorrow, {}, Modifier) }
        compose.onNodeWithText("Когда · Завтра").assertIsDisplayed()
    }

    @Test
    fun `своя дата — отмечена и подписана днём и месяцем`() {
        val today = LocalDate.of(2026, 10, 16)
        compose.setContent { Column { NearbyDayOptions("2026-10-20", today) {} } }
        compose.onNodeWithText("Дата · 20.10").assertIsSelected()
        compose.onNodeWithText("Все дни").assertIsNotSelected()
    }

    @Test
    fun `без своей даты есть строка «Другая дата…»`() {
        compose.setContent { Column { NearbyDayOptions(null, LocalDate.of(2026, 10, 16)) {} } }
        compose.onNodeWithText("Другая дата…").assertIsNotSelected()
    }

    @Test
    fun `деревня из поиска подписана на языке человека`() {
        // Аскарово нет в топе городов с поездками — её нашли поиском по справочнику.
        val state = NearbyCityFilterState().apply {
            fromOptions = listOf(FilterCity("Баймак", "Баймаҡ"))
            found = listOf(FilterCity("Аскарово", "Асҡар"))
            from = setOf("Аскарово")
        }
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) { Row { NearbyCityChips(state, Modifier) } }
        }
        compose.onNodeWithText("Ҡайҙан · Асҡар").assertIsDisplayed()
    }

    @Test
    fun `найденная и снятая деревня уходит, когда шторку закрыли`() {
        // Приёмки PR148/PR151: нашёл «Кипчак-Аскарово», снял галочку, закрыл шторку — деревня так и висела
        // строкой в «Откуда» и появлялась в «Куда». Выбранная остаётся: по ней подписан чип.
        val state = NearbyCityFilterState().apply {
            fromOptions = listOf(FilterCity("Баймак", "Баймаҡ"))
            found = listOf(FilterCity("Кипчак-Аскарово", "Ҡыпсаҡ-Асҡар"), FilterCity("Аскарово", "Асҡар"))
            from = setOf("Аскарово")
        }
        compose.setContent { Row { NearbyCityChips(state, Modifier) } }
        compose.onNodeWithText("Откуда · Аскарово").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Кипчак-Аскарово").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Кипчак-Аскарово").assertIsOff()   // пока шторка открыта — строка есть, вернуть одной галочкой
        compose.onNodeWithText("Готово").performSemanticsAction(SemanticsActions.OnClick)
        // Шторка уходит вниз, и только потом убирается — ждём конца движения.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Кипчак-Аскарово").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf("Аскарово"), state.found.map { it.ru })
        compose.onNodeWithText("Откуда · Аскарово").assertIsDisplayed()
    }

    @Test
    fun `«Сбросить фильтры» снимает и найденные поиском — деревни нет ни в «Откуда», ни в «Куда»`() {
        // Приёмка PR154: нашёл «Кипчак-Аскарово», поездок нет, нажал «Сбросить фильтры» — деревня висела строкой в обеих шторках.
        val state = NearbyCityFilterState().apply {
            fromOptions = listOf(FilterCity("Баймак", "Баймаҡ"))
            found = listOf(FilterCity("Кипчак-Аскарово", "Ҡыпсаҡ-Асҡар"))
            from = setOf("Кипчак-Аскарово")
        }
        state.clear()
        assertEquals(emptySet<String>(), state.from)
        assertEquals(emptyList<FilterCity>(), state.found)
        compose.setContent { Row { NearbyCityChips(state, Modifier) } }
        for (chip in listOf("Откуда", "Куда")) {
            compose.onNodeWithText(chip).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Готово").fetchSemanticsNodes().isNotEmpty() }
            assertEquals("в «$chip» деревни нет", 0, compose.onAllNodesWithText("Кипчак-Аскарово").fetchSemanticsNodes().size)
            compose.onNodeWithText("Готово").performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Готово").fetchSemanticsNodes().isEmpty() }
        }
    }

    /** Кнопка нажата: в тот же миг шторка ещё на экране (уходит вниз), а дождались — её нет. */
    private fun assertLeavesWithMotion(button: String, marker: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(button).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()   // шторка доехала до места
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(button).performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(48)
        assertEquals("шторку выдернули без движения ухода", 1, compose.onAllNodesWithText(marker).fetchSemanticsNodes().size)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) { compose.onAllNodesWithText(marker).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `«Готово» в шторке города уводит её вниз, а не выдёргивает`() {
        val state = NearbyCityFilterState().apply { fromOptions = listOf(FilterCity("Баймак", "Баймаҡ")) }
        compose.setContent { Row { NearbyCityChips(state, Modifier) } }
        compose.onNodeWithText("Откуда").performClick()
        assertLeavesWithMotion("Готово", "Все города")
    }

    @Test
    fun `«Показать N поездок» уводит шторку условий вниз`() {
        compose.setContent { Row { NearbyConditionsChip(setOf("child"), {}, false, 5, Modifier) } }
        compose.onNodeWithText("Фильтры · 1").performClick()
        assertLeavesWithMotion("Показать 5 поездок", "Тихая поездка")
    }

    @Test
    fun `выбор дня применяется сразу, а шторка уходит вниз`() {
        var picked: String? = "unset"
        compose.setContent { NearbyWhenChip(null, { picked = it }, Modifier) }
        compose.onNodeWithText("Когда").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Завтра").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Завтра").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(48)
        assertEquals("день применился в тот же миг", java.time.LocalDate.now().plusDays(1).toString(), picked)
        assertEquals("шторку выдернули без движения ухода", 1, compose.onAllNodesWithText("Все дни").fetchSemanticsNodes().size)
        compose.mainClock.autoAdvance = true
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Все дни").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `после закрытия остаются найденные, выбранные хоть в «Откуда», хоть в «Куда»`() {
        val state = NearbyCityFilterState().apply {
            found = listOf(FilterCity("Кипчак-Аскарово", "Ҡыпсаҡ-Асҡар"), FilterCity("Темясово", "Темәс"), FilterCity("Аскарово", "Асҡар"))
            from = setOf("Аскарово")
            to = setOf("Темясово")
        }
        state.dropUnchosenFound()
        assertEquals(listOf("Темясово", "Аскарово"), state.found.map { it.ru })
    }

    @Test
    fun `«Сбросить» в шторке снимает только условия`() {
        var chosen: Set<String>? = null
        compose.setContent { Row { NearbyConditionsChip(setOf("pets", "child"), { chosen = it }, false, 5, Modifier) } }
        compose.onNodeWithText("Фильтры · 2").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Сбросить").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Сбросить").performClick()
        assertEquals(emptySet<String>(), chosen)
    }

    @Test
    fun `ноль поездок — кнопка честно говорит, что их нет`() {
        compose.setContent { Row { NearbyConditionsChip(setOf("child"), {}, false, 0, Modifier) } }
        compose.onNodeWithText("Фильтры · 1").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Нет поездок с такими условиями").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
