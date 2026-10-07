package com.yuldash.app

import android.content.Context
import android.graphics.Bitmap
import android.os.Process
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.TripPass
import com.yuldash.app.data.TripPassStore
import com.yuldash.app.ui.theme.YuldashTheme
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Real v2 storage and actual offline screen. Synthetic data; use StorageAuditRunner with radios off. */
@RunWith(AndroidJUnit4::class)
class OfflineTripPaymentInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext.applicationContext
    private val mounted = mutableStateOf(false)
    private lateinit var listener: ServerSocket
    private lateinit var accept: Thread
    @Volatile private var closed = false
    private val sockets = CopyOnWriteArrayList<Socket>()

    @Before fun prepare() {
        listener = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        accept = thread(isDaemon = true, name = "b02-payment-offline") {
            while (!closed) {
                // Never answer: the client's bounded read timeout causes a transport outage.
                val socket = runCatching { listener.accept() }.getOrNull() ?: break
                sockets += socket
            }
        }
        ApiClient.resetForTest()
        ApiClient.serverUnreachable.value = false
        ApiClient.init(context)
        ApiClient.logout()
        ApiClient.testBaseUrl = "http://127.0.0.1:${listener.localPort}"
        ApiClient.testTimeoutMs = 700
        TripPassStore.init(context)
    }
    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        compose.waitForIdle()
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        closed = true
        listener.close()
        accept.join(2000)
        sockets.forEach { it.close() }
    }

    @Test fun cashRuSurvivesRealStorageReopen() = journey(AppLanguage.Ru, "cash", 400,
        "400 ₽ · Наличными", "cash-ru")
    @Test fun zeroBaSurvivesRealStorageReopen() = journey(AppLanguage.Ba, "cash", 0,
        "0 ₽ · Наличный менән", "zero-ba")
    @Test fun legacyDoesNotInventSbp() = journey(AppLanguage.Ru, null, null,
        "700 ₽ · Способ оплаты не сохранён", "legacy-ru")

    private fun journey(language: AppLanguage, method: String?, amount: Int?, expected: String, name: String) {
        val json = JSONObject().put("booking_id", 42).put("price", 700).put("boarding_code", "4821")
        if (method != null) json.put("pay_method", method).put("pay_amount", amount ?: JSONObject.NULL)
        assertTrue(TripPassStore.save(context, TripPass.fromJson(json)))
        assertFalse("New snapshot unexpectedly stored in plain preferences",
            context.getSharedPreferences("yuldash_trippass_v2", Context.MODE_PRIVATE).contains("pass_42"))
        TripPassStore.init(context) // Real EncryptedSharedPreferences reopen, not a model restart/process death.
        val restored = requireNotNull(TripPassStore.load(context, 42)).toJson()
        if (method == null) assertTrue(restored.isNull("pay_method")) else assertEquals(method, restored.getString("pay_method"))
        if (amount == null) assertTrue(restored.isNull("pay_amount")) else assertEquals(amount, restored.getInt("pay_amount"))
        mounted.value = true
        compose.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    if (mounted.value) ActiveTripScreen(ride = null, contacts = emptyList(), bookingId = 42,
                        onBack = {}, onTripEnd = {}, onSos = {})
                }
            }
        }
        val banner = if (language == AppLanguage.Ba) "Офлайн — мәғлүмәт һаҡланған" else "Офлайн — данные сохранены"
        compose.waitUntil(15000) { ApiClient.serverUnreachable.value }
        // LazyColumn does not compose the banner below the hero until we scroll to it.
        compose.waitUntil(15000) {
            runCatching {
                compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
                    .performScrollToNode(hasText(banner))
                compose.onNodeWithText(banner).assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(expected))
        compose.onNodeWithText(expected).assertIsDisplayed()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val file = File(context.filesDir, "b02-payment-$name.png")
        file.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
        Log.i("B02PaymentDevice", "verified case=$name pid=${Process.myPid()} encryptedOnly=true screenshot=${file.name}")
    }
}
