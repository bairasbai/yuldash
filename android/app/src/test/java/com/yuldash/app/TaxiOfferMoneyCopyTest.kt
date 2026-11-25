package com.yuldash.app

import com.yuldash.app.data.toTaxiOfferMoneyDto
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class TaxiOfferMoneyCopyTest {
    private fun copy(json: String, language: AppLanguage = AppLanguage.Ru) =
        taxiOfferMoneyCopy(JSONObject(json).toTaxiOfferMoneyDto(), language)

    @Test
    fun `legacy fare and missing net are not treated as income`() {
        assertEquals("Уточняется", copy("""{"price_estimate":700}""").income)
        val incomplete = copy("""{"driver_gross_kop":70000,"driver_net_kop":null}""", AppLanguage.Ba)
        assertEquals("Асыҡлана", incomplete.income)
        assertFalse(incomplete.incomeKnown)
        assertNull(incomplete.breakdown)
        assertEquals("Уточняется", copy("""{"driver_gross_kop":0,"driver_net_kop":0}""").income)
    }

    @Test
    fun `explicit zero and partial fee information are distinct`() {
        val result = copy("""{"driver_gross_kop":70000,"driver_net_kop":0,"driver_fee_kop":0}""")
        assertEquals("0 ₽", result.income)
        assertTrue(result.incomeKnown)
        assertEquals("Пассажир платит 700 ₽ · комиссия 0 ₽", result.breakdown)
        assertNull(copy("""{"driver_gross_kop":70000,"driver_net_kop":62000}""").breakdown)
    }

    @Test
    fun `server kopecks and commission match the web in both languages`() {
        val offer = """{"driver_gross_kop":70000,"driver_net_kop":57575,"driver_fee_kop":12425,"driver_fee_percent":17.75}"""
        assertEquals("575,75 ₽", copy(offer).income)
        assertEquals("Пассажир платит 700 ₽ · комиссия 124,25 ₽ (17,75%)", copy(offer).breakdown)
        assertEquals("Пассажир 700 ₽ түләй · комиссия 124,25 ₽ (17,75%)", copy(offer, AppLanguage.Ba).breakdown)
        assertEquals("1\u00A0234,56 ₽", copy("""{"driver_gross_kop":200000,"driver_net_kop":123456}""").income)
    }

    @Test
    fun `strings fractional and out of range amounts are not accepted`() {
        for (value in listOf("\"0\"", "1.5", "-1", "70001", "2147483648")) {
            assertEquals("Уточняется", copy("""{"driver_gross_kop":70000,"driver_net_kop":$value}""").income)
        }
        assertEquals("Пассажир платит 700 ₽ · комиссия 1 ₽",
            copy("""{"driver_gross_kop":70000,"driver_fee_kop":100,"driver_fee_percent":101}""").breakdown)
    }
}
