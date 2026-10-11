package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Count actual wire requests; an EOF after server commit must not silently resend a mutation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientContactsTransportTest {
    private lateinit var server: MockWebServer
    private val records = CopyOnWriteArrayList<RecordedRequest>()
    private val mutations = AtomicInteger()
    private val token = "header.eyJzdWIiOiIxMSJ9.signature"
    @Volatile private var row = false
    private var drop = true
    private var commit = true
    private var replyCode = 200
    private var refreshRequired = false
    private var redirect = false
    private var dropFirstRead = false
    private val payloadName = "Әсәй — контакт"
    @Before fun setup() {
        ApiClient.testTrace = null; ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Application>()); ApiClient.saveToken(token)
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    records += request
                    val path = request.requestUrl!!.encodedPath
                    if (path == "/auth/verify") return MockResponse().setBody("""{"access_token":"old-access","refresh_token":"old-refresh"}""")
                    if (path == "/auth/refresh") return MockResponse().setBody("""{"access_token":"new-access","refresh_token":"new-refresh"}""")
                    if (path == "/trusted-contacts" && request.method == "GET") {
                        if (dropFirstRead) { dropFirstRead=false; return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
                        return MockResponse().setBody(if (row) """{"items":[{"id":7,"name":"Synthetic","phone":"+70000000007"}]}""" else """{"items":[]}""")
                    }
                    if ((request.method == "POST" && path == "/trusted-contacts") || (request.method == "DELETE" && path == "/trusted-contacts/7")) {
                        mutations.incrementAndGet()
                        if (refreshRequired && request.getHeader("Authorization") != "Bearer new-access") return MockResponse().setResponseCode(401).setBody("{}")
                        if (redirect) return MockResponse().setResponseCode(307).setHeader("Location",server.url("/redirect-target")).setBody("{}")
                        if (replyCode != 200) return MockResponse().setResponseCode(replyCode).setHeader("Retry-After","0").setBody("""{"detail":"Synthetic refusal"}""")
                        if (commit) row = request.method == "POST"
                        if (drop) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                        return MockResponse().setBody("{\"ok\":true}")
                    }
                    return MockResponse().setResponseCode(404).setBody("{}")
                }
            }; start()
        }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 2000
    }
    @After fun cleanup() {
        ApiClient.testTrace = null; ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown()
    }
    private suspend fun mutate(add: Boolean): Result<Unit> = if (add)
        ApiClient.addContact(payloadName, "Синтетический", "+70000000007", false) else ApiClient.deleteContact(7)
    private fun eof(add: Boolean, committed: Boolean) = runBlocking {
        row = !add; commit = committed
        val owner = ApiClient.queueSessionGeneration()
        val before = if (add) ApiClient.contactAdditions.value.version else ApiClient.contactDeletions.value.version
        assertEquals(if (add) emptyList<Int>() else listOf(7), ApiClient.getContacts().getOrThrow().map { it.id })
        val result = mutate(add)
        assertTrue("Lost reply must remain a failure", result.isFailure)
        assertEquals("Transport silently replayed a sent mutation", 1, mutations.get())
        assertTrue(ApiClient.isUncertainContactMutation(result.exceptionOrNull()))
        assertEquals(before+1, if (add) ApiClient.contactAdditions.value.version else ApiClient.contactDeletions.value.version)
        assertEquals(owner, ApiClient.queueSessionGeneration())
        assertEquals(if (if (committed) add else !add) listOf(7) else emptyList<Int>(), ApiClient.getContacts().getOrThrow().map { it.id })
        assertEquals(3, records.size)
        val sent = records.single { it.method == if (add) "POST" else "DELETE" }
        assertEquals("Bearer $token", sent.getHeader("Authorization"))
        if (add) {
            val raw = sent.body.clone().readByteArray(); val body = JSONObject(raw.toString(Charsets.UTF_8))
            assertEquals(payloadName, body.getString("name")); assertEquals("Синтетический", body.getString("relation"))
            assertEquals("+70000000007", body.getString("phone")); assertFalse(body.getBoolean("notify_by_default"))
            assertEquals(4, body.length())
        } else assertEquals(0L, sent.bodySize)
    }
    @Test fun deleteEofAfterCommitDoesNotReplayAndRefreshesCache() = eof(false, true)
    @Test fun addEofAfterCommitDoesNotReplayAndRefreshesCache() = eof(true, true)
    @Test fun deleteEofWithoutCommitDoesNotGuessSuccess() = eof(false, false)
    @Test fun addEofWithoutCommitDoesNotGuessSuccess() = eof(true, false)

    private fun manualRetry(add: Boolean) = runBlocking {
        assertTrue(mutate(add).isFailure); assertEquals(1,mutations.get())
        drop=false; mutate(add).getOrThrow(); assertEquals(2,mutations.get())
        assertEquals(if(add) listOf(7) else emptyList<Int>(),ApiClient.getContacts().getOrThrow().map { it.id })
    }
    @Test fun explicitSecondDeleteIsAllowedAfterUncertainFirstAttempt() = manualRetry(false)
    @Test fun explicitSecondAddIsAllowedAfterUncertainFirstAttempt() = manualRetry(true)
    private fun rejected(add: Boolean, code: Int) = runBlocking {
        drop=false; replyCode=code; row=!add
        assertEquals(if(add) emptyList<Int>() else listOf(7),ApiClient.getContacts().getOrThrow().map { it.id })
        val before=if(add) ApiClient.contactAdditions.value.version else ApiClient.contactDeletions.value.version
        val error=mutate(add).exceptionOrNull()
        assertTrue(error is ApiException); assertEquals(code,(error as ApiException).status)
        assertEquals(1,mutations.get()); assertEquals(before,if(add) ApiClient.contactAdditions.value.version else ApiClient.contactDeletions.value.version)
        assertEquals(if(add) emptyList<Int>() else listOf(7),ApiClient.getContacts().getOrThrow().map { it.id })
        assertEquals(2,records.size)
    }
    @Test fun add503RetryAfterZeroDoesNotReplay()=rejected(true,503)
    @Test fun delete503RetryAfterZeroDoesNotReplay()=rejected(false,503)
    @Test fun add408RetryAfterZeroDoesNotReplay()=rejected(true,408)
    @Test fun delete403DoesNotReplay()=rejected(false,403)
    private fun redirect(add:Boolean)=runBlocking {
        redirect=true; drop=false
        val error=mutate(add).exceptionOrNull(); assertTrue(error is ApiException); assertEquals(307,(error as ApiException).status)
        assertEquals(1,mutations.get()); assertEquals(1,records.size)
        assertFalse(records.any { it.path=="/redirect-target" })
    }
    @Test fun addRedirectDoesNotForwardMutation()=redirect(true)
    @Test fun deleteRedirectDoesNotForwardMutation()=redirect(false)
    private fun authRefresh(add:Boolean,eof:Boolean)=runBlocking {
        ApiClient.verifyCode("+70000000000","000000","Synthetic").getOrThrow()
        refreshRequired=true; drop=eof
        val owner=ApiClient.queueSessionGeneration(); val result=mutate(add)
        assertEquals(!eof,result.isSuccess); assertEquals(owner,ApiClient.queueSessionGeneration())
        assertEquals("new-access",ApiClient.currentToken()); assertEquals(2,mutations.get())
        assertEquals(1,records.count { it.path=="/auth/refresh" })
        val sent=records.filter { it.path==(if(add) "/trusted-contacts" else "/trusted-contacts/7") }
        assertEquals(listOf("Bearer old-access","Bearer new-access"),sent.map { it.getHeader("Authorization") })
        if(eof) assertTrue(ApiClient.isUncertainContactMutation(result.exceptionOrNull()))
    }
    @Test fun add401RefreshResendsOnlyWithNewBearer()=authRefresh(true,false)
    @Test fun delete401RefreshResendsOnlyWithNewBearer()=authRefresh(false,false)
    @Test fun refreshedAddEofDoesNotReplay()=authRefresh(true,true)
    @Test fun refreshedDeleteEofDoesNotReplay()=authRefresh(false,true)
    @Test fun contactReadKeepsSafeTransportRecovery()=runBlocking {
        dropFirstRead=true; assertTrue(ApiClient.getContacts().getOrThrow().isEmpty())
        assertEquals(2,records.size); assertEquals(0,mutations.get())
    }
    @Test fun successfulAddUsesUtf8ByteLength()=runBlocking {
        drop=false; mutate(true).getOrThrow()
        val sent=records.single(); val raw=sent.body.clone().readByteArray()
        assertEquals(raw.size.toString(),sent.getHeader("Content-Length"))
        assertEquals(payloadName,JSONObject(raw.toString(Charsets.UTF_8)).getString("name")); assertEquals(1,mutations.get())
    }
    @Test fun successfulDeleteSendsEmptyBody()=runBlocking {
        drop=false; mutate(false).getOrThrow(); assertEquals(1,mutations.get())
        assertEquals(0L,records.single().bodySize); assertEquals("0",records.single().getHeader("Content-Length"))
    }
    @Test fun nonAsciiBearerStillReceives401AndExpiresSession()=runBlocking {
        ApiClient.saveToken("мёртвый_токен"); drop=false; replyCode=401
        val error=ApiClient.deleteContact(7).exceptionOrNull()
        assertTrue(error is ApiException); assertEquals(401,(error as ApiException).status)
        assertEquals(1,mutations.get()); assertFalse(ApiClient.isLoggedIn()); assertTrue(ApiClient.sessionExpired.value)
    }
    @Test fun newlineBearerIsRejectedBeforeSendingAnyRequest()=runBlocking {
        ApiClient.saveToken("synthetic\r\nX-Injected: yes")
        val before=ApiClient.contactDeletions.value.version
        val error=ApiClient.deleteContact(7).exceptionOrNull()
        assertTrue(error is IllegalArgumentException); assertEquals(0,records.size); assertEquals(0,mutations.get())
        assertEquals(before,ApiClient.contactDeletions.value.version)
    }
}
