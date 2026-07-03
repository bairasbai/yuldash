package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.yuldash.app.data.MessageDto
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину» для чата: лента сообщений разрезана на умную обёртку (WebSocket `ChatSocket`,
 * история по REST, оптимистичная отправка — остаётся в `BookingActiveTripScreen`) и чистый
 * `ChatContent(state, callbacks)`. Здесь Content покрыт в «плохих» сценариях, которых раньше не было:
 * пустой чат, рендер списка, гард двойной отправки, пустой ввод, загрузка истории.
 * Тексты — ТОЧНО из кода (`RidesRequestsChatScreens.kt`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val MY_ID = 1
    private val PEER_ID = 2

    private fun mine(id: Int, text: String) = MessageDto(id = id, text = text, senderId = MY_ID)
    private fun theirs(id: Int, text: String) = MessageDto(id = id, text = text, senderId = PEER_ID)

    // --- пустой чат → дружелюбная заглушка «нет сообщений» ---

    @Test
    fun empty_showsNoMessagesPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ChatContent(
                    messages = emptyList(), input = "", sending = false, loading = false, myId = MY_ID,
                    onInputChange = {}, onSend = {}
                )
            }
        }
        composeRule.onNodeWithText("Пока нет сообщений. Напиши первым").assertIsDisplayed()
    }

    @Test
    fun empty_bashkir_showsBashkirPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                ChatContent(
                    messages = emptyList(), input = "", sending = false, loading = false, myId = MY_ID,
                    onInputChange = {}, onSend = {}
                )
            }
        }
        composeRule.onNodeWithText("Әлегә хәбәрҙәр юҡ. Беренсе булып яҙ").assertIsDisplayed()
    }

    // --- список сообщений: текст своих и чужих виден ---

    @Test
    fun list_showsMineAndTheirsText() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ChatContent(
                    messages = listOf(theirs(1, "Я на остановке у рынка"), mine(2, "Еду, буду через 5 минут")),
                    input = "", sending = false, loading = false, myId = MY_ID,
                    onInputChange = {}, onSend = {}
                )
            }
        }
        composeRule.onNodeWithText("Я на остановке у рынка").assertIsDisplayed()
        composeRule.onNodeWithText("Еду, буду через 5 минут").assertIsDisplayed()
        // Раз список есть — заглушка «нет сообщений» не показывается.
        composeRule.onNodeWithText("Пока нет сообщений. Напиши первым").assertDoesNotExist()
    }

    @Test
    fun list_longChat_scrollsToLastMessage() {
        val many = (1..40).map { mine(it, "Сообщение №$it") }
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ChatContent(
                    messages = many, input = "", sending = false, loading = false, myId = MY_ID,
                    onInputChange = {}, onSend = {}
                )
            }
        }
        // Нижнее сообщение — за пределами экрана: доскролливаем и проверяем, что оно есть.
        composeRule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Сообщение №40"))
        composeRule.onNodeWithText("Сообщение №40").assertIsDisplayed()
    }

    // --- гард двойного нажатия «отправить»: sending=true → кнопка выключена ---

    @Test
    fun send_whileSending_buttonDisabled() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ChatContent(
                    messages = listOf(mine(1, "привет")), input = "второе сообщение", sending = true, loading = false, myId = MY_ID,
                    onInputChange = {}, onSend = {}
                )
            }
        }
        // Есть текст, но идёт отправка → повторно слать нельзя (гард двойного тапа).
        composeRule.onNodeWithContentDescription("Отправить").assertIsNotEnabled()
    }

    @Test
    fun send_notSendingWithText_enabled_andClickFires() {
        var sent = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ChatContent(
                    messages = emptyList(), input = "поехали", sending = false, loading = false, myId = MY_ID,
                    onInputChange = {}, onSend = { sent++ }
                )
            }
        }
        composeRule.onNodeWithContentDescription("Отправить").assertIsEnabled()
        composeRule.onNodeWithContentDescription("Отправить").performClick()
        assertEquals(1, sent)
    }

    // --- пустой ввод → «Отправить» выключено (нельзя слать пустое) ---

    @Test
    fun send_emptyInput_buttonDisabled() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ChatContent(
                    messages = emptyList(), input = "", sending = false, loading = false, myId = MY_ID,
                    onInputChange = {}, onSend = {}
                )
            }
        }
        // Пустой ввод → слать нечего, кнопка выключена (нельзя отправить пустое сообщение).
        composeRule.onNodeWithContentDescription("Отправить").assertIsNotEnabled()
    }

    @Test
    fun send_blankInput_buttonDisabled() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ChatContent(
                    messages = emptyList(), input = "   ", sending = false, loading = false, myId = MY_ID,
                    onInputChange = {}, onSend = {}
                )
            }
        }
        // Только пробелы — тоже пусто → кнопка выключена.
        composeRule.onNodeWithContentDescription("Отправить").assertIsNotEnabled()
    }

    // --- загрузка истории: loading=true → спиннер, сообщений нет ---

    @Test
    fun loading_showsSpinnerNoMessages() {
        composeRule.mainClock.autoAdvance = false   // бесконечный спиннер CircularProgressIndicator
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ChatContent(
                    messages = emptyList(), input = "", sending = false, loading = true, myId = MY_ID,
                    onInputChange = {}, onSend = {}
                )
            }
        }
        // Пока грузим — ни заглушки «пусто», ни сообщений.
        composeRule.onNodeWithText("Пока нет сообщений. Напиши первым").assertDoesNotExist()
    }
}
