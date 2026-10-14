package com.yuldash.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Имя Telegram-бота для входа попадает в сборку без `local.properties` (его агентам читать нельзя,
 * в CI его нет). Раньше без файла `BuildConfig.TELEGRAM_BOT` был пустым, и «Войти через Telegram»
 * в такой сборке отвечала тостом «скоро» — проверить вход на эмуляторе было нельзя. Теперь у имени
 * есть дефолт в `build.gradle.kts` (порядок: -PyuldashTelegramBot → env → local.properties → дефолт).
 *
 * Тест не привязан к конкретному имени (на машине разработчика оно может быть переопределено),
 * он ловит поломку, которая важна: пусто, с «@» или с пробелом — ссылка `t.me/<имя>` не откроется.
 */
class TelegramBotBuildConfigTest {

    @Test
    fun `buildconfig has a bot name even without local properties`() {
        assertTrue("BuildConfig.TELEGRAM_BOT пуст — кнопка входа через Telegram мертва", BuildConfig.TELEGRAM_BOT.isNotBlank())
    }

    @Test
    fun `bot name is a bare telegram username`() {
        // Username Telegram: латиница, цифры, «_», 5–32 знака; «@» подставлять нельзя — в коде
        // это `https://t.me/${имя}`.
        assertTrue(
            "Имя бота «${BuildConfig.TELEGRAM_BOT}» не похоже на username без «@»",
            Regex("[A-Za-z][A-Za-z0-9_]{4,31}").matches(BuildConfig.TELEGRAM_BOT),
        )
    }

    @Test
    fun `login bot falls back to the buildconfig name`() {
        TelegramLoginBot.testName = null
        assertEquals(BuildConfig.TELEGRAM_BOT, TelegramLoginBot.name)
    }
}
