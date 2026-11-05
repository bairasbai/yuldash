package com.yuldash.app

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** QA-B01-007: real login UI, HTTP and failed durable reset; no mocked auth result. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoginFailureRecoveryTest {
    @get:Rule val compose = createComposeRule()
    private class OfflineContext(base: Context) : ContextWrapper(base) {
        val passes = MemoryDiskPreferences()
        val outbox = MemoryDiskPreferences()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = when (name) {
            "yuldash_trippass_v2" -> passes
            "yuldash_outbox_v2" -> outbox
            else -> super.getSharedPreferences(name, mode)
        }
    }
    private lateinit var context: OfflineContext
    private lateinit var server: MockWebServer
    private val mounted = mutableStateOf(true)
    private val continuations = AtomicInteger()
    private val starts = AtomicInteger()
    private val attempts = CopyOnWriteArrayList<String>()
    private val consumed = mutableSetOf<String>()
    private val tokenA = TripPassAuthorityFixture.jwt(7)
    private val preparationRequests = CopyOnWriteArrayList<String>()
    @Volatile private var verifyStatus = 200
    private val invalidCode = "Неверный код. Проверь и введи снова."
    private val temporaryWarning = "Не удалось проверить код. Попробуй ещё раз."
    private val storageWarning = "Не удалось сохранить вход на телефоне. Получи новый код и попробуй ещё раз."

    private fun awaitApiBackgroundCompletion()=runBlocking {
        // Observe only the test-owned API coroutine job, never auth/key fields.
        val scope=ApiClient::class.java.getDeclaredField("bg").apply {isAccessible=true}.get(ApiClient) as CoroutineScope
        val parent=requireNotNull(scope.coroutineContext[Job])
        try {
            withTimeout(10_000) {
                while(true) {
                    val children=parent.children.toList()
                    if(children.isEmpty()) break
                    children.forEach {it.join()}
                }
            }
        } catch(failure:TimeoutCancellationException) {
            throw AssertionError("Fixture API jobs still running; local origin/server retained. Abort this isolated run.",failure)
        }
    }
    private fun awaitSnapshotCleanup() = runBlocking {
        withTimeout(5_000) {
            while (!OfflineTripSnapshots.ensureResetCommitted()) delay(10)
        }
    }

    private fun response(status: Int, body: String) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json").setBody(body)
    private fun session(account: String): String {
        val access = if (account == "A") tokenA else "$account-access"
        return """{"access_token":"$access","refresh_token":"$account-refresh","user":{"name":"Test $account","role":"passenger"}}"""
    }

    private fun seedBooking(request: RecordedRequest): MockResponse {
        if (request.method != "GET" || request.getHeader("Authorization") != "Bearer $tokenA")
            return response(403, "{}")
        preparationRequests.add("${request.method} ${request.path}")
        return when (request.path) {
            "/bookings/7/role" -> response(200, """{"role":"passenger","status":"confirmed","driver_phase":""}""")
            "/bookings/7/details" -> response(200, TripPass.fromJson(JSONObject()
                .put("booking_id", 7).put("boarding_code", "old-account").put("pay_method", "negotiate"))
                .toJson().put("ride_id", 1007).put("role", "passenger").put("status", "confirmed")
                .put("contact_unlocked", true).toString())
            else -> response(200, """{"code":"old-account"}""")
        }
    }

    private fun authorizeSeedPass() = runBlocking {
        assertEquals("passenger", ApiClient.getBookingRole(7).getOrThrow())
        val actual = ApiClient.getBookingDetails(7).getOrThrow()
        assertTrue("The initial saved pass requires actual current DETAILS", OfflineTripSnapshots.privateDetails(actual))
        assertEquals("old-account", ApiClient.getBoardingCode(7).getOrThrow())
        assertEquals(listOf("GET /bookings/7/role", "GET /bookings/7/details", "GET /bookings/7/boarding-code"),
            preparationRequests.toList())
    }

    @Before fun setup() {
        // Настоящий поток входа через Telegram. Имя бота подставляем сами: раньше тест требовал
        // BuildConfig.TELEGRAM_BOT из local.properties, и в CI (файла нет) падал ещё в setup —
        // проверял наличие файла на машине разработчика, а не вход.
        TelegramLoginBot.testName = "yuldash_test_bot"
        awaitApiBackgroundCompletion();awaitSnapshotCleanup()
        ApiClient.resetForTest()
        context = OfflineContext(ApplicationProvider.getApplicationContext())
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/config/login-methods" -> response(200, """{"methods":["telegram"]}""")
                        .setBodyDelay(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                    "/auth/tg/start" -> response(200, """{"request_id":"request-${starts.incrementAndGet()}"}""")
                    "/auth/tg/verify" -> {
                        val body = JSONObject(request.body.readUtf8())
                        val id = body.getString("request_id")
                        val code = body.getString("code")
                        attempts.add("$id:$code")
                        when {
                            verifyStatus != 200 -> response(verifyStatus, """{"detail":"temporarily unavailable"}""")
                            id in consumed -> response(409, """{"detail":"code consumed"}""")
                            code != (if (id == "request-1") "123456" else "654321") -> response(400, """{"detail":"invalid code"}""")
                            else -> {
                                consumed.add(id)
                                response(200, session("B"))
                            }
                        }
                    }
                    "/auth/verify" -> response(200, session("A"))
                    "/bookings/7/role", "/bookings/7/details", "/bookings/7/boarding-code" -> seedBooking(request)
                    "/auth/logout", "/push/register", "/push/unregister" -> response(200, "{}")
                    else -> response(404, "{}")
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
        ApiClient.init(context)
        ApiClient.logout()
        awaitSnapshotCleanup()
        ApiClient.init(context)
    }

    @After fun cleanup() {
        if (!::server.isInitialized) { awaitApiBackgroundCompletion(); awaitSnapshotCleanup(); return }
        mounted.value = false
        compose.waitForIdle()
        context.passes.failWriteOf = null
        ApiClient.logout()
        awaitApiBackgroundCompletion()
        awaitSnapshotCleanup()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        TelegramLoginBot.testName = null
        server.shutdown()
    }

    private fun renderAndStart() {
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                LoginScreen(AppLanguage.Ru, {}, { continuations.incrementAndGet() })
            }
        }
        // Способы входа приходят по сети: проверка восстановления кода начинается после их загрузки.
        compose.waitUntil(20_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithTag(TAG_LOGIN_TELEGRAM_BTN).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag(TAG_LOGIN_TELEGRAM_BTN).performScrollTo().assertIsEnabled().performClick()
        waitForText("Код из Telegram")
        compose.onNodeWithText("Код из Telegram").performTextInput("123456")
        compose.onNodeWithTag(TAG_LOGIN_TG_VERIFY_BTN).performScrollTo().performClick()
    }

    private fun waitForText(text: String) {
        compose.waitUntil(20_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertRejectedWith(message: String) {
        waitForText(message)
        compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(invalidCode).assertDoesNotExist()
        assertEquals(0, continuations.get())
        assertFalse(ApiClient.isLoggedIn())
    }

    @Test fun temporaryServerFailureCanRetrySameCodeWithoutFalseInvalidCodeWarning() {
        verifyStatus = 503
        renderAndStart()
        assertRejectedWith(temporaryWarning)
        assertEquals(listOf("request-1:123456"), attempts.toList())
        verifyStatus = 200
        compose.onNodeWithText("Повторить", substring = false).performScrollTo().performClick()
        compose.waitUntil(20_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            continuations.get() == 1
        }
        compose.waitForIdle()
        assertEquals(1, continuations.get())
        assertEquals(1, starts.get())
        assertEquals(listOf("request-1:123456", "request-1:123456"), attempts.toList())
        assertEquals("B-access", ApiClient.currentToken())
    }

    @Test fun localPersistenceFailureExplainsFreshCodeAndRecoversWithNewTelegramRequest() {
        runBlocking { ApiClient.verifyCode("+70000000000", "000000", "Seed").getOrThrow() }
        TripPassStore.initStores(context.passes, MemoryDiskPreferences())
        Outbox.initStores(context.outbox, MemoryDiskPreferences())
        assertFalse(context.passes.restarted().contains(OfflineStoreReset.PENDING))
        TripPassStore.initStores(context.passes, null)
        authorizeSeedPass()
        assertTrue(TripPassStore.save(context, TripPass.fromJson(JSONObject().put("booking_id", 7).put("boarding_code", "old-account").put("pay_method", "negotiate"))))
        context.passes.failWriteOf = OfflineStoreReset.PENDING
        ApiClient.logout()
        assertFalse(context.passes.restarted().contains(OfflineStoreReset.PENDING))
        renderAndStart()
        assertRejectedWith(storageWarning)
        assertEquals(listOf("request-1:123456"), attempts.toList())
        compose.onNodeWithText("Повторить", substring = false).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("Consumed code must not be submitted again", listOf("request-1:123456"), attempts.toList())
        compose.onNodeWithText(storageWarning).assertIsDisplayed()
        assertFalse(context.passes.restarted().contains(OfflineStoreReset.PENDING))
        context.passes.failWriteOf = null
        awaitSnapshotCleanup()
        compose.onNodeWithText("Открыть Telegram ещё раз").performScrollTo().performClick()
        compose.waitUntil(20_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            starts.get() == 2 && compose.onAllNodesWithText("Код из Telegram").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Код из Telegram").performTextInput("654321")
        compose.onNodeWithTag(TAG_LOGIN_TG_VERIFY_BTN).performScrollTo().performClick()
        compose.waitUntil(20_000) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            continuations.get() == 1
        }
        compose.waitForIdle()
        assertEquals(1, continuations.get())
        assertEquals(listOf("request-1:123456", "request-2:654321"), attempts.toList())
        assertEquals("B-access", ApiClient.currentToken())
        assertNull(TripPassStore.load(context, 7))
    }

    @Test fun actualWrongCodeRetainsSpecificWarningAndDoesNotLogIn() {
        verifyStatus = 400
        renderAndStart()
        waitForText(invalidCode)
        compose.onNodeWithText(invalidCode).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(temporaryWarning).assertDoesNotExist()
        compose.onNodeWithText(storageWarning).assertDoesNotExist()
        assertEquals(0, continuations.get())
        assertFalse(ApiClient.isLoggedIn())
    }
}
