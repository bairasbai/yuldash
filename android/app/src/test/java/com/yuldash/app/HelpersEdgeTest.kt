package com.yuldash.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

/**
 * Крайние ветки чистых хелперов форматирования (JVM, без Android-фреймворка).
 * Дополняет CoreLogicTest — здесь только НЕпокрытые случаи fmtKm / formatDepart / apiCategoryToUiFor.
 * Ожидания выведены трассировкой кода (String.format с Locale.US → разделитель точка).
 * Запуск: gradlew :app:testDebugUnitTest
 */
class HelpersEdgeTest {

    // Пояс фиксируем: время показывается в часах человека, и без этого тест зависел бы от
    // того, в каком городе его запустили (разбор №2, 2026-08-03).
    private var savedTz: TimeZone? = null

    @Before
    fun fixTimeZone() {
        savedTz = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Yekaterinburg"))   // Уфа, UTC+5
    }

    @After
    fun restoreTimeZone() {
        savedTz?.let { TimeZone.setDefault(it) }
    }

    // fmtKm: ноль → d<10 → "%.1f" → "0.0" (не спецкейс "10.0").
    @Test
    fun fmtKm_zeroKeepsOneDecimal() {
        assertEquals("0.0", fmtKm(0.0))
    }

    // fmtKm: целое < 10 всё равно показывается с одним знаком после точки.
    @Test
    fun fmtKm_wholeValueBelowTenKeepsDecimal() {
        assertEquals("5.0", fmtKm(5.0))
    }

    // fmtKm: 9.96 при округлении "%.1f" даёт "10.0" → спецкейс схлопывает в "10".
    @Test
    fun fmtKm_justBelowTenRoundsToTenWithoutDecimal() {
        assertEquals("10", fmtKm(9.96))
    }

    // fmtKm: ровно 10.0 → ветка Math.round → "10" (без дробной части).
    @Test
    fun fmtKm_exactlyTenHasNoDecimal() {
        assertEquals("10", fmtKm(10.0))
    }

    // fmtKm: выше 10 → Math.round, дробная часть отбрасывается (округление вниз здесь).
    @Test
    fun fmtKm_largeValueRoundsToWholeKm() {
        assertEquals("123", fmtKm(123.4))
    }

    // fmtKm: выше 10 с .5 → Math.round округляет вверх (10.5 → 11).
    @Test
    fun fmtKm_aboveTenRoundsHalfUp() {
        assertEquals("11", fmtKm(10.5))
    }

    // formatDepart: полночь ПО UTC — на экране это 05:00 по Уфе, а не 00:00.
    @Test
    fun formatDepart_midnightFormatsToDayMonthAndZeroTime() {
        assertEquals("31.12, 05:00", formatDepart("2026-12-31T00:00:00"))
    }

    // formatDepart: пустая строка не разбирается ни как дата, ни как момент → возвращаем вход ("").
    @Test
    fun formatDepart_emptyStringReturnsInput() {
        assertEquals("", formatDepart(""))
    }

    // formatDepart: есть 'T', но время слишком короткое → substring(0,5) падает → возвращаем исходную строку.
    @Test
    fun formatDepart_hasTButTooShortTimeReturnsInput() {
        assertEquals("2026-01-05T", formatDepart("2026-01-05T"))
    }

    // apiCategoryToUiFor: "parcel" — двуязычно (в коде обе подписи "Посылка").
    @Test
    fun apiCategoryToUiFor_mapsParcel() {
        assertEquals("Посылка", apiCategoryToUiFor(AppLanguage.Ru, "parcel", withKids = false))
        assertEquals("Посылка", apiCategoryToUiFor(AppLanguage.Ba, "parcel", withKids = false))
    }

    // apiCategoryToUiFor: "cargo" — RU «Груз», BA «Йөк».
    @Test
    fun apiCategoryToUiFor_mapsCargoBilingually() {
        assertEquals("Груз", apiCategoryToUiFor(AppLanguage.Ru, "cargo", withKids = false))
        assertEquals("Йөк", apiCategoryToUiFor(AppLanguage.Ba, "cargo", withKids = false))
    }

    // apiCategoryToUiFor: категория важнее флага «с детьми» — urgent+withKids всё равно «Срочно».
    @Test
    fun apiCategoryToUiFor_categoryWinsOverWithKidsFlag() {
        assertEquals("Срочно", apiCategoryToUiFor(AppLanguage.Ru, "urgent", withKids = true))
    }

    // apiCategoryToUiFor: обычная поездка без детей на башкирском → «Ғәҙәти».
    @Test
    fun apiCategoryToUiFor_regularNoKidsBashkir() {
        assertEquals("Ғәҙәти", apiCategoryToUiFor(AppLanguage.Ba, "regular", withKids = false))
    }

    // apiCategoryToUiFor: неизвестная категория без детей → фолбэк «Обычная».
    @Test
    fun apiCategoryToUiFor_unknownCategoryFallsBackToRegular() {
        assertEquals("Обычная", apiCategoryToUiFor(AppLanguage.Ru, "foobar", withKids = false))
    }
}
