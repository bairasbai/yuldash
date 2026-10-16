package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Запуск волнами (16.10.2026): форма «ищу попутку» не предлагает «Посылку» и «Груз», пока посылки
 * выключены. Раньше человек выбирал «Посылку», жал «Создать» и получал отказ сервера 403 «скоро».
 */
class RequestCategoriesGateTest {

    private val closed = FeatureSet(emptySet(), known = true, fetchedAtMs = 0L)
    private val parcelsOn = FeatureSet(setOf(Feature.Parcels), known = true, fetchedAtMs = 0L)

    private fun keys(features: FeatureSet) = requestCategories(features).map { it.first }

    @Test
    fun `посылки выключены — посылки и груза в списке нет`() {
        assertEquals(listOf("regular", "urgent", "kids"), keys(closed))
    }

    @Test
    fun `посылки включены — все пять категорий`() {
        assertEquals(listOf("regular", "urgent", "parcel", "cargo", "kids"), keys(parcelsOn))
    }

    @Test
    fun `ответа сервера нет — неизвестное считается выключенным`() {
        assertEquals(listOf("regular", "urgent", "kids"), keys(FeatureSet.UNKNOWN))
    }

    @Test
    fun `курьер или такси посылки не открывают`() {
        // Форма заявки привязана именно к флагу посылок — как гейты CreateRide и Booking.
        assertEquals(listOf("regular", "urgent", "kids"), keys(FeatureSet(setOf(Feature.Courier, Feature.Taxi), known = true, fetchedAtMs = 0L)))
    }

    @Test
    fun `оставшиеся категории двуязычны и не потеряли надписи`() {
        val closedList = requestCategories(closed).toMap()
        assertEquals("Обычная", closedList.getValue("regular").ru)
        assertEquals("Ғәҙәти", closedList.getValue("regular").ba)
        assertEquals("С детьми", closedList.getValue("kids").ru)
        requestCategories(parcelsOn).forEach { (key, label) ->
            assertTrue("категория $key без русской надписи", label.ru.isNotBlank())
            assertTrue("категория $key без башкирской надписи", label.ba.isNotBlank())
        }
    }

    @Test
    fun `форма заявки берёт список через requestCategories и сбрасывает закрытую категорию`() {
        val form = File("src/main/java/com/yuldash/app/AccessibilityScreens.kt").readText()
            .substringAfter("internal fun CreatePassengerRequestScreen(")
            .substringBefore("internal fun passengerRequestValid")
        assertTrue("список категорий формы не проходит через гейт посылок", form.contains("val categories = requestCategories(features)"))
        assertTrue("флаги должны быть живыми, а не снимком", form.contains("val features = rememberFeatures()"))
        assertTrue(
            "выбранная «Посылка» должна сбрасываться, если доставку закрыли",
            form.contains("LaunchedEffect(features) { if (rideCategoryClosed(category, features)) category = \"regular\" }"),
        )
    }
}
