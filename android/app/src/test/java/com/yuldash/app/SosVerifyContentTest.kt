package com.yuldash.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Verified
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Content-композаблы экранов SOS / «Проверка водителя»: чистые (без сети/state/эффектов)
 * под-компоненты стали `internal` → покрываем на JVM через Robolectric (без эмулятора).
 *
 * StatusBanner / DocumentRow берут готовые String → тестируем напрямую. SubmitErrorBanner /
 * UploadTile / DriverReasonBanner зовут `appText` внутри → оборачиваем в CompositionLocalProvider
 * с нужным языком. Тексты сверены 1-в-1 с SosVerifyScreens.kt.
 *
 * Заголовок класса — как в RobolectricSmokeTest / SecondaryScreensContentTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SosVerifyContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- StatusBanner: иконка + заголовок + подзаголовок (готовые String) ---

    @Test
    fun statusBanner_showsTitleAndSubtitle() {
        composeRule.setContent {
            StatusBanner(
                icon = Icons.Default.Verified,
                title = "Профиль подтверждён",
                sub = "Вам доверяют — значок «Проверен» виден пассажирам.",
                bg = CanonMint,
                fg = CanonGreen2,
            )
        }
        composeRule.onNodeWithText("Профиль подтверждён").assertIsDisplayed()
        composeRule.onNodeWithText("Вам доверяют — значок «Проверен» виден пассажирам.").assertIsDisplayed()
    }

    @Test
    fun statusBanner_bashkirText_renders() {
        // Текст приходит готовым — компонент одинаково рисует любой язык.
        composeRule.setContent {
            StatusBanner(
                icon = Icons.Default.Schedule,
                title = "Тикшереүҙә",
                sub = "Ғәҙәттә әҙ ваҡыт ала. Һөҙөмтә тураһында хәбәр итәбеҙ.",
                bg = CanonMint,
                fg = CanonGreen2,
            )
        }
        composeRule.onNodeWithText("Тикшереүҙә").assertIsDisplayed()
    }

    // --- DocumentRow: строка документа (иконка + заголовок + статус, готовые String) ---

    @Test
    fun documentRow_showsTitleAndStatus() {
        composeRule.setContent {
            DocumentRow(
                icon = Icons.Default.Verified,
                title = "Водительские права",
                status = "Загружено",
                loaded = true,
            )
        }
        composeRule.onNodeWithText("Водительские права").assertIsDisplayed()
        composeRule.onNodeWithText("Загружено").assertIsDisplayed()
    }

    // --- SubmitErrorBanner: инлайн-ошибка отправки (текст через appText) ---

    @Test
    fun submitErrorBanner_russian_showsErrorText() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                SubmitErrorBanner()
            }
        }
        composeRule.onNodeWithText("Не отправилось").assertIsDisplayed()
        composeRule.onNodeWithText("Проверь интернет и нажми «Отправить на проверку» ещё раз.").assertIsDisplayed()
    }

    @Test
    fun submitErrorBanner_bashkir_showsErrorText() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                SubmitErrorBanner()
            }
        }
        composeRule.onNodeWithText("Ебәрелмәне").assertIsDisplayed()
    }

    // --- UploadTile: плитка загрузки фото (заголовок + статус + колбэк + спиннер) ---

    @Test
    fun uploadTile_notDone_showsPrompt() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                UploadTile(title = "Фото автомобиля", done = false, loading = false) {}
            }
        }
        composeRule.onNodeWithText("Фото автомобиля").assertIsDisplayed()
        composeRule.onNodeWithText("Нажмите, чтобы выбрать фото").assertIsDisplayed()
    }

    @Test
    fun uploadTile_done_showsLoadedStatus() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                UploadTile(title = "Фото прав", done = true, loading = false) {}
            }
        }
        composeRule.onNodeWithText("Загружено").assertIsDisplayed()
    }

    @Test
    fun uploadTile_loading_showsSpinnerAndText() {
        // loading=true рисует бесконечный CircularProgressIndicator → останавливаем часы,
        // иначе бесконечная анимация зависает waitForIdle.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                UploadTile(title = "Фото прав", done = false, loading = true) {}
            }
        }
        composeRule.onNodeWithText("Загрузка…").assertIsDisplayed()
    }

    @Test
    fun uploadTile_click_firesCallback() {
        var fired = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                UploadTile(title = "Фото прав", done = false, loading = false) { fired = true }
            }
        }
        assertFalse(fired)
        composeRule.onNodeWithText("Фото прав").performClick()
        assertTrue(fired)
    }

    // --- DriverReasonBanner: причина отказа/правки для водителя (3 String, ветвление) ---

    @Test
    fun driverReasonBanner_rejected_showsWhyRejectedTitleAndFallbackAdvice() {
        // rejected + пустой autocheckData → показываем баннер, заголовок «Почему отклонили»
        // и запасной совет по качеству фото (ветка isEmpty()).
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                DriverReasonBanner(docsStatus = "rejected", autocheckResult = "", autocheckData = "")
            }
        }
        composeRule.onNodeWithText("Почему отклонили").assertIsDisplayed()
        composeRule.onNodeWithText("Сделай фото прав чётким: хорошо освещено, без бликов, номер и срок читаются.").assertIsDisplayed()
    }

    @Test
    fun driverReasonBanner_expiredReason_showsExpiredLine() {
        // Конкретный код причины из JSON → соответствующая понятная строка.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                DriverReasonBanner(
                    docsStatus = "rejected",
                    autocheckResult = "reject",
                    autocheckData = """{"reasons":["license_expired"]}""",
                )
            }
        }
        composeRule.onNodeWithText("Похоже, срок действия прав истёк.").assertIsDisplayed()
    }

    @Test
    fun driverReasonBanner_needsHuman_showsManualCheckTitle() {
        // Не отклонено, но авто-проверка требует человека → заголовок «Нужна ручная проверка».
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                DriverReasonBanner(docsStatus = "none", autocheckResult = "needs_human", autocheckData = "")
            }
        }
        composeRule.onNodeWithText("Нужна ручная проверка").assertIsDisplayed()
    }
}
