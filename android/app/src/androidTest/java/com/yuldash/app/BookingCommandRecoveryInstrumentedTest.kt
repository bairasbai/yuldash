package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yuldash.app.data.BookingCommandOutcome
import com.yuldash.app.data.BookingCreatedDto
import com.yuldash.app.data.CommandIntent
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production recovery controls in a narrow independent host, with no network or account. */
@RunWith(AndroidJUnit4::class)
class BookingCommandRecoveryInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun verify(language: AppLanguage, dark: Boolean) {
        val state = BookingRecoveryState(1).apply {
            slot = CommandIntent.Slot(7, "synthetic", "synthetic", 1, CommandIntent.State.Unknown)
        }
        compose.setContent {
            CompositionLocalProvider(
                LocalAppLanguage provides language, LocalNightRide provides dark,
                LocalDensity provides Density(LocalDensity.current.density, 1.3f),
            ) {
                YuldashTheme(darkTheme = dark) {
                    Box(Modifier.width(320.dp).fillMaxHeight().background(CanonBg).padding(CanonSpace.lg)) {
                        BookingRecoveryActions(state, busy = false, onOpen = {})
                    }
                }
            }
        }
        val ba = language == AppLanguage.Ba
        val check = if (ba) "Һорауҙы тикшереү" else "Проверить запрос"
        val close = if (ba) "Һорауҙы ябыу" else "Закрыть запрос"
        compose.onNodeWithText(check).assertIsDisplayed()
        compose.onNodeWithText(close).assertIsDisplayed()
        compose.runOnIdle { state.apply(BookingCommandOutcome(CommandIntent.State.Closed)) }
        compose.onNodeWithText(close).assertDoesNotExist()
        compose.onNodeWithText(if (ba) "Һорау ябылды. Уның буйынса бронь яһалманы." else "Запрос закрыт. Бронь по нему не создана.")
            .assertIsDisplayed()
        compose.runOnIdle { state.apply(BookingCommandOutcome(CommandIntent.State.Applied, BookingCreatedDto(42, "cancelled"))) }
        compose.onNodeWithText(if (ba) "Бронде асыу" else "Открыть бронь").assertIsDisplayed()
    }

    @Test fun russianLight() = verify(AppLanguage.Ru, false)
    @Test fun russianDark() = verify(AppLanguage.Ru, true)
    @Test fun bashkirLight() = verify(AppLanguage.Ba, false)
    @Test fun bashkirDark() = verify(AppLanguage.Ba, true)
}
