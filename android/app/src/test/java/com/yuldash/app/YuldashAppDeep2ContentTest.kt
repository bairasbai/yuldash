package com.yuldash.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Второй слой глубокого покрытия оболочки приложения (YuldashApp.kt): чистый шелл главного экрана
 * `HomeShell` (вынесен из «умного» HomeScreen). Держит выбранную вкладку, рисует нижнее меню
 * (YuldashBottomBar) и переключает тело вкладки слотом `tabContent(tab, selectTab)`. Реальные экраны
 * вкладок (карта/сеть/ViewModel) в JVM-тест не тянем — подставляем крошечное фейковое тело на вкладку
 * и проверяем именно логику шелла: стартовая вкладка, тап по бару → смена тела, слот-колбэк selectTab
 * (внутри-табовая навигация, напр. Поездки→Чат), проброс onTabChange наверх.
 *
 * НЕ дублирует:
 *  - YuldashAppContentTest — тапы по самому YuldashBottomBar (Карта/Поездки/Заявка/Чат/Профиль),
 *    ScreenTopBar, lang-chip/toggle, RoleCard/Chooser, SafetyNote;
 *  - YuldashAppDeepContentTest — весь онбординг (OnboardingContent + листовые карточки, hero, dots,
 *    slides/heroIcon).
 *
 * Паттерн как в остальных Content-тестах: appText(ru, ba) резолвится через LocalAppLanguage → оборачиваем
 * в CompositionLocalProvider. Метки вкладок бара берём ДОСЛОВНО из YuldashBottomBar исходника. Высокое
 * окно в @Config → бар и тело вкладки целиком в кадре, скролл не нужен. Анимации таб-бара и смены вкладки
 * (AnimatedContent) затухают сами → autoAdvance не отключаем.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YuldashAppDeep2ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Фейковое тело вкладки: у каждой — свой маркерный текст, чтобы видеть, что рисует шелл сейчас.
     *  Тело Поездок несёт кнопку «→Чат», дёргающую selectTab(Chat) — проверяем внутри-табовую навигацию. */
    @Composable
    private fun tabBody(tab: HomeTab, selectTab: (HomeTab) -> Unit) {
        when (tab) {
            HomeTab.Map -> Text("BODY_MAP", Modifier.fillMaxSize())
            HomeTab.Rides -> Text(
                "BODY_RIDES_GO_CHAT",
                Modifier
                    .fillMaxSize()
                    .clickable { selectTab(HomeTab.Chat) }
            )
            HomeTab.Request -> Text("BODY_REQUEST", Modifier.fillMaxSize())
            HomeTab.Chat -> Text("BODY_CHAT", Modifier.fillMaxSize())
            HomeTab.Profile -> Text("BODY_PROFILE", Modifier.fillMaxSize())
        }
    }

    // --- Стартовая вкладка: шелл рисует тело именно initialTab ---

    @Test
    fun homeShell_startsOnInitialTab_map() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                HomeShell(initialTab = HomeTab.Map) { tab, selectTab -> tabBody(tab, selectTab) }
            }
        }
        composeRule.onNodeWithText("BODY_MAP").assertIsDisplayed()
        // Нижнее меню на месте (метка вкладки «Карта» из бара).
        composeRule.onNodeWithText("Карта").assertIsDisplayed()
    }

    @Test
    fun homeShell_startsOnInitialTab_profile() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                HomeShell(initialTab = HomeTab.Profile) { tab, selectTab -> tabBody(tab, selectTab) }
            }
        }
        // initialTab=Profile → шелл сразу показывает тело профиля, а не карту.
        composeRule.onNodeWithText("BODY_PROFILE").assertIsDisplayed()
    }

    // --- Тап по нижнему меню → шелл меняет тело вкладки ---

    @Test
    fun homeShell_tapRidesInBottomBar_swapsBodyToRides() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                HomeShell(initialTab = HomeTab.Map) { tab, selectTab -> tabBody(tab, selectTab) }
            }
        }
        composeRule.onNodeWithText("BODY_MAP").assertIsDisplayed()
        // Тап по «Поездки» в баре → тело меняется на Поездки.
        composeRule.onNodeWithText("Поездки").performClick()
        composeRule.onNodeWithText("BODY_RIDES_GO_CHAT").assertIsDisplayed()
    }

    @Test
    fun homeShell_tapProfileInBottomBar_swapsBodyToProfile() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                HomeShell(initialTab = HomeTab.Map) { tab, selectTab -> tabBody(tab, selectTab) }
            }
        }
        composeRule.onNodeWithText("Профиль").performClick()
        composeRule.onNodeWithText("BODY_PROFILE").assertIsDisplayed()
    }

    // --- Слот-колбэк selectTab: внутри-табовая навигация (Поездки → Чат) без нажатия бара ---

    @Test
    fun homeShell_selectTabFromBody_navigatesRidesToChat() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                HomeShell(initialTab = HomeTab.Rides) { tab, selectTab -> tabBody(tab, selectTab) }
            }
        }
        composeRule.onNodeWithText("BODY_RIDES_GO_CHAT").assertIsDisplayed()
        // Тело Поездок дёргает selectTab(Chat) — шелл переключает тело на Чат (как onMessage в HomeScreen).
        composeRule.onNodeWithText("BODY_RIDES_GO_CHAT").performClick()
        composeRule.onNodeWithText("BODY_CHAT").assertIsDisplayed()
    }

    // --- onTabChange: смена активной вкладки прокидывается наверх (для «Назад» с под-экранов) ---

    @Test
    fun homeShell_onTabChange_firesInitialTabOnFirstComposition() {
        var lastTab: HomeTab? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                HomeShell(
                    initialTab = HomeTab.Request,
                    onTabChange = { lastTab = it },
                ) { tab, selectTab -> tabBody(tab, selectTab) }
            }
        }
        // LaunchedEffect(selectedTab) стреляет сразу стартовой вкладкой.
        composeRule.waitForIdle()
        assertEquals(HomeTab.Request, lastTab)
    }

    @Test
    fun homeShell_onTabChange_firesNewTabAfterBottomBarTap() {
        var lastTab: HomeTab? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                HomeShell(
                    initialTab = HomeTab.Map,
                    onTabChange = { lastTab = it },
                ) { tab, selectTab -> tabBody(tab, selectTab) }
            }
        }
        composeRule.onNodeWithText("Чат").performClick()
        composeRule.waitForIdle()
        // После тапа по «Чат» наверх ушла новая активная вкладка.
        assertEquals(HomeTab.Chat, lastTab)
    }

    // --- Двуязычие бара внутри шелла: башкирская метка вкладки «Поездки» = «Сәфәрҙәр» ---

    @Test
    fun homeShell_bottomBarLabel_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                HomeShell(initialTab = HomeTab.Map) { tab, selectTab -> tabBody(tab, selectTab) }
            }
        }
        // Метки бара резолвятся через appText → на башкирском «Поездки» = «Сәфәрҙәр».
        composeRule.onNodeWithText("Сәфәрҙәр").assertIsDisplayed()
        composeRule.onNodeWithText("BODY_MAP").assertIsDisplayed()
    }

    // --- Тап по вкладке Заявка (маркер, что все 5 веток слота достижимы) ---

    @Test
    fun homeShell_tapRequestInBottomBar_swapsBodyToRequest() {
        var lastTab: HomeTab? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                HomeShell(
                    initialTab = HomeTab.Map,
                    onTabChange = { lastTab = it },
                ) { tab, selectTab -> tabBody(tab, selectTab) }
            }
        }
        assertNull(lastTab?.takeIf { it == HomeTab.Request })
        composeRule.onNodeWithText("Заявка").performClick()
        composeRule.onNodeWithText("BODY_REQUEST").assertIsDisplayed()
    }
}
