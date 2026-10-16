package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Маршрут на экране поездки водителя (16.10.2026).
 *
 * Было: «Открыть поездку» в «Едут с тобой» кладёт поездку-заглушку с пустыми `from` / `to`. Пустая
 * строка — не `null`, поэтому запасной вариант `ride?.from ?: …` не срабатывал: на экране было
 * «— → —», а карточка погоды пропадала. Теперь пустой город уступает деталям брони, которые
 * экран и так загружает.
 */
class TripRouteLabelTest {

    @Test
    fun `заглушка поездки с пустым маршрутом берёт города из деталей брони`() {
        val stub = bookingLinkStubRide(77)
        assertEquals("", stub.from)
        assertEquals("", stub.to)
        assertEquals("Сибай → Уфа", tripRouteLabel(stub.from, stub.to, "Сибай", "Уфа"))
    }

    @Test
    fun `города самой поездки главнее деталей`() {
        assertEquals("Уфа → Сибай", tripRouteLabel("Уфа", "Сибай", "Мелеуз", "Стерлитамак"))
    }

    @Test
    fun `пробелы вместо города — тоже пусто`() {
        assertEquals("Сибай → Уфа", tripRouteLabel("  ", "", "Сибай", "Уфа"))
        assertEquals("Уфа → Уфа", tripRouteLabel(null, " ", "Уфа", "Уфа"))
    }

    @Test
    fun `город берётся по отдельности для начала и конца пути`() {
        assertEquals("Уфа → Сибай", tripRouteLabel("Уфа", "", "Мелеуз", "Сибай"))
    }

    @Test
    fun `пока неизвестен хотя бы один город подписи нет`() {
        assertNull(tripRouteLabel("", "", "", ""))
        assertNull(tripRouteLabel(null, "Уфа", null, null))
        assertNull(tripRouteLabel("Сибай", "", "", " "))
        assertNull(tripRouteLabel("", ""))
    }

    @Test
    fun `первый непустой город из списка, лишние пробелы срезаются`() {
        assertEquals("Сибай", tripRouteCity("", " Сибай ", "Уфа"))
        assertEquals("Уфа", tripRouteCity(null, "  ", "Уфа"))
        assertEquals("", tripRouteCity(null, "", " "))
        assertEquals("", tripRouteCity())
    }

    // ── Проводка: экран поездки считает город именно так ──

    private fun screenSource(): String {
        var dir = File("").absoluteFile
        repeat(4) {
            listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app").forEach { path ->
                val file = File(File(dir, path), "BookingActiveTripScreen.kt")
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile ?: return ""
        }
        return ""
    }

    @Test
    fun `экран поездки берёт город через tripRouteCity и для карты, и для погоды`() {
        val screen = screenSource()
        assertTrue("не нашёл BookingActiveTripScreen.kt", screen.isNotEmpty())
        assertTrue(
            "начало пути снова берётся мимо запасного варианта",
            screen.contains("val activeFromCity = tripRouteCity(ride?.from, detailsRoute.first, tripPass?.fromCity)"),
        )
        assertTrue(
            "конец пути снова берётся мимо запасного варианта",
            screen.contains("val activeToCity = tripRouteCity(ride?.to, detailsRoute.second, tripPass?.toCity)"),
        )
        assertTrue(
            "города из деталей брони больше не запоминаются",
            screen.contains("detailsRoute = d.fromCity to d.toCity"),
        )
        assertTrue(
            "погода снова берёт город из пустой заглушки",
            !screen.contains("val wFrom = ride?.from ?: tripPass?.fromCity"),
        )
    }
}
