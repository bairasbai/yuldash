package com.yuldash.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.map.MapLoadedListener
import com.yandex.mapkit.map.MapLoadStatistics
import com.yandex.mapkit.mapview.MapView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in provider check: requires the real SDK's onMapLoaded completion callback. */
@RunWith(AndroidJUnit4::class)
class LiveMapProviderInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var retainedListener: MapLoadedListener? = null

    @Test fun configuredMapKitLoadsGeometry() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveMaps") == "true")
        val loaded = CountDownLatch(1)
        val objects = AtomicInteger(0)
        val callbacks = AtomicInteger(0)
        val diagnostics = AtomicReference("no callback")
        var mapView: MapView? = null
        val listener = object : MapLoadedListener {
            override fun onMapLoaded(statistics: MapLoadStatistics) {
                callbacks.incrementAndGet()
                objects.set(statistics.renderObjectCount)
                diagnostics.set("objects=${statistics.renderObjectCount}, geometry=${statistics.curZoomGeometryLoaded}, labels=${statistics.curZoomLabelsLoaded}, fullyLoaded=${statistics.fullyLoaded}, tiles=${statistics.tileMemoryUsage}")
                // The real SDK reports zero renderObjectCount even for a visibly loaded map.
                // Completion is the onMapLoaded event, not this diagnostic counter.
                loaded.countDown()
            }
        }
        retainedListener = listener
        compose.setContent {
            val view = remember {
                MapView(compose.activity).also {
                    mapView = it
                    it.mapWindow.map.setMapLoadedListener(listener)
                    it.mapWindow.map.move(CameraPosition(Point(54.7388, 55.9721), 14f, 0f, 0f))
                }
            }
            DisposableEffect(view) {
                MapKitFactory.getInstance().onStart()
                view.onStart()
                onDispose {
                    view.mapWindow.map.setMapLoadedListener(null)
                    view.onStop()
                    MapKitFactory.getInstance().onStop()
                }
            }
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        }
        val geometryLoaded = loaded.await(45, TimeUnit.SECONDS)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val target = File(compose.activity.getExternalFilesDir(null), "audit-live-map.png")
        target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        var viewState = "unavailable"
        compose.runOnUiThread {
            viewState = "size=${mapView?.width}x${mapView?.height}, attached=${mapView?.isAttachedToWindow}, lifecycle=${compose.activity.lifecycle.currentState}"
        }
        val diagnosticText = "callbacks=${callbacks.get()}, ${diagnostics.get()}, $viewState"
        File(compose.activity.getExternalFilesDir(null), "audit-live-map.txt").writeText(diagnosticText)
        assertTrue("MapKit did not report onMapLoaded within 45 seconds: $diagnosticText", geometryLoaded)
        assertTrue(callbacks.get() > 0)
        compose.runOnUiThread {
            assertTrue(mapView?.isAttachedToWindow == true)
            assertTrue((mapView?.width ?: 0) > 0 && (mapView?.height ?: 0) > 0)
        }
    }
}
