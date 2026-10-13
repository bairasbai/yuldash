package com.yuldash.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Запуск волнами (13.10.2026): разбор флагов направлений и решение гейта.
 *
 * Главное правило — НЕИЗВЕСТНО ЗНАЧИТ ВЫКЛЮЧЕНО. Первый запуск без сети, кривой ответ,
 * строка вместо true — человек должен увидеть «скоро», а не экран, который упрётся в отказ.
 */
class FeatureFlagsTest {

    private val all = Feature.entries.toSet()

    @Test
    fun `нет ответа — ничего не включено и состояние неизвестно`() {
        val set = FeatureFlags.parse(null, fetchedAtMs = 1L)
        assertFalse(set.known)
        Feature.entries.forEach { assertFalse("$it включено без ответа сервера", set.isOn(it)) }
        assertFalse(set.deliveryOn)
    }

    @Test
    fun `включено ровно то, где сервер сказал true`() {
        val set = FeatureFlags.parse(
            JSONObject("""{"rides":true,"taxi":false,"courier":true,"parcels":false,"ads":true,"business":false}"""),
            fetchedAtMs = 5L,
        )
        assertTrue(set.known)
        assertEquals(setOf(Feature.Courier, Feature.Ads), set.on)
        assertTrue("курьер включён — доставка открыта", set.deliveryOn)
        assertEquals(5L, set.fetchedAtMs)
    }

    @Test
    fun `строка, число, null и пропуск — это не включено`() {
        val set = FeatureFlags.parse(
            JSONObject("""{"taxi":"true","courier":1,"parcels":null,"ads":"yes"}"""),
            fetchedAtMs = 1L,
        )
        assertTrue("ответ пришёл — состояние известно", set.known)
        assertTrue("кривые значения не должны ничего включать: ${set.on}", set.on.isEmpty())
    }

    @Test
    fun `незнакомые поля не мешают, пустой ответ — всё выключено`() {
        val set = FeatureFlags.parse(JSONObject("""{"rides":true,"spaceships":true}"""), fetchedAtMs = 1L)
        assertTrue(set.known)
        assertTrue(set.on.isEmpty())
    }

    @Test
    fun `доставка открыта, если открыта хоть одна её половина`() {
        assertTrue(FeatureSet(setOf(Feature.Parcels), true, 0).deliveryOn)
        assertTrue(FeatureSet(setOf(Feature.Courier), true, 0).deliveryOn)
        assertFalse(FeatureSet(setOf(Feature.Taxi, Feature.Ads, Feature.Business), true, 0).deliveryOn)
    }

    @Test
    fun `свежесть кеша`() {
        val fresh = FeatureSet(all, known = true, fetchedAtMs = 1_000_000L)
        assertTrue("неизвестное спрашиваем всегда", FeatureFlags.isStale(FeatureSet.UNKNOWN, 1_000_000L))
        assertFalse(FeatureFlags.isStale(fresh, 1_000_000L + FeatureFlags.MAX_AGE_MS))
        assertTrue(FeatureFlags.isStale(fresh, 1_000_000L + FeatureFlags.MAX_AGE_MS + 1))
        assertTrue("часы перевели назад — не верим метке, спрашиваем заново", FeatureFlags.isStale(fresh, 999_000L))
    }

    @Test
    fun `гейт пускает только во включённое`() {
        val open: (FeatureSet) -> Boolean = { it.isOn(Feature.Ads) }
        val on = FeatureSet(setOf(Feature.Ads), known = true, fetchedAtMs = 0)
        val off = FeatureSet(emptySet(), known = true, fetchedAtMs = 0)
        assertEquals(GatePhase.Open, gatePhase(on, settled = true, open))
        assertEquals(GatePhase.Open, gatePhase(on, settled = false, open))
        assertEquals(GatePhase.Soon, gatePhase(off, settled = true, open))
        // Сервер уже сказал «выключено» (из кеша) — свежий запрос не повод прятать ответ за загрузкой.
        assertEquals(GatePhase.Soon, gatePhase(off, settled = false, open))
    }

    @Test
    fun `первый запуск — загрузка до первого ответа, потом «скоро», а не вечный скелет`() {
        val open: (FeatureSet) -> Boolean = { it.deliveryOn }
        assertEquals(GatePhase.Checking, gatePhase(FeatureSet.UNKNOWN, settled = false, open))
        assertEquals(GatePhase.Soon, gatePhase(FeatureSet.UNKNOWN, settled = true, open))
    }
}
