package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Отказ сервера показываем ЕГО словами, а не своим «не получилось».
 *
 * Сервер объясняет отказ по-человечески и на двух языках. На один только отклик водителя
 * заявке у него шесть разных объяснений: «заявка закрыта», «нельзя откликнуться на свою»,
 * «заявка только для своих», «эта заявка сейчас недоступна — посмотри другие, рядом есть
 * ещё». Экран показывал вместо этого одну строку «Не получилось. Проверь сеть и повтори».
 *
 * Что делает человек. Проверяет сеть — сеть в порядке. Жмёт ещё раз — тот же ответ. И так и
 * не узнаёт, что откликаться на эту заявку ему просто нельзя. Хуже того, текст ему врёт:
 * сеть-то работает.
 *
 * Помощник `serverSaid(e, fallback)` для этого и написан, и в его описании правило записано
 * прямо. Он стоял в 13 местах, а мимо него шли ещё 34 пользовательских — правило соблюдалось
 * там, где о нём вспомнили. Запасной текст остаётся: при настоящем обрыве связи ответа от
 * сервера нет вообще, и сказать про сеть — правда.
 *
 * Сторож ловит возврат к своему тексту на пользовательских экранах. Админские не считаем:
 * там читатель один и он же автор сервера.
 */
class ServerWordsGuardTest {

    private fun sourcesDir(): File? {
        var dir = File("").absoluteFile
        repeat(4) {
            for (rel in listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app")) {
                val d = File(dir, rel)
                if (d.isDirectory) return d
            }
            dir = dir.parentFile ?: return null
        }
        return null
    }

    @Test
    fun `сторож видит исходники`() {
        assertTrue("не нашёл каталог экранов", sourcesDir() != null)
    }

    @Test
    fun `отказ показываем словами сервера`() {
        val dir = sourcesDir() ?: return
        // Простая форма: `.onFailure { … Toast.makeText(ctx, готоваяСтрока, …) }` без своего
        // имени параметра. Сложные ветвления (разбор кода ответа, откат состояния) не трогаем —
        // там решение осмысленное, и сторож в него не лезет.
        val block = Regex("""\.onFailure\s*\{(?![^}\n]*->)((?:[^{}]|\{[^{}]*\})*?)\}""", RegexOption.DOT_MATCHES_ALL)
        val toast = Regex("""Toast\.makeText\([A-Za-z]\w*,\s*([a-zA-Z]\w*),\s*Toast\.LENGTH_\w+\)""")
        val ok = setOf("sentMsg", "copiedMsg")   // сообщения об УСПЕХЕ, не про отказ
        val silent = mutableListOf<String>()
        for (file in dir.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            if (file.name.startsWith("Admin")) continue
            val text = file.readText()
            for (m in block.findAll(text)) {
                val body = m.groupValues[1]
                val t = toast.find(body) ?: continue
                if (t.groupValues[1] in ok) continue
                // Правило только про ОТВЕТ СЕРВЕРА. `runCatching { startActivity(…) }.onFailure`
                // — это локальный сбой (нет звонилки на телефоне), и слов сервера там нет
                // в принципе. Первая версия сторожа этого не различала и обвинила честный код.
                val before = text.substring(maxOf(0, m.range.first - 400), m.range.first)
                if (!before.contains("ApiClient.")) continue
                val line = text.take(m.range.first).count { it == '\n' } + 1
                silent += "${file.name}:$line  показывает своё «${t.groupValues[1]}» вместо слов сервера"
            }
        }
        assertTrue(
            "отказ сервера подменён своим текстом — ${silent.size} шт. Оберни в serverSaid(it, …): " +
                "человек должен узнать ПРИЧИНУ, а не «проверь сеть» при работающей сети:\n" +
                silent.joinToString("\n"),
            silent.isEmpty(),
        )
    }
}
