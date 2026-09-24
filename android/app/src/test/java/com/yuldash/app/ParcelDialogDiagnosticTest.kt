package com.yuldash.app

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.TimeUnit

/** Temporary isolation of delivery dialog layout; archived outside the suite after diagnosis. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ParcelDialogDiagnosticTest {
    @get:Rule val compose = createComposeRule()
    private val mounted = mutableStateOf(true)
    private fun timeout(seconds: Long) {
        Class.forName("androidx.test.espresso.IdlingPolicies")
            .getMethod("setMasterPolicyTimeout", java.lang.Long.TYPE, TimeUnit::class.java)
            .invoke(null, seconds, TimeUnit.SECONDS)
    }
    @Before fun before() { timeout(10) }
    @After fun after() {
        mounted.value = false
        compose.waitForIdle()
        timeout(60)
    }
    @Test fun longConfirmation() = show("Подтвердить вручение")
    @Test fun shortConfirmation() = show("Вручить")
    @Test fun minimalText() = show("Вручить", "text")
    @Test fun minimalField() = show("Вручить", "field")
    @Test fun minimalPhoto() = show("Вручить", "photo")
    @Test @Config(sdk = [35]) fun fieldSdk35() = show("Вручить", "field")
    @Test @GraphicsMode(GraphicsMode.Mode.LEGACY) fun fieldLegacyGraphics() = show("Вручить", "field")
    private fun show(confirm: String, kind: String = "full") {
        compose.setContent {
            YuldashTheme {
                if (mounted.value) {
                    var code by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = {}, containerColor = CanonSurface,
                        title = { Text("Код вручения", fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (kind in listOf("full", "text")) DeliveryHint("Спроси код у получателя и введи его. Так подтвердим, что заказ попал по адресу.")
                                if (kind in listOf("full", "field")) OutlinedTextField(value = code, onValueChange = { code = it },
                                    modifier = Modifier.fillMaxWidth(), label = { Text("Код от получателя") }, singleLine = true)
                                if (kind == "full") DialogErrorLine(null)
                                if (kind in listOf("full", "photo")) AppButton("Сфотографировать при вручении", {}, style = AppButtonStyle.Secondary)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = {}, enabled = code.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(confirm, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = DeliveryBody)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {}, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("Отмена", color = CanonMuted, fontSize = DeliveryBody)
                            }
                        },
                    )
                }
            }
        }
        if (kind in listOf("full", "field")) {
            compose.onNodeWithText("Код от получателя").assertIsDisplayed().performTextReplacement("317204")
            compose.onNodeWithText(confirm).assertIsEnabled().performClick()
        } else {
            compose.onNodeWithText(confirm).assertIsDisplayed()
        }
    }
}
