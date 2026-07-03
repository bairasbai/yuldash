package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину»: пилот выноса стейта. Экран `TrustedContactsScreen` разрезан на умную обёртку
 * (сеть/стейт/диалог добавления) и чистый `TrustedContactsContent(state, callbacks)`. Здесь Content
 * покрыт во ВСЕХ состояниях (загрузка / ошибка+повтор / пусто / список) — то, что раньше было 0%.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilityDeep2ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun contact(
        name: String = "Айгуль",
        relation: String = "Дочь",
        phone: String = "+7 927 111-22-33",
        notifyByDefault: Boolean = true,
    ) = TrustedContact(name = name, relation = relation, phone = phone, notifyByDefault = notifyByDefault)

    @Test
    fun loading_showsSpinnerNotList() {
        composeRule.mainClock.autoAdvance = false   // бесконечный спиннер
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TrustedContactsContent(loading = true, loadError = false, contacts = emptyList(), loadErrorText = "Ошибка сети", onRetry = {}, onAddClick = {})
            }
        }
        // Пустой список во время загрузки не должен показывать заглушку «пока нет контактов».
        composeRule.onNodeWithText("Пока нет контактов").assertDoesNotExist()
    }

    @Test
    fun error_showsMessageAndRetryButton() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TrustedContactsContent(loading = false, loadError = true, contacts = emptyList(), loadErrorText = "Сеть упала", onRetry = {}, onAddClick = {})
            }
        }
        composeRule.onNodeWithText("Сеть упала").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить").assertIsDisplayed()
    }

    @Test
    fun error_retryClick_firesOnRetry() {
        var retried = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TrustedContactsContent(loading = false, loadError = true, contacts = emptyList(), loadErrorText = "Ошибка", onRetry = { retried = true }, onAddClick = {})
            }
        }
        composeRule.onNodeWithText("Повторить").performClick()
        assertTrue(retried)
    }

    @Test
    fun empty_russian_showsFriendlyPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TrustedContactsContent(loading = false, loadError = false, contacts = emptyList(), loadErrorText = "", onRetry = {}, onAddClick = {})
            }
        }
        composeRule.onNodeWithText("Пока нет контактов").assertIsDisplayed()
        composeRule.onNodeWithText("Добавь близкого — он сможет видеть статус твоей поездки.").assertIsDisplayed()
    }

    @Test
    fun empty_bashkir_showsBashkirPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                TrustedContactsContent(loading = false, loadError = false, contacts = emptyList(), loadErrorText = "", onRetry = {}, onAddClick = {})
            }
        }
        composeRule.onNodeWithText("Әлегә контакттар юҡ").assertIsDisplayed()
    }

    @Test
    fun list_showsContactNameRelationAndPhone() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TrustedContactsContent(loading = false, loadError = false, contacts = listOf(contact()), loadErrorText = "", onRetry = {}, onAddClick = {})
            }
        }
        composeRule.onNodeWithText("Айгуль").assertIsDisplayed()
        composeRule.onNodeWithText("Дочь · +7 927 111-22-33").assertIsDisplayed()
    }

    @Test
    fun list_addButton_alwaysVisibleWithContacts() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TrustedContactsContent(loading = false, loadError = false, contacts = listOf(contact()), loadErrorText = "", onRetry = {}, onAddClick = {})
            }
        }
        composeRule.onNodeWithText("Добавить контакт").assertIsDisplayed()
    }

    @Test
    fun addButton_click_firesOnAddClick() {
        var added = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                TrustedContactsContent(loading = false, loadError = false, contacts = emptyList(), loadErrorText = "", onRetry = {}, onAddClick = { added = true })
            }
        }
        composeRule.onNodeWithText("Добавить контакт").performClick()
        assertTrue(added)
    }
}
