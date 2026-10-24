package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Сторож режима клавиатуры. Экран, который сам отступает под клавиатуру (`imePadding()`,
 * `WindowInsets.ime`), обязан на время показа включать «поджимать» — `ResizeWindowForKeyboard()`
 * (KeyboardResizesWindow.kt). Иначе окно остаётся «сдвигать»: на Android 15+ система сдвигает окно
 * целиком, а наш отступ ещё раз поднимает содержимое — двойной скачок, шапка с «Назад» уезжает под
 * строку состояния (эмулятор Android 15, экран отзыва, 24.10.2026; раньше — вход и чат, приёмка 18.10, п.15).
 *
 * Читает исходники по файлам. Исключение — только поимённо и с причиной: шторки и диалоги живут в
 * своём окне, режим окна Activity на них не действует.
 */
class KeyboardResizeGuardTest {

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

    private val ownWindow = mapOf(
        "NearbyFiltersPanel.kt" to "фильтры — в нижней шторке (AppModalBottomSheet), у неё своё окно",
        "RequestOfferSheet.kt" to "отклик на заявку — в нижней шторке RequestsFeedScreen, своё окно",
        "RideDeparturePicker.kt" to "выбор времени выезда — AlertDialog, своё окно",
    )
    private val keyboardInset = Regex("""\.imePadding\(\)|WindowInsets\.ime\b""")
    private val resize = Regex("""(?<!fun )ResizeWindowForKeyboard\(\)""")

    private fun code(file: File) = file.readLines()
        .map { it.trim() }
        .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }
        .joinToString("\n") { it.substringBefore("//") }

    @Test
    fun everyScreenThatPadsForTheKeyboardAlsoResizesTheWindow() {
        val root = sourceRoot() ?: error("не нашёл исходники приложения")
        val padded = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { keyboardInset.containsMatchIn(code(it)) }.toList()
        assertTrue("нашёл слишком мало экранов с отступом под клавиатуру — сторож ослеп", padded.size >= 8)

        val bare = padded.filter { it.name !in ownWindow && !resize.containsMatchIn(code(it)) }.map { it.name }
        assertTrue(
            "Экран отступает под клавиатуру, но не включает «поджимать» — на Android 15+ двойной скачок. " +
                "Добавь `ResizeWindowForKeyboard()` в composable экрана (KeyboardResizesWindow.kt):\n" +
                bare.joinToString("\n") { "    $it" },
            bare.isEmpty(),
        )
    }

    @Test
    fun exceptionsStillPadForTheKeyboard() {
        val root = sourceRoot() ?: error("не нашёл исходники приложения")
        val stale = ownWindow.keys.filter { name ->
            val file = File(root, name)
            !file.isFile || !keyboardInset.containsMatchIn(code(file))
        }
        assertTrue("исключения сторожа устарели — убери их из списка: $stale", stale.isEmpty())
    }
}
