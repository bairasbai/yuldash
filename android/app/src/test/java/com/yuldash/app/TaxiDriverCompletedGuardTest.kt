package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Сторож утверждённого водительского финала A «доход прежде всего». */
class TaxiDriverCompletedGuardTest {

    private fun source(name: String): String {
        var dir = File("").absoluteFile
        repeat(6) {
            val direct = File(dir, "app/src/main/java/com/yuldash/app/$name")
            if (direct.isFile) return direct.readText()
            val nested = File(dir, "android/app/src/main/java/com/yuldash/app/$name")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile ?: return ""
        }
        return ""
    }

    @Test
    fun `done driver opens dedicated income first screen`() {
        val host = source("InstantOrderScreen.kt")
        val completed = source("TaxiDriverCompletedScreen.kt")
        assertTrue(host.contains("current.status == \"done\" -> TaxiDriverCompletedScreen("))
        assertTrue(host.contains("onOpenReceipt = { NavSignals.openTaxiReceipt.value = current.id }"))
        assertTrue(completed.contains("Чистыми за поездку"))
        assertTrue(completed.contains("order.driverNetKop"))
        assertTrue(completed.contains("order.passengerPayKop"))
        assertTrue(completed.contains("order.driverFeeKop"))
        assertTrue(completed.contains("taxiDriverPayMethodShortLabel(order.paymentMethod)"))
    }

    @Test
    fun `rating tags receipt unpaid and real shift exit stay wired`() {
        val completed = source("TaxiDriverCompletedScreen.kt")
        assertTrue(completed.contains("ApiClient.rateInstantOrder(id, stars, tags)"))
        assertTrue(completed.contains("selectedTags.joinToString(\",\")"))
        assertTrue(completed.contains("TaxiDriverRatingTag(\"ontime\""))
        assertTrue(completed.contains("TaxiDriverRatingTag(\"unsafe\""))
        assertTrue(completed.contains("UnpaidReportButton(orderId = order.id)"))
        assertTrue(completed.contains("Подтвердить наличные, маршрут и помощь"))
        assertTrue(completed.contains("ApiClient.setOnline(false)"))
        assertTrue(completed.contains("Вернуться на линию"))
        assertTrue(completed.contains("Завершить смену"))
    }
}
