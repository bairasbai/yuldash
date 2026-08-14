package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Фото уходит на сервер пережатым — иначе человек платит за наш недосмотр трафиком.
 *
 * Зачем это важно именно здесь. Юлдаш работает в районах, где интернет мобильный, медленный
 * и платный по мегабайтам. Снимок с камеры весит пять-шесть мегабайт. Пережатый — триста
 * килобайт, разницы на глаз никакой.
 *
 * Половина экранов пережимала фото с самого начала, а половина отправляла как есть
 * (аудит 2026-08-08, волна 88). Хуже всего было в анкете таксиста: четыре документа подряд,
 * то есть двадцать мегабайт по сельской сети. Это минуты ожидания, деньги за трафик и полная
 * потеря всего при обрыве — а обрывается там регулярно. Чат-фото и снимки для проверки
 * личности уходили тем же способом.
 *
 * Документы пережимаем бережнее обычных фото: модератор должен разобрать серию, номер и даты
 * в правах. Поэтому у них своя, более щадящая планка.
 *
 * Тест читает исходники: если новый экран берёт байты картинки напрямую из галереи, он попадёт
 * сюда и покраснеет. Проверка по коду, а не по поведению, потому что сжатие делает системный
 * декодер Android — в тестовой среде его нет.
 */
class PhotosAreCompressedBeforeSendingTest {

    /** Так выглядит «взял файл из галереи как есть». */
    private val RAW_READ = Regex("""contentResolver\.openInputStream\(\s*uri\s*\)\s*\?\.use\s*\{\s*it\.readBytes\(\)""")

    private fun screens(): List<File> {
        val root = File("src/main/java/com/yuldash/app")
        assertTrue("не нашёл исходники: ${root.absolutePath}", root.isDirectory)
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `ни один экран не отправляет снимок из галереи как есть`() {
        val guilty = screens()
            .filter { f -> RAW_READ.containsMatchIn(f.readText()) }
            .map { it.name }
            .sorted()
        assertTrue(
            "эти экраны отправляют фото без сжатия: $guilty. " +
                "На сельской сети это минуты ожидания и деньги человека за трафик — " +
                "используй decodeToJpeg(context, uri) как остальные экраны.",
            guilty.isEmpty(),
        )
    }

    @Test
    fun `документы пережимаются бережнее обычных фото`() {
        val docs = File("src/main/java/com/yuldash/app/TaxiOnboardingScreen.kt").readText()
        val side = Regex("""DOC_PHOTO_SIDE\s*=\s*(\d+)""").find(docs)?.groupValues?.get(1)?.toInt()
        val quality = Regex("""DOC_PHOTO_QUALITY\s*=\s*(\d+)""").find(docs)?.groupValues?.get(1)?.toInt()
        assertTrue("у документов пропала своя планка сжатия", side != null && quality != null)
        assertTrue(
            "документы жмутся как обычное фото ($side px, $quality) — модератор не разберёт " +
                "номер и даты в правах, и человека отправят переснимать",
            side!! >= 1400 && quality!! >= 90,
        )
    }

    @Test
    fun `обычные фото не раздуты до размера документов`() {
        val profile = File("src/main/java/com/yuldash/app/ProfileScreen.kt").readText()
        val default = Regex("""maxSize:\s*Int\s*=\s*(\d+)""").find(profile)?.groupValues?.get(1)?.toInt()
        assertTrue("не нашёл планку сжатия по умолчанию", default != null)
        assertTrue(
            "обычные фото стали тяжёлыми ($default px) — трафик человека вырос на ровном месте",
            default!! <= 1280,
        )
    }
}
