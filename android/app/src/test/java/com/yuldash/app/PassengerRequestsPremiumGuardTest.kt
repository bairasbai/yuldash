package com.yuldash.app

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Структурный контракт утверждённого варианта A для водительской ленты заявок. */
class PassengerRequestsPremiumGuardTest {

    private val root = generateSequence(File(System.getProperty("user.dir").orEmpty())) { it.parentFile }
        .first { File(it, "src/main/java/com/yuldash/app/RidesRequestsChatScreens.kt").exists() }
    private val ui = File(root, "src/main/java/com/yuldash/app/RidesRequestsChatScreens.kt").readText()
    private val api = File(root, "src/main/java/com/yuldash/app/data/ApiClient.kt").readText()

    @Test
    fun routeFirstCard_keepsPremiumHierarchyAndRealFacts() {
        assertTrue(ui.contains("PremiumPassengerRequestCard"))
        assertTrue(ui.contains("RequestMetricCell"))
        assertTrue(ui.contains("По пути · крюк ≈"))
        assertTrue(ui.contains("По прямой"))
        assertTrue(ui.contains("Новый пассажир"))
        assertTrue(ui.contains("passengerVerified"))
        assertTrue(ui.contains("Modifier.appearIn"))
        assertFalse(ui.contains("Пассажиры ищут поездку. Откликнись — предложи цену и время."))
    }

    @Test
    fun offer_usesPremiumSheetAndThreeClearFields() {
        assertTrue(ui.contains("ModalBottomSheet("))
        assertTrue(ui.contains("RequestOfferSheet("))
        assertTrue(ui.contains("Твоя цена, ₽"))
        assertTrue(ui.contains("Когда сможешь выехать"))
        assertTrue(ui.contains("Комментарий пассажиру"))
        assertTrue(ui.contains("Отправить предложение"))
    }

    @Test
    fun api_doesNotInventMissingPremiumValues() {
        assertTrue(api.contains("val desiredAt: String = \"\""))
        assertTrue(api.contains("val maxPrice: Int? = null"))
        assertTrue(api.contains("val distanceKm: Double? = null"))
        assertTrue(api.contains("val passengerRating: Double? = null"))
        assertTrue(api.contains("val passengerVerified: Boolean = false"))
    }
}
