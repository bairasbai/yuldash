package com.yuldash.app

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PermissionGateDismissalTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explanationSystemBackCallsDeniedOnceWithoutGranting() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        var denied = 0
        var granted = 0
        compose.setContent {
            val ask = rememberPermissionGate(
                permission = Manifest.permission.ACCESS_FINE_LOCATION,
                titleRu = "Показывать машину пассажирам",
                titleBa = "Машинаны пассажирҙарға күрһәтергә",
                whyRu = "Передавай положение машины пассажирам этого рейса, пока везёшь их.",
                whyBa = "Юлсыларҙы алып барған саҡта машинаның урынын ошо рейс пассажирҙарына күрһәт.",
                onGranted = { granted++ },
                onDenied = { denied++ },
            )
            AppButton("Показывать машину пассажирам", onClick = ask)
        }
        compose.onNodeWithText("Показывать машину пассажирам").performClick()
        compose.onNode(isDialog()).assertExists()
        compose.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, denied)
            assertEquals(0, granted)
        }
    }
}
