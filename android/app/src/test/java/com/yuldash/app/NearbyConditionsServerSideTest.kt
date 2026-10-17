package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Условия «Ближайших» режет сервер, а «Показать ещё» видна и при условиях (DESIGN023, 16.10.2026).
 *
 * Было: телефон применял условия («детское кресло», «с животными»…) к уже полученной порции из
 * пяти поездок, а «Показать ещё» при включённом условии пряталась. Поездка с креслом шестой в
 * списке превращалась в «поездок нет». Теперь условия уходят на сервер, тот считает `count` по
 * ним, и кнопка «Показать ещё» зависит только от этого счёта.
 */
class NearbyConditionsServerSideTest {

    private fun mapScreen(): String {
        var dir = File("").absoluteFile
        repeat(4) {
            listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app").forEach { path ->
                val file = File(File(dir, path), "MapScreen.kt")
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile ?: return ""
        }
        return ""
    }

    @Test
    fun `лента отдаёт условия серверу и перезапрашивает при их смене`() {
        val src = mapScreen()
        assertTrue("не нашёл MapScreen.kt", src.isNotEmpty())
        assertTrue(
            "условия снова режутся только на телефоне — подходящая поездка дальше порции пропадёт",
            src.contains("conditions = prefFilter, verifiedOnly = verifiedOnly"),
        )
        assertTrue(
            "смена условий не сбрасывает порцию и не перезапрашивает ленту",
            src.contains("userLatKey, userLngKey, dateFilter, prefFilter, cityFilter.from, cityFilter.to) { nearbyLimit = NEARBY_PAGE }"),
        )
    }

    @Test
    fun `показать ещё не прячется при включённых условиях`() {
        val src = mapScreen()
        assertTrue(
            "«Показать ещё» снова прячется при условиях — вернётся ложная «пустота»",
            !src.contains("prefFilter.isEmpty() && nearby.size < nearbyTotal"),
        )
        assertTrue("пропала кнопка «Показать ещё»", src.contains("if (nearby.size < nearbyTotal) {"))
    }

    @Test
    fun `пусто при фильтре — подсказка со сбросом, а не «поездок нет»`() {
        // Приёмка PR147: сервер вернул пусто из-за условия → экран писал «поездок нет» без пути назад.
        val src = mapScreen()
        assertTrue(
            "пусто при включённом фильтре снова показывает «поездок нет»",
            src.contains("""if (nearbyError) "error" else if (prefFilter.isNotEmpty() || cityFilter.active || dateFilter != null) "filtered" else "empty""""),
        )
        assertTrue("кнопка «Сбросить фильтры» не снимает города", src.contains("onClick = { prefFilter = emptySet(); cityFilter.clear(); dateFilter = null }"))
        assertTrue("список городов не обновляется вместе с лентой", src.contains("rememberNearbyCityFilter(nearbyReload)"))
    }

    @Test
    fun `города уходят на сервер, а радиус и маршрут карусели при них снимаются`() {
        val src = mapScreen()
        assertTrue("города не уходят на сервер", src.contains("fromCities = cityFilter.from, toCities = cityFilter.to"))
        assertTrue(
            "при выбранном городе остался радиус 50 км — «Куда · Сибай» из Уфы даст ложное «поездок нет»",
            src.contains("userLng != null && !cityFilter.active) NEARBY_RADIUS_KM"),
        )
        assertTrue("чипов городов нет в ряду фильтров", src.contains("NearbyCityChips(cityFilter, chipTouch)"))
        assertTrue("нет чипа «Когда»", src.contains("NearbyWhenChip(dateFilter, { dateFilter = it }, chipTouch)"))
        assertTrue(
            "чип «Фильтры» не получает условия, «только проверенные» или счёт поездок",
            src.contains("NearbyConditionsChip(prefFilter, { prefFilter = it }, verifiedOnly, nearbyTotal, chipTouch)"),
        )
        assertTrue(
            "ряд фильтров прячется, когда рядом пусто, — до города «Откуда» не добраться",
            src.contains("prefFilter.isNotEmpty() || cityFilter.offered) {"),
        )
    }

    @Test
    fun `сбой сети после смены фильтра не оставляет старый список`() {
        // Приёмки PR147/148/151: сменил день, условие или город, запрос упал — под новым чипом оставались
        // поездки прежнего поиска. Теперь лента помнит, для какого поиска пришёл список, и чужой убирает.
        val src = mapScreen()
        val searchLine = src.lines().firstOrNull { "val search = " in it }.orEmpty()
        assertTrue(
            "в поиск ленты входят не все выборы человека (маршрут, день, условия, города, «только проверенные»)",
            searchLine.contains("nearbySearch(focusFrom, focusTo, dateFilter, prefFilter, cityFilter.from, cityFilter.to, verifiedOnly)"),
        )
        // Решение лида 17.10.2026: позиция меняется сама, пока человек едет, — это не его выбор.
        assertFalse("позиция снова в поиске — человек в автобусе увидит «нет сети» вместо списка", "userLat" in searchLine || "userLng" in searchLine)
        assertTrue("лента не помнит, для какого поиска пришёл список", src.contains("nearbyError = false; nearbyFor = search }"))
        assertTrue(
            "сбой сети не сверяет поиск — старый список остаётся под новым фильтром",
            src.contains(".onFailure { if (nearbyDropsOnFailure(nearbyFor, search)) { nearby = emptyList(); nearbyTotal = 0 }; nearbyError = true }"),
        )
        assertTrue(
            "сдвиг позиции больше не обновляет ленту — список «рядом» устареет",
            src.contains("LaunchedEffect(focusFrom, focusTo, userLatKey, userLngKey, nearbyReload, nearbyLimit, dateFilter, prefFilter, cityFilter.from, cityFilter.to) {"),
        )
    }

    /** Поиск «Уфа → Сибай, кресло, без дня и городов» — как его собирает MapScreen. */
    private fun search(
        from: String? = null,
        to: String? = null,
        date: String? = null,
        conditions: Set<String> = setOf("child"),
        fromCities: Set<String> = emptySet(),
        toCities: Set<String> = emptySet(),
        verifiedOnly: Boolean = false,
    ) = nearbySearch(from, to, date, conditions, fromCities, toCities, verifiedOnly)

    @Test
    fun `сдвиг позиции при сбое держит список, смена дня — ошибка`() {
        val shown = search()
        // Человек едет в автобусе: позиция сдвинулась на километр, лента перезапросилась, сеть упала.
        // Позиции в поиске нет — это тот же поиск, список остаётся.
        assertFalse("сдвиг позиции — не новый поиск", nearbyDropsOnFailure(shown, search()))
        assertTrue("сменили день — старый список под «Когда · Завтра» неправда", nearbyDropsOnFailure(shown, search(date = "2026-10-18")))
    }

    @Test
    fun `тот же поиск при сбое держит список, выбор человека — убирает`() {
        val shown = search()
        assertTrue("ещё ничего не показано — ошибка, а не пустота", nearbyDropsOnFailure(null, shown))
        assertFalse("авто-обновление раз в ~25 с и «Показать ещё» — тот же поиск, список остаётся", nearbyDropsOnFailure(shown, search()))
        assertTrue("сменили условие", nearbyDropsOnFailure(shown, search(conditions = setOf("child", "pets"))))
        assertTrue("выбрали город «Откуда»", nearbyDropsOnFailure(shown, search(fromCities = setOf("Баймак"))))
        assertTrue("выбрали город «Куда»", nearbyDropsOnFailure(shown, search(toCities = setOf("Уфа"))))
        assertTrue("выбрали маршрут на карусели", nearbyDropsOnFailure(shown, search(from = "Сибай", to = "Уфа")))
        assertTrue("включили «только проверенные»", nearbyDropsOnFailure(shown, search(verifiedOnly = true)))
    }

    @Test
    fun `подпись чипа города`() {
        assertEquals("Откуда", cityChipLabel("Откуда", emptyList()))
        assertEquals("Откуда · Баймак", cityChipLabel("Откуда", listOf("Баймак")))
        assertEquals("Откуда · Баймак +2", cityChipLabel("Откуда", listOf("Баймак", "Сибай", "Учалы")))
    }

    @Test
    fun `города перечитываются не чаще раза в 2 минуты, а после сбоя — сразу`() {
        assertTrue("ни разу не пришли — грузить", citiesStale(0L, 5_000L))
        assertTrue("через 25 с после загрузки — не грузить", !citiesStale(10_000L, 35_000L))
        assertTrue("через 2 минуты — грузить снова", citiesStale(10_000L, 10_000L + CITIES_REFRESH_MS))
    }
}
