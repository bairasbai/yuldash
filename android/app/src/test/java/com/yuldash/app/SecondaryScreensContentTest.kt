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

    /** Уведомление-заготовка: тесту важны только заголовок, текст и «прочитано». */
    private fun notif(titleRu: String, bodyRu: String, read: Boolean) = NotifDto(
        id = 1, type = "message",
        titleRu = titleRu, titleBa = titleRu,
        bodyRu = bodyRu, bodyBa = bodyRu,
        refKind = "", refId = null,
        read = read, createdAt = "",
    )

    // --- NotificationRow: строка уведомления (заголовок + текст + «когда» + непрочитанное) ---

    @Test
    fun notificationRow_showsTitleAndSubtitle() {
        composeRule.setContent {
            // Строка уведомления давно берёт целый NotifDto (а не иконку/заголовок по кусочкам)
            // и умеет открываться по нажатию. Тест звал старую сигнатуру и не компилировался.
            NotificationRow(
                notif = notif(titleRu = "Новое сообщение", bodyRu = "Рамиль ответил на заявку", read = false),
                onClick = {},
            )
        }
        composeRule.onNodeWithText("Новое сообщение").assertIsDisplayed()
        composeRule.onNodeWithText("Рамиль ответил на заявку").assertIsDisplayed()
    }

    @Test
    fun notificationRow_read_stillShowsText() {
        // unread=false: точка непрочитанного скрыта, но текст рендерится как обычно.
        composeRule.setContent {
            NotificationRow(
                notif = notif(titleRu = "Заголовок", bodyRu = "Подзаголовок", read = true),
                onClick = {},
            )
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
