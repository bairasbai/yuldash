package com.yuldash.app

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.RideDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «Только проверенные» по умолчанию и «Есть N поездок непроверенных водителей — показать?»
 * (решение Александра, 17.10.2026).
 *
 * Публиковать поездку может каждый, а пассажир по умолчанию видит только проверенных водителей.
 * Проверяем три обещания: (1) кто не выбирал — фильтр включён, кто сам выключил — так и осталось;
 * (2) пустая лента проверенных не врёт «поездок нет», если непроверенные на этот поиск есть;
 * (3) «Показать» действует только на этот поиск и не трогает настройку «Безопасности».
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NearbyVerifiedOnlyTest {

    @get:Rule
    val compose = createComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clean() {
        ctx.getSharedPreferences("yuldash_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ---------------------------------------------------------------- настройка по умолчанию

    @Test
    fun `кто не выбирал — «только проверенные» включены`() {
        assertTrue("новый человек должен видеть только проверенных водителей", AppPrefs.verifiedOnly(ctx))
    }

    @Test
    fun `кто сам выключил в «Безопасности» — так и остаётся`() {
        AppPrefs.setVerifiedOnly(ctx, false)
        assertFalse("явный выбор человека перезаписан значением по умолчанию", AppPrefs.verifiedOnly(ctx))
        AppPrefs.setVerifiedOnly(ctx, true)
        assertTrue(AppPrefs.verifiedOnly(ctx))
    }

    // ---------------------------------------------------------------- «Показать» — на один поиск

    private fun search(date: String? = null, conditions: Set<String> = emptySet()) =
        nearbySearch("Баймак", "Уфа", date, conditions, emptySet(), emptySet(), verifiedOnly = true)

    @Test
    fun `«Показать» снимает фильтр только для того поиска, где нажали`() {
        val shown = search()
        assertTrue("до нажатия режем", nearbyVerifiedOnly(saved = true, revealedFor = null, search = shown))
        assertFalse("нажали «Показать» — этот поиск без фильтра", nearbyVerifiedOnly(saved = true, revealedFor = shown, search = search()))
        assertTrue("сменили день — снова только проверенные", nearbyVerifiedOnly(saved = true, revealedFor = shown, search = search(date = "2026-10-18")))
        assertTrue("добавили условие — снова только проверенные", nearbyVerifiedOnly(saved = true, revealedFor = shown, search = search(conditions = setOf("child"))))
        assertFalse("выключено в «Безопасности» — не режем вовсе", nearbyVerifiedOnly(saved = false, revealedFor = null, search = shown))
    }

    @Test
    fun `состояние ленты помнит «Показать», но не трогает сохранённую настройку`() {
        val state = NearbyVerifiedState(saved = true)
        assertTrue(state.verifiedOnly(search()))
        state.reveal(search())
        assertFalse(state.verifiedOnly(search()))
        assertTrue("сохранённая настройка не изменилась", state.saved)
        assertTrue("и на диске тоже", AppPrefs.verifiedOnly(ctx))
    }

    @Test
    fun `сменил поиск — «Показать» забыто, тот же поиск — помнится`() {
        val state = NearbyVerifiedState(saved = true)
        state.reveal(search(date = "2026-10-18"))
        state.forgetUnless(search(date = "2026-10-18"))
        assertFalse("тот же поиск (авто-обновление) — непроверенные остаются", state.verifiedOnly(search(date = "2026-10-18")))
        state.forgetUnless(search())
        assertEquals("ушёл на «Все дни» — нажатие забыто", null, state.revealedFor)
        assertTrue("вернулся на «Завтра» — снова только проверенные", state.verifiedOnly(search(date = "2026-10-18")))
    }

    @Test
    fun `Завтра, «Показать», Все дни, Завтра — фильтр снова режет, экран забывает нажатие сам`() {
        // Приёмка PR167 (живой сайт): после такого круга лента снова шла без фильтра — без карточки и нажатия.
        val state = NearbyVerifiedState(saved = true)
        var current by mutableStateOf<Any>(search(date = "2026-10-18"))
        var effective = true
        compose.setContent { effective = state.verifiedOnlyFor(current) }
        compose.runOnIdle { state.reveal(current) }
        compose.waitForIdle()
        assertFalse("«Показать» на «Завтра»", effective)
        compose.runOnIdle { current = search() }
        compose.waitForIdle()
        assertTrue("«Все дни» — снова только проверенные", effective)
        compose.runOnIdle { current = search(date = "2026-10-18") }
        compose.waitForIdle()
        assertTrue("вернулся на «Завтра» — нажатие не воскресло", effective)
        assertEquals(null, state.revealedFor)
    }

    @Test
    fun `подсказка — только когда проверенных нет, а спрятанные есть`() {
        assertTrue(nearbyUnverifiedHint(fetched = 0, error = false, verifiedOnly = true, hidden = 2))
        assertFalse("сервер не насчитал спрятанных (или старый сервер) — обычная пустота", nearbyUnverifiedHint(0, false, true, 0))
        assertFalse("сбой сети — «Не удалось загрузить», а не подсказка", nearbyUnverifiedHint(0, true, true, 2))
        assertFalse("проверенные есть — показываем их", nearbyUnverifiedHint(3, false, true, 2))
        assertFalse("уже нажали «Показать» — фильтр не режет", nearbyUnverifiedHint(0, false, false, 2))
    }

    // ---------------------------------------------------------------- карточка-подсказка

    @Test
    fun `пустая лента предлагает показать непроверенных, кнопка зовёт «Показать»`() {
        var shown = 0
        compose.setContent { Column { NearbyUnverifiedCard(hidden = 3, onShow = { shown++ }) } }
        compose.onNodeWithText("Проверенных водителей пока нет").assertIsDisplayed()
        compose.onNodeWithText("Есть 3 поездки непроверенных водителей — показать?").assertIsDisplayed()
        compose.onNodeWithText("Только для этого поиска — настройка «Безопасности» не изменится.").assertIsDisplayed()
        compose.onNodeWithText("Показать").performClick()
        assertEquals(1, shown)
        assertEquals("без других фильтров сбрасывать нечего", 0, compose.onAllNodesWithText("Сбросить фильтры").fetchSemanticsNodes().size)
    }

    @Test
    fun `при других фильтрах рядом есть «Сбросить фильтры»`() {
        var reset = 0
        compose.setContent { Column { NearbyUnverifiedCard(hidden = 1, onShow = {}, onReset = { reset++ }) } }
        compose.onNodeWithText("Есть 1 поездка непроверенных водителей — показать?").assertIsDisplayed()
        compose.onNodeWithText("Сбросить фильтры").performClick()
        assertEquals(1, reset)
    }

    @Test
    fun `русский склоняет только «поездку», «водителей» — всегда множественное`() {
        // Приёмка PR167: «Есть 21 поездка непроверенного водителя» читалось как один водитель.
        val texts = mutableMapOf<Int, String>()
        compose.setContent { listOf(1, 2, 5, 11, 21, 31).forEach { texts[it] = unverifiedRidesText(it) } }
        compose.waitForIdle()
        assertEquals("Есть 1 поездка непроверенных водителей — показать?", texts[1])
        assertEquals("Есть 2 поездки непроверенных водителей — показать?", texts[2])
        assertEquals("Есть 5 поездок непроверенных водителей — показать?", texts[5])
        assertEquals("Есть 11 поездок непроверенных водителей — показать?", texts[11])
        assertEquals("Есть 21 поездка непроверенных водителей — показать?", texts[21])
        assertEquals("Есть 31 поездка непроверенных водителей — показать?", texts[31])
    }

    @Test
    fun `башкирский счётное не склоняет — одна форма на любое число`() {
        val texts = mutableMapOf<Int, String>()
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                listOf(1, 21, 5).forEach { texts[it] = unverifiedRidesText(it) }
            }
        }
        compose.waitForIdle()
        listOf(1, 21, 5).forEach { assertEquals("Тикшерелмәгән йөрөтөүселәрҙең $it сәфәре бар — күрһәтәйекме?", texts[it]) }
    }

    @Test
    fun `по-башкирски — те же смыслы`() {
        compose.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                Column { NearbyUnverifiedCard(hidden = 2, onShow = {}, onReset = {}) }
            }
        }
        compose.onNodeWithText("Тикшерелгән йөрөтөүселәр әлегә юҡ").assertIsDisplayed()
        compose.onNodeWithText("Тикшерелмәгән йөрөтөүселәрҙең 2 сәфәре бар — күрһәтәйекме?").assertIsDisplayed()
        compose.onNodeWithText("Күрһәтергә").assertIsDisplayed()
        compose.onNodeWithText("Тик ошо эҙләү өсөн — «Хәүефһеҙлек» көйләүе үҙгәрмәй.").assertIsDisplayed()
        compose.onNodeWithText("Фильтрҙы бушат").assertIsDisplayed()
    }

    @Test
    fun `после «Показать» пояснение «только проверенных» в шторке не врёт`() {
        // Шторка получает действующий фильтр (verifiedOnly ленты), а не сохранённый: показали непроверенных —
        // строки «Показываем только проверенных водителей» нет.
        val state = NearbyVerifiedState(saved = true)
        state.reveal(search())
        val effective = state.verifiedOnly(search())
        compose.setContent { Column { NearbyConditionsList(emptySet(), {}, verifiedOnly = effective) } }
        assertEquals(
            0,
            compose.onAllNodesWithText("Показываем только проверенных водителей — так включено в «Безопасности».").fetchSemanticsNodes().size,
        )
    }

    // ---------------------------------------------------------------- подстраховка на старый сервер

    private fun ride(id: Int, verified: Boolean, childSeat: Boolean = false) = RideDto(
        id = id, fromCity = "Баймак", toCity = "Уфа", departAt = "2030-01-02T10:00:00", seatsTotal = 3, seatsLeft = 3,
        price = 500, category = "regular", driverName = "Водитель $id", driverRating = 5.0, driverVerified = verified,
        driverCar = "", childSeat = childSeat,
    )

    @Test
    fun `старый сервер прислал всех — телефон сам прячет непроверенных`() {
        val all = listOf(ride(1, verified = true), ride(2, verified = false, childSeat = true), ride(3, verified = true, childSeat = true))
        assertEquals(listOf(1, 3), nearbyShown(all, emptySet(), verifiedOnly = true).map { it.id })
        assertEquals(listOf(3), nearbyShown(all, setOf("child"), verifiedOnly = true).map { it.id })
        assertEquals("после «Показать» — все", listOf(1, 2, 3), nearbyShown(all, emptySet(), verifiedOnly = false).map { it.id })
        assertEquals(listOf(2, 3), nearbyShown(all, setOf("child"), verifiedOnly = false).map { it.id })
    }
}
