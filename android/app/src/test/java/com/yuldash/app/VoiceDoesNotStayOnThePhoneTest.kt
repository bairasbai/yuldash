package com.yuldash.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Голос человека не должен оставаться на телефоне после выхода из аккаунта.
 *
 * История. Гульнара пишет водителю голосовые: «я у пятого подъезда», «выхожу, две минуты».
 * Каждое такое сообщение сначала становится файлом в памяти приложения, потом уходит
 * на сервер. Уходило — и оставалось лежать: удалять записи никто не пытался (аудит
 * 2026-08-08, волна 75). За полгода переписки в телефоне копится весь архив сказанного
 * вслух. Дальше телефон отдают мужу, сыну или продают, и записи прежнего владельца едут
 * вместе с ним: выход из аккаунта их не трогал, хотя чужие данные он уносить обязан.
 *
 * Голос — такие же личные данные, как телефонный номер, и правило для них одно.
 *
 * Обратная сторона: чужие файлы в той же папке трогать нельзя. Приложение кладёт туда
 * ещё и картинку «Мой Юлдаш» для отправки друзьям — уборка не должна сносить всё подряд.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceDoesNotStayOnThePhoneTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun voiceFile(name: String): File =
        File(ctx.cacheDir, name).apply { writeText("голос Гульнары") }

    @Test
    fun `выход из аккаунта уносит записанные голосовые`() {
        ApiClient.init(ctx)
        val mine = voiceFile("voice_111.m4a")
        val alsoMine = voiceFile("voice_222.m4a")

        // Именно выход из аккаунта, а не уборка напрямую: важно, что кнопка «выйти» до неё
        // доходит. Проверять саму функцию в обход выхода — значит проверять деталь, а не обещание.
        ApiClient.logout()

        assertFalse("голосовая осталась на телефоне после выхода: ${mine.name}", mine.exists())
        assertFalse("голосовая осталась на телефоне после выхода: ${alsoMine.name}", alsoMine.exists())
    }

    @Test
    fun `уборка не трогает чужие файлы в той же папке`() {
        ApiClient.init(ctx)
        val share = File(ctx.cacheDir, "my_yuldash.png").apply { writeText("картинка для друзей") }

        ApiClient.logout()

        assertTrue("уборка снесла картинку «Мой Юлдаш» — человек не смог поделиться", share.exists())
        share.delete()
    }

    @Test
    fun `отправленная запись стирается сразу, а не ждёт выхода`() {
        ApiClient.init(ctx)
        val sent = voiceFile("voice_333.m4a")

        ApiClient.dropVoiceFile(sent.absolutePath)

        assertFalse("запись осталась на телефоне после отправки", sent.exists())
    }

    @Test
    fun `удалить можно только запись голоса, а не любой файл по пути`() {
        ApiClient.init(ctx)
        val stranger = File(ctx.cacheDir, "important.txt").apply { writeText("не наше") }

        ApiClient.dropVoiceFile(stranger.absolutePath)

        assertTrue("удалили посторонний файл — так можно снести что угодно", stranger.exists())
        stranger.delete()
    }
}
