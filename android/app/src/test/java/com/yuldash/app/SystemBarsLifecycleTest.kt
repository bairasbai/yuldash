package com.yuldash.app

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.view.View
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.view.WindowCompat
import com.yuldash.app.ui.theme.AppSystemBars
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Проверяем настоящее Window и флаги Android, а не только MaterialTheme.colorScheme.
 * Полный MainActivity здесь не запускается: отложенный вызов reapply воспроизводит
 * завершение системной заставки, а её подключение проверяется отдельно на эмуляторе.
 * IntroSystemBars — тот же lifecycle-эффект, который монтирует настоящее интро;
 * его анимации и сетевые службы для этой проверки не нужны.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SystemBarsLifecycleTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var activity: ActivityController<Activity>
    private lateinit var bars: AppSystemBars
    private var previousOverride: Boolean? = null

    @Before
    fun prepareWindow() {
        previousOverride = ThemePrefs.darkOverride
        ThemePrefs.darkOverride = null
        activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        bars = AppSystemBars(activity.get().window)
    }

    @After
    fun releaseWindow() {
        ThemePrefs.darkOverride = previousOverride
        activity.pause().stop().destroy()
    }

    @Suppress("DEPRECATION")
    private fun assertAppearance(darkIcons: Boolean, statusDarkIcons: Boolean = false) {
        val window = activity.get().window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        assertEquals("Читаемые значки статус-бара", statusDarkIcons, controller.isAppearanceLightStatusBars)
        assertEquals("Читаемые значки навигации", darkIcons, controller.isAppearanceLightNavigationBars)

        // AndroidX Core 1.18 Impl30 с Window пишет именно decorView.systemUiVisibility.
        // InsetsController.systemBarsAppearance обновляется другим путём и в Robolectric
        // не отражает эти записи. Проверяем настоящие биты, которые продукт отправляет Android,
        // дополнительно к compat-геттерам; проверка пикселей остаётся на эмуляторе.
        val appearance = window.decorView.systemUiVisibility
        assertEquals(
            "Флаг Android для статус-бара",
            statusDarkIcons,
            appearance and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR != 0,
        )
        assertEquals(
            "Флаг Android для навигации",
            darkIcons,
            appearance and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR != 0,
        )
    }

    @Suppress("DEPRECATION")
    private fun overwriteAsLateLightWindow() {
        val window = activity.get().window
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }

    @Suppress("DEPRECATION")
    @Test
    fun chosenThemeSetsBothIconAppearancesAndBarBackgrounds() {
        bars.updateTheme(false)
        assertAppearance(darkIcons = true)
        assertEquals(0xFF0B6B3A.toInt(), activity.get().window.statusBarColor)
        assertEquals(0xFFF7F7F2.toInt(), activity.get().window.navigationBarColor)

        bars.updateTheme(true)
        assertAppearance(darkIcons = false)
        assertEquals(0xFF0B6B3A.toInt(), activity.get().window.statusBarColor)
        assertEquals(0xFF0F1613.toInt(), activity.get().window.navigationBarColor)
    }

    @Suppress("DEPRECATION")
    @Test
    fun reapplyRepairsWindowFlagsOverwrittenAfterComposition() {
        bars.updateTheme(true)
        overwriteAsLateLightWindow()
        assertAppearance(darkIcons = true, statusDarkIcons = true)

        bars.reapply()

        assertAppearance(darkIcons = false)
        assertEquals(0xFF0B6B3A.toInt(), activity.get().window.statusBarColor)
        assertEquals(0xFF0F1613.toInt(), activity.get().window.navigationBarColor)
    }

    @Test
    fun delayedCallbackUsesLatestThemeRatherThanThemeAtRegistration() {
        bars.updateTheme(false)
        val afterSplashRemoved = bars::reapply
        bars.updateTheme(true)
        overwriteAsLateLightWindow()

        afterSplashRemoved()

        assertAppearance(darkIcons = false)
        bars.updateTheme(false)
        afterSplashRemoved()
        assertAppearance(darkIcons = true)
    }

    @Test
    fun repeatedReleaseCannotRemoveAnotherActiveLightIconOwner() {
        bars.updateTheme(false)
        val releaseFirst = bars.withLightIcons()
        val releaseSecond = bars.withLightIcons()
        assertAppearance(darkIcons = false)

        releaseFirst()
        releaseFirst()
        assertAppearance(darkIcons = false)
        overwriteAsLateLightWindow()
        bars.reapply()
        assertAppearance(darkIcons = false)

        releaseSecond()
        assertAppearance(darkIcons = true)
        bars.updateTheme(true)
        releaseSecond()
        assertAppearance(darkIcons = false)
    }

    @Test
    fun finalOverrideReleaseRestoresThemeChosenWhileOverrideWasActive() {
        bars.updateTheme(true)
        val release = bars.withLightIcons()
        bars.updateTheme(false)
        assertAppearance(darkIcons = false)

        release()

        assertAppearance(darkIcons = true)
        bars.updateTheme(true)
        assertAppearance(darkIcons = false)
    }

    @Test
    fun manualAppThemeWinsWhenSystemThemeIsOpposite() {
        var systemDark by mutableStateOf(false)
        var observedSystemDark: Boolean? = null
        var observedAppDark: Boolean? = null
        ThemePrefs.darkOverride = true
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (systemDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            CompositionLocalProvider(LocalConfiguration provides config) {
                observedSystemDark = isSystemInDarkTheme()
                val dark = appIsDark()
                observedAppDark = dark
                YuldashTheme(darkTheme = dark, systemBars = bars) {}
            }
        }
        compose.runOnIdle {
            assertEquals(false, observedSystemDark)
            assertEquals(true, observedAppDark)
            assertAppearance(darkIcons = false)
            systemDark = true
            ThemePrefs.darkOverride = false
        }
        compose.runOnIdle {
            assertEquals(true, observedSystemDark)
            assertEquals(false, observedAppDark)
            assertAppearance(darkIcons = true)
        }
    }

    @Test
    fun leavingActualIntroEffectRestoresCurrentThemeIncludingThemeChangedDuringIntro() {
        var dark by mutableStateOf(true)
        var introMounted by mutableStateOf(true)
        compose.setContent {
            YuldashTheme(darkTheme = dark, systemBars = bars) {
                if (introMounted) IntroSystemBars()
            }
        }
        compose.runOnIdle {
            assertAppearance(darkIcons = false)
            introMounted = false
        }
        compose.runOnIdle {
            // Раньше onDispose интро всегда ставил true и ломал тёмную тему.
            assertAppearance(darkIcons = false)
            introMounted = true
        }
        compose.runOnIdle {
            dark = false
        }
        compose.runOnIdle {
            assertAppearance(darkIcons = false)
            introMounted = false
        }
        compose.runOnIdle { assertAppearance(darkIcons = true) }
    }

    @Test
    fun removingOneOfTwoMountedIntroEffectsKeepsOtherOverrideAlive() {
        var firstMounted by mutableStateOf(true)
        var secondMounted by mutableStateOf(true)
        compose.setContent {
            YuldashTheme(darkTheme = false, systemBars = bars) {
                key("first") { if (firstMounted) IntroSystemBars() }
                key("second") { if (secondMounted) IntroSystemBars() }
            }
        }
        compose.runOnIdle {
            assertAppearance(darkIcons = false)
            firstMounted = false
        }
        compose.runOnIdle {
            assertAppearance(darkIcons = false)
            secondMounted = false
        }
        compose.runOnIdle { assertAppearance(darkIcons = true) }
    }

    @Suppress("DEPRECATION")
    @Test
    fun statusForegroundAndBackgroundStayBrandedAcrossThemeAndOwners() {
        val window = activity.get().window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        for (dark in listOf(false, true, false)) {
            bars.updateTheme(dark)
            val first = bars.withLightIcons()
            val second = bars.withLightIcons()
            first()
            first()
            second()
            bars.reapply()
            assertFalse("Часы не должны менять оттенок при смене темы и уходе интро", controller.isAppearanceLightStatusBars)
            assertEquals(0xFF0B6B3A.toInt(), window.statusBarColor)
            assertEquals(!dark, controller.isAppearanceLightNavigationBars)
        }
    }

    @Test
    fun startingAndPostSplashThemesKeepWhiteStatusIconsOnBrand() {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/styles.xml"))
        val styles = root.getElementsByTagName("style")
        for (name in listOf("Theme.Yuldash", "Theme.Yuldash.Starting")) {
            val style = (0 until styles.length).map { styles.item(it) as org.w3c.dom.Element }
                .single { it.getAttribute("name") == name }
            val items = style.getElementsByTagName("item")
            val status = (0 until items.length).map { items.item(it) as org.w3c.dom.Element }
                .single { it.getAttribute("name") == "android:windowLightStatusBar" }
            assertEquals("$name: фон заставки и сцены используют белые часы", "false", status.textContent)
        }
    }
}
