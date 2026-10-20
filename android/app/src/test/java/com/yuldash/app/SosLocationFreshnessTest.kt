package com.yuldash.app

import android.Manifest
import android.app.Application
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import com.yuldash.app.data.ApiClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** Настоящий SOS: время/разрешение проверяются и при получении места, и при действии. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SosLocationFreshnessTest {
    @get:Rule val compose = createComposeRule()

    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = app.getSystemService(LocationManager::class.java)
    private val clipboard get() = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val sent = CopyOnWriteArrayList<String>()
    private val pointText = "54.73510, 55.95840"
    private val description = "Синтетическое ДТП"
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    // Перехватываем Intent настоящего контракта экрана, как перед передачей Android.
    private inner class PermissionRequest : ActivityResultRegistryOwner {
        val launches = mutableListOf<List<String>>()
        var requestCode: Int? = null
        override val activityResultRegistry = object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
                options: ActivityOptionsCompat?,
            ) {
                this@PermissionRequest.requestCode = requestCode
                val intent = contract.createIntent(app, input)
                launches += intent.getStringArrayExtra(
                    ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS,
                )?.toList().orEmpty()
            }
        }
        fun answer(fine: Boolean, coarse: Boolean) {
            val permissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            shadowOf(app).denyPermissions(*permissions)
            if (fine) shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            if (coarse) shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            activityResultRegistry.dispatchResult(checkNotNull(requestCode), Activity.RESULT_OK,
                Intent().putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, permissions)
                    .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                        intArrayOf(if (fine) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED,
                            if (coarse) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED)))
        }
    }

    private fun fix(ageMillis: Long = 0L, provider: String = LocationManager.GPS_PROVIDER) = Location(provider).apply {
        latitude = 54.7351
        longitude = 55.9584
        accuracy = 5f
        time = System.currentTimeMillis() - ageMillis
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() - ageMillis * 1_000_000L
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text))
    }

    private fun click(text: String) {
        val action = hasText(text) and hasClickAction()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(action)
        compose.onNode(action).performClick()
    }

    private fun retainedClick(text: String): () -> Boolean {
        val action = hasText(text) and hasClickAction()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(action)
        return compose.onNode(action).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    }

    private fun signal(): JSONObject {
        compose.waitUntil(10_000) { sent.isNotEmpty() }
        assertEquals(1, sent.size)
        return JSONObject(sent.single()).also {
            assertEquals(42, it.getInt("booking_id"))
            assertFalse(it.has("order_id"))
        }
    }

    private fun assertNoLocation(body: JSONObject) {
        assertFalse(body.has("lat"))
        assertFalse(body.has("lng"))
        assertFalse(body.getString("note").contains("Координаты:"))
        assertFalse(body.getString("note").contains("yandex.ru/maps"))
    }

    private fun withSos(
        prepareLocation: () -> Unit = {},
        permissionOwner: ActivityResultRegistryOwner? = null,
        scenario: () -> Unit,
    ) {
        val originalLat = LocationPrefs.lastLat
        val originalLng = LocationPrefs.lastLng
        val mounted = mutableStateOf(true)
        val server = MockWebServer()
        try {
            // Положительное монотонное время позволяет отличить старую точку от нулевой.
            ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
            shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
            shadowOf(manager).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
            shadowOf(manager).setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)
            LocationPrefs.lastLat = 51.0
            LocationPrefs.lastLng = 58.0
            prepareLocation()
            ApiClient.resetForTest()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "POST" && request.path == "/sos") {
                        sent.add(request.body.readUtf8())
                        return MockResponse().setResponseCode(200).setBody("{}")
                    }
                    return MockResponse().setResponseCode(503).setBody("{}")
                }
            }
            server.start()
            ApiClient.testBaseUrl = server.url("/").toString().trimEnd('/')
            ApiClient.testTimeoutMs = 1_000
            ApiClient.init(app)
            ApiClient.saveToken("local-sos-freshness")
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
            compose.setContent {
                if (mounted.value) CompositionLocalProvider(
                    LocalAppLanguage provides AppLanguage.Ru, LocalLifecycleOwner provides owner,
                    LocalActivityResultRegistryOwner provides
                        (permissionOwner ?: checkNotNull(LocalActivityResultRegistryOwner.current)),
                ) {
                    SosScreen(onBack = {}, onLoginRequired = {}, bookingId = 42)
                }
            }
            scenario()
        } finally {
            compose.runOnIdle { mounted.value = false }
            compose.waitForIdle()
            compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.DESTROYED }
            ApiClient.logout()
            ApiClient.resetForTest()
            ApiClient.testTimeoutMs = null
            LocationPrefs.lastLat = originalLat
            LocationPrefs.lastLng = originalLng
            server.shutdown()
        }
    }

    @Test fun firstLocationPermissionRequest_requestsFineAndCoarseTogether() {
        val permissionRequest = PermissionRequest()
        withSos(
            prepareLocation = {
                shadowOf(app).denyPermissions(
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                )
            },
            permissionOwner = permissionRequest,
        ) {
            compose.waitForIdle()
            assertNotNull("SosScreen должен действительно запустить запрос Android", permissionRequest.requestCode)
            val requested = permissionRequest.launches.single()
            assertEquals(setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                requested.toSet())
            assertEquals("Оба разрешения должны быть в одном запросе", 2, requested.size)
        }
    }

    private fun permissionAnswerWithoutPrecise_keepsEmergencyActions(coarse: Boolean) {
        val permissionRequest = PermissionRequest()
        withSos(
            prepareLocation = {
                shadowOf(app).denyPermissions(
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                )
                shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix())
                shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER,
                    fix(provider = LocationManager.NETWORK_PROVIDER))
            },
            permissionOwner = permissionRequest,
        ) {
            compose.runOnUiThread {
                owner.registry.currentState = Lifecycle.State.STARTED
                permissionRequest.answer(fine = false, coarse = coarse)
                owner.registry.currentState = Lifecycle.State.RESUMED
            }
            compose.waitForIdle()
            scrollTo("Продиктуй оператору")
            compose.onNodeWithText("Координаты: $pointText").assertDoesNotExist()
            compose.onNodeWithText("Скопировать").assertDoesNotExist()
            // Повторный запрос тоже обязан содержать пару разрешений.
            click("Повторить")
            assertEquals(2, permissionRequest.launches.size)
            permissionRequest.launches.forEach {
                assertEquals(setOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    it.toSet())
            }
            compose.runOnUiThread { permissionRequest.answer(fine = false, coarse = coarse) }
            compose.waitForIdle()
            compose.onNodeWithText("Позвонить 112").assertIsEnabled()
            click("Позвонить 112")
            val dial = shadowOf(app).nextStartedActivity
            assertEquals(Intent.ACTION_DIAL, dial.action)
            assertEquals("tel:112", dial.dataString)
            click("Сообщить поддержке")
            assertNoLocation(signal())
        }
    }

    @Test fun deniedPermissionAnswer_keepsSosAnd112Available() =
        permissionAnswerWithoutPrecise_keepsEmergencyActions(coarse = false)

    @Test fun approximatePermissionAnswer_keepsSosWithoutPreciseCoordinates() =
        permissionAnswerWithoutPrecise_keepsEmergencyActions(coarse = true)

    @Test fun precisePermissionAnswer_acceptsFreshPlaceWithoutSecondTap() {
        val permissionRequest = PermissionRequest()
        withSos(
            prepareLocation = {
                shadowOf(app).denyPermissions(
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
                )
                shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix())
            },
            permissionOwner = permissionRequest,
        ) {
            compose.runOnUiThread {
                owner.registry.currentState = Lifecycle.State.STARTED
                permissionRequest.answer(fine = true, coarse = true)
                owner.registry.currentState = Lifecycle.State.RESUMED
            }
            compose.waitForIdle()
            assertEquals(1, permissionRequest.launches.size)
            scrollTo("Продиктуй оператору")
            compose.onNodeWithText("Координаты: $pointText").assertIsDisplayed()
            click("Сообщить поддержке")
            val body = signal()
            assertEquals(54.7351, body.getDouble("lat"), 0.0)
            assertEquals(55.9584, body.getDouble("lng"), 0.0)
        }
    }

    @Test fun freshLastKnown_isShownCopiedAndSent() = withSos(
        prepareLocation = { shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix()) },
    ) {
        scrollTo("Продиктуй оператору")
        compose.onNodeWithText("Координаты: $pointText").assertIsDisplayed()
        click("Скопировать")
        assertEquals("Координаты: $pointText", clipboard.primaryClip!!.getItemAt(0).text.toString())
        click("Сообщить поддержке")
        val body = signal()
        assertEquals(54.7351, body.getDouble("lat"), 0.0)
        assertEquals(55.9584, body.getDouble("lng"), 0.0)
        assertTrue(body.getString("note").contains("Координаты: $pointText"))
        assertTrue(body.getString("note").contains("pt=55.9584,54.7351"))
    }

    @Test fun staleLastKnown_isNotShownCopiedOrSent() = withSos(
        prepareLocation = {
            shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix(SOS_LOCATION_MAX_AGE_MS + 1))
        },
    ) {
        scrollTo("Продиктуй оператору")
        compose.onNodeWithText("Координаты: $pointText").assertDoesNotExist()
        compose.onNodeWithText("Скопировать").assertDoesNotExist()
        click("Сообщить поддержке")
        assertNoLocation(signal())
    }

    @Test fun staleGps_doesNotHideFreshNetworkLocation() = withSos(
        prepareLocation = {
            shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix(SOS_LOCATION_MAX_AGE_MS + 1))
            shadowOf(manager).setLastKnownLocation(LocationManager.NETWORK_PROVIDER, fix(provider = LocationManager.NETWORK_PROVIDER))
        },
    ) {
        scrollTo("Продиктуй оператору")
        compose.onNodeWithText("Координаты: $pointText").assertIsDisplayed()
        click("Сообщить поддержке")
        assertEquals(54.7351, signal().getDouble("lat"), 0.0)
    }

    @Test fun freshLiveFix_isAcceptedAfterRejectedOldCallback() = withSos {
        compose.runOnUiThread {
            shadowOf(manager).simulateLocation(fix(SOS_LOCATION_MAX_AGE_MS + 1))
        }
        compose.waitForIdle()
        scrollTo("Продиктуй оператору")
        compose.onNodeWithText("Координаты: $pointText").assertDoesNotExist()
        compose.runOnUiThread { shadowOf(manager).simulateLocation(fix()) }
        compose.waitForIdle()
        compose.onNodeWithText("Координаты: $pointText").assertIsDisplayed()
        click("Сообщить поддержке")
        assertEquals(54.7351, signal().getDouble("lat"), 0.0)
    }

    private fun locationInvalidatedBeforeActions(invalidate: () -> Unit) = withSos(
        prepareLocation = { shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix()) },
    ) {
        scrollTo("Что случилось?")
        compose.onNodeWithText("Что случилось?").performTextInput(description)
        val copyAction = retainedClick("Скопировать")
        val sendAction = retainedClick("Сообщить поддержке")
        // Вызываем уже отрисованные действия без промежуточной перерисовки. Так проверка
        // только при открытии/отрисовке экрана не сможет ложно сделать тест зелёным.
        compose.runOnUiThread {
            invalidate()
            assertTrue(copyAction())
            assertTrue(sendAction())
        }
        assertEquals(description, clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertNoLocation(signal())
        scrollTo("Продиктуй оператору")
        compose.onNodeWithText("Координаты: $pointText").assertDoesNotExist()
        compose.onNodeWithText("Позвонить 112").assertIsDisplayed()
    }

    @Test fun permissionRevokedAfterRender_isNotCopiedOrSent() = locationInvalidatedBeforeActions {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    @Test fun locationExpiredAfterRender_isNotCopiedOrSent() = locationInvalidatedBeforeActions {
        ShadowSystemClock.advanceBy(Duration.ofMillis(SOS_LOCATION_MAX_AGE_MS + 1))
    }

    @Test fun returningAfterPermissionRevoked_clearsPlaceBeforeAnyAction() = withSos(
        prepareLocation = { shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix()) },
    ) {
        scrollTo("Продиктуй оператору")
        compose.onNodeWithText("Координаты: $pointText").assertIsDisplayed()
        compose.runOnUiThread {
            owner.registry.currentState = Lifecycle.State.STARTED
            shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
        compose.onNodeWithText("Координаты: $pointText").assertDoesNotExist()
        compose.onNodeWithText("Скопировать").assertDoesNotExist()
        click("Сообщить поддержке")
        assertNoLocation(signal())
    }

    @Test fun placeExpiresWhileScreenRemainsOpen_withoutButtonAction() = withSos(
        prepareLocation = { shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, fix()) },
    ) {
        scrollTo("Продиктуй оператору")
        compose.onNodeWithText("Координаты: $pointText").assertIsDisplayed()
        compose.runOnUiThread {
            ShadowSystemClock.advanceBy(Duration.ofMillis(SOS_LOCATION_MAX_AGE_MS + 1))
        }
        compose.mainClock.advanceTimeBy(SOS_LOCATION_MAX_AGE_MS + 1)
        compose.waitForIdle()
        compose.onNodeWithText("Координаты: $pointText").assertDoesNotExist()
        compose.onNodeWithText("Скопировать").assertDoesNotExist()
    }

    @Test fun freshnessBoundary_acceptsOnlyLastMinuteAndReturnsSnapshot() {
        val nowMillis = 1_000_000L
        val nowNanos = nowMillis * 1_000_000L
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 54.7351; longitude = 55.9584
            time = nowMillis - SOS_LOCATION_MAX_AGE_MS
            elapsedRealtimeNanos = nowNanos - SOS_LOCATION_MAX_AGE_MS * 1_000_000L
        }
        val snapshot = freshSosLocation(location, true, nowNanos, nowMillis)
        assertNotNull(snapshot)
        location.latitude = 51.0
        assertEquals(54.7351, snapshot!!.latitude, 0.0)
        assertNull(freshSosLocation(location, true, nowNanos + 1_000_000L, nowMillis + 1))
        assertNull(freshSosLocation(location, false, nowNanos, nowMillis))
    }

    @Test fun missingFutureOrOldTimestamp_isRejectedEvenWhenOtherClockIsFresh() {
        val nowMillis = 1_000_000L
        val nowNanos = nowMillis * 1_000_000L
        fun check(wall: Long, elapsed: Long) {
            val location = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = 54.7351; longitude = 55.9584
                time = wall; elapsedRealtimeNanos = elapsed
            }
            assertNull(freshSosLocation(location, true, nowNanos, nowMillis))
        }
        check(0L, nowNanos)
        check(nowMillis, 0L)
        check(nowMillis + 1, nowNanos)
        check(nowMillis, nowNanos + 1)
        check(nowMillis - SOS_LOCATION_MAX_AGE_MS - 1, nowNanos)
        check(nowMillis, nowNanos - (SOS_LOCATION_MAX_AGE_MS + 1) * 1_000_000L)
    }
}
