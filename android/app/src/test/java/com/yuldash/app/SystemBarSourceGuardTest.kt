package com.yuldash.app

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Every full-screen Material3 sheet owns a Window and must share the status policy. */
class SystemBarSourceGuardTest {
    private fun hasDirectSheetCall(source: String): Boolean {
        val aliases = Regex("""(?m)^import\s+androidx\.compose\.material3\.ModalBottomSheet\s+as\s+(\w+)\s*$""")
            .findAll(source).map { it.groupValues[1] }.toList()
        val body = source.lineSequence().filterNot { it.trimStart().startsWith("import ") }.joinToString("\n")
        return (listOf("ModalBottomSheet") + aliases).any {
            Regex("""\b${Regex.escape(it)}\s*\(""").containsMatchIn(body)
        }
    }

    @Test fun materialSheetsCannotBypassTheSharedWindowPolicy() {
        val root = File("src/main/java/com/yuldash/app")
        val offenders = root.walkTopDown().filter { it.extension == "kt" && it.name != "AppSystemBarProtection.kt" }
            .filter { hasDirectSheetCall(it.readText()) }.map { it.relativeTo(root).path }.toList()
        assertTrue("Шторки обходят общую политику часов: $offenders", offenders.isEmpty())
    }

    @Test fun unusedImportsDoNotCreateAWindow() {
        assertFalse(hasDirectSheetCall("import androidx.compose.material3.ModalBottomSheet\nfun screen() {}"))
        assertFalse(hasDirectSheetCall("import androidx.compose.material3.ModalBottomSheet as Sheet\nfun screen() {}"))
        assertFalse(hasDirectSheetCall("AppModalBottomSheet(onDismissRequest = {}) {}"))
    }

    @Test fun aliasesAndQualifiedCallsCannotHideAWindow() {
        assertTrue(hasDirectSheetCall("ModalBottomSheet(onDismissRequest = {}) {}"))
        assertTrue(hasDirectSheetCall("androidx.compose.material3.ModalBottomSheet(onDismissRequest = {}) {}"))
        assertTrue(hasDirectSheetCall("import androidx.compose.material3.ModalBottomSheet as Sheet\nSheet(onDismissRequest = {}) {}"))
    }
}
