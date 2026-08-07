package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.PendingDriverDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину»: пилот выноса стейта. Экран `AdminDriversScreen` разрезан на умную обёртку
 * (сеть/стейт) и чистый `AdminDriversContent(state, callbacks)`. Здесь Content покрыт во ВСЕХ
 * состояниях (загрузка / ошибка+повтор / пусто / список / одобрить-отклонить) — то, что раньше было 0%.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecondaryDeepContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // Пустые URL/autocheck → DocImage и AutoCheckRow схлопываются (без сети и JSON) → чистый рендер на JVM.
    private fun driver(userId: Int = 1, name: String = "Айрат", phone: String = "+79990001122", car: String = "Lada Vesta",
                       genderClaimed: String = "", genderVerified: Boolean = false) =
        PendingDriverDto(userId = userId, name = name, phone = phone, car = car, licenseUrl = "", carPhotoUrl = "",
            genderClaimed = genderClaimed, genderVerified = genderVerified)

    @Test
    fun loading_showsSpinnerNotList() {
        composeRule.mainClock.autoAdvance = false   // держим кадр загрузки
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminDriversContent(loading = true, error = null, drivers = listOf(driver()), token = "", onRetry = {}, onApprove = { _, _ -> }, onReject = {})
            }
        }
        composeRule.onNodeWithText("Загрузка…").assertIsDisplayed()
        composeRule.onNodeWithText("Айрат").assertDoesNotExist()
    }

    @Test
    fun error_showsMessageAndRetryButton() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminDriversContent(loading = false, error = "Сеть упала", drivers = emptyList(), token = "", onRetry = {}, onApprove = { _, _ -> }, onReject = {})
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
                AdminDriversContent(loading = false, error = "Ошибка", drivers = emptyList(), token = "", onRetry = { retried = true }, onApprove = { _, _ -> }, onReject = {})
            }
        }
        composeRule.onNodeWithText("Повторить").performClick()
        assertTrue(retried)
    }

    @Test
    fun empty_russian_showsFriendlyPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminDriversContent(loading = false, error = null, drivers = emptyList(), token = "", onRetry = {}, onApprove = { _, _ -> }, onReject = {})
            }
        }
        composeRule.onNodeWithText("Нет заявок на проверку").assertIsDisplayed()
        composeRule.onNodeWithText("Здесь появятся водители, отправившие документы.").assertIsDisplayed()
    }

    @Test
    fun empty_bashkir_showsBashkirPlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                AdminDriversContent(loading = false, error = null, drivers = emptyList(), token = "", onRetry = {}, onApprove = { _, _ -> }, onReject = {})
            }
        }
        composeRule.onNodeWithText("Тикшереүгә заявка юҡ").assertIsDisplayed()
    }

    @Test
    fun list_showsDriverNameCarPhoneAndActionButtons() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminDriversContent(loading = false, error = null, drivers = listOf(driver()), token = "", onRetry = {}, onApprove = { _, _ -> }, onReject = {})
            }
        }
        composeRule.onNodeWithText("Айрат").assertIsDisplayed()
        composeRule.onNodeWithText("Lada Vesta · +79990001122").assertIsDisplayed()
        composeRule.onNodeWithText("Одобрить").assertIsDisplayed()
        composeRule.onNodeWithText("Отклонить").assertIsDisplayed()
    }

    /**
     * Подтверждение пола модератором (2026-08-07). Бейдж «женщина за рулём» и женские заказы
     * такси раньше включались самим водителем — фича безопасности женщин держалась на честном
     * слове. Теперь её включает модератор, у которого фото прав уже перед глазами.
     */
    @Test
    fun list_genderClaim_showsConfirmToggle() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminDriversContent(loading = false, error = null, drivers = listOf(driver(genderClaimed = "female")),
                    token = "", onRetry = {}, onApprove = { _, _ -> }, onReject = {})
            }
        }
        composeRule.onNodeWithText("Это женщина — подтверждаю").assertIsDisplayed()
    }

    @Test
    fun list_noGenderClaim_hidesConfirmToggle() {
        // Водитель пол не заявлял — подтверждать нечего, лишнего переключателя быть не должно.
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminDriversContent(loading = false, error = null, drivers = listOf(driver()),
                    token = "", onRetry = {}, onApprove = { _, _ -> }, onReject = {})
            }
        }
        composeRule.onNodeWithText("Это женщина — подтверждаю").assertDoesNotExist()
        composeRule.onNodeWithText("Это мужчина — подтверждаю").assertDoesNotExist()
    }

    @Test
    fun list_approve_passesGenderConfirmationAlongside() {
        // Уже подтверждённый водитель: одобрение не должно молча снимать подтверждение.
        var passed: Boolean? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminDriversContent(loading = false, error = null,
                    drivers = listOf(driver(genderClaimed = "female", genderVerified = true)),
                    token = "", onRetry = {}, onApprove = { _, ok -> passed = ok }, onReject = {})
            }
        }
        composeRule.onNodeWithText("Одобрить").performClick()
        assertEquals(true, passed)
    }

    @Test
    fun list_approve_withoutGenderClaim_passesNull() {
        // Пол не заявлен → шлём null: «не трогать», а не «снять подтверждение».
        var called = false
        var passed: Boolean? = true
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminDriversContent(loading = false, error = null, drivers = listOf(driver()),
                    token = "", onRetry = {}, onApprove = { _, ok -> called = true; passed = ok }, onReject = {})
            }
        }
        composeRule.onNodeWithText("Одобрить").performClick()
        assertTrue(called)
        assertNull(passed)
    }

    @Test
    fun list_approveClick_firesOnApproveWithItem() {
        var approved: PendingDriverDto? = null
        val d = driver(userId = 42)
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminDriversContent(loading = false, error = null, drivers = listOf(d), token = "", onRetry = {}, onApprove = { drv, _ -> approved = drv }, onReject = {})
            }
        }
        composeRule.onNodeWithText("Одобрить").performClick()
        assertEquals(42, approved?.userId)
    }

    @Test
    fun list_rejectClick_firesOnRejectWithItem() {
        var rejected: PendingDriverDto? = null
        val d = driver(userId = 77)
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminDriversContent(loading = false, error = null, drivers = listOf(d), token = "", onRetry = {}, onApprove = { _, _ -> }, onReject = { rejected = it })
            }
        }
        composeRule.onNodeWithText("Отклонить").performClick()
        assertEquals(77, rejected?.userId)
    }

    @Test
    fun list_blankCar_showsDash() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                AdminDriversContent(loading = false, error = null, drivers = listOf(driver(car = "")), token = "", onRetry = {}, onApprove = { _, _ -> }, onReject = {})
            }
        }
        composeRule.onNodeWithText("— · +79990001122").assertIsDisplayed()
    }
}
