package com.yuldash.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yuldash.app.data.RequestFeedDto
import com.yuldash.app.ui.theme.YuldashTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Живой полноэкранный рендер утверждённого варианта A на Pixel API 35. */
@RunWith(AndroidJUnit4::class)
class PassengerRequestsPremiumInstrumentedTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun optionA_routeFirst_showsRealSymmetricFactsAndTrust() {
        renderFeed()

        composeRule.onNodeWithText("Заявки пассажиров").assertIsDisplayed()
        composeRule.onNodeWithText("Сначала — те, кто действительно по пути.").assertIsDisplayed()
        composeRule.onNodeWithText("Баймак → Сибай").assertIsDisplayed()
        composeRule.onNodeWithText("По пути · крюк ≈ 3 км").assertIsDisplayed()
        holdIfRequested()
        composeRule.onNodeWithText("1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("450 ₽").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("≈ 42 км").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Обычная").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Айгуль").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("★ 4.9 · 18 оценок").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Проверен").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Без курения").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Предложить поездку").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun optionA_alongTheWayFilter_hidesLargeDetour() {
        renderFeed(rows = listOf(request(id = 8, from = "Сибай", to = "Уфа", detour = 14)))

        composeRule.onNodeWithText("Сибай → Уфа").assertExists()
        composeRule.onNodeWithText("По пути").performClick()
        composeRule.onNodeWithText("Сибай → Уфа").assertDoesNotExist()
        composeRule.onNodeWithText("Подходящих заявок нет").assertIsDisplayed()
    }

    @Test
    fun optionA_keepsBashkirCoreCopyVisible() {
        renderFeed(language = AppLanguage.Ba)

        composeRule.onNodeWithText("Пассажир заявкалары").assertIsDisplayed()
        composeRule.onNodeWithText("Тәүҙә — ысынлап юл ыңғайында булғандар.").assertIsDisplayed()
        composeRule.onNodeWithText("Юл ыңғайында · урау ≈ 3 км").assertIsDisplayed()
        composeRule.onNodeWithText("Тура юлдан").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Тикшерелгән").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Сәфәр тәҡдим итеү").performScrollTo().assertIsDisplayed()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun offerSheet_keepsPriceTimeCommentAndSubmitClickable() {
        var submitted = 0
        val request = request()
        composeRule.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    ModalBottomSheet(onDismissRequest = {}, sheetState = sheetState, containerColor = CanonSurface) {
                        RequestOfferSheet(
                            request = request,
                            price = "450",
                            departureNote = "Сегодня в 19:00",
                            comment = "Встречу у автовокзала",
                            responding = false,
                            onPriceChange = {},
                            onDepartureNoteChange = {},
                            onCommentChange = {},
                            onSubmit = { submitted++ },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Предложить поездку").assertIsDisplayed()
        composeRule.onNodeWithText("Твоя цена, ₽").assertIsDisplayed()
        holdIfRequested()
        composeRule.onNodeWithText("Когда сможешь выехать").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Комментарий пассажиру").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Отправить предложение").performScrollTo().performClick()
        assertEquals(1, submitted)
    }

    private fun renderFeed(language: AppLanguage = AppLanguage.Ru, rows: List<RequestFeedDto> = listOf(request())) {
        composeRule.setContent {
            YuldashTheme {
                CompositionLocalProvider(LocalAppLanguage provides language) {
                    var filter by remember { mutableStateOf(RequestsFeedFilter.All) }
                    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Заявки пассажиров", "Пассажир заявкалары"), {}) }) { padding ->
                        RequestsFeedContent(
                            loading = false,
                            error = false,
                            feed = rows,
                            onRetry = {},
                            onRespond = {},
                            selectedFilter = filter,
                            onFilterChange = { filter = it },
                            modifier = Modifier.padding(padding),
                        )
                    }
                }
            }
        }
    }

    private fun request(
        id: Int = 7,
        from: String = "Баймак",
        to: String = "Сибай",
        detour: Int = 3,
    ) = RequestFeedDto(
        id = id,
        passengerName = "Айгуль",
        from = from,
        to = to,
        seats = 1,
        comment = "Встречу у автовокзала",
        responded = false,
        prefs = listOf("nosmoke", "baggage"),
        detourKm = detour,
        desiredAt = "2026-09-05T13:30:00",
        maxPrice = 450,
        distanceKm = 42.0,
        category = "regular",
        passengerRating = 4.9,
        passengerRatingCount = 18,
        passengerVerified = true,
    )

    private fun holdIfRequested() {
        val holdMs = InstrumentationRegistry.getArguments().getString("holdMs")?.toLongOrNull()?.coerceIn(0L, 60_000L) ?: 0L
        Thread.sleep(holdMs)
    }
}
