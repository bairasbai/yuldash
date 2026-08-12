package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину-2» по экрану активной поездки: покрываем БОЕВОЙ пузырь чата
 * [MessageBubble] из `BookingActiveTripScreen` (умный — с меню/повтором/плеером), который
 * раньше был `private` и не покрывался. Он ≠ `ChatFeedBubble`/`ChatContent` из
 * `RidesRequestsChatScreens.kt` (те покрыты в ChatContentTest) — тут другой компонент.
 *
 * Всё, что рисует пузырь, — чистый Compose (Text/Icon/Row/Surface/DropdownMenu/AsyncImage).
 * MediaPlayer создаётся ТОЛЬКО внутри onClick (не при композиции), сеть/сокет снаружи —
 * значит компонент покрываем на JVM через Robolectric. Двуязычие — через appText
 * (LocalAppLanguage). Тексты в ассертах — ДОСЛОВНО из BookingActiveTripScreen.kt.
 *
 * «Плохие»/крайние сценарии: удалённое сообщение (без меню), «изменено», «не доставлено →
 * повтор», голос, фото ([img]-префикс), длинное меню (правка / удалить у себя / у всех) —
 * и что клики отдают правильные аргументы в колбэки.
 *
 * Заголовок класса — как в BookingActiveTripContentDeepTest. Анимаций у пузыря нет →
 * autoAdvance не трогаем.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookingActiveTripDeep2ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- обычный текст: свой и чужой ---

    @Test
    fun bubble_mineText_isShown() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MessageBubble(text = "Еду, буду через 5 минут", voiceUrl = null, mine = true)
            }
        }
        composeRule.onNodeWithText("Еду, буду через 5 минут").assertIsDisplayed()
    }

    @Test
    fun bubble_theirsText_isShown() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MessageBubble(text = "Я на остановке у рынка", voiceUrl = null, mine = false)
            }
        }
        composeRule.onNodeWithText("Я на остановке у рынка").assertIsDisplayed()
    }

    // --- удалённое сообщение: заглушка, меню НЕ появляется даже при флагах прав ---

    @Test
    fun bubble_deleted_russian_showsDeletedPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MessageBubble(text = "секрет", voiceUrl = null, mine = true, deleted = true)
            }
        }
        composeRule.onNodeWithText("Сообщение удалено").assertIsDisplayed()
        // Оригинальный текст удалённого сообщения не показывается.
        composeRule.onNodeWithText("секрет").assertDoesNotExist()
    }

    @Test
    fun bubble_deleted_bashkir_showsDeletedPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                MessageBubble(text = "секрет", voiceUrl = null, mine = false, deleted = true)
            }
        }
        composeRule.onNodeWithText("Хәбәр юйылды").assertIsDisplayed()
    }

    @Test
    fun bubble_deleted_longPress_showsNoMenu() {
        // deleted → showMenu=false: даже с правами долгий тап не открывает меню.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MessageBubble(
                    text = "секрет", voiceUrl = null, mine = true, deleted = true,
                    canEdit = true, canDeleteAll = true, canDeleteMine = true,
                )
            }
        }
        composeRule.onNodeWithText("Сообщение удалено").performTouchInput { longClick() }
        composeRule.onNodeWithText("Редактировать").assertDoesNotExist()
        composeRule.onNodeWithText("Удалить у всех").assertDoesNotExist()
    }

    // --- «изменено» / «не доставлено · повторить» подписи ---

    @Test
    fun bubble_edited_showsEditedLabel() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MessageBubble(text = "текст", voiceUrl = null, mine = true, edited = true)
            }
        }
        composeRule.onNodeWithText("изменено").assertIsDisplayed()
    }

    @Test
    fun bubble_edited_bashkir_showsEditedLabel() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                MessageBubble(text = "текст", voiceUrl = null, mine = true, edited = true)
            }
        }
        composeRule.onNodeWithText("үҙгәртелде").assertIsDisplayed()
    }

    @Test
    fun bubble_failed_showsRetryLabel_andClickFires() {
        var retried = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MessageBubble(
                    text = "не ушло", voiceUrl = null, mine = true, failed = true,
                    onRetry = { retried = true },
                )
            }
        }
        assertFalse(retried)
        composeRule.onNodeWithText("Не доставлено · Повторить").assertIsDisplayed()
        composeRule.onNodeWithText("Не доставлено · Повторить").performClick()
        assertTrue(retried)
    }

    @Test
    fun bubble_failed_bashkir_showsRetryLabel() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                MessageBubble(text = "не ушло", voiceUrl = null, mine = true, failed = true)
            }
        }
        composeRule.onNodeWithText("Ебәрелмәне · Ҡабатлау").assertIsDisplayed()
    }

    // --- голосовое: подпись + кнопка воспроизведения (contentDescription) ---

    @Test
    fun bubble_voice_showsVoiceLabelAndPlayButton() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MessageBubble(text = "", voiceUrl = "https://example.com/a.m4a", mine = false)
            }
        }
        composeRule.onNodeWithText("Голосовое").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Воспроизвести").assertIsDisplayed()
    }

    @Test
    fun bubble_voice_bashkir_showsVoiceLabel() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                MessageBubble(text = "", voiceUrl = "https://example.com/a.m4a", mine = true)
            }
        }
        composeRule.onNodeWithText("Тауыш").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Уйнатыу").assertIsDisplayed()
    }

    // --- фото ([img]-префикс) → AsyncImage с contentDescription «Фото», без сырого url текстом ---

    @Test
    fun bubble_imagePrefix_rendersPhotoNotRawUrl() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MessageBubble(text = "[img]https://example.com/p.jpg", voiceUrl = null, mine = true)
            }
        }
        composeRule.onNodeWithContentDescription("Фото").assertIsDisplayed()
        // Префиксный url не должен рендериться как обычный текст.
        composeRule.onNodeWithText("[img]https://example.com/p.jpg").assertDoesNotExist()
    }

    // --- меню по долгому тапу: правка / удалить у себя / удалить у всех ---

    @Test
    fun bubble_longPress_showsFullMenu() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MessageBubble(
                    text = "моё сообщение", voiceUrl = null, mine = true,
                    canEdit = true, canDeleteAll = true, canDeleteMine = true,
                )
            }
        }
        composeRule.onNodeWithText("моё сообщение").performTouchInput { longClick() }
        composeRule.onNodeWithText("Редактировать").assertIsDisplayed()
        composeRule.onNodeWithText("Удалить у себя").assertIsDisplayed()
        composeRule.onNodeWithText("Удалить у всех").assertIsDisplayed()
    }

    @Test
    fun bubble_menuEdit_firesOnEdit() {
        var edited = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MessageBubble(
                    text = "моё сообщение", voiceUrl = null, mine = true,
                    canEdit = true, onEdit = { edited = true },
                )
            }
        }
        composeRule.onNodeWithText("моё сообщение").performTouchInput { longClick() }
        composeRule.onNodeWithText("Редактировать").performClick()
        assertTrue(edited)
    }

    @Test
    fun bubble_menuDeleteMine_firesDeleteWithMeScope() {
        var scope: String? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MessageBubble(
                    text = "чужое сообщение", voiceUrl = null, mine = false,
                    canDeleteMine = true, onDelete = { scope = it },
                )
            }
        }
        composeRule.onNodeWithText("чужое сообщение").performTouchInput { longClick() }
        composeRule.onNodeWithText("Удалить у себя").performClick()
        assertEquals("me", scope)
    }

    @Test
    fun bubble_menuDeleteAll_firesDeleteWithAllScope() {
        var scope: String? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MessageBubble(
                    text = "моё сообщение", voiceUrl = null, mine = true,
                    canDeleteAll = true, onDelete = { scope = it },
                )
            }
        }
        composeRule.onNodeWithText("моё сообщение").performTouchInput { longClick() }
        composeRule.onNodeWithText("Удалить у всех").performClick()
        assertEquals("all", scope)
    }

    @Test
    fun bubble_menuDeleteAll_bashkirLabel() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                MessageBubble(
                    text = "моё сообщение", voiceUrl = null, mine = true,
                    canDeleteAll = true,
                )
            }
        }
        composeRule.onNodeWithText("моё сообщение").performTouchInput { longClick() }
        composeRule.onNodeWithText("Барыһында юйыу").assertIsDisplayed()
    }

    @Test
    fun bubble_noRights_longPress_showsNoMenu() {
        // Нет прав (canEdit/canDelete* = false) → showMenu=false → долгий тап ничего не открывает.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MessageBubble(text = "обычный текст", voiceUrl = null, mine = false)
            }
        }
        composeRule.onNodeWithText("обычный текст").performTouchInput { longClick() }
        composeRule.onNodeWithText("Редактировать").assertDoesNotExist()
        composeRule.onNodeWithText("Удалить у себя").assertDoesNotExist()
    }

    @Test
    fun bubble_onlyDeleteMine_menuHasNoEditNorDeleteAll() {
        // Права только на «удалить у себя»: в меню нет «Редактировать» и «Удалить у всех».
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MessageBubble(
                    text = "чужое", voiceUrl = null, mine = false,
                    canEdit = false, canDeleteAll = false, canDeleteMine = true,
                )
            }
        }
        composeRule.onNodeWithText("чужое").performTouchInput { longClick() }
        composeRule.onNodeWithText("Удалить у себя").assertIsDisplayed()
        composeRule.onNodeWithText("Редактировать").assertDoesNotExist()
        composeRule.onNodeWithText("Удалить у всех").assertDoesNotExist()
    }
}
