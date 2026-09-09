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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in provider check: unlike chrome-only tests, requires map geometry to load. */
@RunWith(AndroidJUnit4::class)
class LiveMapProviderInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun configuredMapKitLoadsGeometry() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveMaps") == "true")
        val loaded = CountDownLatch(1)
        var objects = 0
        val listener = object : MapLoadedListener {
            override fun onMapLoaded(statistics: MapLoadStatistics) {
                objects = statistics.renderObjectCount
                if (objects > 0) loaded.countDown()
            }
        }
        compose.setContent {
            val view = remember {
                MapView(compose.activity).also {
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
        assertTrue("MapKit did not report loaded geometry within 45 seconds", geometryLoaded)
        assertTrue(objects > 0)
    }
}
