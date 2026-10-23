package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Храповик размера файлов. Читает исходники приложения и падает, если файл вырос.
 *
 * Зачем. К октябрю 2026 `InstantOrderScreen.kt` дорос до 8254 строк, `ApiClient.kt` — до 8179.
 * В таком файле экран, сеть и состояние перемешаны, и каждая новая гонка чинится ещё одним
 * флагом рядом с прежними (экран контактов набрал так около 400 строк машинерии). Пилот слоёв
 * «экран → ViewModel экрана → репозиторий → API домена» (decisions.md, «Слои Android-экрана»)
 * показал выход: код домена уезжает в свои файлы. Этот тест не даёт большим файлам расти,
 * пока их разбирают по одному: сегодняшний размер — потолок.
 *
 * Правила:
 *  • файл из [ceilings] не может стать длиннее своего потолка;
 *  • любой другой файл (в том числе новый) — не длиннее [DEFAULT_CEILING] строк;
 *  • похудел больше чем на [SLACK] строк — опусти потолок до нового размера, чтобы выигрыш не съели.
 *
 * Упал на росте — не поднимай потолок не подумав. Новый код домена — в свой файл
 * (ViewModel экрана, репозиторий, API домена), а не в общий котёл.
 */
class FileSizeRatchetTest {

    private val ceilings = mapOf(
        "InstantOrderScreen.kt" to 8258,   // 13.10.2026: +4 — шторка «Куда едем?» не прячется, пока уходит смена адреса
        "data/ApiClient.kt" to 7853,   // 18.10.2026 (приёмка PR184): −34 — DriverBookingDto и его разбор уехали в data/DriverBookings.kt, потолок опущен до нового размера; ранее 18.10: +2 no_show_needs_arriving; +2 no_show_at в DriverBookingDto (поле и разбор; «Не пришёл» через 15 мин после выезда); 14.10.2026: +12 опции мест (поля DTO и параметры запросов; UI — SeatOptions.kt), +2 passenger_id в DriverBookingDto (разбор по брони «в пути»)
        "ProfileScreen.kt" to 4748,   // 23.10.2026: −81 — оценка пассажира водителем уехала в DriverPassengerRow.kt (PassengerStarsRating), потолок опущен; 16.10.2026: +9 — флаг такси в кабинетах (волна 1) и «Открыть поездку» в «Едут с тобой»; 14.10.2026: +4 — опции мест (UI — SeatOptions.kt)
        // 13.10.2026: чат поездки уехал в TripChatState.kt / TripChatScreen.kt — потолок опущен до нового размера.
        "BookingActiveTripScreen.kt" to 3587,   // 17.10: нижняя панель — в ActiveTripDecisionBar.kt, посадка — в RideshareBoarding.kt; 16.10: маршрутная карточка вынесена
        "RidesRequestsChatScreens.kt" to 3252,   // 23.10.2026: −110 — экран откликов на заявку (обёртка с границей входа) уехал в RequestResponsesScreen.kt, потолок опущен; 13.10.2026: +2 — запись после «Разрешить» (withResumed)
        "ParcelsScreen.kt" to 3044,
        "YuldashApp.kt" to 2788,
        "CourierScreen.kt" to 2553,
        "SecondaryScreens.kt" to 2345,   // 23.10.2026: −229 — «Мои подписки» уехали в RouteWatchesScreen.kt, сетевая часть — в data/RouteWatchApi.kt; потолок опущен
        "MapScreen.kt" to 2224,   // 16.10.2026: ряд из 12 чипов → 4 вопроса, условия — в NearbyFiltersPanel.kt; −9 мёртвых импортов
        "AccessibilityScreens.kt" to 2150,   // 13.10.2026: +1 — импорт withResumed для того же фикса микрофона
        "TaxiTripScreen.kt" to 1629,
        "FairnessScreens.kt" to 1612,   // 14.10.2026: +1 — initialType у FileIncidentDialog (разбор «Пассажир не вышел» сразу с типом)
        "LoginScreen.kt" to 1330,
        "TaxiDocsScreens.kt" to 1200,
        "SosVerifyScreens.kt" to 1111,   // 23.10.2026: −20 — карточки итога сигнала уехали в SosSignalResult.kt; −135 — место для SOS (свежесть, разрешение, GPS) уехало в SosLocation.kt; потолок опущен
        "CreateRideScreen.kt" to 1167,   // 14.10.2026: +9 — состояние и проводка опций мест (карточка — SeatOptions.kt)
        "TaxiOnboardingScreen.kt" to 1108,
        "TaxiReceiptScreen.kt" to 1070,
        "RideshareCompletedScreen.kt" to 1064,
        "MainActivity.kt" to 1037,
        "SupportBoostScreen.kt" to 951,
        "MobilityUi.kt" to 874,
        "PartnerCabinetScreen.kt" to 818,
        "TrustScreens.kt" to 769,
        "CouponsScreen.kt" to 866,
        "SavedPlacesScreen.kt" to 679,
        "AdminIncidentsScreen.kt" to 651,
        "CarPhotoScreen.kt" to 643,
        "MyDataScreen.kt" to 641,
        "WalletScreen.kt" to 618,
        "TaxiDriverCompletedScreen.kt" to 607,
        "CourierOnboardingScreen.kt" to 606,
    )

    private fun sourceRoot(): File? {
        var dir = File("").absoluteFile
        repeat(4) {
            listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app").forEach { path ->
                val src = File(dir, path)
                if (src.isDirectory) return src
            }
            dir = dir.parentFile ?: return null
        }
        return null
    }

    /** Путь от корня пакета (`data/ApiClient.kt`) → число строк. */
    private fun sizes(): Map<String, Int> {
        val root = sourceRoot() ?: return emptyMap()
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readLines().size }
    }

    @Test
    fun `исходники на месте — иначе храповик ничего не держит`() {
        assertTrue("не нашёл исходники приложения", sizes().size > 100)
    }

    @Test
    fun `большой файл не растёт выше своего потолка`() {
        val sizes = sizes()
        val grown = ceilings.mapNotNull { (file, ceiling) ->
            val actual = sizes[file] ?: return@mapNotNull null
            if (actual > ceiling) "$file: $actual строк при потолке $ceiling (+${actual - ceiling})" else null
        }
        assertTrue(
            "Файлы выросли — вынеси новый код в отдельный файл домена (см. decisions.md, «Слои Android-экрана»):\n" +
                grown.joinToString("\n") { "    $it" },
            grown.isEmpty(),
        )
    }

    @Test
    fun `новый и небольшой файл не длиннее общего потолка`() {
        val big = sizes().filter { (file, lines) -> file !in ceilings && lines > DEFAULT_CEILING }
            .map { (file, lines) -> "$file: $lines строк" }
        assertTrue(
            "Файлы длиннее $DEFAULT_CEILING строк — раздели по слоям, а не заводи новый котёл:\n" +
                big.joinToString("\n") { "    $it" },
            big.isEmpty(),
        )
    }

    @Test
    fun `похудевший файл закрепляет выигрыш`() {
        val sizes = sizes()
        val loose = ceilings.mapNotNull { (file, ceiling) ->
            val actual = sizes[file]
            when {
                actual == null -> "$file: файла больше нет — убери строку из ceilings"
                ceiling - actual > SLACK -> "$file: сейчас $actual строк — опусти потолок с $ceiling до $actual"
                else -> null
            }
        }
        assertTrue("Храповик ослаб:\n" + loose.joinToString("\n") { "    $it" }, loose.isEmpty())
    }

    private companion object {
        const val DEFAULT_CEILING = 600
        const val SLACK = 50
    }
}
