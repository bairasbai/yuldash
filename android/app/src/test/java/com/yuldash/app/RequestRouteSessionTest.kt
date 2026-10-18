package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class,qualifiers="w411dp-h1400dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RequestRouteSessionTest {
    @get:Rule val compose=createComposeRule()
    private val mounted=mutableStateOf(true)
    private val requests=CopyOnWriteArrayList<String>()
    private lateinit var server:MockWebServer
    private val held=CountDownLatch(1)
    private val release=CountDownLatch(1)
    @Volatile private var holdFeed=false
    private val feed="""{"items":[{"id":5,"passenger_name":"Пассажир","from_city":"Ургаза","to_city":"Бурибай","seats":1,"matched_ride_id":7,"matched_ride_from_city":"Сибай","matched_ride_to_city":"Акъяр","matched_ride_depart_at":"2030-01-02T10:00:00Z","matched_pickup_stop":"Ургаза","matched_dropoff_stop":"Бурибай"}]}"""
    @Before fun setup() {
        ApiClient.resetForTest();ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        ApiClient.saveToken("synthetic-route-driver");ApiClient.saveRole("driver")
        server=MockWebServer().apply {
            dispatcher=object:Dispatcher(){ override fun dispatch(request:RecordedRequest):MockResponse {
                requests.add("${request.method} ${request.path}")
                if(request.path?.startsWith("/requests/feed")==true) {
                    if(holdFeed){held.countDown();if(!release.await(20,TimeUnit.SECONDS))return MockResponse().setResponseCode(503)}
                    return MockResponse().setBody(feed)
                }
                return MockResponse().setResponseCode(404).setBody("{}")
            }};start()
        }
        ApiClient.testBaseUrl=server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs=30000
    }
    @After fun cleanup(){release.countDown();compose.runOnIdle{mounted.value=false};ApiClient.logout();ApiClient.resetForTest();ApiClient.testTimeoutMs=null;server.shutdown()}
    private fun mount(){compose.setContent{CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru){if(mounted.value)RequestsFeedScreen({})}}}
    private fun settle(){Shadows.shadowOf(Looper.getMainLooper()).idle()}
    private fun assertNoResponseSent() {
        val actualPaths=requests.toList()
        assertFalse(
            "Отклик не должен отправляться без нового подтверждения. Фактические запросы: ${actualPaths.joinToString()}",
            actualPaths.any { it.startsWith("POST /requests/") && it.substringBefore('?').endsWith("/respond") },
        )
    }


    @Test fun roleChangeClosesStaleRideBindingWithoutSendingAndRequiresFreshChoice() {
        mount()
        compose.waitUntil(15000){settle();compose.onAllNodesWithText("Предложить поездку").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Предложить поездку").performScrollTo().performClick()
        compose.onNodeWithText("Место в моём рейсе").performScrollTo().performClick()
        compose.runOnIdle{ApiClient.saveRole("passenger")}
        compose.waitUntil(15000){settle();compose.onAllNodesWithText("Место в моём рейсе").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithText("Отправить предложение").assertDoesNotExist()
        compose.runOnIdle{assertNoResponseSent();ApiClient.saveRole("driver")}
        compose.waitUntil(15000){settle();compose.onAllNodesWithText("Предложить поездку").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Предложить поездку").performScrollTo().performClick()
        compose.onNodeWithText("Отправить предложение").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle{assertNoResponseSent()}
    }

    @Test fun logoutDuringDelayedFeedDoesNotRenderPreviousOwnerMatch() {
        holdFeed=true;mount()
        compose.waitUntil(15000){settle();held.count==0L}
        compose.runOnIdle{ApiClient.logout()};release.countDown()
        compose.waitUntil(15000){settle();ApiClient.currentToken()==null}
        compose.onNodeWithText("Рейс: Сибай → Акъяр").assertDoesNotExist()
        compose.onNodeWithText("Ургаза → Бурибай").assertDoesNotExist()
        assertNoResponseSent()
    }
}
