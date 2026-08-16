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

    /**
     * Признак приватной картинки — не имя компонента, а СПОСОБ загрузки.
     *
     * Первая версия сторожа перечисляла имена рисовальщиков («DocImage», «TaxiDocImage»,
     * «CourierDocImage») и потому видела только документы. Мимо неё спокойно прошли фото
     * из разбора спора и снимки посылки: они грузятся так же — с токеном, через
     * `authedImageRequest`, — но называются иначе (волна 123).
     *
     * Имя выбирает автор экрана и не обязан знать про этот тест. Способ загрузки — не выбирает:
     * приватную картинку иначе просто не получить.
     */
    private val privateImageLoad = "authedImageRequest("

    private fun sourceFiles(): List<File> {
        val root = File("src/main/java/com/yuldash/app")
        assertTrue("не нашёл исходники приложения: ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `экраны с чужими документами и фото закрыты от скриншота`() {
        val offenders = sourceFiles().filter { file ->
            // Файл, где `authedImageRequest` ОБЪЯВЛЕН, сам ничего не рисует.
            if (file.name == "SecureImageRequest.kt") return@filter false
            val src = file.readText()
            src.contains(privateImageLoad) && !src.contains("SecureWindow()")
        }
        assertTrue(
            "эти экраны показывают чужие документы или фото, но не защищены от скриншота — " +
                "добавь SecureWindow() в начало экрана: ${offenders.map { it.name }}. " +
                "Речь не только про паспорта: в разборе спора человек прикладывает снимок " +
                "своего лица и ссадины, и уносить его в сельский чат нельзя тем более.",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `сторож видит не только документы`() {
        // Проверка зрения самого сторожа (приём волны 109). Ослабить признак — значит сделать
        // тест зелёным: чем меньше файлов он считает приватными, тем меньше «нарушителей».
        val приватные = sourceFiles().filter {
            it.name != "SecureImageRequest.kt" && it.readText().contains(privateImageLoad)
        }.map { it.name }.toSet()
        for (экран in listOf("FairnessScreens.kt", "ParcelsScreen.kt")) {
            assertTrue(
                "сторож перестал считать $экран экраном с приватными картинками — " +
                    "значит снятую защиту он там не заметит",
                экран in приватные,
            )
        }
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
