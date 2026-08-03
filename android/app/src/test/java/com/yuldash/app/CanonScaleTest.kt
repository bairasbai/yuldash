package com.yuldash.app

import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Шкалы дизайн-системы — это решения, а не числа. Тест держит их, чтобы они не разъехались
 * обратно: замер 2026-08-03 показал 88 разных отступов и 43 кегля по экранам именно потому,
 * что никакого договора не было.
 */
class CanonScaleTest {

    @Test
    fun `все отступы кратны четырём`() {
        val steps = listOf(
            "xs" to CanonSpace.xs, "sm" to CanonSpace.sm, "md" to CanonSpace.md,
            "lg" to CanonSpace.lg, "xl" to CanonSpace.xl, "xxl" to CanonSpace.xxl,
            "huge" to CanonSpace.huge,
        )
        steps.forEach { (name, v) ->
            assertEquals("$name не на сетке 4pt: $v", 0f, v.value % 4f, 0.001f)
        }
    }

    @Test
    fun `шкала отступов возрастает без повторов`() {
        val v = listOf(
            CanonSpace.xs, CanonSpace.sm, CanonSpace.md,
            CanonSpace.lg, CanonSpace.xl, CanonSpace.xxl, CanonSpace.huge,
        ).map { it.value }
        assertEquals("ступени повторяются", v.size, v.distinct().size)
        assertEquals("ступени не по возрастанию", v.sorted(), v)
    }

    /**
     * Главное решение полировки: основной текст НЕ жирный. Было 1022 жирных текста из 1074 —
     * когда выделено всё, не выделено ничто. Если этот тест когда-нибудь захотят «починить»
     * сделав CanonBody жирным — значит возвращаемся ровно туда, откуда уходили.
     */
    @Test
    fun `основной текст обычного веса, а не жирный`() {
        assertEquals(FontWeight.Normal, CanonBody.fontWeight)
        assertEquals(FontWeight.Normal, CanonCaption.fontWeight)
    }

    @Test
    fun `жирность оставлена только там, где она работает`() {
        // крупное число и заголовок экрана — им положено кричать
        assertEquals(FontWeight.Bold, CanonDisplay.fontWeight)
        assertEquals(FontWeight.Bold, CanonTitle.fontWeight)
        // заголовок блока и кнопка — заметны, но не кричат
        assertEquals(FontWeight.SemiBold, CanonHeading.fontWeight)
        assertEquals(FontWeight.SemiBold, CanonButton.fontWeight)
    }

    @Test
    fun `основной текст не мельче 15sp`() {
        // самым частым кеглем было 13sp — это мелко, у Apple и Тинькофф основной 15-17
        assertTrue("основной текст мелкий: ${CanonBody.fontSize}", CanonBody.fontSize.value >= 15f)
    }

    @Test
    fun `типографическая шкала возрастает`() {
        val v = listOf(CanonMicro, CanonCaption, CanonBody, CanonHeading, CanonTitle, CanonDisplay)
            .map { it.fontSize.value }
        assertEquals("кегли не по возрастанию", v.sorted(), v)
    }

    @Test
    fun `мелкому тексту дано больше воздуха, чем крупному`() {
        // межстрочный относительно кегля: мелкое слипается, крупное наоборот дышит само
        val micro = CanonMicro.lineHeight.value / CanonMicro.fontSize.value
        val display = CanonDisplay.lineHeight.value / CanonDisplay.fontSize.value
        assertTrue("мелкому нужно больше межстрочного ($micro vs $display)", micro > display)
    }

    @Test
    fun `длительности анимаций в разумных пределах`() {
        // короче 150мс глаз не замечает, длиннее 350мс раздражает
        listOf(CanonMotion.QUICK, CanonMotion.NORMAL, CanonMotion.SLOW).forEach {
            assertTrue("длительность вне диапазона: $it", it in 150..350)
        }
        assertTrue(CanonMotion.QUICK < CanonMotion.NORMAL)
        assertTrue(CanonMotion.NORMAL < CanonMotion.SLOW)
    }

    @Test
    fun `глубина возрастает и начинается с нуля`() {
        assertEquals(0f, CanonDepth.flat.value, 0.001f)
        val v = listOf(CanonDepth.flat, CanonDepth.card, CanonDepth.raised, CanonDepth.sheet).map { it.value }
        assertEquals(v.sorted(), v)
    }
}
