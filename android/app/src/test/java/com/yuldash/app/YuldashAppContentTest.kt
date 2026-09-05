package com.yuldash.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Content-композаблы корня навигации (YuldashApp.kt): чистые (без сети/state/viewModel/эффектов)
 * под-компоненты стали `internal` → покрываем их на JVM через Robolectric. Сам роутер `when(screen)`
 * и HomeScreen НЕ трогаем — тестируем только листовые куски: нижний таб-бар, шапку экрана и
 * элементы онбординга. Проверяем ядро продукта: двуязычие (RU/BA) и клики-колбэки.
 *
 * Заголовок класса — как в SecondaryScreensContentTest / ProfileScreenContentTest. appText(ru, ba)
 * резолвится через LocalAppLanguage → оборачиваем в CompositionLocalProvider. Анимации таб-бара и
 * bounceClick затухают сами → autoAdvance отключать не нужно.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YuldashAppContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- YuldashBottomBar: нижнее меню из 5 вкладок (Карта / Поездки / Заявка / Чат / Профиль) ---

    @Test
    fun bottomBar_showsAllTabLabels_russian() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashBottomBar(selectedTab = HomeTab.Map, onSelect = {})
            }
        }
        composeRule.onNodeWithText("Карта").assertIsDisplayed()
        composeRule.onNodeWithText("Поездки").assertIsDisplayed()
        composeRule.onNodeWithText("Заявка").assertIsDisplayed()
        composeRule.onNodeWithText("Чат").assertIsDisplayed()
        composeRule.onNodeWithText("Профиль").assertIsDisplayed()
    }

    @Test
    fun bottomBar_ridesTab_bashkirLabel() {
        // «Поездки» отличается по языку: башкирский → «Сәфәрҙәр». Остальные вкладки в баре одинаковы RU/BA.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                YuldashBottomBar(selectedTab = HomeTab.Map, onSelect = {})
            }
        }
        composeRule.onNodeWithText("Сәфәрҙәр").assertIsDisplayed()
    }

    @Test
    fun bottomBar_tapRidesTab_selectsRides() {
        var picked: HomeTab? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashBottomBar(selectedTab = HomeTab.Map, onSelect = { picked = it })
            }
        }
        assertNull(picked)
        composeRule.onNodeWithText("Поездки").performClick()
        assertEquals(HomeTab.Rides, picked)
    }

    @Test
    fun bottomBar_tapProfileTab_selectsProfile() {
        var picked: HomeTab? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashBottomBar(selectedTab = HomeTab.Map, onSelect = { picked = it })
            }
        }
        composeRule.onNodeWithText("Профиль").performClick()
        assertEquals(HomeTab.Profile, picked)
    }

    @Test
    fun bottomBar_tapChatTab_selectsChat() {
        var picked: HomeTab? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashBottomBar(selectedTab = HomeTab.Map, onSelect = { picked = it })
            }
        }
        composeRule.onNodeWithText("Чат").performClick()
        assertEquals(HomeTab.Chat, picked)
    }

    @Test
    fun bottomBar_tapRequestTab_selectsRequest() {
        var picked: HomeTab? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                YuldashBottomBar(selectedTab = HomeTab.Map, onSelect = { picked = it })
            }
        }
        composeRule.onNodeWithText("Заявка").performClick()
        assertEquals(HomeTab.Request, picked)
    }

    // --- ScreenTopBar: шапка вторичного экрана (заголовок + стрелка «Назад») ---

    @Test
    fun screenTopBar_showsTitle_andBackFiresCallback() {
        var backFired = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ScreenTopBar(title = "Безопасность", onBack = { backFired = true })
            }
        }
        composeRule.onNodeWithText("Безопасность").assertIsDisplayed()
        assertFalse(backFired)
        // Кнопка «Назад» помечена contentDescription = appText("Назад", "Артҡа").
        composeRule.onNodeWithContentDescription("Назад").performClick()
        assertTrue(backFired)
    }

    @Test
    fun screenTopBar_backContentDescription_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                ScreenTopBar(title = "Заголовок", onBack = {})
            }
        }
        // На башкирском contentDescription стрелки = «Кире».
        composeRule.onNodeWithContentDescription("Артҡа").assertIsDisplayed()
    }

    // --- OnboardingLangChip: чип переключения языка (текст + клик) ---

    @Test
    fun onboardingLangChip_showsText_andClickFires() {
        var fired = false
        composeRule.setContent { OnboardingLangChip(text = "БАШ", active = false, onClick = { fired = true }) }
        composeRule.onNodeWithText("БАШ").assertIsDisplayed()
        assertFalse(fired)
        composeRule.onNodeWithText("БАШ").performClick()
        assertTrue(fired)
    }

    @Test
    fun onboardingLangChip_active_stillShowsText() {
        // active=true меняет фон/цвет, текст рендерится как обычно.
        composeRule.setContent { OnboardingLangChip(text = "РУС", active = true, onClick = {}) }
        composeRule.onNodeWithText("РУС").assertIsDisplayed()
    }

    // --- OnboardingLangToggle: пара чипов РУС/БАШ, тап по языку → колбэк с этим языком ---

    @Test
    fun onboardingLangToggle_showsBothChips() {
        composeRule.setContent {
            OnboardingLangToggle(language = AppLanguage.Ru, onSelect = {})
        }
        composeRule.onNodeWithText("РУС").assertIsDisplayed()
        composeRule.onNodeWithText("БАШ").assertIsDisplayed()
    }

    @Test
    fun onboardingLangToggle_tapBashkir_selectsBa() {
        var picked: AppLanguage? = null
        composeRule.setContent {
            OnboardingLangToggle(language = AppLanguage.Ru, onSelect = { picked = it })
        }
        composeRule.onNodeWithText("БАШ").performClick()
        assertEquals(AppLanguage.Ba, picked)
    }

    @Test
    fun onboardingLangToggle_tapRussian_selectsRu() {
        var picked: AppLanguage? = null
        composeRule.setContent {
            OnboardingLangToggle(language = AppLanguage.Ba, onSelect = { picked = it })
        }
        composeRule.onNodeWithText("РУС").performClick()
        assertEquals(AppLanguage.Ru, picked)
    }

    // --- OnboardingRoleCard: карточка роли (готовые String title/body + клик) ---

    @Test
    fun onboardingRoleCard_showsTitleAndBody() {
        composeRule.setContent {
            OnboardingRoleCard(
                icon = Icons.Default.Person,
                title = "Я пассажир",
                body = "Ищу поездки и создаю заявки.",
                selected = false,
                onClick = {},
            )
        }
        composeRule.onNodeWithText("Я пассажир").assertIsDisplayed()
        composeRule.onNodeWithText("Ищу поездки и создаю заявки.").assertIsDisplayed()
    }

    @Test
    fun onboardingRoleCard_click_firesCallback() {
        var fired = false
        composeRule.setContent {
            OnboardingRoleCard(
                icon = Icons.Default.Person,
                title = "Я водитель",
                body = "Публикую поездки.",
                selected = true,
                onClick = { fired = true },
            )
        }
        assertFalse(fired)
        composeRule.onNodeWithText("Я водитель").performClick()
        assertTrue(fired)
    }

    // --- OnboardingRoleChooser: обе карточки ролей, тап по карточке → колбэк с ролью ---

    @Test
    fun onboardingRoleChooser_showsBothRoles_russian() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                OnboardingRoleChooser(selected = RideRole.Passenger, onSelect = {})
            }
        }
        composeRule.onNodeWithText("Я пассажир").assertIsDisplayed()
        composeRule.onNodeWithText("Я водитель").assertIsDisplayed()
    }

    @Test
    fun onboardingRoleChooser_showsBothRoles_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                OnboardingRoleChooser(selected = RideRole.Passenger, onSelect = {})
            }
        }
        composeRule.onNodeWithText("Мин пассажир").assertIsDisplayed()
        composeRule.onNodeWithText("Мин йөрөтөүсе").assertIsDisplayed()
    }

    @Test
    fun onboardingRoleChooser_tapDriver_selectsDriver() {
        var picked: RideRole? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                OnboardingRoleChooser(selected = RideRole.Passenger, onSelect = { picked = it })
            }
        }
        composeRule.onNodeWithText("Я водитель").performClick()
        assertEquals(RideRole.Driver, picked)
    }

    // --- OnboardingSafetyNote: примечание безопасности (готовый String) ---

    @Test
    fun onboardingSafetyNote_rendersText() {
        composeRule.setContent { OnboardingSafetyNote(text = "Так закрывается путь: заявка → отклик → поездка") }
        composeRule.onNodeWithText("Так закрывается путь: заявка → отклик → поездка").assertIsDisplayed()
    }

    @Test
    fun onboardingSafetyNote_bashkirText_renders() {
        // Текст приходит готовым — компонент одинаково рисует любой язык.
        composeRule.setContent { OnboardingSafetyNote(text = "Шулай юл ябыла: заявка → яуап → сәфәр") }
        composeRule.onNodeWithText("Шулай юл ябыла: заявка → яуап → сәфәр").assertIsDisplayed()
    }
}
