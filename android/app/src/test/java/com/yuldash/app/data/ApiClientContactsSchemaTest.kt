package com.yuldash.app.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Real decoder/cache and synthetic local HTTP; malformed success must be Result.failure. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApiClientContactsSchemaTest {
    private lateinit var server: MockWebServer
    private val token = "header.eyJzdWIiOiIxMSJ9.signature"
    private var generation = 0L
    private val valid = """[{"id":7,"name":"Fresh","relation":"Synthetic","phone":"+70000000007","notify_by_default":true}]"""
    @Before fun setup() {
        ApiClient.resetForTest(); ApiClient.init(ApplicationProvider.getApplicationContext<Application>()); ApiClient.saveToken(token)
        generation = ApiClient.queueSessionGeneration(); server = MockWebServer(); server.start()
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/'); ApiClient.testTimeoutMs = 60000
    }
    @After fun cleanup() { ApiClient.resetForTest(); ApiClient.testTimeoutMs = null; server.shutdown() }
    private fun json(body: String) = MockResponse().setResponseCode(200).setBody(body)
    private suspend fun read(): Result<List<ContactDto>> {
        val outer = runCatching { ApiClient.getContacts(expectedGeneration = generation) }
        assertTrue("Decoder must return failure, not throw out of getContacts: ${outer.exceptionOrNull()}", outer.isSuccess)
        return outer.getOrThrow()
    }
    private fun request() {
        val q = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", q.method); assertEquals("/trusted-contacts", q.path); assertEquals("Bearer $token", q.getHeader("Authorization"))
    }
    private fun rejectsThenRefetches(body: String) = runBlocking {
        server.enqueue(json(body)); server.enqueue(json(valid)); server.enqueue(json("[]"))
        assertTrue("Malformed contacts must not be successful: $body", read().isFailure)
        request(); assertEquals(1, server.requestCount)
        val fresh = read().getOrThrow(); assertEquals(1, fresh.size); assertEquals(7, fresh.single().id)
        assertEquals("Fresh", fresh.single().name); assertEquals("Synthetic", fresh.single().relation)
        assertEquals("+70000000007", fresh.single().phone); assertTrue(fresh.single().notifyByDefault)
        request(); assertEquals(2, server.requestCount)
        assertEquals(fresh, read().getOrThrow()); assertEquals(2, server.requestCount)
    }
    private fun acceptsAndCaches(body: String, size: Int) = runBlocking {
        server.enqueue(json(body)); server.enqueue(json("{}"))
        val contacts = read().getOrThrow(); assertEquals(size, contacts.size); request()
        assertEquals(contacts, read().getOrThrow()); assertEquals(1, server.requestCount)
    }
    @Test fun missingItemsIsFailureAndNotCached() = rejectsThenRefetches("""{}""")
    @Test fun nullItemsIsFailureAndNotCached() = rejectsThenRefetches("""{"items":null}""")
    @Test fun objectItemsIsFailureAndNotCached() = rejectsThenRefetches("""{"items":{}}""")
    @Test fun stringItemsIsFailureAndNotCached() = rejectsThenRefetches("""{"items":"wrong"}""")
    @Test fun scalarRowIsFailureAndNotCached() = rejectsThenRefetches("""[7]""")
    @Test fun nullRowIsFailureAndNotCached() = rejectsThenRefetches("""[null]""")
    @Test fun missingIdIsFailureAndNotCached() = rejectsThenRefetches("""[{"name":"A"}]""")
    @Test fun zeroIdIsFailureAndNotCached() = rejectsThenRefetches("""[{"id":0,"name":"A"}]""")
    @Test fun negativeIdIsFailureAndNotCached() = rejectsThenRefetches("""[{"id":-1,"name":"A"}]""")
    @Test fun fractionalIdIsFailureAndNotCached() = rejectsThenRefetches("""[{"id":7.5,"name":"A"}]""")
    @Test fun stringIdIsFailureAndNotCached() = rejectsThenRefetches("""[{"id":"7","name":"A"}]""")
    @Test fun overflowIdIsFailureAndNotCached() = rejectsThenRefetches("""[{"id":2147483648,"name":"A"}]""")
    @Test fun duplicateIdsIsFailureAndNotCached() = rejectsThenRefetches("""[{"id":7,"name":"A"},{"id":7,"name":"B"}]""")
    @Test fun missingNameIsFailureAndNotCached() = rejectsThenRefetches("""[{"id":7}]""")
    @Test fun nullNameIsFailureAndNotCached() = rejectsThenRefetches("""[{"id":7,"name":null}]""")
    @Test fun numericNameIsFailureAndNotCached() = rejectsThenRefetches("""[{"id":7,"name":8}]""")
    @Test fun objectRelationIsFailureAndNotCached() = rejectsThenRefetches("""[{"id":7,"name":"A","relation":{}}]""")
    @Test fun nullPhoneIsFailureAndNotCached() = rejectsThenRefetches("""[{"id":7,"name":"A","phone":null}]""")
    @Test fun numericPhoneIsFailureAndNotCached() = rejectsThenRefetches("""[{"id":7,"name":"A","phone":8}]""")
    @Test fun stringNotifyIsFailureAndNotCached() = rejectsThenRefetches("""[{"id":7,"name":"A","notify_by_default":"true"}]""")
    @Test fun nullNotifyIsFailureAndNotCached() = rejectsThenRefetches("""[{"id":7,"name":"A","notify_by_default":null}]""")
    @Test fun backendBareArrayRemainsValid() = acceptsAndCaches(valid, 1)
    @Test fun legacyItemsWrapperRemainsValid() = acceptsAndCaches("""{"items":$valid}""", 1)
    @Test fun emptyBareArrayRemainsValid() = acceptsAndCaches("[]", 0)
    @Test fun emptyItemsWrapperRemainsValid() = acceptsAndCaches("""{"items":[]}""", 0)
    @Test fun legacyOptionalFieldsAndEmptyNameRemainValid() = runBlocking {
        server.enqueue(json("""[{"id":7.0,"name":"","unknown":"ignored"}]"""))
        val c = read().getOrThrow().single(); request()
        assertEquals(7, c.id); assertEquals("", c.name); assertEquals("", c.relation); assertEquals("", c.phone); assertFalse(c.notifyByDefault)
        assertEquals(c, read().getOrThrow().single()); assertEquals(1, server.requestCount)
    }
    @Test fun staleGenerationCannotReadWarmContactsCache() = runBlocking {
        server.enqueue(json(valid)); assertTrue(read().isSuccess); request(); assertEquals(1, server.requestCount)
        val tokenB = "header.eyJzdWIiOiIxMiJ9.signature"
        ApiClient.saveToken(tokenB)
        server.enqueue(json(valid.replace("Fresh", "OwnerB")))
        val current = ApiClient.getContacts(expectedGeneration = ApiClient.queueSessionGeneration()).getOrThrow()
        assertEquals("OwnerB", current.single().name)
        val b = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/trusted-contacts", b.path); assertEquals("Bearer $tokenB", b.getHeader("Authorization"))
        assertEquals(2, server.requestCount)
        assertTrue(read().isFailure); assertEquals(2, server.requestCount)
        assertEquals(current, ApiClient.getContacts(expectedGeneration = ApiClient.queueSessionGeneration()).getOrThrow())
        assertEquals(2, server.requestCount)
    }
}
