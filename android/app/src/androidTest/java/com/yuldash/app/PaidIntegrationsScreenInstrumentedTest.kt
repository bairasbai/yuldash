package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class PaidIntegrationsScreenInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val mounted = mutableStateOf(true)
    private lateinit var server: LocalSwitchServer

    @Before fun prepare() {
        server = LocalSwitchServer()
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = server.url
        ApiClient.testTimeoutMs = 5_000
        ApiClient.serverUnreachable.value = false
        ApiClient.init(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext)
        ApiClient.saveToken(SYNTHETIC_TOKEN)
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        ApiClient.resetForTest()
        server.close()
        assertTrue(server.errors.toString(), server.errors.isEmpty())
    }

    @Test fun russianLight() = verifyPause(AppLanguage.Ru, dark = false)
    @Test fun russianDark() = verifyPause(AppLanguage.Ru, dark = true)
    @Test fun bashkirLight() = verifyPause(AppLanguage.Ba, dark = false)
    @Test fun bashkirDark() = verifyPause(AppLanguage.Ba, dark = true)

    @Test fun cardRussianLight() = verifyCard(AppLanguage.Ru, dark = false)
    @Test fun cardRussianDark() = verifyCard(AppLanguage.Ru, dark = true)
    @Test fun cardBashkirLight() = verifyCard(AppLanguage.Ba, dark = false)
    @Test fun cardBashkirDark() = verifyCard(AppLanguage.Ba, dark = true)

    private fun verifyCard(language: AppLanguage, dark: Boolean) {
        val bashkir = language == AppLanguage.Ba
        val name = if (bashkir) "Карта менән түләү — әҙерлек" else "Оплата картой — подготовка"
        val blocker = if (bashkir) "Банк тикшерелә" else "Банк на проверке"
        val firstSwitch = if (bashkir) "Түләүле хеҙмәттәр" else "Платные подключения"
        server.showCard = true
        mount(language, dark)

        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription(firstSwitch).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(blocker))
        val card = compose.onNodeWithContentDescription(name)
        compose.onNodeWithText(blocker).assertIsDisplayed()
        card.assertIsOn().assertIsEnabled().performClick()
        compose.waitUntil(10_000) {
            runCatching { card.assertIsOff().assertIsNotEnabled() }.isSuccess
        }
        assertEquals(listOf(Write(false, false, 5)), server.cardWrites.toList())
    }

    private fun mount(language: AppLanguage, dark: Boolean) {
        compose.setContent {
            YuldashTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    if (mounted.value) AdminSwitchesScreen(onBack = {})
                }
            }
        }
    }

    private fun verifyPause(language: AppLanguage, dark: Boolean) {
        val bashkir = language == AppLanguage.Ba
        val master = if (bashkir) "Түләүле хеҙмәттәр" else "Платные подключения"
        val notices = if (bashkir) "SMS-хәбәрҙәр" else "SMS-уведомления"
        val paused = if (bashkir) "Түләүле хеҙмәттәр туҡтатылып тора" else "Платные подключения на паузе"
        val warning = if (bashkir) "Түләүле хеҙмәттәрҙе туҡтатып торорғамы?" else "Приостановить платные подключения?"
        val cancel = if (bashkir) "Кире алыу" else "Отмена"
        val confirm = if (bashkir) "Туҡтатып тороу" else "Приостановить"
        mount(language, dark)

        fun waitToggle(on: Boolean) {
            compose.waitUntil(10_000) {
                runCatching {
                    val node = compose.onNodeWithContentDescription(master).assertIsDisplayed()
                    if (on) node.assertIsOn() else node.assertIsOff()
                    true
                }.getOrDefault(false)
            }
        }
        fun tapMaster() = compose.onNodeWithContentDescription(master).performClick()
        fun waitText(text: String) = compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

        waitToggle(false)
        waitText(paused)
        assertTrue(compose.onAllNodesWithContentDescription(notices).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithContentDescription(notices).assertIsOn()
        tapMaster()
        waitToggle(true)

        tapMaster()
        waitText(warning)
        compose.onNodeWithText(cancel).performClick()
        waitToggle(true)
        assertEquals(listOf(Write(true, false, 0), Write(false, false, 1)), server.writes.toList())
        tapMaster()
        waitText(warning)
        compose.onNodeWithText(confirm).performClick()
        waitToggle(false)
        waitText(paused)
        compose.onNodeWithContentDescription(notices).assertIsOn()

        server.dropNextWriteReply = true
        tapMaster()
        waitToggle(true)
        assertEquals(
            listOf(Write(true, false, 0), Write(false, false, 1), Write(false, false, 1),
                Write(false, true, 1), Write(true, false, 2)),
            server.writes.toList(),
        )
        assertEquals(3, server.revision)
    }

    private data class Write(val value: Boolean, val confirmed: Boolean, val revision: Int)

    private class LocalSwitchServer : Closeable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${listener.localPort}"
        val writes = CopyOnWriteArrayList<Write>()
        val cardWrites = CopyOnWriteArrayList<Write>()
        val errors = CopyOnWriteArrayList<String>()
        @Volatile var showCard = false
        @Volatile var dropNextWriteReply = false
        @Volatile var revision = 0
            private set
        private var paid = false
        private var cardStored = true
        private var cardRevision = 5
        private val worker = thread(isDaemon = true, name = "qa-paid-switch-http") {
            while (!listener.isClosed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                runCatching { socket.use(::respond) }.onFailure { errors += it.javaClass.simpleName }
            }
        }

        private fun respond(socket: Socket) {
            socket.soTimeout = 5_000
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val request = reader.readLine() ?: return
            val headers = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }.toList()
            val route = request.substringBeforeLast(' ')
            if (route == "GET /health") {
                writeResponse(socket, 200, JSONObject())
                return
            }
            check(headers.any { it.equals("Authorization: Bearer $SYNTHETIC_TOKEN", ignoreCase = true) })
            fun readWrite(): Write {
                val length = headers.first { it.startsWith("Content-Length:", ignoreCase = true) }
                    .substringAfter(':').trim().toInt()
                val payload = JSONObject((1..length).map { reader.read().toChar() }.joinToString(""))
                return Write(payload.getBoolean("value"), payload.getBoolean("confirm"),
                    payload.getInt("expected_revision"))
            }
            when (route) {
                "GET /admin/switches" -> writeResponse(socket, 200, JSONObject().put("switches", JSONObject()
                    .put("external.paid", row(paid, paid, revision))
                    .put("external.sms_notices", row(true, paid, 0, blocked = !paid))
                    .apply { if (showCard) put("rideshare_card_payments", cardRow()) }))
                "POST /admin/switches/external.paid" -> {
                    val write = readWrite()
                    writes += write
                    check(write.revision == revision)
                    if (!write.value && !write.confirmed) {
                        writeResponse(socket, 409, JSONObject().put("detail", JSONObject()
                            .put("code", "switch_needs_confirm").put("affected", 7)))
                        return
                    }
                    paid = write.value
                    revision++
                    if (dropNextWriteReply) {
                        dropNextWriteReply = false
                        return
                    }
                    writeResponse(socket, 200, row(paid, paid, revision))
                }
                "POST /admin/switches/rideshare_card_payments" -> {
                    val write = readWrite()
                    cardWrites += write
                    check(showCard && cardStored && !write.value && !write.confirmed && write.revision == cardRevision)
                    cardStored = false
                    cardRevision++
                    writeResponse(socket, 200, cardRow())
                }
                else -> error("Unexpected route: $route")
            }
        }

        private fun row(stored: Boolean, effective: Boolean, revision: Int, blocked: Boolean = false) =
            JSONObject().put("stored", stored).put("effective", effective).put("revision", revision)
                .put("blockers", JSONArray().apply { if (blocked) put("external_paid_paused") })
                .put("history", JSONArray())

        private fun cardRow() = row(cardStored, true, cardRevision).put("blockers", JSONArray().put(JSONObject()
            .put("code", "bank_project_unaccepted").put("ru", "Банк на проверке").put("ba", "Банк тикшерелә")))

        private fun writeResponse(socket: Socket, status: Int, result: JSONObject) {
            val body = result.toString().toByteArray(Charsets.UTF_8)
            val line = if (status == 409) "409 Conflict" else "200 OK"
            socket.getOutputStream().apply {
                write("HTTP/1.1 $line\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII))
                write(body)
                flush()
            }
        }

        override fun close() {
            listener.close()
            worker.join(6_000)
            check(!worker.isAlive)
        }
    }

    private companion object {
        const val SYNTHETIC_TOKEN = "header.eyJzdWIiOiIxMDEifQ.synthetic"
    }
}
