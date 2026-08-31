package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Сторож утверждённого водительского экрана отмены/неявки C + A. */
class TaxiDriverCancelledGuardTest {

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
    fun `cancelled driver opens dedicated route and finance screen`() {
        val host = source("InstantOrderScreen.kt")
        val cancelled = source("TaxiDriverCancelledScreen.kt")

        assertTrue(host.contains("current.status == \"cancelled\" -> TaxiDriverCancelledScreen("))
        assertTrue(cancelled.contains("MobilityRouteTimeline("))
        assertTrue(cancelled.contains("from = order.fromText"))
        assertTrue(cancelled.contains("to = order.toText"))
        assertTrue(cancelled.contains("order.cancelFeeKop > 0"))
        assertTrue(cancelled.contains("order.waitingStartedAt"))
        assertTrue(cancelled.contains("order.noShowAt"))
        assertTrue(cancelled.contains("taxiDriverPayMethodShortLabel(order.paymentMethod)"))
        assertTrue(cancelled.contains("Плата за подачу зафиксирована"))
        assertTrue(cancelled.contains("Деньги не списываются автоматически"))
    }

    @Test
    fun `recovery actions use real api and preserve both exits`() {
        val cancelled = source("TaxiDriverCancelledScreen.kt")

        assertTrue(cancelled.contains("ApiClient.reportUser("))
        assertTrue(cancelled.contains("FileIncidentDialog("))
        assertTrue(cancelled.contains("ApiClient.setOnline(false)"))
        assertTrue(cancelled.contains("Вернуться на линию"))
        assertTrue(cancelled.contains("Завершить смену"))
        assertTrue(cancelled.contains("Не получилось завершить смену"))
    }
}
