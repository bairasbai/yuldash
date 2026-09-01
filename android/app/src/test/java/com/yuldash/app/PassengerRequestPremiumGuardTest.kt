package com.yuldash.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Сторож утверждённого варианта B «быстро и симметрично». */
class PassengerRequestPremiumGuardTest {

    private fun source(): String {
        var dir = File("").absoluteFile
        repeat(6) {
            val direct = File(dir, "app/src/main/java/com/yuldash/app/AccessibilityScreens.kt")
            if (direct.isFile) return direct.readText()
            val nested = File(dir, "android/app/src/main/java/com/yuldash/app/AccessibilityScreens.kt")
            if (nested.isFile) return nested.readText()
            dir = dir.parentFile ?: return ""
        }
        return ""
    }

    @Test
    fun `option B keeps route then a symmetric two by two grid`() {
        val screen = source()
        val route = screen.indexOf("passenger_route_card")
        val topRow = screen.indexOf("passenger_metric_grid_top")
        val bottomRow = screen.indexOf("passenger_metric_grid_bottom")

        assertTrue(route >= 0)
        assertTrue(topRow > route)
        assertTrue(bottomRow > topRow)
        assertTrue(screen.contains("Modifier.weight(1f).height(metricHeight)"))
        assertTrue(screen.contains("LocalDensity.current.fontScale >= 1.2f"))
    }

    @Test
    fun `all real request controls and compact review remain reachable`() {
        val screen = source()
        assertTrue(screen.contains("RequestPublishBar("))
        assertTrue(screen.contains("passenger_submit_btn"))
        assertTrue(screen.contains("onBaggageChange(!baggage)"))
        assertTrue(screen.contains("onNonSmokingChange(!nonSmoking)"))
        assertTrue(screen.contains("onOnlyTrustedChange(!onlyTrusted)"))
        assertTrue(screen.contains("PrefToggleRow(R.drawable.yu_women_only"))
        assertTrue(screen.contains("PrefToggleRow(R.drawable.yu_child_seat"))
        assertTrue(screen.contains("PrefToggleRow(R.drawable.yu_pet"))
        assertTrue(screen.contains("PrefToggleRow(R.drawable.yu_accessible"))
        assertTrue(screen.contains("PrefToggleRow(R.drawable.yu_ac"))
        assertTrue(screen.contains("appText(\"Опубликовать заявку\", \"Заявканы баҫтырыу\")"))
    }

    @Test
    fun `premium screen uses Canon tokens without local color literals`() {
        val screen = source()
        val form = screen.substringAfter("internal fun CreatePassengerRequestContent(")
            .substringBefore("internal fun familyOrderValid")
        assertTrue(form.contains("CanonCardShape"))
        assertTrue(form.contains("CanonItemShape"))
        assertTrue(form.contains("CanonMotion.QUICK"))
        assertFalse(form.contains("Color(0x"))
    }
}
