package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Условия «Ближайших» режет сервер, а «Показать ещё» видна и при условиях (DESIGN023, 16.10.2026).
 *
 * Было: телефон применял условия («детское кресло», «с животными»…) к уже полученной порции из
 * пяти поездок, а «Показать ещё» при включённом условии пряталась. Поездка с креслом шестой в
 * списке превращалась в «поездок нет». Теперь условия уходят на сервер, тот считает `count` по
 * ним, и кнопка «Показать ещё» зависит только от этого счёта.
 */
class NearbyConditionsServerSideTest {

    private fun mapScreen(): String {
        var dir = File("").absoluteFile
        repeat(4) {
            listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app").forEach { path ->
                val file = File(File(dir, path), "MapScreen.kt")
                if (file.isFile) return file.readText()
            }
            dir = dir.parentFile ?: return ""
        }
        return ""
    }

    @Test
    fun `лента отдаёт условия серверу и перезапрашивает при их смене`() {
        val src = mapScreen()
        assertTrue("не нашёл MapScreen.kt", src.isNotEmpty())
        assertTrue(
            "условия снова режутся только на телефоне — подходящая поездка дальше порции пропадёт",
            src.contains("conditions = prefFilter, verifiedOnly = verifiedOnly"),
        )
        assertTrue(
            "смена условий не сбрасывает порцию и не перезапрашивает ленту",
            src.contains("userLatKey, userLngKey, dateFilter, prefFilter) { nearbyLimit = NEARBY_PAGE }"),
        )
    }

    @Test
    fun `показать ещё не прячется при включённых условиях`() {
        val src = mapScreen()
        assertTrue(
            "«Показать ещё» снова прячется при условиях — вернётся ложная «пустота»",
            !src.contains("prefFilter.isEmpty() && nearby.size < nearbyTotal"),
        )
        assertTrue("пропала кнопка «Показать ещё»", src.contains("if (nearby.size < nearbyTotal) {"))
    }
}
