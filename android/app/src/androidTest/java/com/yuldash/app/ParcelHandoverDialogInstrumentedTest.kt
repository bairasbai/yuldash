package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.test.espresso.IdlingPolicies
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yuldash.app.ui.theme.YuldashTheme
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Окно «Код вручения» на настоящем Android. В Robolectric (JVM) окно с полем ввода
 * не успокаивается — бесконечно пересчитывает раскладку (ParcelDialogDiagnosticTest).
 * Здесь ответ на вопрос «есть ли это у людей»: окно должно замереть, принять код и
 * отдать его кнопке «Подтвердить вручение».
 */
@RunWith(AndroidJUnit4::class)
class ParcelHandoverDialogInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Цикл раскладки не должен ждать минуту: 20 секунд с запасом отличают «успокоилось» от зависания.
    @Before
    fun shortIdleTimeout() {
        IdlingPolicies.setMasterPolicyTimeout(20, TimeUnit.SECONDS)
    }

    /** Минимальный случай, который зависает в Robolectric: заголовок + одно поле ввода. */
    @Test
    fun minimalFieldDialog_settlesAndSubmitsCode() = assertDialogSettles(product = false)

    /** Та же вёрстка, что у окна вручения в CourierScreen. */
    @Test
    fun handoverDialog_settlesAndSubmitsCode() = assertDialogSettles(product = true)

    private fun assertDialogSettles(product: Boolean) {
        val measures = AtomicInteger()
        var submitted: String? = null
        composeRule.setContent {
            YuldashTheme {
                HandoverDialogReplica(product, measures) { submitted = it }
            }
        }
        composeRule.waitForIdle()
        val settled = measures.get()
        // Секунда настоящих кадров без действий: у зацикленного окна счётчик раскладок растёт.
        Thread.sleep(1_000)
        composeRule.waitForIdle()
        assertEquals("окно пересчитывает раскладку без причины", settled, measures.get())

        composeRule.onNodeWithText("Код от получателя").assertIsDisplayed().performTextReplacement("317204")
        composeRule.onNodeWithText(if (product) "Подтвердить вручение" else "Вручить")
            .assertIsEnabled()
            .performClick()
        composeRule.runOnIdle { assertEquals("317204", submitted) }
    }
}

/** Вёрстка окна вручения из CourierScreen без сети и выбора фото; measures считает раскладки. */
@Composable
private fun HandoverDialogReplica(product: Boolean, measures: AtomicInteger, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        containerColor = CanonSurface,
        title = { Text(appText("Код вручения", "Тапшырыу коды"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = DeliveryTitle, lineHeight = DeliveryTitleLine) },
        text = {
            Column(
                Modifier.layout { measurable, constraints ->
                    measures.incrementAndGet()
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (product) {
                    DeliveryHint(appText("Спроси код у получателя и введи его. Так подтвердим, что заказ попал по адресу.", "Кодты алыусынан һора һәм индер. Шулай заказ дөрөҫ ергә барғанын раҫлайбыҙ."))
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(appText("Код от получателя", "Алыусы коды")) },
                    shape = RoundedCornerShape(14.dp),
                    singleLine = true,
                    isError = false,
                )
                if (product) {
                    DialogErrorLine(null)
                    AppButton(
                        text = appText("Сфотографировать при вручении", "Тапшырғанда фотоға төшөрөү"),
                        onClick = {},
                        style = AppButtonStyle.Secondary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.heightIn(min = 48.dp),
                enabled = code.isNotBlank(),
                onClick = { onSubmit(code.trim()) },
            ) {
                Text(
                    if (product) appText("Подтвердить вручение", "Тапшырыуҙы раҫлау") else "Вручить",
                    color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = DeliveryBody,
                )
            }
        },
        dismissButton = {
            TextButton(modifier = Modifier.heightIn(min = 48.dp), onClick = {}) {
                Text(appText("Отмена", "Баш тартыу"), color = CanonMuted, fontSize = DeliveryBody)
            }
        },
    )
}
