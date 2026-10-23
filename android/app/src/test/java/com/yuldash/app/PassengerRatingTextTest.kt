package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Приёмка 23.10.2026 (P3): рейтинг пассажира у водителя записан одинаково — «4,8» — и в «Пассажирах»,
 * и в «Ждут подтверждения». Раньше в одном месте было «4,8», в другом «4.8».
 */
class PassengerRatingTextTest {
    @Test fun ratingUsesCommaWhateverThePhoneLanguage() {
        val saved = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.forLanguageTag("ru"), Locale.forLanguageTag("ba"), Locale.GERMANY)) {
                Locale.setDefault(locale)
                assertEquals("4,8", passengerRatingText(4.8))
                assertEquals("5,0", passengerRatingText(5.0))
                assertEquals("3,7", passengerRatingText(3.66))
            }
        } finally { Locale.setDefault(saved) }
    }

    @Test fun passengersAndPendingRequestsShareOneFormatter() {
        val root = requireNotNull(sourceRoot()) { "не нашёл исходники Android — тест ничего не проверяет" }
        val row = File(root, "DriverPassengerRow.kt").readText()
        val profile = File(root, "ProfileScreen.kt").readText()
        assertTrue("«Пассажиры»: рейтинг — через общую функцию", row.contains("Text(passengerRatingText(rating)"))
        val pending = profile.substringAfter("b.passengerRating?.let { r ->").substringBefore("}")
        assertTrue("«Ждут подтверждения»: рейтинг — через общую функцию", pending.contains("passengerRatingText(r)"))
        assertFalse("Рейтинг пассажира с точкой вместо запятой", pending.contains("\"%.1f\""))
    }

    /** Корень пакета — как в FileSizeRatchetTest: тест запускают и из android/, и из android/app/. */
    private fun sourceRoot(): File? {
        var dir = File("").absoluteFile
        repeat(4) {
            listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app").forEach { path ->
                val src = File(dir, path)
                if (src.isDirectory) return src
            }
            dir = dir.parentFile ?: return null
        }
        return null
    }
}
