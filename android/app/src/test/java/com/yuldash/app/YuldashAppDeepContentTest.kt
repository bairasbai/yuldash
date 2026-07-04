package com.yuldash.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Shield
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
 * Глубокое покрытие оболочки приложения (YuldashApp.kt): шелл онбординга и его листовые карточки.
 * НЕ дублирует YuldashAppContentTest (там — bottom bar, ScreenTopBar, lang-chip/toggle, RoleCard/Chooser,
 * SafetyNote). Здесь берём остальное: чистый OnboardingContent (вынесен из умного OnboardingScreen),
 * OnboardingFeatureCard / OnboardingHeroCard / OnboardingMiniTrust / OnboardingTrustStrip /
 * OnboardingIconBubble / OnboardingDots + чистые хелперы onboardingHeroIcon / onboardingSlides.
 *
 * Паттерн как в остальных Content-тестах: appText(ru, ba) резолвится через LocalAppLanguage →
 * оборачиваем в CompositionLocalProvider. Строки скопированы ДОСЛОВНО из onboardingSlides() исходника.
 * Анимации онбординга (onbAppear, dots) затухают сами → autoAdvance не отключаем.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")   // высокое окно: страница пейджера онбординга целиком в кадре
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YuldashAppDeepContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Первый слайд онбординга (только он композится в HorizontalPager при старте).
    private fun firstSlide() = onboardingSlides().first()

    // Последний слайд: на нём isLastPage=true → кнопка «Войти» и выбор роли. Пейджер из ОДНОГО слайда
    // делает первую же страницу последней → без свайпов (свайп с animateScrollToPage флаки в тесте).
    private fun lastSlideOnly() = listOf(onboardingSlides().last())

    // --- OnboardingContent: шелл онбординга, первая страница (RU) ---

    @Test
    fun onboardingContent_firstPage_showsTitleAndEyebrow_russian() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                OnboardingContent(
                    slides = onboardingSlides(),
                    language = AppLanguage.Ru,
                    onSelectLanguage = {},
                    onFinish = {},
                )
            }
        }
        // Заголовок и надзаголовок первого слайда — дословно из исходника.
        composeRule.onNodeWithText("Едешь с юлдашом, не с незнакомцем").assertIsDisplayed()
        composeRule.onNodeWithText("Дорога по Башкортостану").assertIsDisplayed()
        // Бренд-плашка в hero-карточке всегда на экране.
        composeRule.onNodeWithText("Юлдаш").assertIsDisplayed()
    }

    @Test
    fun onboardingContent_firstPage_title_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                OnboardingContent(
                    slides = onboardingSlides(),
                    language = AppLanguage.Ba,
                    onSelectLanguage = {},
                    onFinish = {},
                )
            }
        }
        composeRule.onNodeWithText("Ят кеше менән түгел, юлдаш менән бараһың").assertIsDisplayed()
    }

    @Test
    fun onboardingContent_firstPage_showsBodyText_russian() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                OnboardingContent(
                    slides = onboardingSlides(),
                    language = AppLanguage.Ru,
                    onSelectLanguage = {},
                    onFinish = {},
                )
            }
        }
        // Текст-описание первого слайда идёт сразу под заголовком → на экране без скролла.
        // (Фича-карточки ниже сгиба покрыты отдельно standalone-тестом OnboardingFeatureCard.)
        composeRule.onNodeWithText(
            "Поездки и заявки между своими: Баймак, Сибай, Уфа и другие привычные маршруты рядом."
        ).assertIsDisplayed()
    }

    @Test
    fun onboardingContent_langChips_bothVisible_andTapSwitches() {
        // Верхний переключатель языка (OnboardingLangToggle) внутри шелла: РУС/БАШ + колбэк выбора.
        var picked: AppLanguage? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                OnboardingContent(
                    slides = onboardingSlides(),
                    language = AppLanguage.Ru,
                    onSelectLanguage = { picked = it },
                    onFinish = {},
                )
            }
        }
        composeRule.onNodeWithText("РУС").assertIsDisplayed()
        composeRule.onNodeWithText("БАШ").assertIsDisplayed()
        composeRule.onNodeWithText("БАШ").performClick()
        assertEquals(AppLanguage.Ba, picked)
    }

    @Test
    fun onboardingContent_firstPage_nextAndSkipButtons_visible_russian() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                OnboardingContent(
                    slides = onboardingSlides(),
                    language = AppLanguage.Ru,
                    onSelectLanguage = {},
                    onFinish = {},
                )
            }
        }
        // Не последняя страница → главная кнопка «Далее», плюс «Пропустить». Кнопки «Назад» на 1-й нет.
        composeRule.onNodeWithText("Далее").assertIsDisplayed()
        composeRule.onNodeWithText("Пропустить").assertIsDisplayed()
    }

    @Test
    fun onboardingContent_firstPage_nextButton_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                OnboardingContent(
                    slides = onboardingSlides(),
                    language = AppLanguage.Ba,
                    onSelectLanguage = {},
                    onFinish = {},
                )
            }
        }
        // «Далее» → башкирский «Артабан»; «Пропустить» → «Үткәреп ебәреү».
        composeRule.onNodeWithText("Артабан").assertIsDisplayed()
        composeRule.onNodeWithText("Үткәреп ебәреү").assertIsDisplayed()
    }

    @Test
    fun onboardingContent_firstPage_skip_firesOnFinish() {
        var finishedRole: RideRole? = null
        var fired = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                OnboardingContent(
                    slides = onboardingSlides(),
                    language = AppLanguage.Ru,
                    onSelectLanguage = {},
                    onFinish = { fired = true; finishedRole = it },
                )
            }
        }
        assertFalse(fired)
        // «Пропустить» завершает онбординг с текущей ролью (по умолчанию — пассажир).
        composeRule.onNodeWithText("Пропустить").performClick()
        assertTrue(fired)
        assertEquals(RideRole.Passenger, finishedRole)
    }

    // --- OnboardingContent: последняя страница (пейджер из одного слайда) ---

    @Test
    fun onboardingContent_lastPage_showsLoginButton_andRoleChooser_russian() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                OnboardingContent(
                    slides = lastSlideOnly(),
                    language = AppLanguage.Ru,
                    onSelectLanguage = {},
                    onFinish = {},
                )
            }
        }
        // Последний слайд: заголовок «Начнём?» и главная кнопка «Войти через Telegram» (кнопка — вне
        // LazyColumn, внизу экрана). Карточки ролей у последнего слайда помещаются на экран без скролла
        // (items у слайда пусты → под заголовком сразу выбор роли). assertExists — без требования видимости.
        composeRule.onNodeWithText("Начнём?").assertIsDisplayed()
        composeRule.onNodeWithText("Войти через Telegram").assertIsDisplayed()
        composeRule.onNodeWithText("Я пассажир").assertExists()
        composeRule.onNodeWithText("Я водитель").assertExists()
    }

    @Test
    fun onboardingContent_lastPage_loginButton_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                OnboardingContent(
                    slides = lastSlideOnly(),
                    language = AppLanguage.Ba,
                    onSelectLanguage = {},
                    onFinish = {},
                )
            }
        }
        // «Войти через Telegram» → башкирский «Telegram аша инеү»; заголовок → «Башлайбыҙмы?».
        composeRule.onNodeWithText("Telegram аша инеү").assertIsDisplayed()
        composeRule.onNodeWithText("Башлайбыҙмы?").assertIsDisplayed()
    }

    @Test
    fun onboardingContent_lastPage_login_firesOnFinishAsPassengerByDefault() {
        var finishedRole: RideRole? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                OnboardingContent(
                    slides = lastSlideOnly(),
                    language = AppLanguage.Ru,
                    onSelectLanguage = {},
                    onFinish = { finishedRole = it },
                )
            }
        }
        assertNull(finishedRole)
        composeRule.onNodeWithText("Войти через Telegram").performClick()
        assertEquals(RideRole.Passenger, finishedRole)
    }

    @Test
    fun onboardingContent_lastPage_pickDriverThenLogin_firesOnFinishAsDriver() {
        var finishedRole: RideRole? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                OnboardingContent(
                    slides = lastSlideOnly(),
                    language = AppLanguage.Ru,
                    onSelectLanguage = {},
                    onFinish = { finishedRole = it },
                )
            }
        }
        // На последнем слайде карточки ролей помещаются на экран (items слайда пусты) → кликаем напрямую.
        composeRule.onNodeWithText("Я водитель").performClick()
        // Кнопка «Войти» вне списка (внизу экрана) — доступна без скролла.
        composeRule.onNodeWithText("Войти через Telegram").performClick()
        assertEquals(RideRole.Driver, finishedRole)
    }

    // --- OnboardingFeatureCard: карточка фичи (двуязычный заголовок + тело + порядковый номер) ---

    @Test
    fun onboardingFeatureCard_showsTitleAndBody_russian() {
        val item = firstSlide().items.first()
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                OnboardingFeatureCard(item = item, index = 1)
            }
        }
        composeRule.onNodeWithText("Нашёл маршрут").assertIsDisplayed()
        composeRule.onNodeWithText("Смотри ближайшие поездки или оставь заявку, если машины ещё нет.").assertIsDisplayed()
    }

    @Test
    fun onboardingFeatureCard_showsTitle_bashkir() {
        val item = firstSlide().items.first()
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                OnboardingFeatureCard(item = item, index = 1)
            }
        }
        composeRule.onNodeWithText("Маршрут таптың").assertIsDisplayed()
    }

    // --- OnboardingHeroCard: бренд-карточка слайда (плашка «Юлдаш» + eyebrow) ---

    @Test
    fun onboardingHeroCard_showsBrandAndEyebrow_russian() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                OnboardingHeroCard(slide = firstSlide())
            }
        }
        composeRule.onNodeWithText("Юлдаш").assertIsDisplayed()
        composeRule.onNodeWithText("Дорога по Башкортостану").assertIsDisplayed()
    }

    @Test
    fun onboardingHeroCard_eyebrow_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                OnboardingHeroCard(slide = firstSlide())
            }
        }
        composeRule.onNodeWithText("Башҡортостан буйлап юл").assertIsDisplayed()
    }

    // --- OnboardingMiniTrust: мини-плитка доверия (иконка + подпись) ---

    @Test
    fun onboardingMiniTrust_rendersLabel() {
        composeRule.setContent {
            OnboardingMiniTrust(icon = Icons.Default.Handshake, label = "Между своими")
        }
        composeRule.onNodeWithText("Между своими").assertIsDisplayed()
    }

    // --- OnboardingTrustStrip: три плитки доверия (двуязычные подписи с переносами строк) ---

    @Test
    fun onboardingTrustStrip_showsAllThree_russian() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                OnboardingTrustStrip()
            }
        }
        // Подписи содержат перенос строки в исходнике — сопоставляем ровно как есть.
        composeRule.onNodeWithText("Между\nсвоими").assertIsDisplayed()
        composeRule.onNodeWithText("Без\nкомиссии").assertIsDisplayed()
        composeRule.onNodeWithText("Весь\nБашкортостан").assertIsDisplayed()
    }

    @Test
    fun onboardingTrustStrip_showsAllThree_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                OnboardingTrustStrip()
            }
        }
        composeRule.onNodeWithText("Үҙ кеше\nараһында").assertIsDisplayed()
        composeRule.onNodeWithText("Комиссия\nюҡ").assertIsDisplayed()
        composeRule.onNodeWithText("Бөтә\nБашҡортостан").assertIsDisplayed()
    }

    // --- OnboardingIconBubble: кружок-иконка с опциональным бейджем-номером ---

    @Test
    fun onboardingIconBubble_withIndex_showsBadgeNumber() {
        composeRule.setContent {
            OnboardingIconBubble(icon = Icons.Default.NearMe, index = 3)
        }
        // index != null → в углу рисуется бейдж с номером шага.
        composeRule.onNodeWithText("3").assertIsDisplayed()
    }

    // --- OnboardingDots: индикатор страниц (рендерится без падения, любое количество) ---

    @Test
    fun onboardingDots_rendersWithoutCrash() {
        composeRule.setContent {
            OnboardingDots(count = 4, selected = 1)
        }
        // У точек нет текста/семантики — достаточно, что композиция построилась без падения.
        composeRule.waitForIdle()
    }

    // --- onboardingHeroIcon: чистая функция hero → иконка (без Compose) ---

    @Test
    fun onboardingHeroIcon_mapsEachHeroToDistinctIcon() {
        assertEquals(Icons.Default.NearMe, onboardingHeroIcon(OnboardingHero.Route))
        assertEquals(Icons.Default.Shield, onboardingHeroIcon(OnboardingHero.Security))
        assertEquals(Icons.Default.Route, onboardingHeroIcon(OnboardingHero.Steps))
        assertEquals(Icons.Default.RocketLaunch, onboardingHeroIcon(OnboardingHero.Start))
    }

    // --- onboardingSlides: чистые данные онбординга (структура, порядок hero, последний слайд) ---

    @Test
    fun onboardingSlides_hasFourSlides_withExpectedHeroOrder() {
        val slides = onboardingSlides()
        assertEquals(4, slides.size)
        assertEquals(OnboardingHero.Route, slides[0].hero)
        assertEquals(OnboardingHero.Security, slides[1].hero)
        assertEquals(OnboardingHero.Steps, slides[2].hero)
        assertEquals(OnboardingHero.Start, slides[3].hero)
    }

    @Test
    fun onboardingSlides_lastSlideHasNoFeatureItems() {
        // Последний слайд — экран входа: фича-карточек нет (items пуст), показывается выбор роли.
        assertTrue(onboardingSlides().last().items.isEmpty())
        assertEquals("Начнём?", onboardingSlides().last().titleRu)
    }

    @Test
    fun onboardingSlides_stepsSlideHasSafetyNote() {
        // Только слайд «Заявка не пропадает…» (Steps) несёт note — примечание про путь заявка→отклик→сәфәр.
        val steps = onboardingSlides()[2]
        assertEquals("Так закрывается путь: заявка → отклик → поездка", steps.noteRu)
        assertEquals("Шулай юл ябыла: заявка → яуап → сәфәр", steps.noteBa)
    }
}
