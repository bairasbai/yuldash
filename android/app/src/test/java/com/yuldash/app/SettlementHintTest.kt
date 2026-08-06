package com.yuldash.app

import com.yuldash.app.data.SettlementDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Подсказки населённых пунктов, когда в справочнике появились деревни (6600 точек).
 *
 * Зачем эти тесты: «Берёзовок» в Башкортостане четыре, «Ивановок» ещё больше. Если в списке
 * показать одно имя без района — человек ткнёт наугад и поедет не туда; если записать в поле
 * тоже одно имя — сервер тоже возьмёт наугад. Оба места проверяем.
 */
class SettlementHintTest {

    private fun village(name: String, district: String?, region: String = "РБ", ba: String? = null) =
        SettlementDto(id = 1, nameRu = name, nameBa = ba, region = region, kind = "village",
                      lat = 54.0, lng = 56.0, district = district)

    private fun city(name: String, region: String = "РБ", ba: String? = null) =
        SettlementDto(id = 2, nameRu = name, nameBa = ba, region = region, kind = "city",
                      lat = 54.7, lng = 55.9, district = null)

    @Test
    fun `у деревни в подсказке видно район и регион`() {
        assertEquals("Иглинский р-н · РБ", settlementHintFor(village("Берёзовка", "Иглинский р-н")))
        assertEquals(
            "Кизильский р-н · Челябинская обл.",
            settlementHintFor(village("Смеловский", "Кизильский р-н", "Челябинская обл.")),
        )
    }

    @Test
    fun `у города района нет — остаётся только регион`() {
        assertEquals("РБ", settlementHintFor(city("Уфа")))
    }

    @Test
    fun `в поле деревня записывается вместе с районом, город — без`() {
        assertEquals(
            "Берёзовка (Иглинский р-н)",
            settlementPickText(AppLanguage.Ru, village("Берёзовка", "Иглинский р-н")),
        )
        assertEquals("Уфа", settlementPickText(AppLanguage.Ru, city("Уфа")))
    }

    @Test
    fun `на башкирском в поле идёт башкирское имя, район остаётся как есть`() {
        val v = village("Старая Кара", "Аскинский р-н", ba = "Иҫке Ҡара")
        assertEquals("Иҫке Ҡара (Аскинский р-н)", settlementPickText(AppLanguage.Ba, v))
        assertEquals("Уфа", settlementPickText(AppLanguage.Ba, city("Уфа")))          // нет перевода → русское
        assertEquals("Өфө", settlementPickText(AppLanguage.Ba, city("Уфа", ba = "Өфө")))
    }

    @Test
    fun `атрибуцию OpenStreetMap показываем только когда в списке есть деревня`() {
        assertTrue(needsOsmCredit(listOf(city("Уфа"), village("Берёзовка", "Иглинский р-н"))))
        assertFalse(needsOsmCredit(listOf(city("Уфа"), city("Магнитогорск", "Челябинская обл."))))
        assertFalse(needsOsmCredit(emptyList()))
    }

    @Test
    fun `район без названия не оставляет висящую точку-разделитель`() {
        assertEquals("РБ", settlementHintFor(village("Ольховка", null)))
        assertEquals("Ольховка", settlementPickText(AppLanguage.Ru, village("Ольховка", "  ")))
    }
}
