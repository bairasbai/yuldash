package com.yuldash.app.walk.l2_3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * leaf-2.3, 152-ФЗ: ни один из семи файлов листа не смеет писать в лог телефон водителя
 * (`TripPass.driverPhone`), координаты точки сбора, текст переписки из очереди исходящих или
 * содержимое экспорта "Мои данные". Сейчас ни один из них не вызывает `Log`/`println` вовсе —
 * но это не самоочевидно и не закреплено нигде: будущая правка (например, отладочный `Log.d`
 * при разборе JSON) могла бы тихо начать писать `pass.driverPhone` в logcat, и ни один
 * существующий тест этого не заметил бы — Robolectric не проверяет содержимое системного лога.
 * Сторож того же вида, что `MoneySourceGuardTest`/`ScrubMirrorsServerTest`: читает исходники
 * и падает на самом факте вызова `Log.`/`println(`, а не пытается угадать, что именно туда
 * попадёт.
 */
class NoPersonalDataLoggingTest {
    private val files = listOf(
        "MyDataScreen.kt",
        "data/OfflineLegacyStores.kt",
        "data/OfflineMigration.kt",
        "data/OfflineStoreReset.kt",
        "data/OfflineWrites.kt",
        "data/PersonalDataExports.kt",
        "data/TripPass.kt",
    )

    private fun sourceDir(): File {
        var dir = File("").absoluteFile
        repeat(4) {
            val src = File(dir, "app/src/main/java/com/yuldash/app")
            if (src.isDirectory) return src
            val src2 = File(dir, "src/main/java/com/yuldash/app")
            if (src2.isDirectory) return src2
            dir = dir.parentFile ?: error("could not locate com.yuldash.app source root")
        }
        error("could not locate com.yuldash.app source root")
    }

    @Test fun `сторож видит все семь файлов листа`() {
        val dir = sourceDir()
        val missing = files.filterNot { File(dir, it).isFile }
        assertTrue("не нашёл файлы листа, сторож ничего не проверяет: $missing", missing.isEmpty())
        assertEquals(7, files.size)
    }

    @Test fun `ни один файл листа не пишет в лог или на консоль`() {
        val dir = sourceDir()
        val offenders = mutableListOf<String>()
        for (relative in files) {
            val text = File(dir, relative).readText()
            if (Regex("""\bLog\.[a-zA-Z]+\(""").containsMatchIn(text)) offenders.add("$relative: Log.*(")
            if (Regex("""\bprintln\(""").containsMatchIn(text)) offenders.add("$relative: println(")
            if (Regex("""\bprint\(""").containsMatchIn(text)) offenders.add("$relative: print(")
        }
        assertTrue(
            "личные данные (телефон водителя, координаты, текст сообщений, дамп экспорта) " +
                "не должны оказаться в логе/консоли — найден вызов лога там, где его не должно " +
                "быть вовсе: $offenders",
            offenders.isEmpty(),
        )
    }
}
