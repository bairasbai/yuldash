package com.yuldash.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.MessageDto
import com.yuldash.app.data.RequestFeedDto
import com.yuldash.app.data.RideDto
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину #3» для вкладок Поездки/Заявки/Чат (RidesRequestsChatScreens.kt).
 *
 * Добираем НЕпокрытые ветки внутри уже разрезанных чистых composable — то, чего нет
 * в RidesRequestsChatContentTest / ChatContentTest / RidesDeepContentTest /
 * RidesFeedContentTest / RidesRequestsChatDeep2ContentTest:
 *
 *  - RideCard (compact, не full-width): строка посылки «📦 кому: …», бейдж «Проверен» (verified),
 *    бейдж «Вверху» (boosted, без verified), «на линии» (driverOnline), дизейбл брони при seats=0;
 *  - FullRideCard (через RideCard compact+fullWidth): бейдж «В больницу» (car содержит «больниц»),
 *    бейдж «Вверху» (boosted) и лента чипов условий RidePrefChips (женщины/дети/животные/…);
 *  - NearbyRideCard: ветка дистанции distanceKm → «рядом» (км<1) и «N км»;
 *  - NearbySkeletonCard: статичный скелетон карточки «Ближайших» (раньше 0%);
 *  - ChatContent → ChatFeedBubble: удалённое сообщение «Сообщение удалено» и голос «Голосовое»;
 *  - RequestsFeedContent: чипы условий пассажира (prefs) в карточке заявки;
 *  - VoiceMessageCard: башкирский вариант + contentDescription кнопки плеера.
 *
 * Всё — чистые composable без сети/LaunchedEffect/state (данные и колбэки параметрами),
 * поэтому идут на JVM через Robolectric без эмулятора.
 * Высокое окно (h2600dp) в @Config → длинных списков нет, весь контент в кадре,
 * performScrollToNode не нужен (обходим и виртуализацию LazyColumn, и бесконечные анимации).
 * Тексты — ДОСЛОВНО из RidesRequestsChatScreens.kt / MainActivity.kt (appText), RU и BA не мешаем.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h2600dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RidesRequestsChatDeep3ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- фикстуры ---

    private fun ride(
        from: String = "Баймак",
        to: String = "Уфа",
        car: String = "Lada Vesta",
        price: Int = 450,
        seats: Int = 3,
        verified: Boolean = false,
        boosted: Boolean = false,
        driverOnline: Boolean = false,
        receiverName: String = "",
        parcelSize: String = "",
        womenOnly: Boolean = false,
        childSeat: Boolean = false,
        petsAllowed: Boolean = false,
    ) = Ride(
        id = "1", from = from, to = to, time = "12 мая, 08:00", driver = "Рамиль",
        driverOnline = driverOnline, car = car, price = price, seats = seats,
        rating = 4.8, verified = verified, boosted = boosted,
        receiverName = receiverName, parcelSize = parcelSize,
        womenOnly = womenOnly, childSeat = childSeat, petsAllowed = petsAllowed,
    )

    private fun rideDto(
        from: String = "Баймак",
        to: String = "Сибай",
        price: Int = 250,
        km: Double? = null,
    ) = RideDto(
        id = 1, fromCity = from, toCity = to, departAt = "2026-05-12T08:00:00",
        seatsTotal = 4, seatsLeft = 2, price = price, category = "regular",
        driverName = "Айдар", driverRating = 4.9, driverVerified = false,
        driverCar = "Kia Rio", driverOnline = false, distanceKm = km,
    )

    private fun feedRequest(prefs: List<String>) = RequestFeedDto(
        id = 1, passengerName = "Айгуль", from = "Баймак", to = "Сибай",
        seats = 2, comment = "", responded = false, passengerAvatar = "", prefs = prefs,
    )

    private fun mine(id: Int, text: String = "", voiceUrl: String? = null, deleted: Boolean = false) =
        MessageDto(id = id, text = text, senderId = 1, voiceUrl = voiceUrl, deleted = deleted)

    // =====================================================================================
    // RideCard (compact, не full-width) — ветки бейджей/посылки/онлайна/дизейбла брони.
    // Deep2 покрыл compact только «по умолчанию» (без verified/boosted/посылки/онлайна).
    // =====================================================================================

    @Test
    fun rideCard_compact_parcelLine_showsReceiver_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RideCard(
                    ride = ride(receiverName = "Айгуль", parcelSize = "небольшой пакет"),
                    compact = true, onBook = {}, onShare = {}, onBoost = {},
                )
            }
        }
        // Строка посылки: "📦 " + "кому: Айгуль" · "небольшой пакет" (joinToString " · ").
        composeRule.onNodeWithText("📦 кому: Айгуль · небольшой пакет").assertIsDisplayed()
    }

    @Test
    fun rideCard_compact_parcelLine_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                RideCard(
                    ride = ride(receiverName = "Айгуль", parcelSize = ""),
                    compact = true, onBook = {}, onShare = {}, onBoost = {},
                )
            }
        }
        // Только получатель, без габарита → башкирский префикс «кемгә:».
        composeRule.onNodeWithText("📦 кемгә: Айгуль").assertIsDisplayed()
    }

    @Test
    fun rideCard_compact_verified_showsVerifiedBadge_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RideCard(ride = ride(verified = true), compact = true, onBook = {}, onShare = {}, onBoost = {})
            }
        }
        // verified → VerifiedBadge с подписью «Проверен».
        composeRule.onNodeWithText("Проверен").assertIsDisplayed()
    }

    @Test
    fun rideCard_compact_boostedNotVerified_showsBoostBadge_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                // verified=false, boosted=true → в шапке ветка BoostBadge («Вверху»).
                RideCard(ride = ride(verified = false, boosted = true), compact = true, onBook = {}, onShare = {}, onBoost = {})
            }
        }
        composeRule.onNodeWithText("Вверху").assertIsDisplayed()
    }

    @Test
    fun rideCard_compact_boosted_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                RideCard(ride = ride(verified = false, boosted = true), compact = true, onBook = {}, onShare = {}, onBoost = {})
            }
        }
        // Башкирский вариант «Вверху» = «Өҫтә».
        composeRule.onNodeWithText("Өҫтә").assertIsDisplayed()
    }

    @Test
    fun rideCard_compact_driverOnline_showsOnlineBadge_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RideCard(ride = ride(driverOnline = true), compact = true, onBook = {}, onShare = {}, onBoost = {})
            }
        }
        // driverOnline → OnlineBadge «на линии» в строке водителя.
        composeRule.onNodeWithText("на линии").assertIsDisplayed()
    }

    @Test
    fun rideCard_compact_noSeats_goDisabled_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RideCard(ride = ride(seats = 0), compact = true, onBook = {}, onShare = {}, onBoost = {})
            }
        }
        // compact + seats=0 → кнопка «Поехать» заменена на «Мест нет» и выключена.
        composeRule.onNodeWithText("Мест нет").assertIsNotEnabled()
        composeRule.onNodeWithText("Подробнее").assertIsEnabled()   // «Подробнее» доступна всегда
    }

    // =====================================================================================
    // FullRideCard (через RideCard compact+fullWidth) — больница / boost / чипы условий.
    // Deep2 покрыл fullWidth только «по умолчанию» (обычная поездка, без этих веток).
    // =====================================================================================

    @Test
    fun fullRideCard_hospital_showsHospitalBadge_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                // car содержит «больниц» → isHospital=true → бейдж «В больницу».
                RideCard(
                    ride = ride(car = "в больницу, Lada"),
                    compact = true, fullWidth = true, onBook = {}, onShare = {}, onBoost = {},
                )
            }
        }
        composeRule.onNodeWithText("В больницу").assertIsDisplayed()
    }

    @Test
    fun fullRideCard_hospital_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                RideCard(
                    ride = ride(car = "в больницу"),
                    compact = true, fullWidth = true, onBook = {}, onShare = {}, onBoost = {},
                )
            }
        }
        // Башкирский «В больницу» = «Больницаға».
        composeRule.onNodeWithText("Больницаға").assertIsDisplayed()
    }

    @Test
    fun fullRideCard_boosted_showsBoostBadge_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RideCard(
                    ride = ride(boosted = true),
                    compact = true, fullWidth = true, onBook = {}, onShare = {}, onBoost = {},
                )
            }
        }
        composeRule.onNodeWithText("Вверху").assertIsDisplayed()
    }

    @Test
    fun fullRideCard_prefChips_showConditions_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                // Хотя бы одно условие → RidePrefChips рисует чипы (иначе return).
                RideCard(
                    ride = ride(womenOnly = true, childSeat = true, petsAllowed = true),
                    compact = true, fullWidth = true, onBook = {}, onShare = {}, onBoost = {},
                )
            }
        }
        composeRule.onNodeWithText("Только женщины").assertIsDisplayed()
        composeRule.onNodeWithText("Детское кресло").assertIsDisplayed()
        composeRule.onNodeWithText("С животным").assertIsDisplayed()
    }

    // =====================================================================================
    // NearbyRideCard — ветка дистанции (distanceKm). Deep2 её не трогал.
    // =====================================================================================

    @Test
    fun nearbyRideCard_distanceNear_showsRyadom_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                // km < 1.0 → «рядом» (а не «N км»).
                NearbyRideCard(dto = rideDto(km = 0.4), soonest = false, onOpen = {})
            }
        }
        composeRule.onNodeWithText("рядом").assertIsDisplayed()
    }

    @Test
    fun nearbyRideCard_distanceNear_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                NearbyRideCard(dto = rideDto(km = 0.4), soonest = false, onOpen = {})
            }
        }
        // Башкирский «рядом» = «янда».
        composeRule.onNodeWithText("янда").assertIsDisplayed()
    }

    @Test
    fun nearbyRideCard_distanceKm_showsKilometers() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                // km >= 1.0 → «3.4 км» (fmtKm: d<10 → одна десятая).
                NearbyRideCard(dto = rideDto(km = 3.4), soonest = false, onOpen = {})
            }
        }
        composeRule.onNodeWithText("3.4 км").assertIsDisplayed()
    }

    // =====================================================================================
    // NearbySkeletonCard — статичный скелетон (раньше 0% покрытия). Просто рендерится без падений.
    // =====================================================================================

    @Test
    fun nearbySkeletonCard_rendersRoutePlaceholder() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                // Текста нет (только серые прямоугольники) — проверяем, что композиция строится
                // и корневой узел существует (компонент рисуется без исключений).
                NearbySkeletonCard()
            }
        }
        composeRule.onRoot().assertExists()
    }

    // =====================================================================================
    // ChatContent → ChatFeedBubble — ветки «удалено» и «голос».
    // ChatContentTest покрыл только обычный текст свой/чужой; эти две ветки были не тронуты.
    // =====================================================================================

    @Test
    fun chatContent_deletedMessage_showsDeletedPlaceholder_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ChatContent(
                    messages = listOf(mine(1, text = "", deleted = true)),
                    input = "", sending = false, loading = false, myId = 1,
                    onInputChange = {}, onSend = {},
                )
            }
        }
        // deleted=true проходит фильтр visibleMessages → пузырь «Сообщение удалено».
        composeRule.onNodeWithText("Сообщение удалено").assertIsDisplayed()
    }

    @Test
    fun chatContent_deletedMessage_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                ChatContent(
                    messages = listOf(mine(1, text = "", deleted = true)),
                    input = "", sending = false, loading = false, myId = 1,
                    onInputChange = {}, onSend = {},
                )
            }
        }
        composeRule.onNodeWithText("Хәбәр юйылды").assertIsDisplayed()
    }

    @Test
    fun chatContent_voiceMessage_showsVoiceLabel_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ChatContent(
                    messages = listOf(mine(1, text = "", voiceUrl = "https://x/v.m4a")),
                    input = "", sending = false, loading = false, myId = 1,
                    onInputChange = {}, onSend = {},
                )
            }
        }
        // voiceUrl != null → пузырь «Голосовое» (текст пустой, но сообщение видимо по voiceUrl).
        composeRule.onNodeWithText("Голосовое").assertIsDisplayed()
    }

    @Test
    fun chatContent_voiceMessage_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                ChatContent(
                    messages = listOf(mine(1, text = "", voiceUrl = "https://x/v.m4a")),
                    input = "", sending = false, loading = false, myId = 1,
                    onInputChange = {}, onSend = {},
                )
            }
        }
        composeRule.onNodeWithText("Тауыш").assertIsDisplayed()
    }

    // =====================================================================================
    // RequestsFeedContent — чипы условий пассажира (prefs). RidesFeedContentTest их не задавал.
    // =====================================================================================

    @Test
    fun requestsFeed_prefsChips_showConditions_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RequestsFeedContent(
                    loading = false, error = false,
                    feed = listOf(feedRequest(prefs = listOf("women", "child", "pets"))),
                    onRetry = {}, onRespond = {},
                )
            }
        }
        composeRule.onNodeWithText("Только женщины").assertIsDisplayed()
        composeRule.onNodeWithText("Детское кресло").assertIsDisplayed()
        composeRule.onNodeWithText("С животным").assertIsDisplayed()
    }

    @Test
    fun requestsFeed_prefsChips_baggageAndAc_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RequestsFeedContent(
                    loading = false, error = false,
                    feed = listOf(feedRequest(prefs = listOf("baggage", "nosmoke", "ac"))),
                    onRetry = {}, onRespond = {},
                )
            }
        }
        composeRule.onNodeWithText("Багаж").assertIsDisplayed()
        composeRule.onNodeWithText("Не курить").assertIsDisplayed()
        composeRule.onNodeWithText("Кондиционер").assertIsDisplayed()
    }

    // =====================================================================================
    // VoiceMessageCard — башкирский текст + contentDescription кнопки плеера.
    // Deep2 покрыл только RU (расшифровка / длительность), без BA и без проверки кнопки.
    // =====================================================================================

    @Test
    fun voiceMessageCard_transcript_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                VoiceMessageCard(
                    message = LocalVoiceMessage(
                        author = "Азат", transcript = "Мин баҙарҙа", time = "12:30", audioPath = null,
                    )
                )
            }
        }
        // Башкирские подписи: «Тауыш хәбәр: <имя>» и «Водитель өсөн текст».
        composeRule.onNodeWithText("Тауыш хәбәр: Азат").assertIsDisplayed()
        composeRule.onNodeWithText("Водитель өсөн текст").assertIsDisplayed()
    }

    @Test
    fun voiceMessageCard_playButton_hasContentDescription_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                VoiceMessageCard(
                    message = LocalVoiceMessage(
                        author = "Гуля", transcript = "", time = "09:15",
                        audioPath = "/tmp/voice.m4a", durationSec = 5,
                    )
                )
            }
        }
        // Кнопка плеера озвучена для доступности («Воспроизвести»); клик по ней безопасен
        // (audioPath есть, но реального MediaPlayer.prepare под Robolectric не дёргаем — только наличие).
        composeRule.onNodeWithContentDescription("Воспроизвести").assertIsDisplayed()
    }

    // =====================================================================================
    // Смоук интерактива: RideCard compact — тап «Подробнее» и «Поехать» шлют onBook.
    // (Deep2 проверял full/ fullWidth; здесь фиксируем клики compact-варианта.)
    // =====================================================================================

    @Test
    fun rideCard_compact_bookButtonsFireOnBook() {
        var books = 0
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RideCard(ride = ride(), compact = true, onBook = { books++ }, onShare = {}, onBoost = {})
            }
        }
        // В compact и «Подробнее», и «Поехать» вызывают onBook (открыть карточку/бронь).
        composeRule.onNodeWithText("Подробнее").performClick()
        composeRule.onNodeWithText("Поехать").performClick()
        assertTrue(books >= 2)
    }
}
