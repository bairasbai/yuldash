package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Joined real API calls and actual default VM/current Bundle; no Compose/device/real deletion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class AccountDeletionReceiptTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private val a="header.eyJzdWIiOiIxMSJ9.signature"
    private val b="header.eyJzdWIiOiIxMiJ9.signature"
    private lateinit var server:MockWebServer
    private val records=CopyOnWriteArrayList<RecordedRequest>()
    private val started=CountDownLatch(1);private val release=CountDownLatch(1)
    private val logoutStarted=CountDownLatch(1)
    @Volatile private var held=false
    @Volatile private var status=200
    @Before fun setup() {
        ApiClient.resetForTest();ApiClient.init(context);ApiClient.saveToken(a)
        server=MockWebServer().apply {dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                records+=request
                if(request.path=="/auth/logout") logoutStarted.countDown()
                if(request.path=="/me/delete") {started.countDown();if(held) assertTrue(release.await(8,TimeUnit.SECONDS))}
                return MockResponse().setResponseCode(if(request.path=="/me/delete") status else 200).setBody("{}")
            }
        };start()}
        ApiClient.testBaseUrl=server.url("/").toString().trimEnd('/');ApiClient.testTimeoutMs=10000
        DeepLink.pendingRideId.value=null
    }
    @After fun cleanup() {release.countDown();DeepLink.pendingRideId.value=null;ApiClient.resetForTest();ApiClient.testTimeoutMs=null;server.shutdown()}
    private fun receipt():Long=runBlocking {ApiClient.deleteAccount(ApiClient.queueSessionGeneration()).getOrThrow()}
    private fun logoutLocally() {
        ApiClient.logout()
        assertTrue("Logout must reach the local server before the test URL is reset",logoutStarted.await(5,TimeUnit.SECONDS))
    }
    @Test fun currentReceiptClearsAndSavesLoginBeforeAnyComposeEffect() {
        val actor=Robolectric.buildActivity(MainActivity::class.java).create()
        try {
            val vm=ViewModelProvider(actor.get())[YuldashViewModel::class.java]
            vm.screen.value=Screen.Home;vm.navPrev.value=Screen.Home;vm.startHomeTab.value=HomeTab.Profile
            vm.activeBookingId.value=77;vm.supportTicketId.value=44;vm.responsesRequestId.value=43;vm.isAdmin.value=true
            vm.trustedContacts.add(TrustedContact("Локальный тест","друг","000",true));vm.navHistory.add(Screen.Settings);vm.persistNav()
            val ticket=receipt();assertEquals(ApiClient.queueSessionGeneration(),ticket);assertNull(ApiClient.currentToken())
            vm.requestBookingDestination(45,11,completed=false);DeepLink.pendingRideId.value=42
            assertTrue(completeAccountDeletion(context,vm,ticket))
            assertEquals(Screen.Login,vm.screen.value);assertEquals(HomeTab.Map,vm.startHomeTab.value)
            assertNull(DeepLink.pendingRideId.value);assertNull(vm.pendingBookingNavigation.value);assertNull(vm.activeBookingId.value)
            assertEquals(0,vm.supportTicketId.value);assertEquals(0,vm.responsesRequestId.value);assertFalse(vm.isAdmin.value);assertTrue(vm.trustedContacts.isEmpty());assertTrue(vm.navHistory.isEmpty())
            val bundle=Bundle();actor.saveInstanceState(bundle)
            val next=Robolectric.buildActivity(MainActivity::class.java).create(bundle)
            try {val restored=ViewModelProvider(next.get())[YuldashViewModel::class.java];assertEquals(Screen.Login,restored.screen.value);assertEquals(HomeTab.Map,restored.startHomeTab.value);assertNull(restored.activeBookingId.value);assertNull(restored.pendingBookingNavigation.value);assertTrue(restored.navHistory.isEmpty())} finally {next.destroy()}
        } finally {actor.destroy()}
    }
    private fun rejectsOldReceipt(change:()->Unit) {
        val actor=Robolectric.buildActivity(MainActivity::class.java).create()
        try {val ticket=receipt();change();val vm=ViewModelProvider(actor.get())[YuldashViewModel::class.java];vm.screen.value=Screen.Settings;vm.activeBookingId.value=88;vm.isAdmin.value=true
            assertFalse(completeAccountDeletion(context,vm,ticket));assertEquals(Screen.Settings,vm.screen.value);assertEquals(88,vm.activeBookingId.value);assertTrue(vm.isAdmin.value)
        } finally {actor.destroy()}
    }
    @Test fun oldReceiptCannotClearNewAccount() {rejectsOldReceipt {ApiClient.saveToken(b)};assertEquals(b,ApiClient.currentToken())}
    @Test fun oldReceiptCannotClearLaterGuestGeneration() {rejectsOldReceipt {ApiClient.saveToken(b);logoutLocally()};assertNull(ApiClient.currentToken())}
    @Test fun oldReceiptCannotClearSameOwnerNewLogin() {rejectsOldReceipt {ApiClient.saveToken(a)};assertEquals(a,ApiClient.currentToken())}
    @Test fun capturedGenerationCannotStartDeletionUnderB()=runBlocking {
        val generation=ApiClient.queueSessionGeneration();ApiClient.saveToken(b)
        assertTrue(ApiClient.deleteAccount(generation).isFailure);assertEquals(b,ApiClient.currentToken());assertTrue(records.none {it.path=="/me/delete"})
    }
    @Test fun capturedGenerationCannotStartDeletionAsGuest()=runBlocking {
        val generation=ApiClient.queueSessionGeneration();logoutLocally()
        assertTrue(ApiClient.deleteAccount(generation).isFailure);assertNull(ApiClient.currentToken());assertTrue(records.none {it.path=="/me/delete"})
    }
    @Test fun failureDoesNotMintReceiptOrChangeSession()=runBlocking {
        status=503;val generation=ApiClient.queueSessionGeneration()
        assertTrue(ApiClient.deleteAccount(generation).isFailure);assertEquals(a,ApiClient.currentToken());assertEquals(generation,ApiClient.queueSessionGeneration())
        assertEquals("Bearer $a",records.single {it.path=="/me/delete"}.getHeader("Authorization"))
    }
    @Test fun joinedHeldSuccessCannotMintReceiptAfterBLogin()=runBlocking {
        held=true;val generation=ApiClient.queueSessionGeneration();val result=async(Dispatchers.IO) {ApiClient.deleteAccount(generation)}
        assertTrue(started.await(5,TimeUnit.SECONDS));ApiClient.saveToken(b);release.countDown()
        assertTrue(result.await().isFailure);assertEquals(b,ApiClient.currentToken());assertFalse(ApiClient.sessionExpired.value)
    }
}
