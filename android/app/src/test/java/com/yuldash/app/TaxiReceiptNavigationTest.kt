package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaxiReceiptNavigationTest {
    @get:Rule val compose = createComposeRule()
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private val mounted = mutableStateOf(true)
    private lateinit var vm: YuldashViewModel
    private lateinit var server: MockWebServer
    private val receiptTokens = CopyOnWriteArrayList<String>()
    private val cashTokens = CopyOnWriteArrayList<String>()
    private val requests = CopyOnWriteArrayList<String>()   // все запросы к серверу — для показаний таймаута
    @Volatile private var paid = false
    private var role = "passenger"
    private var firstFailure = 0

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        NavSignals.openTaxiReceipt.value = 0
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests.add("${request.method} ${request.requestUrl!!.encodedPath}")
                    return when (request.requestUrl!!.encodedPath) {
                        "/instant/orders/71/receipt" -> {
                            receiptTokens.add(request.getHeader("Authorization").orEmpty())
                            if (firstFailure != 0 && receiptTokens.size == 1) json("{}", firstFailure)
                            else json(receipt())
                        }
                        "/instant/orders/71/tip" -> json("""{"already_thanked":false}""")
                        "/instant/orders/71/cash-received" -> {
                            cashTokens.add(request.getHeader("Authorization").orEmpty())
                            paid = true
                            json("""{"status":"paid"}""")
                        }
                        else -> json("{}", 404)
                    }
                }
            }
            start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to "Support"))) as T
        })[YuldashViewModel::class.java]
    }

    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false }
        owner.viewModelStore.clear()
        NavSignals.openTaxiReceipt.value = 0
        ApiClient.logout()
        ApiClient.resetForTest()
        ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    @Test fun passengerSignalOpensServerReceiptWithExactKopecks() {
        openThroughSignal()
        verifyReceipt()
        compose.onNodeWithText("Оценить водителя").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("Наличные получил").assertCountEquals(0)
    }

    @Test fun driverSignalOpensReceiptAndCashAcknowledgement() {
        role = "driver"
        openThroughSignal()
        verifyReceipt()
        compose.onNodeWithText("Оценить пассажира").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Наличные получил").performScrollTo().performClick()
        waitOrExplain("нет отметки «Оплачено»") {
            compose.onAllNodesWithText("Оплачено", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(listOf("Bearer local-receipt-driver"), cashTokens.toList())
        assertEquals(2, receiptTokens.size)
        compose.onAllNodesWithText("Наличные получил").assertCountEquals(0)
        compose.onNodeWithText("Оплачено", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun loadFailureCanRetrySameOrderWithoutLosingNavigation() {
        firstFailure = 503
        openThroughSignal()
        waitForText("Повторить")
        compose.onAllNodesWithText("350,50 ₽").assertCountEquals(0)
        compose.onNodeWithText("Повторить").performClick()
        verifyReceipt(expectedReads = 2)
    }

    @Test fun unfinishedOrderShowsPendingInsteadOfInventedReceipt() {
        firstFailure = 409
        openThroughSignal()
        waitForText("Чек ещё не готов")
        compose.onAllNodesWithText("350,50 ₽").assertCountEquals(0)
        assertEquals(1, receiptTokens.size)
        compose.runOnIdle { assertEquals(Screen.TaxiReceipt, vm.screen.value) }
    }

    private fun openThroughSignal() {
        ApiClient.saveToken("local-receipt-$role")
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner,
                LocalAppLanguage provides AppLanguage.Ru) { YuldashApp() }
        }
        compose.runOnIdle {
            assertEquals(Screen.Support, vm.screen.value)
            NavSignals.openTaxiReceipt.value = 71
        }
        waitOrExplain("чек не открылся по сигналу") {
            vm.screen.value == Screen.TaxiReceipt && NavSignals.openTaxiReceipt.value == 0
        }
    }

    private fun verifyReceipt(expectedReads: Int = 1) {
        waitForText("350,50 ₽")
        compose.onAllNodesWithText("350,50 ₽").onFirst().performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Итого").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Пункт А").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Пункт Б").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Заказ № 71").performScrollTo().assertIsDisplayed()
        assertEquals(List(expectedReads) { "Bearer local-receipt-$role" }, receiptTokens.toList())
    }
    private fun waitForText(value: String) = waitOrExplain("нет текста «$value»") {
        compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
    }
    // Таймаут здесь в CI выглядел как голое «Condition still not satisfied after 15000 ms»:
    // ни экрана, ни сигнала, ни запросов (2026-09-26, 1 прогон из 3, локально не повторяется).
    // Теперь он сам говорит, где остановились, — чинить по показаниям, а не по догадке.
    // Сообщить Compose о записях состояния, сделанных вне композиции (сигнал из теста, ответы
    // ViewModel). Обычно это делает GlobalSnapshotManager, но в Robolectric он может «уснуть»
    // до конца прогона: сброс главного Looper между тестами выкидывает его отложенную отправку,
    // а флаг «уже отправлено» остаётся поднятым — экран больше не узнаёт об изменениях
    // (CI 2026-09-26: screen=Support, signal=71). Встроенное ожидание Compose делает то же.
    private fun settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Snapshot.sendApplyNotifications()
    }
    private fun waitOrExplain(what: String, condition: () -> Boolean) {
        try {
            compose.waitUntil(15000) {
                settle()
                condition()
            }
        } catch (e: ComposeTimeoutException) {
            throw AssertionError(
                "$what: screen=${vm.screen.value}, signal=${NavSignals.openTaxiReceipt.value}, " +
                    "loggedIn=${ApiClient.isLoggedIn()}, запросы=${requests.toList()}", e,
            )
        }
    }
    private fun receipt() = """{"order_id":71,"role":"$role","from_text":"Пункт А","to_text":"Пункт Б","done_at":"2030-01-02T10:30:00Z","distance_km":8.5,"amount":350,"amount_kop":35050,"price_kop":35050,"payment_method":"cash","paid":$paid,"driver_name":"Тестовый водитель","driver_verified":true,"counterparty_id":9,"counterparty_name":"Тестовый участник","my_stars":0,"ride_price":350,"driver_fee_percent":15,"driver_fee_kop":5250,"driver_gross_kop":35050,"driver_net_kop":29800}"""
    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code)
        .setHeader("Content-Type", "application/json").setBody(body)
}
