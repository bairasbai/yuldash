package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class RouteWatchStatusInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val mounted = mutableStateOf(false)
    private lateinit var listener: ServerSocket
    private lateinit var worker: Thread

    @Before fun prepare() {
        listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        worker = thread(name = "route-watch-test-http", isDaemon = true) {
            while (!listener.isClosed) {
                val client = runCatching { listener.accept() }.getOrNull() ?: break
                client.use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val request = input.readLine()
                    while (!input.readLine().isNullOrEmpty()) Unit
                    val body = if (request?.startsWith("GET /route-watch ") == true) watch else "{}"
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        write(bytes)
                        flush()
                    }
                }
            }
        }
        ApiClient.resetForTest()
        ApiClient.testBaseUrl = "http://127.0.0.1:${listener.localPort}"
        ApiClient.testTimeoutMs = 10000
        ApiClient.init(context)
        ApiClient.saveToken("synthetic-watch-status-owner")
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        listener.close()
        worker.join(6000)
        check(!worker.isAlive)
    }

    @Test fun russianLight() = checkScreen(AppLanguage.Ru, false)
    @Test fun russianDark() = checkScreen(AppLanguage.Ru, true)
    @Test fun bashkirLight() = checkScreen(AppLanguage.Ba, false)
    @Test fun bashkirDark() = checkScreen(AppLanguage.Ba, true)

    private fun checkScreen(language: AppLanguage, dark: Boolean) {
        val ba = language == AppLanguage.Ba
        val title = if (ba) "Яҙылыуҙарым" else "Мои подписки"
        val channel = if (ba) "Ҡушымта каналы теркәлгән" else "Канал приложения зарегистрирован"
        mounted.value = true
        compose.setContent {
            YuldashTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalAppLanguage provides language, LocalNightRide provides dark) {
                    if (mounted.value) RouteWatchesScreen({}, "Ургаза", "Акъяр")
                }
            }
        }
        compose.onNodeWithText(title).assertIsDisplayed()
        val last = if (ba) "Һуңғы хәбәр · Әлегә булманы" else "Последнее сообщение · Пока не было"
        compose.waitUntil(15000) {
            runCatching { compose.onNode(hasScrollAction()).performScrollToNode(hasText(last)) }.isSuccess
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(channel))
        compose.onNodeWithText(channel).assertIsDisplayed()
    }

    private companion object {
        val watch = """[{"id":71,"from_city":"Ургаза","to_city":"Акъяр","direction":"forward",
            "watch_kind":"rides","watch_date":"2030-05-01T19:00:00","last_notified_at":null,
            "registered_push_channels":{"app":true,"web":true},"quiet_from":22,"quiet_to":7}]"""
    }
}
