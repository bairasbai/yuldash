package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Сторож: экран, который показывает ЧУЖИЕ документы, обязан закрывать себя от скриншота.
 *
 * Зачем (аудит 2026-08-12, волна 30). В очереди модерации видны права водителя, селфи курьера,
 * фото машины — паспортные данные посторонних. Android по умолчанию кладёт снимок последнего
 * экрана в переключатель приложений и разрешает скриншот кому угодно. На всё приложение флаг
 * вешать нельзя: скриншот поездки — полезная вещь («скинул мужу номер машины»). Значит защита
 * точечная, а точечную легко забыть на следующем таком экране.
 *
 * Тест читает исходники (как `CanonSourceGuardTest`): нашёл показ документа — требует
 * `SecureWindow()` в том же файле. Появится четвёртый экран с документами — станет красным
 * раньше, чем чужой паспорт попадёт в чей-то скриншот.
 */
class SecureWindowGuardTest {

    /** Признаки того, что файл РИСУЕТ приватный документ (а не просто упоминает поле). */
    private val docRenderers = listOf("DocImage(", "TaxiDocImage(", "CourierDocImage(")

    private fun sourceFiles(): List<File> {
        val root = File("src/main/java/com/yuldash/app")
        assertTrue("не нашёл исходники приложения: ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `экраны с чужими документами закрыты от скриншота`() {
        val offenders = sourceFiles().filter { file ->
            val src = file.readText()
            // Считаем только ВЫЗОВЫ рисовальщика, а не его объявление: компонент часто лежит
            // в том же файле, что и экран, и по одному лишь имени эти два случая неразличимы.
            // Первая версия этого теста именно на этом и ослепла: файл с объявлением целиком
            // выпадал из проверки, и снятая защита прошла мимо (мутация волны 30).
            val callsRenderer = docRenderers.any { token ->
                Regex("(?<!fun )" + Regex.escape(token)).containsMatchIn(src)
            }
            callsRenderer && !src.contains("SecureWindow()")
        }
        assertTrue(
            "эти экраны показывают чужие документы, но не защищены от скриншота — " +
                "добавь SecureWindow() в начало экрана: ${offenders.map { it.name }}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `защита снимается при уходе с экрана, а не залипает навсегда`() {
        val src = File("src/main/java/com/yuldash/app/SecureWindow.kt").readText()
        assertTrue("SecureWindow должен снимать флаг в onDispose", src.contains("onDispose"))
        assertTrue("должен быть clearFlags — иначе защита залипнет на всё приложение",
            src.contains("clearFlags"))
        assertTrue("нужен счётчик вложенности, иначе выход из верхнего экрана снимет защиту с нижнего",
            src.contains("secureDepth"))
    }
}
