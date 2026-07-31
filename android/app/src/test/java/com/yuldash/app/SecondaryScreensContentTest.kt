package com.yuldash.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.NotifDto
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Content-композаблы вторичных экранов (Уведомления / Оплата / Список людей): чистые (без
 * сети/state/анимаций) под-компоненты стали `internal` → покрываем на JVM через Robolectric.
 * Эти три берут готовые String-параметры (текст двух языков считается выше), поэтому тестируем
 * их рендер и поведение колбэков напрямую, без CompositionLocalProvider.
 *
 * Заголовок класса — как в RobolectricSmokeTest / LoginScreenContentTest. Анимаций нет → autoAdvance не нужен.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecondaryScreensContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- NotificationRow: строка уведомления (иконка + заголовок + подзаголовок + время) ---

    /** Собрать уведомление под текущий контракт: NotificationRow берёт DTO и сам достаёт
     *  заголовок/текст по языку, а время считает относительно «сейчас» из createdAt. */
    private fun notif(
        titleRu: String,
        bodyRu: String,
        read: Boolean = false,
    ) = NotifDto(
        id = 1,
        type = "system",
        titleRu = titleRu, titleBa = titleRu,
        bodyRu = bodyRu, bodyBa = bodyRu,
        refKind = "", refId = null,
        read = read,
        createdAt = "2026-01-01T12:30:00Z",
    )

    @Test
    fun notificationRow_showsTitleAndSubtitle() {
        composeRule.setContent {
            NotificationRow(notif("Новое сообщение", "Рамиль ответил на заявку"), onClick = {})
        }
        composeRule.onNodeWithText("Новое сообщение").assertIsDisplayed()
        composeRule.onNodeWithText("Рамиль ответил на заявку").assertIsDisplayed()
        // Время НЕ проверяем строкой: notifTimeAgo считает его относительно текущего момента
        // («5 мин», «вчера»), поэтому фиксированный ожидаемый текст был бы ложным тестом.
    }

    @Test
    fun notificationRow_read_stillShowsText() {
        // read=true: подложка спокойная (не мятная), но текст рендерится как обычно.
        composeRule.setContent {
            NotificationRow(notif("Заголовок", "Подзаголовок", read = true), onClick = {})
        }
        composeRule.onNodeWithText("Заголовок").assertIsDisplayed()
        composeRule.onNodeWithText("Подзаголовок").assertIsDisplayed()
    }

    // --- PaymentStepRow: шаг оплаты (номер + текст) ---

    @Test
    fun paymentStepRow_showsNumberAndText() {
        composeRule.setContent { PaymentStepRow("1", "Договоритесь о цене в чате") }
        composeRule.onNodeWithText("1").assertIsDisplayed()
        composeRule.onNodeWithText("Договоритесь о цене в чате").assertIsDisplayed()
    }

    @Test
    fun paymentStepRow_bashkirText_renders() {
        // Текст приходит готовым — компонент одинаково рисует любой язык.
        composeRule.setContent { PaymentStepRow("2", "Сәфәрҙән һуң СБП аша күсерегеҙ") }
        composeRule.onNodeWithText("2").assertIsDisplayed()
        composeRule.onNodeWithText("Сәфәрҙән һуң СБП аша күсерегеҙ").assertIsDisplayed()
    }

    // --- PersonRow: строка человека (имя + кнопка действия) ---

    @Test
    fun personRow_showsNameAndAction() {
        composeRule.setContent {
            PersonRow(name = "Азат", actionLabel = "Заблокировать", danger = true, onAction = {})
        }
        composeRule.onNodeWithText("Азат").assertIsDisplayed()
        composeRule.onNodeWithText("Заблокировать").assertIsDisplayed()
    }

    @Test
    fun personRow_clickingAction_firesCallback() {
        var fired = false
        composeRule.setContent {
            PersonRow(name = "Гульназ", actionLabel = "Разблокировать", danger = false, onAction = { fired = true })
        }
        assertFalse(fired)
        composeRule.onNodeWithText("Разблокировать").performClick()
        assertTrue(fired)
    }
}
