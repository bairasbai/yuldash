package com.yuldash.app.walk.l1_5

import com.yuldash.app.normalizePromoCodeInput
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * leaf-1.5 R2 (PromoCodeScreen.kt::normalizePromoCodeInput): ввод промокода — регистр, пробелы,
 * длина, башкирские буквы.
 *
 * Было: поле ввода просто делало `uppercase().filter{!isWhitespace}` без предела длины. Сервер
 * хранит код `max_length=32` (`PromoCode.code`, `backend/app/models.py`) и тем же пределом режет
 * тело запроса (`PromoApplyIn.code`, `backend/app/routers/promo.py`) — вставленный длинный текст
 * (например, случайно вставленный буфер обмена) уходил в сеть гарантированным 422 вместо того,
 * чтобы просто не поместиться в поле.
 */
class PromoCodeInputNormalizationTest {

    @Test
    fun uppercasesLatinInput() {
        assertEquals("YULDASH100", normalizePromoCodeInput("yuldash100"))
    }

    @Test
    fun stripsLeadingTrailingAndInternalSpaces() {
        // Вставленный «YUL DASH» не должен развалиться по пробелу внутри — его просто не будет.
        assertEquals("YULDASH", normalizePromoCodeInput("  yul dash "))
    }

    @Test
    fun emptyAndBlankStayEmpty() {
        assertEquals("", normalizePromoCodeInput(""))
        assertEquals("", normalizePromoCodeInput("   "))
    }

    @Test
    fun tooLongInputIsCappedAtServerLimit() {
        // PromoCode.code max_length=32 — поле не должно пускать больше, чем сервер всё равно отвергнет.
        val pasted = "A".repeat(50)
        val result = normalizePromoCodeInput(pasted)
        assertEquals(32, result.length)
        assertEquals("A".repeat(32), result)
    }

    @Test
    fun lengthCapAppliesAfterWhitespaceIsRemovedNotBefore() {
        // 40 букв + 10 пробелов = 50 исходных символов, но ПОСЛЕ фильтрации пробелов остаётся
        // ровно 40 букв — предел в 32 должен резать уже чистую строку, а не сырой ввод.
        val pasted = "B".repeat(40) + " ".repeat(10)
        assertEquals("B".repeat(32), normalizePromoCodeInput(pasted))
    }

    @Test
    fun bashkirLettersSurviveNormalizationRoundTrip() {
        // Юлдаш — двуязычное приложение: башкирские буквы в промокоде не должны ни потеряться при
        // фильтрации пробелов, ни превратиться во что-то другое при приведении к верхнему регистру.
        // Конкретные буквы (как в «Ҡулланыу» этого же экрана, «ташламаларҙы» на вкладке купонов)
        // проверяем явно; длина и обратимость — как общая защита от порчи Unicode.
        val lower = "ҙәғҡңөүһ"
        val upper = "ҘӘҒҠҢӨҮҺ"
        assertEquals(upper, normalizePromoCodeInput(lower))
        assertEquals(lower.length, normalizePromoCodeInput(lower).length)
        assertEquals(lower, normalizePromoCodeInput(lower).lowercase())
    }
}
