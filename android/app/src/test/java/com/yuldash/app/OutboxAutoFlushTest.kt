package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MemoryDiskPreferences
import com.yuldash.app.data.Outbox
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Ревью 23.10.2026: написал «Жду у подъезда», пока сервер лежал, ушёл на карту — сервер вернулся, а
 * сообщение ждало, пока человек снова откроет поездку или перезапустит приложение. Теперь очередь
 * уходит, пока приложение на экране, где бы человек ни был, — один раз (тот же ключ запроса).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OutboxAutoFlushTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private val posts = CopyOnWriteArrayList<String>()
    @Volatile private var serverDown = true
    private val mounted = mutableStateOf(false)

    @Before fun setup() {
        ApiClient.resetForTest()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path == "/auth/logout") return MockResponse().setBody("{}")
                    if (serverDown) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    if (request.method == "POST" && request.path == "/bookings/42/messages") {
                        posts += request.getHeader("Idempotency-Key").orEmpty()
                        return MockResponse().setBody("{}")
                    }
                    return MockResponse().setResponseCode(404).setBody("{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
        ApiClient.init(context)
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        Outbox.initStores(MemoryDiskPreferences(), null)
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Outbox.initStores(MemoryDiskPreferences(), null)
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    @Test fun queueIsSentOnceWhenServerReturnsWhileUserIsElsewhere() {
        val action = Outbox.newMessage(42, "Жду у подъезда")
        assertTrue(Outbox.enqueue(context, action, ApiClient.queueSessionGeneration()))
        mounted.value = true
        compose.setContent { if (mounted.value) OutboxAutoFlush(sessionKey = 0) }   // корень приложения, экрана поездки нет
        compose.waitForIdle()
        assertEquals("Сервер лежит — ничего не ушло, очередь цела", 1, Outbox.count(context, 42))

        serverDown = false
        // Первая (неудачная) попытка идёт по настоящей сети и может закончиться позже любой разовой
        // перемотки — тогда ожидание следующей так и не наступало. Перематываем понемногу, пока ждём.
        compose.waitUntil(15_000) {
            compose.mainClock.advanceTimeBy(1_000)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Outbox.count(context, 42) == 0
        }
        assertEquals(listOf(Outbox.messageRequestKey(action)), posts.toList())

        compose.mainClock.advanceTimeBy(OUTBOX_RETRY_MS * 2)
        compose.waitForIdle()
        assertEquals("Пустую очередь не шлём повторно", 1, posts.size)
    }
}
