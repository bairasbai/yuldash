package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
class DriverPaySettingsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val mounted = mutableStateOf(true)
    private lateinit var server: DriverFixture

    @Before fun prepare() {
        server = DriverFixture()
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = server.url
        ApiClient.testTimeoutMs = 5_000
        ApiClient.serverUnreachable.value = false
        ApiClient.init(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext)
        ApiClient.saveToken(token)
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        ApiClient.resetForTest()
        server.close()
        assertTrue(server.errors.toString(), server.errors.isEmpty())
    }

    @Test fun russianLight() = verifySettings(AppLanguage.Ru, false)
    @Test fun russianDark() = verifySettings(AppLanguage.Ru, true)
    @Test fun bashkirLight() = verifySettings(AppLanguage.Ba, false)
    @Test fun bashkirDark() = verifySettings(AppLanguage.Ba, true)

    private fun verifySettings(language: AppLanguage, dark: Boolean) {
        val ba = language == AppLanguage.Ba
        val cash = if (ba) "Наличный менән" else "Наличными"
        val sbp = if (ba) "СБП аша күсереү" else "Перевод по СБП"
        val save = if (ba) "Һаҡларға" else "Сохранить"
        val saved = if (ba) "Түләү ысулдары һаҡланды." else "Способы оплаты сохранены."
        val review = if (ba) "Әлеге һайлауҙы тикшер һәм ҡабат һаҡла." else "Проверь текущий выбор и сохрани снова."
        compose.setContent {
            YuldashTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    if (mounted.value) DriverPaySettingsCard(ApiClient.queueSessionGeneration())
                }
            }
        }

        fun waitText(text: String) = compose.waitUntil(10_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }

        waitText(cash)
        compose.onNodeWithText(cash).assertIsOff().performClick()
        server.dropReply = true
        compose.onNodeWithText(save).assertIsEnabled().performClick()
        waitText(saved)
        compose.onNodeWithText(cash).assertIsOn()
        assertEquals(listOf(Put(listOf("cash"), null)), server.puts.toList())

        server.settings = View(listOf("sbp"), revision = 2)
        compose.onNodeWithText(sbp).assertIsOff().performClick()
        compose.onNodeWithText(save).assertIsEnabled().performClick()
        waitText(review)
        compose.onNodeWithText(cash).assertIsOff()
        compose.onNodeWithText(sbp).assertIsOn()
        assertEquals(2, server.puts.size)

        compose.onNodeWithText(cash).performClick()
        compose.onNodeWithText(save).assertIsEnabled().performClick()
        waitText(saved)
        assertEquals(
            listOf(Put(listOf("cash"), null), Put(listOf("cash", "sbp"), timestamp(1)),
                Put(listOf("cash", "sbp"), timestamp(2))),
            server.puts.toList(),
        )
    }

    private data class Put(val methods: List<String>, val expected: String?)
    private data class View(val methods: List<String>, val revision: Int)

    private class DriverFixture : Closeable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:" + listener.localPort
        val puts = CopyOnWriteArrayList<Put>()
        val errors = CopyOnWriteArrayList<String>()
        @Volatile var settings = View(emptyList(), revision = 0)
        @Volatile var dropReply = false

        private val worker = thread(isDaemon = true, name = "qa-driver-pay-http") {
            while (!listener.isClosed) {
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                runCatching { socket.use(::receive) }.onFailure { errors += it.javaClass.simpleName }
            }
        }

        private fun receive(socket: Socket) {
            socket.soTimeout = 5_000
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            val request = reader.readLine()?.substringBeforeLast(' ') ?: return
            val headers = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }.toList()
            if (request == "GET /health") {
                respond(socket, "200 OK", JSONObject())
                return
            }
            check(headers.any { it.equals("Authorization: Bearer " + token, ignoreCase = true) })
            when (request) {
                "GET /driver/pay-settings" -> respond(socket, "200 OK", viewJson(settings))
                "PUT /driver/pay-settings" -> {
                    val count = headers.first { it.startsWith("Content-Length:", ignoreCase = true) }.substringAfter(':').trim().toInt()
                    val body = JSONObject((1..count).map { reader.read().toChar() }.joinToString(""))
                    check(body.has("expected_updated_at"))
                    val methods = body.getJSONArray("methods").let { List(it.length()) { index -> it.getString(index) } }
                    val expected = if (body.isNull("expected_updated_at")) null else body.getString("expected_updated_at")
                    puts += Put(methods, expected)
                    val current = settings
                    if (expected != timestamp(current.revision)) {
                        respond(socket, "409 Conflict", JSONObject().put("detail", JSONObject()
                            .put("code", "pay_settings_changed").put("current", viewJson(current))))
                        return
                    }
                    settings = View(methods, current.revision + 1)
                    if (dropReply) dropReply = false else respond(socket, "200 OK", viewJson(settings))
                }
                else -> error("Unexpected fixture request")
            }
        }

        private fun viewJson(view: View) = JSONObject().put("methods", JSONArray(view.methods))
            .put("updated_at", timestamp(view.revision) ?: JSONObject.NULL)

        private fun respond(socket: Socket, status: String, result: JSONObject) {
            val body = result.toString().toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                val header = "HTTP/1.1 " + status + "\r\nContent-Type: application/json\r\nContent-Length: " +
                    body.size + "\r\nConnection: close\r\n\r\n"
                write(header.toByteArray(Charsets.US_ASCII))
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
        const val token = "driver-pay-instrumentation-local"
        fun timestamp(revision: Int): String? =
            if (revision == 0) null else "2030-01-02T12:00:0" + revision + "Z"
    }
}
