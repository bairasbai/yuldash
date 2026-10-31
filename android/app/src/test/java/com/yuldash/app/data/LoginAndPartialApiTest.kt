package com.yuldash.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoginAndPartialApiTest {
    private lateinit var server: MockWebServer

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken("header.eyJzdWIiOiIxMSJ9.signature")
        server = MockWebServer().apply { start() }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
    }

    @After fun cleanup() {
        ApiClient.logout()
        ApiClient.resetForTest()
        server.shutdown()
    }

    @Test fun loginMethodsIsPublicAndKeepsEmptyList() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"methods":[]}"""))
        assertEquals(emptyList<String>(), ApiClient.loginMethods().getOrThrow())
        val request = server.takeRequest()
        assertEquals("/config/login-methods", request.path)
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun loginMethodsRejectsMalformedSuccessSoCacheIsNotErased() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))
        assertTrue(ApiClient.loginMethods().isFailure)
    }

    @Test fun requestsFeedPreservesHeaderForRawListAndDoesNotLatchIt() = runBlocking {
        server.enqueue(MockResponse().setHeader("X-Yuldash-Partial", "1").setBody("""[{"id":9,"seats":2}]"""))
        val partial = ApiClient.getRequestsFeed(onRoute = true).getOrThrow()
        assertEquals(9, partial.items.single().id)
        assertTrue(partial.partial)
        assertEquals("/requests/feed?on_route=true", server.takeRequest().path)
        server.enqueue(MockResponse().setBody("[]"))
        val complete = ApiClient.getRequestsFeed().getOrThrow()
        assertTrue(complete.isEmpty())
        assertFalse(complete.partial)
    }

    @Test fun matchingRidesPreservesPartialEvenForEmptyResult() = runBlocking {
        server.enqueue(MockResponse().setHeader("X-Yuldash-Partial", "1").setBody("[]"))
        val result = ApiClient.matchRides(7).getOrThrow()
        assertTrue(result.isEmpty())
        assertTrue(result.partial)
        assertEquals("/match/rides?request_id=7", server.takeRequest().path)
    }
}
