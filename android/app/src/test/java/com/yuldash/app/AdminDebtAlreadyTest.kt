package com.yuldash.app

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.File

/**
 * «Долг уже оплачен» в админке долгов за такси (лист 1.2c, доработка клиента).
 *
 * Сервер отвечает `already: true`, когда долг закрыли другим путём, пока админ смотрел на строку
 * (карта, кошелёк, разбор жалобы). Приложение рисовало на это обычное «Оплата подтверждена» — и
 * лишний перевод по СБП админ видел только в журнале сервера.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdminDebtAlreadyTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var server: MockWebServer

    private fun ctx(): Context = org.robolectric.RuntimeEnvironment.getApplication()

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }

    @After
    fun teardown() {
        ApiClient.logout()
        ApiClient.testBaseUrl = null
        server.shutdown()
    }

    private fun reply(body: String) = server.enqueue(MockResponse().setResponseCode(200).setBody(body))

    @Test
    fun `ответ already отличим от обычного на всех трёх кнопках`() = runBlocking {
        reply("""{"ok":true,"status":"paid","already":true,"paid_kop":0}""")
        assertTrue(ApiClient.confirmDebt(1, 25_000).getOrThrow())
        reply("""{"ok":true,"status":"paid","already":true,"unpaid_kop":0}""")
        assertTrue(ApiClient.rejectDebt(1, 25_000).getOrThrow())
        reply("""{"ok":true,"status":"paid","already":true}""")
        assertTrue(ApiClient.adminForgiveDebt(1, "спор").getOrThrow())

        reply("""{"ok":true,"status":"paid","paid_kop":25000}""")
        assertFalse(ApiClient.confirmDebt(1, 25_000).getOrThrow())
        reply("""{"ok":true,"status":"unpaid","unpaid_kop":25000}""")
        assertFalse(ApiClient.rejectDebt(1, 25_000).getOrThrow())
        reply("""{"ok":true,"status":"paid","forgiven_kop":25000,"reason":"спор"}""")
        assertFalse(ApiClient.adminForgiveDebt(1, "спор").getOrThrow())
    }

    @Test
    fun `уже оплачено показывается окном до Понятно, обычный итог тостом`() {
        val notice = AdminDebtNotice(adminDebtMsgs(AppLanguage.Ru))
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) { AdminDebtAlreadyDialog(notice) }
        }

        composeRule.runOnIdle { notice.after(ctx(), false, notice.msgs.alreadyConfirm, "Оплата подтверждена") }
        composeRule.waitForIdle()
        assertNull("обычный итог не должен открывать окно", notice.text)
        assertEquals("Оплата подтверждена", ShadowToast.getTextOfLatestToast())

        composeRule.runOnIdle { notice.after(ctx(), true, notice.msgs.alreadyConfirm, "Оплата подтверждена") }
        composeRule.onNodeWithText(notice.msgs.alreadyConfirm).assertExists()
        assertEquals("на «уже оплачено» не должно быть тоста «подтверждено»", 1, ShadowToast.shownToastCount())

        composeRule.onNodeWithText("Понятно").performClick()
        composeRule.waitForIdle()
        assertNull(notice.text)
        composeRule.onNodeWithText(notice.msgs.alreadyConfirm).assertDoesNotExist()
    }

    @Test
    fun `тексты на двух языках и совпадают с веб-админкой`() {
        val ru = adminDebtMsgs(AppLanguage.Ru)
        val ba = adminDebtMsgs(AppLanguage.Ba)
        listOf(ru.alreadyConfirm to ba.alreadyConfirm, ru.alreadyReject to ba.alreadyReject,
               ru.alreadyForgive to ba.alreadyForgive, ru.gotIt to ba.gotIt, ru.forgiven to ba.forgiven).forEach { (r, b) ->
            assertTrue(r.isNotBlank() && b.isNotBlank())
            assertNotEquals("башкирский не должен совпадать с русским: $r", r, b)
        }
        assertTrue("админ должен узнать про лишний перевод по СБП", ru.alreadyConfirm.contains("СБП"))

        val web = webDebtAlreadySource() ?: return
        listOf(ru.alreadyConfirm, ba.alreadyConfirm, ru.alreadyReject, ba.alreadyReject,
               ru.alreadyForgive, ba.alreadyForgive).forEach {
            assertTrue("приложение и веб-админка разошлись в тексте: $it", web.contains("\"$it\""))
        }
    }

    /** `webapp/src/utils/debtAlready.ts` — если тест запущен из клона целиком (CI, ноутбук). */
    private fun webDebtAlreadySource(): String? {
        var dir: File? = File("").absoluteFile
        repeat(5) {
            val f = File(dir ?: return null, "webapp/src/utils/debtAlready.ts")
            if (f.isFile) return f.readText()
            dir = dir?.parentFile
        }
        return null
    }
}
