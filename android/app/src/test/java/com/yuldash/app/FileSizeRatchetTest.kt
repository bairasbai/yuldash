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
        "InstantOrderScreen.kt" to 8254,
        "data/ApiClient.kt" to 8044,
        "ProfileScreen.kt" to 4834,
        "BookingActiveTripScreen.kt" to 3989,
        "RidesRequestsChatScreens.kt" to 3556,
        "ParcelsScreen.kt" to 3044,
        "YuldashApp.kt" to 2788,
        "CourierScreen.kt" to 2553,
        "SecondaryScreens.kt" to 2507,
        "MapScreen.kt" to 2292,
        "AccessibilityScreens.kt" to 2149,
        "TaxiTripScreen.kt" to 1629,
        "FairnessScreens.kt" to 1611,
        "LoginScreen.kt" to 1330,
        "TaxiDocsScreens.kt" to 1200,
        "SosVerifyScreens.kt" to 1188,
        "CreateRideScreen.kt" to 1158,
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
