package com.yuldash.app

import android.app.Application
import androidx.compose.runtime.key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import com.yuldash.app.data.ApiClient
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual dialog, synthetic stored records and lost HTTP responses. No SMS or backend execution. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InstantShareReconciliationTest : ShareHelpBoundaryHarness() {
    @Volatile private var exists = false
    @Volatile private var mutationFails = true
    @Volatile private var commitMutation = true
    @Volatile private var mutationCode = 503
    @Volatile private var loseBody = false
    @Volatile private var listFails = false
    @Volatile private var listBody: String? = null
    @Volatile private var holdRecheck = false
    @Volatile private var falseAck = false
    private val recheckStarted = CountDownLatch(1)
    private val recheckRelease = CountDownLatch(1)
    private val recheckReturned = CountDownLatch(1)
    private val row = """{"id":501,"contact_id":7,"order_id":91,"token":"recovered-link"}"""
    override fun customResponse(request: RecordedRequest, path: String): MockResponse? {
        if (!path.startsWith("/instant/orders/")) return null
        if (path.endsWith("/shares")) {
            if (holdRecheck && count("POST", "/share") + count("DELETE", "/share/501") > 0) {
                recheckStarted.countDown(); assertTrue(recheckRelease.await(30, TimeUnit.SECONDS))
                recheckReturned.countDown()
            }
            val body = listBody ?: if (exists) "[$row]" else "[]"
            return response(body, if (listFails) 503 else 200)
        }
        if (request.method == "POST" && path.endsWith("/share")) {
            if (commitMutation) exists = true
            if (loseBody) return response(row).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            return response(row, if (mutationFails) mutationCode else 200)
        }
        if (request.method == "DELETE") {
            if (commitMutation) exists = false
            if (loseBody) return response("""{"ok":true,"padding":"${"x".repeat(1000)}"}""").setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            return response(if (falseAck) """{"ok":false}""" else """{"ok":true}""", if (mutationFails) mutationCode else 200)
        }
        return null
    }
    private fun response(body: String, code: Int = 200) = MockResponse().setHeader("Content-Type", "application/json").setResponseCode(code).setBody(body)
    private fun mount(language: AppLanguage = AppLanguage.Ru) {
        compose.mainClock.autoAdvance = false
        compose.setContent { if (mounted.value) key(slot.value) { CompositionLocalProvider(LocalAppLanguage provides language) { YuldashTheme {
            InstantShareDialog(target.value, { dismisses++ }, isCurrentParent = { parentCurrent.value })
        } } } }
        pump(); text("Контакт А")
    }
    private fun shown(s: String) = compose.onAllNodesWithText(s).fetchSemanticsNodes().isNotEmpty()
    private fun copied() = shown("Скопировать")
    private fun recovered() { await { copied() }; assertEquals(0, dismisses) }
    private fun unknown() { await { shown("Повторить") }; assertFalse(copied()); assertEquals(0, dismisses) }
    private fun startExisting() { exists = true; mount(); text("Отозвать") }
    private fun checks() = count("GET", "/shares")
    @After fun releaseRecheck() { recheckRelease.countDown() }

    @Test fun createCommittedThen503RecoversWithoutSecondPostOrSmsPromise() {
        mount(); click("Контакт А"); recovered(); assertEquals(1, count("POST", "/share")); assertEquals(2, checks()); assertNull(ShadowToast.getTextOfLatestToast())
        click("Скопировать"); assertTrue(clipboard.primaryClip!!.getItemAt(0).text.toString().endsWith("/t/recovered-link"))
    }
    @Test fun createCommittedThen429AlsoReconciles() { mutationCode = 429; mount(); click("Контакт А"); recovered(); assertEquals(2, checks()); assertNull(ShadowToast.getTextOfLatestToast()) }
    @Test fun createLostBodyRecoversStoredRow() { loseBody = true; mount(); click("Контакт А"); recovered(); assertEquals(1, count("POST", "/share")); assertEquals(2, checks()) }
    @Test fun ordinaryCreateRejectionStillDismissesAfterVerifiedAbsence() { commitMutation = false; mount(); click("Контакт А"); await { dismisses == 1 }; assertEquals(2, checks()); assertNotNull(ShadowToast.getTextOfLatestToast()) }
    @Test fun revokeCommittedThen503UsesAbsenceAndDoesNotRepeatDelete() { startExisting(); click("Отозвать"); await { checks() == 2 && !shown("Отозвать") }; assertEquals(1, count("DELETE", "/share/501")); assertEquals("Ссылка отозвана", ShadowToast.getTextOfLatestToast()) }
    @Test fun revokeLostBodyUsesCurrentList() { startExisting(); loseBody = true; click("Отозвать"); await { checks() == 2 && !shown("Отозвать") }; assertEquals(1, count("DELETE", "/share/501")) }
    @Test fun revoke404AlsoChecksWhetherRecordStillExists() { startExisting(); mutationCode = 404; click("Отозвать"); await { checks() == 2 && !shown("Отозвать") }; assertEquals("Ссылка отозвана", ShadowToast.getTextOfLatestToast()) }
    @Test fun explicitFalseRevokeAckCannotClaimSuccessWithoutList() { startExisting(); mutationFails = false; falseAck = true; commitMutation = false; click("Отозвать"); await { checks() == 2 }; pump(); assertTrue(shown("Отозвать")); assertNotEquals("Ссылка отозвана", ShadowToast.getTextOfLatestToast()) }
    @Test fun ordinaryRevokeRejectionKeepsRecordAndCanRetry() { startExisting(); commitMutation = false; click("Отозвать"); await { checks() == 2 }; pump(); assertTrue(shown("Отозвать")); commitMutation = true; mutationFails = false; click("Отозвать"); await { !shown("Отозвать") }; assertEquals(2, count("DELETE", "/share/501")) }
    @Test fun failedCreateRecheckBlocksOldContactAndRetryReadsBeforeAnyNewMutation() {
        mount(); val old = text("Контакт А"); listFails = true; compose.runOnIdle { dispatch(old) }; unknown()
        compose.runOnIdle { dispatch(old) }; pump(); assertEquals(1, count("POST", "/share"))
        listFails = false; click("Повторить"); recovered(); assertEquals(3, checks()); assertEquals(1, count("POST", "/share"))
    }
    @Test fun failedRevokeRecheckBlocksOldRevokeAndRoutesUntilRetry() {
        mutationFails = false; mount(); click("Контакт А"); recovered(); val copy = text("Скопировать"); val send = text("Отправить"); val revoke = text("Отозвать")
        mutationFails = true; listFails = true; compose.runOnIdle { dispatch(revoke) }; unknown()
        compose.runOnIdle { dispatch(copy); dispatch(send); dispatch(revoke) }; pump(); assertFalse(clipboard.hasPrimaryClip()); assertEquals(1, count("DELETE", "/share/501"))
        listFails = false; click("Повторить"); await { !shown("Повторить") }; assertFalse(copied()); assertFalse(shown("Отозвать")); assertEquals(1, count("DELETE", "/share/501"))
    }
    @Test fun queuedFailedMutationCannotRecheckUnderNextOwner() {
        mount(); holdPath = "/instant/orders/91/share"; click("Контакт А"); await { started.count == 0L }; compose.runOnIdle { ApiClient.saveToken(tokenB) }; finishHeld(); assertEquals(1, checks()); assertFalse(records.any { it[1]!!.endsWith("/shares") && it[2] == "Bearer $tokenB" })
    }
    @Test fun oldRetryCannotReadForNextOwner() { mount(); listFails = true; click("Контакт А"); unknown(); val retry = text("Повторить"); val before = checks(); changeOwner(retry); assertEquals(before, checks()) }
    @Test fun oldRetryCannotReadForInactiveParentBeforeFrame() { mount(); listFails = true; click("Контакт А"); unknown(); val retry = text("Повторить"); val before = checks(); compose.runOnIdle { parentCurrent.value = false; dispatch(retry) }; pump(); assertEquals(before, checks()) }
    @Test fun oldRetryCannotReadAfterCloseBeforeParentFrame() { mount(); listFails = true; click("Контакт А"); unknown(); val retry = text("Повторить"); val before = checks(); val close = text("Закрыть"); compose.runOnIdle { dispatch(close); dispatch(retry) }; pump(); assertEquals(1, dismisses); assertEquals(before, checks()) }
    @Test fun heldRecheckKeepsMutationsBlockedUntilAuthoritativeResponse() { holdRecheck = true; mount(); val contact = text("Контакт А"); compose.runOnIdle { dispatch(contact) }; await { recheckStarted.count == 0L }; compose.runOnIdle { dispatch(contact) }; assertEquals(1, count("POST", "/share")); assertFalse(copied()); recheckRelease.countDown(); assertTrue(recheckReturned.await(5, TimeUnit.SECONDS)); recovered() }
    @Test fun malformedListCannotProveRevocation() { startExisting(); listBody = "{}"; click("Отозвать"); unknown(); assertNotEquals("Ссылка отозвана", ShadowToast.getTextOfLatestToast()) }
    @Test fun zeroIdListCannotProveRevocation() { startExisting(); listBody = """[{"id":0,"contact_id":7}]"""; click("Отозвать"); unknown() }
    @Test fun wrongOrderListCannotPublishRecoveredLink() { mount(); listBody = """[{"id":501,"contact_id":7,"order_id":92,"token":"wrong-order"}]"""; click("Контакт А"); unknown() }
    @Test fun malformedListApiReturnsFailureRatherThanEmptyOrException() {
        for (bad in listOf("{}", """[{"id":-1,"contact_id":7}]""", """[{"id":501}]""", "[$row,$row]", """[{"id":501,"contact_id":7,"booking_id":91}]""", """[{"id":501,"contact_id":7,"parcel_id":91}]""")) {
            listBody = bad; assertTrue("Invalid response accepted: $bad", runBlocking { ApiClient.getInstantShares(91).isFailure })
        }
    }
    @Test fun absentOldIdKeepsNewRecordForSameContactAndRetiresOldRevoke() {
        startExisting(); val old = text("Отозвать")
        listBody = """[{"id":502,"contact_id":7,"order_id":91,"token":"new-record"}]"""
        compose.runOnIdle { dispatch(old) }; await { checks() == 2 && ShadowToast.getTextOfLatestToast() == "Ссылка отозвана" && shown("Отозвать") }
        assertTrue(shown("Отозвать")); compose.runOnIdle { dispatch(old) }; pump(); assertEquals(1, count("DELETE", "/share/501"))
        mutationFails = false; click("Отозвать"); await { count("DELETE", "/share/502") == 1 }
    }
    @Test fun validListWithoutTokenHasNoFabricatedLinkAndEmptyArrayIsValid() {
        listBody = """[{"id":501,"contact_id":7,"order_id":91,"token":null}]"""
        assertNull(runBlocking { ApiClient.getInstantShares(91).getOrThrow().single().link })
        listBody = "[]"; assertTrue(runBlocking { ApiClient.getInstantShares(91).getOrThrow() }.isEmpty())
    }
    @Test fun bashkirUnknownStateRetriesOnlyReadThenRecovers() {
        mount(AppLanguage.Ba); listFails = true; click("Контакт А"); await { shown("Ҡабатлау") }
        assertTrue(shown("Һылтанманы тикшереп булманы")); assertFalse(shown("Күсереү")); listFails = false; click("Ҡабатлау")
        await { shown("Күсереү") }; assertEquals(1, count("POST", "/share")); assertEquals(3, checks()); assertNull(ShadowToast.getTextOfLatestToast())
    }
    @Test fun queuedContactRetiresBeforeLaunchAndBusyRecoversForCurrentParent() {
        mutationFails = false; mount(); val contact = text("Контакт А")
        compose.runOnIdle { contact(); parentCurrent.value = false }; pump(); assertEquals(0, count("POST", "/share"))
        compose.runOnIdle { parentCurrent.value = true }; pump(); click("Контакт А"); recovered(); assertEquals(1, count("POST", "/share"))
    }
    @Test fun queuedRevokeRetiresBeforeLaunchAndBusyRecoversForCurrentParent() {
        mutationFails = false; startExisting(); val revoke = text("Отозвать")
        compose.runOnIdle { revoke(); parentCurrent.value = false }; pump(); assertEquals(0, count("DELETE", "/share/501"))
        compose.runOnIdle { parentCurrent.value = true }; pump(); click("Отозвать"); await { !shown("Отозвать") }; assertEquals(1, count("DELETE", "/share/501"))
    }
    @Test fun explicitNullOrderCannotProvideVerifiedList() {
        listBody = """[{"id":501,"contact_id":7,"order_id":null,"token":"unbound"}]"""
        assertTrue(runBlocking { ApiClient.getInstantShares(91).isFailure })
    }
}
