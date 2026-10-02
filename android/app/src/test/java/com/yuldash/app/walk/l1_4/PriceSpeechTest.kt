package com.yuldash.app.walk.l1_4

import com.yuldash.app.priceAloudBa
import com.yuldash.app.priceAloudRu
import com.yuldash.app.rublesAloud
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Озвучка цены (PriceSpeech.kt) — чистая логика без Android, без Robolectric.
 *
 * Правило R1 (leaf-1.4): читаем ровно три числа — поездка, дорога водителя (если есть),
 * итого — и русское число всегда согласовано по падежу (1 рубль / 2 рубля / 5 рублей),
 * иначе бабушка, которой и сделана эта кнопка, услышит «2 рубль» и решит, что ей попутал
 * произношение синтезатор, а не числительное.
 */
class PriceSpeechTest {

    @Test
    fun rublesAloud_declinesByRussianPluralRules() {
        assertEquals("1 рубль", rublesAloud(1))
        assertEquals("21 рубль", rublesAloud(21))      // 21 % 10 == 1, но не 11..14
        assertEquals("2 рубля", rublesAloud(2))
        assertEquals("3 рубля", rublesAloud(3))
        assertEquals("4 рубля", rublesAloud(4))
        assertEquals("0 рублей", rublesAloud(0))
        assertEquals("5 рублей", rublesAloud(5))
        assertEquals("11 рублей", rublesAloud(11))     // ловушка: 11 % 10 == 1, но это "рублей"
        assertEquals("12 рублей", rublesAloud(12))
        assertEquals("14 рублей", rublesAloud(14))
        assertEquals("100 рублей", rublesAloud(100))
    }

    // ---- R2: без подачи («дорога водителя») — короче: только поездка и итог ----

    @Test
    fun priceAloudRu_withoutPickupFee_skipsDriverRoadClause() {
        val text = priceAloudRu(ridePrice = 200, pickupFee = 0, total = 200)
        assertEquals("Поездка 200 рублей. Всего 200 рублей.", text)
    }

    @Test
    fun priceAloudBa_withoutPickupFee_skipsDriverRoadClause() {
        val text = priceAloudBa(ridePrice = 200, pickupFee = 0, total = 200)
        assertEquals("Сәфәр 200 һум. Барлығы 200 һум.", text)
    }

    // ---- R3: с подачей — ровно три числа, в заданном порядке ----

    @Test
    fun priceAloudRu_withPickupFee_readsThreeNumbersInOrder() {
        val text = priceAloudRu(ridePrice = 200, pickupFee = 50, total = 250)
        assertEquals("Поездка 200 рублей, дорога водителя 50 рублей. Всего 250 рублей.", text)
    }

    @Test
    fun priceAloudBa_withPickupFee_readsThreeNumbersInOrder() {
        val text = priceAloudBa(ridePrice = 200, pickupFee = 50, total = 250)
        assertEquals("Сәфәр 200 һум, водитель юлы 50 һум. Барлығы 250 һум.", text)
    }

    // ---- R4: итог может быть меньше поездки+подачи (скидка промокода) — читаем total как есть,
    //     не пересчитываем сумму на слух, иначе озвучка разойдётся с чеком на экране ----

    @Test
    fun priceAloudRu_doesNotRecomputeTotal_readsItVerbatim() {
        // 200 + 50 = 250, но итог (после скидки) — 180: озвучка обязана сказать именно 180,
        // а не "честно досчитанные" 250 — иначе голос и глаз видят разные суммы.
        val text = priceAloudRu(ridePrice = 200, pickupFee = 50, total = 180)
        assertEquals("Поездка 200 рублей, дорога водителя 50 рублей. Всего 180 рублей.", text)
    }
}
