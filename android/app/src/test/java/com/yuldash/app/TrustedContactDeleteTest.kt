package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Доверенный контакт можно УБРАТЬ, а не только добавить.
 *
 * Что было. Сервер умел удалять контакт с самого начала — в самом коде ручки написано:
 * «Android удалял только локально (после перезапуска контакт возвращался)». Приложение эту
 * ручку не звало НИКОГДА, и кнопки удаления на экране не было вовсе.
 *
 * Почему это важнее, чем «неудобно». Доверенный контакт получает статус твоей поездки и SOS —
 * то есть твою геопозицию и сигнал тревоги ночью. Отношения меняются: расстались, поссорились,
 * ошиблись цифрой в номере. Человек, которому это уходит, оставался в списке навсегда, и убрать
 * его было нельзя никак — ни из приложения, ни через поддержку.
 *
 * Тесты держат три вещи: кнопка есть у каждого контакта, у неё есть подпись для незрячих,
 * и нажатие сообщает наверх ИМЕННО тот контакт, по которому нажали (промах здесь стоил бы
 * удаления не того человека).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TrustedContactDeleteTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun contact(name: String, phone: String) =
        TrustedContact(name = name, relation = "Сестра", phone = phone, notifyByDefault = true, id = 7)

    private fun screen(
        contacts: List<TrustedContact>,
        onDelete: (TrustedContact) -> Unit = {},
    ) {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TrustedContactsContent(
                    loading = false,
                    loadError = false,
                    contacts = contacts,
                    loadErrorText = "",
                    onRetry = {},
                    onAddClick = {},
                    onDelete = onDelete,
                )
            }
        }
    }

    @Test
    fun `у контакта есть кнопка «убрать»`() {
        screen(listOf(contact("Айгуль", "+7 927 111-22-33")))
        composeRule.onNodeWithText("Айгуль").assertIsDisplayed()
        // Подпись для незрячих обязательна: кнопка без текста, только иконка.
        composeRule.onNodeWithContentDescription("Убрать контакт").assertIsDisplayed()
    }

    @Test
    fun `нажатие сообщает наверх именно тот контакт, по которому нажали`() {
        val removed = mutableListOf<String>()
        screen(
            listOf(contact("Айгуль", "+7 927 111-22-33"), contact("Рустам", "+7 927 444-55-66")),
            onDelete = { removed += it.phone },
        )
        // Кнопок две — берём ту, что у второго контакта. Ошибка адресации тут = удалили не того.
        composeRule.onAllNodes(
            androidx.compose.ui.test.hasContentDescription("Убрать контакт")
        )[1].performClick()
        assertEquals(listOf("+7 927 444-55-66"), removed)
    }

    @Test
    fun `в пустом списке кнопки удаления нет`() {
        screen(emptyList())
        composeRule.onNodeWithText("Пока нет контактов").assertIsDisplayed()
        composeRule.onAllNodes(
            androidx.compose.ui.test.hasContentDescription("Убрать контакт")
        ).assertCountEquals(0)
    }
}
