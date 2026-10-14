package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Сторож шва «карта без ключа». Любой composable, который создаёт `MapView` или зовёт
 * `ensureMapKit`, обязан сначала проверить ключ — `MapKitUnavailable(...)` или
 * `mapKitKeyAvailable()` из MapKitGate.kt. Без проверки сборка без `local.properties` (CI, чистый
 * worktree) роняет приложение на `MapKitFactory.initialize` — так падали экран брони, «Отметить на
 * карте» и карта такси (эмулятор, 14.10.2026).
 *
 * Читает исходники. Новую карту без шва тест не пропустит; исключение — только поимённо и с причиной.
 */
class MapKitGateGuardTest {

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

    private val topLevelFun = Regex("""^(?:private |internal |public )*fun """)
    private val mapCreation = Regex("""(?<![\w.])MapView\(|(?<!\w)ensureMapKit\(""")
    private val gate = Regex("""MapKitUnavailable\(|mapKitKeyAvailable\(\)""")

    private fun isComment(code: String) = code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")

    @Test
    fun everyLiveMapIsBehindTheKeyGate() {
        val root = sourceRoot() ?: error("не нашёл исходники приложения")
        var sites = 0
        val bare = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { i, line ->
                val code = line.trim()
                if (isComment(code) || line.contains("fun ensureMapKit(")) return@forEachIndexed
                if (!mapCreation.containsMatchIn(line.substringBefore("//"))) return@forEachIndexed
                sites++
                val start = (i downTo 0).firstOrNull { topLevelFun.containsMatchIn(lines[it]) } ?: 0
                val before = lines.subList(start, i + 1).joinToString("\n") { it.substringBefore("//") }
                if (!gate.containsMatchIn(before)) bare += "${file.name}:${i + 1}  ${code.take(80)}"
            }
        }
        assertTrue("не нашёл ни одного места создания карты — сторож ослеп", sites >= 4)
        assertTrue(
            "Карта создаётся без проверки ключа MapKit — без ключа приложение закроется. " +
                "Первой строкой composable: `if (MapKitUnavailable(modifier)) return` (MapKitGate.kt):\n" +
                bare.joinToString("\n") { "    $it" },
            bare.isEmpty(),
        )
    }

    @Test
    fun mapKitInitializeLivesOnlyBehindEnsureMapKit() {
        val root = sourceRoot() ?: error("не нашёл исходники приложения")
        val direct = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, l) -> l.contains("MapKitFactory.initialize(") && !isComment(l.trim()) }
                .map { (i, _) -> "${file.name}:${i + 1}" }
        }.toList()
        assertTrue(
            "MapKitFactory.initialize зовут только через ensureMapKit (MapKitGate.kt), нашёл ещё: $direct",
            direct.all { it.startsWith("MapKitGate.kt:") },
        )
    }
}
