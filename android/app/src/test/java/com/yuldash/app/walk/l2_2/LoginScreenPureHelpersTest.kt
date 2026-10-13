package com.yuldash.app.walk.l2_2

import com.yuldash.app.data.ApiException
import com.yuldash.app.loginRequestErrorText
import com.yuldash.app.sanitizeLoginCodeInput
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * leaf-2.2 (LoginScreen.kt): два чистых правила экрана входа, у которых раньше не было теста,
 * потому что тело жило внутри приватного `LoginFormCard` и было недоступно тестам напрямую.
 * Разбор — docs/audit-files/android/app/src/main/java/com/yuldash/app/LoginScreen.kt.md (R2, R5).
 */
class LoginScreenPureHelpersTest {

    // ─────────────────── R2: вставка кода — только цифры, не длиннее 6 ───────────────────

    @Test
    fun sanitizeLoginCodeInput_keepsOnlyDigits() {
        assertEquals("123456", sanitizeLoginCodeInput("1a2b3c4d5e6f"))
    }

    @Test
    fun sanitizeLoginCodeInput_cutsLongerThanSix() {
        assertEquals("123456", sanitizeLoginCodeInput("1234567890"))
    }

    @Test
    fun sanitizeLoginCodeInput_realisticSmsAutofillText() {
        // Реалистичная вставка: автозаполнение/буфер несёт код внутри фразы, а не голыми цифрами.
        assertEquals("123456", sanitizeLoginCodeInput("Ваш код: 123456"))
    }

    @Test
    fun sanitizeLoginCodeInput_spacesBetweenDigitsAreDropped() {
        assertEquals("123456", sanitizeLoginCodeInput("12 34 56"))
    }

    @Test
    fun sanitizeLoginCodeInput_nonDigitInputStaysBlank() {
        assertEquals("", sanitizeLoginCodeInput(""))
        assertEquals("", sanitizeLoginCodeInput("abc"))
    }

    // ─────────── R5: сообщение сервера важнее своего обобщённого «проверь интернет» ───────────

    @Test
    fun loginRequestErrorText_usesServerMessage_whenApiExceptionCarriesOne() {
        // 503 «SMS временно не работает» (routers/auth.py: settings.is_prod and not
        // sms_channel_live(); тот же текст у services.send_sms._sms_failed). ApiClient.errorMessage
        // уже собрал готовый двуязычный текст в ApiException.message — его и нужно показать,
        // а не «проверь интернет»: человеку с интернетом всё равно нужен мессенджер, а не повтор.
        val serverSaid = "Вход по SMS временно не работает. Зайди через мессенджер 💚"
        val e = ApiException(503, serverSaid)
        assertEquals(serverSaid, loginRequestErrorText(e, "Проверь интернет и повтори."))
    }

    @Test
    fun loginRequestErrorText_keepsServerMessage_forAnyStatus_notOnly503() {
        // Правило не привязано к одному коду: 429 «Слишком часто. Подожди минуту…» — тоже
        // готовый серверный текст, который не нужно заменять обобщённым.
        val serverSaid = "Слишком часто. Подожди минуту и попробуй снова."
        val e = ApiException(429, serverSaid)
        assertEquals(serverSaid, loginRequestErrorText(e, "Проверь интернет и повтори."))
    }

    @Test
    fun loginRequestErrorText_fallsBackToOwnText_whenRequestNeverReachedServer() {
        // Нет сети вообще — ApiException просто нет (нет ответа сервера). Тут «проверь интернет» —
        // ровно верный совет, и свой текст должен остаться.
        val fallback = "Проверь интернет и повтори."
        assertEquals(fallback, loginRequestErrorText(IOException("no route to host"), fallback))
    }
}
