package com.yuldash.app

import com.yuldash.app.data.SettlementDto
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CreateRideStopsTest {
    private val village = SettlementDto(1, "Берёзовка", "Ҡайынлыҡ", "РБ", "village", 54.0, 56.0, "Иглинский р-н")
    private val namesake = village.copy(id = 2, district = "Уфимский р-н")
    private val center = SettlementDto(3, "Акъяр", "Аҡъяр", "РБ", "district_center", 52.0, 58.0, "Хайбуллинский р-н")
    private val abzakovo = SettlementDto(7, "Абзаково", "Абҙаҡ", "РБ", "village", 53.0, 58.0, "Белорецкий р-н")
    private val otherAbzakovo = abzakovo.copy(id = 8, district = "Учалинский р-н")

    @Test fun canonicalNamesKeepDistrictForVillagesAndDistrictCenters() = runBlocking {
        val values = listOf("Берёзовка (Иглинский р-н)", "Акъяр")
        val result = resolveRideStops(values, "Уфа", "Сибай", AppLanguage.Ru) { listOf(village, namesake, center) }
        assertEquals(listOf("Берёзовка (Иглинский р-н)", "Акъяр (Хайбуллинский р-н)"), result)
        assertEquals(listOf("Берёзовка (Иглинский р-н)", "Акъяр"), values)
    }

    @Test fun bashkirSelectionUsesTheSameDictionaryAndDistrict() = runBlocking {
        assertEquals(listOf("Ҡайынлыҡ (Иглинский р-н)"),
            resolveRideStops(listOf("Ҡайынлыҡ (Иглинский р-н)"), "Өфө", "Сибай", AppLanguage.Ba) { listOf(village, namesake) })
    }

    @Test fun phoneAddressDigitsAndOversizeTextNeverReachSearch() = runBlocking {
        for (value in listOf("+7 900 111-22-33", "ул. Ленина 5", "123", "Д".repeat(61), "дом_у_реки")) {
            var calls = 0
            expect(RideStopProblem.Invalid) {
                resolveRideStops(listOf(value), "Уфа", "Сибай", AppLanguage.Ru) { calls++; emptyList() }
            }
            assertEquals(value, 0, calls)
        }
    }

    @Test fun unknownAndAmbiguousNamesAreNotPublished() = runBlocking {
        expect(RideStopProblem.Invalid) { resolveRideStops(listOf("Берёзовка"), "Уфа", "Сибай", AppLanguage.Ru) { listOf(village, namesake) } }
        expect(RideStopProblem.Invalid) { resolveRideStops(listOf("Вымышленное"), "Уфа", "Сибай", AppLanguage.Ru) { listOf(village) } }
    }

    @Test fun endpointsAreExcludedAcrossLanguagesAndPlacePrefixes() = runBlocking {
        expect(RideStopProblem.Endpoint) { resolveRideStops(listOf("Акъяр"), "с. Аҡъяр", "Сибай", AppLanguage.Ru) { listOf(center) } }
        expect(RideStopProblem.Endpoint) { resolveRideStops(listOf("Аҡъяр"), "Уфа", "Акъяр", AppLanguage.Ba) { listOf(center) } }
    }

    @Test fun repeatedStopsAreRejectedAcrossNamesAndLanguages() = runBlocking {
        expect(RideStopProblem.Duplicate) {
            resolveRideStops(listOf("Берёзовка (Иглинский р-н)", "Ҡайынлыҡ (Иглинский р-н)"), "Уфа", "Сибай", AppLanguage.Ru) { listOf(village) }
        }
    }

    @Test fun atMostFourStopsAndNoSilentTruncation() = runBlocking {
        val towns = (1..4).map { center.copy(id = it, nameRu = listOf("А", "Б", "В", "Г")[it - 1], nameBa = null) }
        assertEquals(4, resolveRideStops(towns.map { it.nameRu }, "Уфа", "Сибай", AppLanguage.Ru) { towns }.size)
        var calls = 0
        expect(RideStopProblem.TooMany) { resolveRideStops(listOf("А", "Б", "В", "Г", "Д"), "Уфа", "Сибай", AppLanguage.Ru) { calls++; towns } }
        assertEquals(0, calls)
    }

    @Test fun failedLookupPreservesDraftAndPropagatesTheOriginalError() = runBlocking {
        val values = listOf("Берёзовка (Иглинский р-н)")
        val failure = IllegalStateException("Справочник временно недоступен")
        try {
            resolveRideStops(values, "Уфа", "Сибай", AppLanguage.Ru) { throw failure }
            fail("Должна сохраниться ошибка справочника")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals(listOf("Берёзовка (Иглинский р-н)"), values)
    }

    @Test fun namesakesInDifferentDistrictsAreDifferentStops() = runBlocking {
        val values = listOf("Абзаково (Белорецкий р-н)", "Абзаково (Учалинский р-н)")
        assertEquals(values, resolveRideStops(values, "Уфа", "Сибай", AppLanguage.Ru) { listOf(abzakovo, otherAbzakovo) })
    }

    @Test fun namesakeOfExplicitEndpointInAnotherDistrictIsAllowed() = runBlocking {
        assertEquals(listOf("Абзаково (Учалинский р-н)"), resolveRideStops(listOf("Абзаково (Учалинский р-н)"),
            "с. Абҙаҡ (Белорецкий р-н)", "Уфа", AppLanguage.Ru) { listOf(abzakovo, otherAbzakovo) })
        expect(RideStopProblem.Endpoint) { resolveRideStops(listOf("Абҙаҡ (Белорецкий р-н)"),
            "Абзаково (Белорецкий р-н)", "Уфа", AppLanguage.Ba) { listOf(abzakovo, otherAbzakovo) } }
    }

    @Test fun endpointWithoutDistrictDoesNotGuessBetweenNamesakes() = runBlocking {
        expect(RideStopProblem.Endpoint) { resolveRideStops(listOf("Абзаково (Учалинский р-н)"),
            "Абзаково", "Уфа", AppLanguage.Ru) { listOf(abzakovo, otherAbzakovo) } }
    }

    @Test fun ruAndBaOfOneNamesakeAreStillOneStop() = runBlocking {
        expect(RideStopProblem.Duplicate) { resolveRideStops(listOf("Абзаково (Белорецкий р-н)", "Абҙаҡ (Белорецкий р-н)"),
            "Уфа", "Сибай", AppLanguage.Ru) { listOf(abzakovo, otherAbzakovo) } }
    }

    private suspend fun expect(problem: RideStopProblem, block: suspend () -> Unit) {
        try { block(); fail("Ожидался отказ $problem") }
        catch (error: RideStopException) { assertEquals(problem, error.problem) }
    }
}
