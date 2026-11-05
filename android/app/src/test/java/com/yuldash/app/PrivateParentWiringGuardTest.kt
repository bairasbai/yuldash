package com.yuldash.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Standalone previews/tests may omit a parent; every production caller must wire one explicitly. */
class PrivateParentWiringGuardTest {
    @Test fun everyProductionPrivateScreenCallerSuppliesAParentGuard() {
        var directory: File? = File("").absoluteFile
        var root: File? = null
        repeat(5) {
            directory?.let { current ->
                listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app").forEach { relative ->
                    File(current, relative).takeIf(File::isDirectory)?.let { root = it }
                }
                if (root == null) directory = current.parentFile
            }
        }
        val source = requireNotNull(root) { "Production source root must exist" }
        val names = setOf("BookingScreen", "ActiveTripScreen", "RideshareCompletedScreen")
        val found = mutableSetOf<String>()
        val ignored = Regex("/\\*[\\s\\S]*?\\*/|//[^\\n]*|\"(?:\\\\.|[^\"\\\\])*\"")
        val calls = Regex("\\b(BookingScreen|ActiveTripScreen|RideshareCompletedScreen)\\s*\\(")
        source.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText().replace(ignored, " ")
            calls.findAll(text).forEach { match ->
                val prefix = text.substring(0, match.range.first).takeLast(40)
                if (Regex("\\bfun\\s*$").containsMatchIn(prefix)) return@forEach
                val start = match.range.last
                var end = start + 1
                var depth = 1
                while (end < text.length && depth > 0) {
                    when (text[end]) { '(' -> depth++; ')' -> depth-- }
                    end++
                }
                assertEquals("Balanced call in ${file.name}", 0, depth)
                val arguments = text.substring(start + 1, end - 1)
                assertTrue("${file.name}: ${match.groupValues[1]} must receive its current parent",
                    Regex("\\bisCurrentParent\\s*=\\s*(::[A-Za-z][A-Za-z0-9_]*|\\{\\s*[A-Za-z][A-Za-z0-9_]*\\s*\\(\\s*\\)\\s*})").containsMatchIn(arguments))
                if (match.groupValues[1] != "BookingScreen") {
                    assertTrue("${file.name}: ${match.groupValues[1]} must receive its receipt parent",
                        Regex("\\bisCurrentReceiptParent\\s*=").containsMatchIn(arguments))
                }
                found += match.groupValues[1]
            }
        }
        assertEquals("Both private destinations must have verified production callers", names, found)
    }
}
