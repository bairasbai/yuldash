package com.yuldash.app.walk.l1_4

import com.yuldash.app.fmtRub
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `fmtRub` (WalletScreen.kt) — единственный форматтер ЦЕЛЫХ рублей в приложении: границы
 * вывода, "Всё" на кошельке, итоги заработка водителя и калькулятора дохода. Проверяем, что
 * разряды всегда бьются пробелом по три цифры и минус не отрывается от числа лишним пробелом.
 *
 * Правило R1 (leaf-1.4).
 */
class WalletMoneyFormatTest {

    @Test
    fun fmtRub_groupsThousandsWithSpace() {
        assertEquals("1 250", fmtRub(1250))
        assertEquals("45 000", fmtRub(45000))
        assertEquals("1 234 567", fmtRub(1234567))
    }

    @Test
    fun fmtRub_smallNumbers_noSeparator() {
        assertEquals("0", fmtRub(0))
        assertEquals("150", fmtRub(150))
        assertEquals("999", fmtRub(999))
    }

    @Test
    fun fmtRub_negative_minusStaysGluedToDigits() {
        // Любая ловушка вида "- 100 000" (пробел после минуса) читается как опечатка,
        // а не как отрицательная сумма — глаз цепляется за пробел, а не за знак.
        assertEquals("-100", fmtRub(-100))
        assertEquals("-100 000", fmtRub(-100000))
        assertEquals("-1 234", fmtRub(-1234))
    }
}
