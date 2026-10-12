package com.yuldash.app

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.runtime.key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.InstantReceiptDto
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual receipt and child dialogs; synthetic local HTTP. Held server release + bounded UI pump. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaxiReceiptOwnerBoundaryTest {
    @get:Rule val compose = createComposeRule()
    private val tokenA = "header.eyJzdWIiOiIxMSJ9.signature"
    private val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
    private val mounted = mutableStateOf(true)
    private val slot = mutableStateOf(0)
    private val target = mutableStateOf(91)
    private val chats = CopyOnWriteArrayList<Int>()
    private val records = CopyOnWriteArrayList<List<String?>>()
    private var backs = 0
    private var hold = ""
    private var fail = ""
    private var wrongReceipt = false
    private var driver = false
    private var paid = false
    private var tipThanked = false
    private var online = false
    private val parentCurrent = mutableStateOf(true)
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private lateinit var vm: YuldashViewModel
    private val started = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val returned = CountDownLatch(1)
    private lateinit var server: MockWebServer
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun json(body: String, status: Int = 200) = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body)
    private fun receipt(id: Int) = InstantReceiptDto(orderId = id, role = if (driver) "driver" else "passenger",
        fromText = "Пункт А $id", toText = "Пункт Б $id", doneAt = "2026-10-10T10:00:00Z", distanceKm = 4.0,
        amount = 250, amountKop = 25000, waitingFeeKop = 0, paymentMethod = "cash", paid = paid,
        driverName = "Водитель $id", driverVerified = true, counterpartyId = 22, counterpartyName = "Участник $id")
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(app); ApiClient.saveToken(tokenA)
        OnlinePayGate.asked = true; OnlinePayGate.unavailable = true
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(q: RecordedRequest): MockResponse {
                    val p = q.requestUrl!!.encodedPath
                    records += listOf(q.method, p, q.getHeader("Authorization"), q.body.readUtf8())
                    val snapshotThanks = tipThanked
                    if (p == hold) { started.countDown(); assertTrue(release.await(35, TimeUnit.SECONDS)); returned.countDown() }
                    if (p == fail) return json("""{"detail":"Контрольный отказ чека"}""", 503)
                    return when {
                        p.endsWith("/receipt") -> {
                            val id = if (wrongReceipt) 999 else p.split('/')[3].toInt()
                            json("""{"order_id":$id,"role":"${if (driver) "driver" else "passenger"}","from_text":"Пункт А $id","to_text":"Пункт Б $id","done_at":"2026-10-10T10:00:00Z","amount":250,"amount_kop":25000,"payment_method":"cash","paid":$paid,"driver_name":"Водитель $id","counterparty_id":22,"counterparty_name":"Участник $id"}""")
                        }
                        p.endsWith("/tip") -> json("""{"already_thanked":$snapshotThanks}""")
                        p.endsWith("/cash-received") -> { paid = true; json("""{"status":"paid"}""") }
                        p.endsWith("/lost-item") -> json("""{"chat_open_until":"2026-10-12T10:00:00Z"}""")
                        p == "/incidents" -> json("""{"id":501,"respondent_id":22,"type":"rude","status":"open"}""")
                        p == "/health" -> json("""{"payments":"${if (online) "yookassa" else "mock"}""" + "}")
                        else -> json("{}")
                    }
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
    }
    private fun pump(ms: Long = 500) { repeat((ms / 100).toInt()) { Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }; compose.waitForIdle() }
    private fun await(check: () -> Boolean) = compose.waitUntil(15000) { Snapshot.sendApplyNotifications(); compose.mainClock.advanceTimeBy(100); Shadows.shadowOf(Looper.getMainLooper()).idle(); check() }
    private fun mount(remote: Boolean = false, initialId: Int? = 91) {
        compose.mainClock.autoAdvance = false
        compose.setContent { if (mounted.value) key(slot.value) { YuldashTheme {
            TaxiReceiptScreen(target.value, onBack = { backs++ }, onOpenChat = { chats += it },
                initialReceipt = initialId?.let { receipt(if (it == 91) target.value else it) }, loadRemote = remote,
                isCurrentParent = { parentCurrent.value })
        } } }; pump()
    }
    private fun retain(matcher: SemanticsMatcher): () -> Boolean {
        await { compose.onAllNodes(matcher and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        return compose.onAllNodes(matcher and hasClickAction()).onFirst().fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }
    private fun text(s: String) = retain(hasText(s))
    private fun star() = retain(hasContentDescription("5 звёзд"))
    private fun share() = retain(hasContentDescription("Поделиться чеком"))
    private fun back() = retain(hasContentDescription("Назад"))
    private fun dispatch(a: () -> Boolean) { a() }
    private fun click(s: String) { val a = text(s); compose.runOnIdle { dispatch(a) } }
    private fun count(suffix: String) = records.count { it[1]!!.endsWith(suffix) }
    private fun nextOwner(a: () -> Boolean, guest: Boolean = false) { compose.runOnIdle { if (guest) ApiClient.logout() else ApiClient.saveToken(tokenB); dispatch(a) }; pump(1000) }
    private fun heldDone() { release.countDown(); assertTrue(returned.await(8, TimeUnit.SECONDS)); pump(1500) }
    /** Existing Robolectric WRAP_CONTENT + text-field fixture workaround; product window unchanged. */
    private fun openTextDialog(action: () -> Boolean) {
        val previous = ShadowDialog.getLatestDialog()
        compose.runOnIdle { dispatch(action) }
        repeat(10) {
            val dialog = ShadowDialog.getLatestDialog()
            if (dialog != null && dialog !== previous && dialog.isShowing) {
                dialog.window!!.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                return
            }
            compose.mainClock.advanceTimeByFrame()
        }
        fail("text dialog did not open")
    }
    private fun reportSend(): () -> Boolean { click("Проблема с поездкой"); openTextDialog(text("Сообщить о нарушении")); click("Нахамил"); return text("Отправить") }
    private fun incidentSend(): () -> Boolean {
        click("Проблема с поездкой"); openTextDialog(text("Открыть разбор")); click("Нагрубили"); pump()
        compose.onNode(hasSetTextAction()).performTextInput("Синтетическое описание")
        return text("Открыть разбор")
    }
    @After fun cleanup() {
        release.countDown(); compose.runOnUiThread { mounted.value = false }; pump(300)
        owner.viewModelStore.clear(); NavSignals.openTaxiReceipt.value = 0
        OnlinePayGate.asked = false; OnlinePayGate.unavailable = false
        ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
    }
    @Test fun currentRemoteReceiptSharesMatchingDocument() {
        mount(remote = true, initialId = null); await { compose.onAllNodesWithText("Водитель 91").fetchSemanticsNodes().isNotEmpty() }
        val a = share(); compose.runOnIdle { dispatch(a) }
        val chooser = Shadows.shadowOf(app).nextStartedActivity; assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action); assertEquals("text/plain", send.type)
        val body = send.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(body.contains("Заказ № 91")); assertTrue(body.contains("250 ₽")); assertFalse(body.contains("+700"))
        assertEquals("Bearer $tokenA", records.single { it[1]!!.endsWith("/receipt") }[2])
    }
    @Test fun mismatchingRemoteReceiptCannotExposeOtherOrder() { wrongReceipt = true; mount(remote = true, initialId = null); await { count("/receipt") == 1 }; pump(1500); compose.onAllNodesWithText("Водитель 999").assertCountEquals(0); compose.onAllNodesWithText("Забыл вещь?").assertCountEquals(0) }
    @Test fun mismatchingInitialReceiptCannotExposeOtherOrder() { mount(initialId = 999); compose.onAllNodesWithText("Водитель 999").assertCountEquals(0); compose.onAllNodesWithText("Забыл вещь?").assertCountEquals(0) }
    @Test fun nextAccountRetiresPrivateDocument() { mount(); compose.runOnIdle { ApiClient.saveToken(tokenB) }; pump(); compose.onAllNodesWithText("Водитель 91").assertCountEquals(0) }
    @Test fun oldShareCannotRouteForNextOwnerBeforeFrame() { mount(); nextOwner(share()); assertNull(Shadows.shadowOf(app).nextStartedActivity) }
    @Test fun oldBackCannotNavigateNextOwner() { mount(); nextOwner(back()); assertEquals(0, backs) }
    @Test fun backRetiresActionsBeforeParentFrame() { mount(); val a = text("Сказать «рәхмәт»"); val b = back(); compose.runOnIdle { dispatch(b); dispatch(a); dispatch(b) }; pump(); assertEquals(1, backs); assertEquals(0, count("/thanks")) }
    @Test fun oldStarCannotUseNextAccount() { mount(); nextOwner(star()); assertEquals(0, count("/rate")) }
    @Test fun oldStarCannotUseGuest() { mount(); nextOwner(star(), true); assertEquals(0, count("/rate")) }
    @Test fun replacedTargetRetiresRating() { mount(); val a = star(); compose.runOnIdle { target.value = 92 }; pump(); compose.runOnIdle { dispatch(a) }; pump(); assertEquals(0, count("/rate")) }
    @Test fun replacedTargetRetiresShareText() { mount(); val a = share(); compose.runOnIdle { target.value = 92 }; pump(); compose.runOnIdle { dispatch(a) }; pump(); assertNull(Shadows.shadowOf(app).nextStartedActivity) }
    @Test fun disposedReceiptRetiresLostAction() { mount(); val a = text("Забыл вещь?"); compose.runOnIdle { slot.value++ }; pump(); compose.runOnIdle { dispatch(a) }; pump(); assertEquals(0, count("/lost-item")) }
    @Test fun currentRatingHasPayloadAndSavedState() { mount(); val a = star(); compose.runOnIdle { dispatch(a) }; await { compose.onAllNodesWithText("Твоя оценка сохранена").fetchSemanticsNodes().isNotEmpty() }; val q = records.single { it[1]!!.endsWith("/rate") }; assertEquals("Bearer $tokenA", q[2]); assertEquals(5, JSONObject(q[3]!!).getInt("stars")) }
    @Test fun duplicateHeldRatingSendsOnce() { hold = "/instant/orders/91/rate"; mount(); val a = star(); compose.runOnIdle { dispatch(a); dispatch(a) }; await { started.count == 0L }; pump(); assertEquals(1, count("/rate")); heldDone() }
    @Test fun failedCurrentRatingCanRetry() { fail = "/instant/orders/91/rate"; mount(); val a = star(); compose.runOnIdle { dispatch(a) }; await { compose.onAllNodesWithText("Контрольный отказ чека").fetchSemanticsNodes().isNotEmpty() }; fail = ""; compose.runOnIdle { dispatch(a) }; await { compose.onAllNodesWithText("Твоя оценка сохранена").fetchSemanticsNodes().isNotEmpty() }; assertEquals(2, count("/rate")) }
    @Test fun heldRatingCannotMarkReplacementSaved() { hold = "/instant/orders/91/rate"; mount(); val a = star(); compose.runOnIdle { dispatch(a) }; await { started.count == 0L }; compose.runOnIdle { target.value = 92 }; pump(); heldDone(); compose.onAllNodesWithText("Твоя оценка сохранена").assertCountEquals(0) }
    @Test fun currentThanksSucceedsOnce() { mount(); val a = text("Сказать «рәхмәт»"); compose.runOnIdle { dispatch(a) }; await { compose.onAllNodesWithText("«Рәхмәт» сказан").fetchSemanticsNodes().isNotEmpty() }; compose.runOnIdle { dispatch(a) }; pump(); assertEquals(1, count("/thanks")); assertEquals("Bearer $tokenA", records.single { it[1]!!.endsWith("/thanks") }[2]) }
    @Test fun oldThanksCannotUseNextAccount() { mount(); nextOwner(text("Сказать «рәхмәт»")); assertEquals(0, count("/thanks")) }
    @Test fun duplicateHeldThanksSendsOnce() { hold = "/instant/orders/91/thanks"; mount(); val a = text("Сказать «рәхмәт»"); compose.runOnIdle { dispatch(a); dispatch(a) }; await { started.count == 0L }; pump(); assertEquals(1, count("/thanks")); heldDone() }
    @Test fun lateTipCannotUndoSuccessfulThanks() { hold = "/instant/orders/91/tip"; mount(); await { started.count == 0L }; click("Сказать «рәхмәт»"); await { compose.onAllNodesWithText("«Рәхмәт» сказан").fetchSemanticsNodes().isNotEmpty() }; heldDone(); compose.onNodeWithText("«Рәхмәт» сказан").assertExists() }
    @Test fun currentCashReloadsPaidReceipt() { driver = true; mount(remote = true); await { count("/receipt") == 1 }; click("Наличные получил"); await { count("/receipt") == 2 }; await { compose.onAllNodesWithText("Наличными · Оплачено").fetchSemanticsNodes().isNotEmpty() }; assertEquals(1, count("/cash-received")) }
    @Test fun oldCashCannotUseNextAccount() { driver = true; mount(); nextOwner(text("Наличные получил")); assertEquals(0, count("/cash-received")) }
    @Test fun heldCashCannotReloadReplacementOrder() { driver = true; hold = "/instant/orders/91/cash-received"; mount(remote = true); val a = text("Наличные получил"); compose.runOnIdle { dispatch(a) }; await { started.count == 0L }; compose.runOnIdle { target.value = 92 }; pump(); await { count("/instant/orders/92/receipt") == 1 }; heldDone(); assertEquals(1, count("/instant/orders/92/receipt")) }
    @Test fun currentLostItemOpensMatchingChatAndReuseDoesNotPostAgain() { mount(); click("Забыл вещь?"); await { chats.size == 1 }; click("Открыть чат поездки"); assertEquals(listOf(91, 91), chats.toList()); assertEquals(1, count("/lost-item")) }
    @Test fun oldLostCannotUseNextOwner() { mount(); nextOwner(text("Забыл вещь?")); assertEquals(0, count("/lost-item")); assertTrue(chats.isEmpty()) }
    @Test fun heldLostCannotOpenReplacementChat() { hold = "/instant/orders/91/lost-item"; mount(); click("Забыл вещь?"); await { started.count == 0L }; compose.runOnIdle { target.value = 92 }; pump(); heldDone(); assertTrue(chats.isEmpty()) }
    @Test fun heldLostCannotNavigateAfterBack() { hold = "/instant/orders/91/lost-item"; mount(); click("Забыл вещь?"); await { started.count == 0L }; val b = back(); compose.runOnIdle { dispatch(b) }; heldDone(); assertEquals(1, backs); assertTrue(chats.isEmpty()) }
    @Test fun receiptErrorCanRetryCurrentOwner() { fail = "/instant/orders/91/receipt"; mount(remote = true, initialId = null); val retry = text("Повторить"); fail = ""; compose.runOnIdle { dispatch(retry) }; await { compose.onAllNodesWithText("Водитель 91").fetchSemanticsNodes().isNotEmpty() }; assertEquals(2, count("/receipt")) }
    @Test fun oldReceiptRetryCannotReadAsNextOwner() { fail = "/instant/orders/91/receipt"; mount(remote = true, initialId = null); val a = text("Повторить"); val n = count("/receipt"); nextOwner(a); assertEquals(n, count("/receipt")) }
    @Test fun currentReportHasReceiptPayload() { mount(); val a = reportSend(); compose.runOnIdle { dispatch(a) }; await { count("/reports") == 1 }; val q = records.single { it[1] == "/reports" }; val b = JSONObject(q[3]!!); assertEquals(91, b.getInt("order_id")); assertEquals("rude", b.getString("category")); assertEquals("Bearer $tokenA", q[2]) }
    @Test fun oldReportCannotUseNextOwnerBeforeFrame() { mount(); nextOwner(reportSend()); assertEquals(0, count("/reports")) }
    @Test fun cancelledReportSendCannotReplayIntoReopenedDialog() { mount(); val old = reportSend(); click("Отмена"); pump(); reportSend(); compose.runOnIdle { dispatch(old) }; pump(); assertEquals(0, count("/reports")) }
    @Test fun oldChoiceCloseCannotCloseReopenedChoice() { mount(); click("Проблема с поездкой"); val old = text("Закрыть"); compose.runOnIdle { dispatch(old) }; pump(); click("Проблема с поездкой"); compose.runOnIdle { dispatch(old) }; pump(); compose.onNodeWithText("Сообщить о нарушении").assertExists() }
    @Config(qualifiers = "w411dp-h891dp") @Test fun currentIncidentHasReceiptPayload() { mount(); val a = incidentSend(); compose.runOnIdle { dispatch(a) }; await { compose.onAllNodesWithText("Разбор открыт. Мы сообщим о решении.").fetchSemanticsNodes().isNotEmpty() }; val q = records.single { it[1] == "/incidents" }; val b = JSONObject(q[3]!!); assertEquals(91, b.getInt("order_id")); assertEquals(22, b.getInt("respondent_id")); assertEquals("rude", b.getString("type")); assertEquals("Синтетическое описание", b.getString("description")); assertEquals("Bearer $tokenA", q[2]) }
    @Config(qualifiers = "w411dp-h891dp") @Test fun oldIncidentCannotUseNextOwnerBeforeFrame() { mount(); nextOwner(incidentSend()); assertEquals(0, count("/incidents")) }
    @Config(qualifiers = "w411dp-h891dp") @Test fun cancelledIncidentCannotReplayIntoReopenedDialog() { mount(); val old = incidentSend(); click("Отмена"); pump(); incidentSend(); compose.runOnIdle { dispatch(old) }; pump(); assertEquals(0, count("/incidents")) }
    @Config(qualifiers = "w411dp-h891dp") @Test fun duplicateHeldIncidentSendsOnce() { hold = "/incidents"; mount(); val a = incidentSend(); compose.runOnIdle { dispatch(a); dispatch(a) }; await { started.count == 0L }; pump(); assertEquals(1, count("/incidents")); heldDone() }
    @Test fun ratingQueuedBeforeBackCannotSendAfterRetirement() { mount(); val a = star(); val b = back(); compose.runOnIdle { dispatch(a); dispatch(b) }; pump(1500); assertEquals(0, count("/rate")) }
    @Test fun thanksQueuedBeforeBackCannotSendAfterRetirement() { mount(); val a = text("Сказать «рәхмәт»"); val b = back(); compose.runOnIdle { dispatch(a); dispatch(b) }; pump(1500); assertEquals(0, count("/thanks")) }
    @Test fun cashQueuedBeforeBackCannotSendAfterRetirement() { driver = true; mount(); val a = text("Наличные получил"); val b = back(); compose.runOnIdle { dispatch(a); dispatch(b) }; pump(1500); assertEquals(0, count("/cash-received")) }
    @Test fun lostQueuedBeforeBackCannotSendAfterRetirement() { mount(); val a = text("Забыл вещь?"); val b = back(); compose.runOnIdle { dispatch(a); dispatch(b) }; pump(1500); assertEquals(0, count("/lost-item")); assertTrue(chats.isEmpty()) }
    @Test fun reportQueuedBeforeBackCannotSendAfterRetirement() { mount(); val b = back(); val a = reportSend(); compose.runOnIdle { dispatch(a); dispatch(b) }; pump(1500); assertEquals(0, count("/reports")) }
    @Config(qualifiers = "w411dp-h891dp") @Test fun incidentQueuedBeforeBackCannotSendAfterRetirement() { mount(); val b = back(); val a = incidentSend(); compose.runOnIdle { dispatch(a); dispatch(b) }; pump(1500); assertEquals(0, count("/incidents")) }
    @Test fun retainedReportSendRejectsOtherWithoutRequiredDescription() { mount(); val a = reportSend(); click("Другое"); compose.runOnIdle { dispatch(a) }; pump(1000); assertEquals(0, count("/reports")) }
    @Test fun preloadedCashCannotReplayAfterServerAcknowledgement() { driver = true; mount(); val a = text("Наличные получил"); compose.runOnIdle { dispatch(a) }; await { count("/cash-received") == 1 }; pump(1500); compose.runOnIdle { dispatch(a) }; pump(1000); assertEquals(1, count("/cash-received")) }
    @Test fun zeroOrderShowsErrorAndAllowsBackWithoutHttp() { invalidOrder(0) }
    @Test fun negativeOrderShowsErrorAndAllowsBackWithoutHttp() { invalidOrder(-1) }
    private fun invalidOrder(id: Int) {
        target.value = id; mount(remote = true, initialId = null)
        compose.onNodeWithContentDescription("Назад").assertExists()
        compose.onNodeWithText("Повторить").assertExists()
        val a = text("Повторить"); compose.runOnIdle { dispatch(a) }; pump()
        val b = back(); compose.runOnIdle { dispatch(b); dispatch(b) }; pump()
        assertEquals(1, backs); assertEquals(0, count("/receipt"))
    }
    @Test fun parentRetirementBeforeFrameBlocksRetainedRatingAndShare() {
        mount(); val a = star(); val s = share()
        compose.runOnIdle { parentCurrent.value = false; dispatch(a); dispatch(s) }; pump(1000)
        assertEquals(0, count("/rate")); assertNull(Shadows.shadowOf(app).nextStartedActivity)
    }
    @Test fun receiptBackRetiresNestedOnlinePaymentBeforeFrame() {
        online = true; OnlinePayGate.asked = false; OnlinePayGate.unavailable = false
        mount(); val pay = text("Оплатить 250 ₽"); val b = back()
        compose.runOnIdle { dispatch(b); dispatch(pay) }; pump(1000)
        assertEquals(1, backs); assertEquals(0, count("/pay"))
    }
    private fun mountRoot() {
        NavSignals.openTaxiReceipt.value = 0
        vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                YuldashViewModel(SavedStateHandle(mapOf("yuldash_screen" to "Support"))) as T
        })[YuldashViewModel::class.java]
        compose.mainClock.autoAdvance = false
        compose.setContent { if (mounted.value) CompositionLocalProvider(LocalViewModelStoreOwner provides owner,
            LocalAppLanguage provides AppLanguage.Ru) { YuldashApp() } }
        pump(); compose.runOnIdle { NavSignals.openTaxiReceipt.value = 91 }
        await { vm.screen.value == Screen.TaxiReceipt && compose.onAllNodesWithText("Водитель 91").fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun actualRootScreenChangeBeforeFrameRetiresReceiptActions() {
        mountRoot(); val a = star(); val s = share()
        compose.runOnIdle { vm.screen.value = Screen.Support; dispatch(a); dispatch(s) }; pump(1000)
        assertEquals(0, count("/rate")); assertNull(Shadows.shadowOf(app).nextStartedActivity)
    }
    @Test fun actualRootReplacementSignalRetiresOldReceiptActions() {
        mountRoot(); val a = star(); val s = share()
        compose.runOnIdle { NavSignals.openTaxiReceipt.value = 92 }
        await { compose.onAllNodesWithText("Водитель 92").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { dispatch(a); dispatch(s) }; pump(1000)
        assertEquals(0, count("/rate")); assertNull(Shadows.shadowOf(app).nextStartedActivity)
    }
    @Test fun staleExpectedGenerationRejectsAllNinePrivateApiEntries() = runBlocking {
        val generation = ApiClient.queueSessionGeneration(); ApiClient.saveToken(tokenB)
        val results = listOf(ApiClient.rateInstantOrder(91, 5, expectedGeneration = generation),
            ApiClient.getInstantReceipt(91, generation), ApiClient.instantCashReceived(91, generation),
            ApiClient.instantLostItem(91, generation), ApiClient.getInstantTipInfo(91, generation),
            ApiClient.sayInstantThanks(91, generation), ApiClient.reportUser(orderId = 91, expectedGeneration = generation),
            ApiClient.fileIncident(22, "rude", "Synthetic", orderId = 91, expectedGeneration = generation),
            ApiClient.uploadEvidence(byteArrayOf(1, 2, 3), expectedGeneration = generation))
        assertEquals(9, results.size); results.forEach { assertTrue(it.isFailure) }; assertTrue(records.isEmpty())
    }
    @Test fun ordinaryThanksFailureAllowsCurrentRetry() {
        fail = "/instant/orders/91/thanks"; mount(); click("Сказать «рәхмәт»")
        await { compose.onAllNodesWithText("Контрольный отказ чека").fetchSemanticsNodes().isNotEmpty() }
        fail = ""; click("Сказать «рәхмәт»"); await { compose.onAllNodesWithText("«Рәхмәт» сказан").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, count("/thanks"))
    }
    @Test fun ordinaryCashFailureAllowsCurrentRetry() {
        driver = true; fail = "/instant/orders/91/cash-received"; mount(); click("Наличные получил")
        await { compose.onAllNodesWithText("Контрольный отказ чека").fetchSemanticsNodes().isNotEmpty() }
        fail = ""; click("Наличные получил"); await { count("/cash-received") == 2 }; pump(1000)
        compose.onAllNodesWithText("Наличные получил").assertCountEquals(0)
    }
    @Test fun ordinaryLostFailureAllowsCurrentRetry() {
        fail = "/instant/orders/91/lost-item"; mount(); click("Забыл вещь?")
        await { compose.onAllNodesWithText("Контрольный отказ чека").fetchSemanticsNodes().isNotEmpty() }
        fail = ""; click("Забыл вещь?"); await { chats.size == 1 }; assertEquals(2, count("/lost-item")); assertEquals(listOf(91), chats.toList())
    }
    @Test fun ordinaryReportFailureAllowsReopeningAndCurrentRetry() {
        fail = "/reports"; mount(); val a = reportSend(); compose.runOnIdle { dispatch(a) }
        await { compose.onAllNodesWithText("Контрольный отказ чека").fetchSemanticsNodes().isNotEmpty() }
        fail = ""; val b = reportSend(); compose.runOnIdle { dispatch(b) }; await { count("/reports") == 2 }
    }
    @Config(qualifiers = "w411dp-h891dp") @Test fun ordinaryIncidentFailureAllowsCurrentRetry() {
        fail = "/incidents"; mount(); val a = incidentSend(); compose.runOnIdle { dispatch(a) }
        await { compose.onAllNodesWithText("Контрольный отказ чека").fetchSemanticsNodes().isNotEmpty() }
        fail = ""; compose.runOnIdle { dispatch(a) }
        await { compose.onAllNodesWithText("Разбор открыт. Мы сообщим о решении.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, count("/incidents"))
    }
}
