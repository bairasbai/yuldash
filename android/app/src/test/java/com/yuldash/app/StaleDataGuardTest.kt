package com.yuldash.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * «Потянул вниз — а обновиться не вышло» должно быть ВИДНО.
 *
 * Как это ломалось. В «Кошельке» жест «потянуть вниз» и появился ради того, чтобы цифры не были
 * старыми. Но неудачу обновления экран обрабатывал только при ПУСТОЙ истории операций — то есть
 * только у водителя, который ещё не заработал. У того, кто уже поездил, жест выглядел успешным:
 * индикатор крутился, пропадал, баланс не менялся. Вывод человек делал сам — «начисление не пришло».
 * Один и тот же экран говорил правду новичку и врал всем остальным.
 *
 * Так было на 21 экране из 25 с этим жестом. Плашка при этом существовала — в трёх разных копиях
 * (`MoneyStaleStrip`, `CourierRefreshStrip`, `AppNoticeCard`) и стояла на четырёх экранах.
 * Правило знали, но каждый экран применял его сам, а значит — как получится.
 *
 * Правило: экран, который умеет обновляться жестом, обязан сказать, что обновление не удалось.
 * Ошибка вместо данных (`AppErrorState`) — это НЕ то же самое: она права, только когда показывать
 * нечего, а поверх уже загруженного списка она стирает то, что человек видел.
 *
 * Сторож грубый нарочно: он не разбирает условия ветвлений (это привело бы к ложному спокойствию,
 * как уже случилось с `ToneSourceGuardTest`), а проверяет факт — есть на экране плашка или нет.
 * Тонкая проверка «в правильной ли ветке она стоит» — работа ревью, а не регулярного выражения.
 */
class StaleDataGuardTest {

    /** Плашки «данные могли устареть». Все — обёртки над `AppStaleStrip` (UiKit). */
    private val staleWidgets = listOf("AppStaleStrip", "MoneyStaleStrip", "CourierRefreshStrip", "AppNoticeCard")

    /**
     * Экраны, где сбой обновления показан по-своему. Каждый — с причиной; список должен
     * оставаться коротким, иначе правило превратится в пожелание.
     */
    private val allowed = mapOf(
        // Статус проверки водителя: своя красная плашка `statusFailed` показывается ВСЕГДА при
        // сбое, а не только поверх непустого списка. Молчания нет, дублировать нечего.
        "SosVerifyScreens.kt" to "своя плашка statusFailed, показывается при любом сбое",
    )

    private fun sources(): List<File> {
        var dir = File("").absoluteFile
        repeat(4) {
            for (rel in listOf("app/src/main/java/com/yuldash/app", "src/main/java/com/yuldash/app")) {
                val src = File(dir, rel)
                if (src.isDirectory) return src.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            }
            dir = dir.parentFile ?: return emptyList()
        }
        return emptyList()
    }

    @Test
    fun `сторож видит исходники`() {
        assertTrue("не нашёл исходники экранов, сторож ничего не проверяет", sources().size > 30)
    }

    @Test
    fun `у каждого экрана с жестом обновления есть плашка «не удалось обновить»`() {
        val silent = mutableListOf<String>()
        for (file in sources()) {
            if (file.name == "UiKit.kt") continue          // тут плашка определена, а не применена
            val text = file.readText()
            if (!text.contains("AppPullRefresh(")) continue
            if (allowed.containsKey(file.name)) continue
            if (staleWidgets.none { text.contains(it) }) silent += file.name
        }
        assertTrue(
            "экран умеет обновляться жестом, но при неудачном обновлении молчит — " +
                "${silent.size} шт. Добавь AppStaleStrip в ветку «ошибка, но данные на экране есть»:\n" +
                silent.joinToString("\n"),
            silent.isEmpty(),
        )
    }

    /*
     * Тут был четвёртый тест — «плашка не собрана вручную второй раз». Он искал в файле пару
     * «фон предупреждения + кнопка Повторить» и на первом же запуске обвинил восемь невиновных
     * экранов: такое сочетание встречается в куче другой разметки. Тест, который краснеет на
     * честном коде, хуже отсутствующего — он приучает дописывать исключения вместо того, чтобы
     * читать претензию. Убран сознательно; единственность плашки держится обёртками
     * (MoneyStaleStrip / CourierRefreshStrip зовут AppStaleStrip) и ревью, а не регуляркой.
     */

    @Test
    fun `у плашки есть оба языка`() {
        val kit = sources().firstOrNull { it.name == "UiKit.kt" }?.readText().orEmpty()
        assertTrue("не нашёл AppStaleStrip в UiKit", kit.contains("internal fun AppStaleStrip"))
        assertTrue(
            "текст плашки должен быть на двух языках через appText(ru, ba)",
            kit.contains("\"Не удалось обновить — данные прежние\"") &&
                kit.contains("\"Яңырта алманыҡ — мәғлүмәт элекке\""),
        )
    }
}
