package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Приёмка 18.10.2026, находка 3: новичок вошёл с первого экрана (по ссылке на поездку), вводные
 * не видел — отметку «вводные пройдены» никто не поставил. После перезапуска приложение показывало
 * интро, а «Пропустить» вело на вход, хотя человек уже вошёл.
 */
class AppStartScreenTest {

    @Test fun `вошедший новичок после перезапуска попадает на главную, а не во вводные`() {
        assertEquals(Screen.Home, appStartScreen(onboardingCompleted = false, loggedIn = true, debug = false))
        assertEquals(Screen.Home, appStartScreen(onboardingCompleted = false, loggedIn = true, debug = true))
    }

    @Test fun `новичок без входа видит интро`() {
        assertEquals(Screen.Intro, appStartScreen(onboardingCompleted = false, loggedIn = false, debug = false))
        assertEquals(Screen.Intro, appStartScreen(onboardingCompleted = false, loggedIn = false, debug = true))
    }

    @Test fun `прошедший вводные без входа — на вход, в отладочной сборке — на карту`() {
        assertEquals(Screen.Login, appStartScreen(onboardingCompleted = true, loggedIn = false, debug = false))
        assertEquals(Screen.Home, appStartScreen(onboardingCompleted = true, loggedIn = false, debug = true))
        assertEquals(Screen.Home, appStartScreen(onboardingCompleted = true, loggedIn = true, debug = false))
    }

    @Test fun `успешный вход ставит отметку о вводных`() {
        val app = source("YuldashApp.kt")
        val start = app.indexOf("Screen.Login -> {")
        val end = app.indexOf("Screen.Home -> HomeRoute(", start)
        assertTrue("ветка входа не найдена: $start..$end", start >= 0 && end > start)
        assertTrue("onContinue входа должен звать prefs.markOnboardingCompleted()",
            app.substring(start, end).contains("prefs.markOnboardingCompleted()"))
    }

    private fun source(name: String): String {
        var dir = File("").absoluteFile
        repeat(6) {
            listOf("app/src/main/java/com/yuldash/app/$name", "android/app/src/main/java/com/yuldash/app/$name",
                "src/main/java/com/yuldash/app/$name").forEach { path ->
                val f = File(dir, path)
                if (f.isFile) return f.readText()
            }
            dir = dir.parentFile ?: return ""
        }
        return ""
    }
}
