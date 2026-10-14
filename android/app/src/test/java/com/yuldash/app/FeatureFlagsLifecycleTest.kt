package com.yuldash.app

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Реальный pause/resume одного смонтированного reader: свежий ответ без GET, просроченный с GET. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeatureFlagsLifecycleTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    private lateinit var server: MockWebServer
    private var now = 1_000_000L

    @Before fun setup() {
        ApiClient.resetForTest()
        ApiClient.init(ApplicationProvider.getApplicationContext<Context>())
        server = MockWebServer().apply { start() }
        ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
        ApiClient.testTimeoutMs = 1000
        FeatureFlags.setForTest(FeatureSet(emptySet(), known = true, fetchedAtMs = now), automatic = true)
    }
    @After fun cleanup() {
        compose.runOnIdle { mounted.value = false; owner.registry.currentState = Lifecycle.State.DESTROYED }
        ApiClient.resetForTest(); ApiClient.testTimeoutMs = null
        server.shutdown()
    }

    @Test fun sameMountedReaderRefreshesOnResumeOnlyAfterTtl() {
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            if (mounted.value) CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Text(rememberFeatures { now }.isOn(Feature.Ads).toString())
            }
        }
        compose.waitForIdle()
        assertEquals(0, server.requestCount)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        now += FeatureFlags.MAX_AGE_MS - 1
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals("до TTL повторный RESUME не создаёт GET", 0, server.requestCount)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        now += 2
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("""{"ads":true}"""))
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(10000) { Shadows.shadowOf(Looper.getMainLooper()).idle(); FeatureFlags.current.isOn(Feature.Ads) }
        assertEquals(1, server.requestCount)
        assertEquals("/config/features", server.takeRequest().requestUrl!!.encodedPath)
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        now += 1
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        assertEquals("новое подтверждение свежее — повторного GET нет", 1, server.requestCount)
    }
}
