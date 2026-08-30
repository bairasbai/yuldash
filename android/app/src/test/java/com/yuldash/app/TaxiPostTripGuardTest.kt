package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Сторож утверждённой связки B (оценка сразу) + C (чек отдельно). */
class TaxiPostTripGuardTest {

    private fun source(name: String): String {
        var dir = File("").absoluteFile
        repeat(5) {
            val direct = File(dir, "app/src/main/java/com/yuldash/app/$name")
            if (direct.isFile) return direct.readText()
            val nested = File(dir, "android/app/src/main/java/com/yuldash/app/$name")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile ?: return ""
        }
        return ""
    }

    @Test
    fun `completed passenger sees rating first and opens receipt separately`() {
        val host = source("InstantOrderScreen.kt")
        val completed = source("TaxiCompletedScreen.kt")
        assertTrue(host.contains("TaxiPassengerCompletedScreen("))
        assertTrue(host.contains("openTaxiReceipt.value = o.id"))
        assertTrue(completed.contains("Как прошла поездка?"))
        assertTrue(completed.contains("Чек и детали"))
        assertTrue(completed.contains("ApiClient.rateInstantOrder(id, stars, tags)"))
        assertTrue(completed.contains("ApiClient.instantLostItem(id)"))
    }

    @Test
    fun `receipt keeps money route own rating and every recovery action`() {
        val receipt = source("TaxiReceiptScreen.kt")
        assertTrue(receipt.contains("TaxiReceiptDocument(r,"))
        assertTrue(receipt.contains("MobilityRouteTimeline("))
        assertTrue(receipt.contains("mutableIntStateOf(r.myStars)"))
        assertTrue(receipt.contains("ApiClient.rateInstantOrder(r.orderId, value, r.myRatingTags)"))
        assertTrue(receipt.contains("Поделиться чеком"))
        assertTrue(receipt.contains("Забыли вещь?"))
        assertTrue(receipt.contains("Проблема с поездкой"))
        assertTrue(receipt.contains("ReportCategoryDialog("))
        assertTrue(receipt.contains("FileIncidentDialog("))
        assertTrue(receipt.contains("PayOnlineCard("))
    }
}
