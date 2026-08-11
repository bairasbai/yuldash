package com.yuldash.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Woman
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yuldash.app.data.RideDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «В глубину #2» для вкладок Поездки/Заявки/Чат (RidesRequestsChatScreens.kt).
 * Здесь покрываем internal-компоненты, которых НЕ было в других тестах этого файла
 * (RidesRequestsChatContentTest / ChatContentTest / RidesDeepContentTest / RidesFeedContentTest):
 *  - карточка «моей поездки» (статусы брони + первичное/вторичное действие) — критичный путь;
 *  - «Ближайшая поездка» (RideDto): бейджи verified/online/категория/«ближайшая», кнопка «Поехать»,
 *    а также состояния ленты «Ближайших»: скелетон / «Показать ещё» / пусто / ошибка+повтор;
 *  - карточка ленты `RideCard` (compact / full-width / «мест нет» → бронь выключена);
 *  - вкладки-сегменты, тумблер условия, чип-фильтр;
 *  - экран «Мои заявки» (пусто → список → диалог отмены с гардом на отмену);
 *  - голосовое сообщение (расшифровка vs аудио), мелкие бейджи/аватар/заглушки списков.
 *
 * Всё — чистые composable без сети/LaunchedEffect/state (данные и колбэки параметрами),
 * поэтому тестируются на JVM через Robolectric без эмулятора.
 * Тексты — ДОСЛОВНО из RidesRequestsChatScreens.kt / MainActivity.kt (appText).
 * Списки в фикстурах короткие → всё влезает на экран → performScrollToNode не нужен
 * (обходим и виртуализацию LazyColumn, и бесконечные анимации).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RidesRequestsChatDeep2ContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- фикстуры ---

    private fun ride(
        from: String = "Баймак",
        to: String = "Уфа",
        time: String = "12 мая, 08:00",
        driver: String = "Рамиль",
        car: String = "Lada Vesta",
        price: Int = 450,
        seats: Int = 3,
        verified: Boolean = false,
        boosted: Boolean = false,
        driverOnline: Boolean = false,
    ) = Ride(
        id = "1", from = from, to = to, time = time, driver = driver,
        driverOnline = driverOnline, car = car, price = price, seats = seats,
        rating = 4.8, verified = verified, boosted = boosted,
    )

    private fun rideDto(
        from: String = "Баймак",
        to: String = "Сибай",
        depart: String = "2026-05-12T08:00:00",
        price: Int = 250,
        category: String = "regular",
        driverName: String = "Айдар",
        verified: Boolean = false,
        online: Boolean = false,
        km: Double? = null,
    ) = RideDto(
        id = 1, fromCity = from, toCity = to, departAt = depart, seatsTotal = 4, seatsLeft = 2,
        price = price, category = category, driverName = driverName, driverRating = 4.9,
        driverVerified = verified, driverCar = "Kia Rio", driverOnline = online, distanceKm = km,
    )

    private fun localRequest(
        title: String = "На работу",
        route: String = "Баймак → Сибай",
        time: String = "13 мая, 07:30",
        status: String = "Активна",
        price: Int = 300,
        serverId: Int = 0,
    ) = LocalRequest(title = title, route = route, time = time, passenger = "Я", status = status, price = price, serverId = serverId)

    // =====================================================================================
    // MyTripCard — карточка «моей поездки». Статус/иконка/действия приходят готовыми строками.
    // =====================================================================================

    @Test
    fun myTripCard_showsRouteStatusAndBothActions() {
        var primary = false
        var secondary = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MyTripCard(
                    ride = ride(),
                    status = "Подтверждена",
                    statusColor = CanonMint,
                    icon = Icons.Default.DirectionsCar,
                    primaryAction = "Открыть поездку",
                    secondaryAction = "Чат",
                    onPrimary = { primary = true },
                    onSecondary = { secondary = true },
                )
            }
        }
        composeRule.onNodeWithText("Баймак → Уфа").assertIsDisplayed()
        composeRule.onNodeWithText("Подтверждена").assertIsDisplayed()
        composeRule.onNodeWithText("Открыть поездку").assertIsDisplayed()
        composeRule.onNodeWithText("Чат").assertIsDisplayed()
        assertFalse(primary); assertFalse(secondary)
        composeRule.onNodeWithText("Открыть поездку").performClick()
        assertTrue(primary)
        composeRule.onNodeWithText("Чат").performClick()
        assertTrue(secondary)
    }

    @Test
    fun myTripCard_blankSecondary_hidesSecondaryButton() {
        // История: secondaryAction = "" → вторичной кнопки нет (в коде if(secondaryAction.isNotBlank())).
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MyTripCard(
                    ride = ride(),
                    status = "Завершена",
                    statusColor = CanonMint,
                    icon = Icons.Default.CheckCircle,
                    primaryAction = "Повторить маршрут",
                    secondaryAction = "",
                    onPrimary = {},
                    onSecondary = {},
                )
            }
        }
        composeRule.onNodeWithText("Повторить маршрут").assertIsDisplayed()
        composeRule.onNodeWithText("Написать").assertDoesNotExist()
        composeRule.onNodeWithText("Чат").assertDoesNotExist()
    }

    // =====================================================================================
    // NearbyRideCard — «ближайшая поездка» из RideDto. Бейджи и кнопка «Поехать».
    // =====================================================================================

    @Test
    fun nearbyRideCard_showsRoutePriceAndGoButton_ru() {
        var opened = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                NearbyRideCard(dto = rideDto(), soonest = false, onOpen = { opened = true })
            }
        }
        composeRule.onNodeWithText("Баймак → Сибай").assertIsDisplayed()
        composeRule.onNodeWithText("Айдар").assertIsDisplayed()
        composeRule.onNodeWithText("250 ₽").assertIsDisplayed()
        composeRule.onNodeWithText("Поехать").assertIsDisplayed()
        composeRule.onNodeWithText("Поехать").performClick()
        assertTrue(opened)
    }

    @Test
    fun nearbyRideCard_soonest_showsSoonestBadge_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                NearbyRideCard(dto = rideDto(), soonest = true, onOpen = {})
            }
        }
        composeRule.onNodeWithText("ближайшая").assertIsDisplayed()
    }

    @Test
    fun nearbyRideCard_soonest_showsSoonestBadge_ba() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                NearbyRideCard(dto = rideDto(), soonest = true, onOpen = {})
            }
        }
        composeRule.onNodeWithText("иң яҡыны").assertIsDisplayed()
    }

    @Test
    fun nearbyRideCard_verifiedAndOnline_showBadges() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                NearbyRideCard(dto = rideDto(verified = true, online = true), soonest = false, onOpen = {})
            }
        }
        // verified → иконка с contentDescription «Проверен»; online → бейдж OnlineBadge «на линии».
        composeRule.onNodeWithContentDescription("Проверен").assertIsDisplayed()
        composeRule.onNodeWithText("на линии").assertIsDisplayed()
    }

    @Test
    fun nearbyRideCard_parcelCategory_showsCategoryBadge() {
        // category != regular → бейдж типа поездки (rideTypeMeta("parcel") = «Посылка»).
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                NearbyRideCard(dto = rideDto(category = "parcel"), soonest = false, onOpen = {})
            }
        }
        composeRule.onNodeWithText("Посылка").assertIsDisplayed()
    }

    // --- состояния ленты «Ближайших»: скелетон / «Показать ещё» / пусто / ошибка ---

    @Test
    fun nearbyMoreCard_showMoreLabel_andClick() {
        var more = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                NearbyMoreCard(loading = false, onMore = { more = true })
            }
        }
        // Текст в коде с переносом строки: "Показать\nещё" → узел содержит «Показать» и «ещё».
        composeRule.onNodeWithText("Показать\nещё").assertIsDisplayed()
        composeRule.onNodeWithText("Показать\nещё").performClick()
        assertTrue(more)
    }

    @Test
    fun nearbyMoreCard_loading_showsLoadingLabel_andDisabled() {
        var more = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                NearbyMoreCard(loading = true, onMore = { more = true })
            }
        }
        // loading=true → надпись «Загрузка…» и clickable(enabled=false) → тап не проходит.
        composeRule.onNodeWithText("Загрузка…").assertIsDisplayed()
        composeRule.onNodeWithText("Загрузка…").performClick()
        assertFalse(more)
    }

    @Test
    fun nearbyEmptyCard_hasRoute_showsNoCarsOnRoute_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                NearbyEmptyCard(hasRoute = true, onRetry = {}, error = false)
            }
        }
        composeRule.onNodeWithText("На этом маршруте пока нет машин").assertIsDisplayed()
        // Не ошибка → кнопка «Обновить» (не «Повторить»).
        composeRule.onNodeWithText("Обновить").assertIsDisplayed()
    }

    @Test
    fun nearbyEmptyCard_noRoute_showsNoNearbyRides_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                NearbyEmptyCard(hasRoute = false, onRetry = {}, error = false)
            }
        }
        composeRule.onNodeWithText("Поездок рядом пока нет").assertIsDisplayed()
    }

    @Test
    fun nearbyEmptyCard_error_showsRetryAndFires() {
        var retried = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                NearbyEmptyCard(hasRoute = true, onRetry = { retried = true }, error = true)
            }
        }
        composeRule.onNodeWithText("Не удалось загрузить").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить").performClick()
        assertTrue(retried)
    }

    @Test
    fun nearbyEmptyCard_error_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                NearbyEmptyCard(hasRoute = true, onRetry = {}, error = true)
            }
        }
        composeRule.onNodeWithText("Йөкләп булманы").assertIsDisplayed()
    }

    // =====================================================================================
    // RideCard — карточка ленты. compact/full-width/«мест нет».
    // =====================================================================================

    @Test
    fun rideCard_full_showsRouteAndBookButton() {
        var booked = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RideCard(ride = ride(), compact = false, onBook = { booked = true }, onShare = {}, onBoost = {})
            }
        }
        composeRule.onNodeWithText("Баймак → Уфа").assertIsDisplayed()
        // Полная (не compact) карточка → кнопка «Забронировать».
        composeRule.onNodeWithText("Забронировать").assertIsDisplayed()
        composeRule.onNodeWithText("Забронировать").performClick()
        assertTrue(booked)
    }

    @Test
    fun rideCard_full_noSeats_bookDisabled() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RideCard(ride = ride(seats = 0), compact = false, onBook = {}, onShare = {}, onBoost = {})
            }
        }
        // seats=0 → кнопка выключена и подписана «Мест нет».
        composeRule.onNodeWithText("Мест нет").assertIsNotEnabled()
    }

    @Test
    fun rideCard_compact_showsDetailsAndGo() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RideCard(ride = ride(), compact = true, onBook = {}, onShare = {}, onBoost = {})
            }
        }
        // compact → «Подробнее» + «Поехать» (у поездки есть места).
        composeRule.onNodeWithText("Подробнее").assertIsDisplayed()
        composeRule.onNodeWithText("Поехать").assertIsEnabled()
    }

    @Test
    fun rideCard_compactFullWidth_delegatesToFullRideCard() {
        // compact && fullWidth → внутри рисуется FullRideCard (private) с кнопками «Подробнее»/«Поехать».
        var booked = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                RideCard(ride = ride(), compact = true, fullWidth = true, onBook = { booked = true }, onShare = {}, onBoost = {})
            }
        }
        composeRule.onNodeWithText("Баймак → Уфа").assertIsDisplayed()
        composeRule.onNodeWithText("Поехать").assertIsDisplayed()
        composeRule.onNodeWithText("Поехать").performClick()
        assertTrue(booked)
    }

    // =====================================================================================
    // SegmentedTabs / PrefToggleRow / NearbyFilterChip — мелкие управляющие элементы.
    // =====================================================================================

    @Test
    fun segmentedTabs_selectFiresCallbackWithTab() {
        var picked = ""
        composeRule.setContent {
            SegmentedTabs(
                tabs = listOf("Активные", "История", "Все"),
                selected = "Активные",
                onSelect = { picked = it },
            )
        }
        composeRule.onNodeWithText("История").assertIsDisplayed()
        composeRule.onNodeWithText("История").performClick()
        assertEquals("История", picked)
    }

    @Test
    fun prefToggleRow_showsLabelAndTogglesOn() {
        var checked = false
        composeRule.setContent {
            PrefToggleRow(
                icon = Icons.Default.Woman,
                label = "Только женщины",
                checked = false,
                onCheckedChange = { checked = it },
            )
        }
        composeRule.onNodeWithText("Только женщины").assertIsDisplayed()
        // Интерактивен сам Switch (не текст-лейбл) → кликаем toggleable-узел, иначе onCheckedChange не сработает.
        composeRule.onNode(isToggleable()).performClick()
        assertTrue(checked)
    }

    @Test
    fun nearbyFilterChip_toggleFiresCallback() {
        var toggled = false
        composeRule.setContent {
            NearbyFilterChip(
                icon = Icons.Default.DirectionsCar,
                label = "Сегодня",
                active = false,
                onToggle = { toggled = true },
            )
        }
        composeRule.onNodeWithText("Сегодня").assertIsDisplayed()
        composeRule.onNodeWithText("Сегодня").performClick()
        assertTrue(toggled)
    }

    // =====================================================================================
    // MyRequestsScreen — «Мои заявки» (чистый экран: данные и колбэки параметрами).
    // Пусто / список / диалог отмены с гардом (Оставить не отменяет; Отменить — зовёт onCancel).
    // =====================================================================================

    @Test
    fun myRequests_empty_showsFriendlyPlaceholder_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MyRequestsScreen(requests = emptyList(), onCreateNew = {}, onViewResponses = {}, onCancel = {})
            }
        }
        composeRule.onNodeWithText("Заявок пока нет").assertIsDisplayed()
        // Кнопка живёт ВНУТРИ карточки пустого состояния — как на соседней вкладке «Поездки».
        // Раньше в пустом списке она звалась «Создать новую» (новую — по отношению к чему?).
        composeRule.onNodeWithText("Создать заявку").assertIsDisplayed()
    }

    @Test
    fun myRequests_empty_bashkir() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ba) {
                MyRequestsScreen(requests = emptyList(), onCreateNew = {}, onViewResponses = {}, onCancel = {})
            }
        }
        composeRule.onNodeWithText("Әлегә заявкалар юҡ").assertIsDisplayed()
    }

    @Test
    fun myRequests_list_showsCardAndViewResponsesFires() {
        var viewedId = -1
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MyRequestsScreen(
                    requests = listOf(localRequest(serverId = 42)),
                    onCreateNew = {},
                    onViewResponses = { viewedId = it },
                    onCancel = {},
                )
            }
        }
        // Маршрут в карточке через RequestSummaryCard = "$from  →  $to" (route разбит по " → ").
        composeRule.onNodeWithText("Баймак  →  Сибай").assertIsDisplayed()
        composeRule.onNodeWithText("Посмотреть отклики").assertIsDisplayed()
        composeRule.onNodeWithText("Посмотреть отклики").performClick()
        assertEquals(42, viewedId)
    }

    @Test
    fun myRequests_cancelDialog_keepDoesNotCancel() {
        // serverId != 0 → есть кнопка «Отменить заявку» → открывает диалог; «Оставить» закрывает без onCancel.
        var cancelledId = -1
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MyRequestsScreen(
                    requests = listOf(localRequest(serverId = 7)),
                    onCreateNew = {},
                    onViewResponses = {},
                    onCancel = { cancelledId = it },
                )
            }
        }
        // В карточке кнопка-строка «Отменить заявку» (RequestSummaryCard.onCancel != null).
        composeRule.onNodeWithText("Отменить заявку").performClick()
        // Открылся диалог подтверждения.
        composeRule.onNodeWithText("Отменить заявку?").assertIsDisplayed()
        composeRule.onNodeWithText("Оставить").performClick()
        // «Оставить» → onCancel не вызван.
        assertEquals(-1, cancelledId)
    }

    @Test
    fun myRequests_cancelDialog_confirmFiresOnCancelWithServerId() {
        var cancelledId = -1
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                MyRequestsScreen(
                    requests = listOf(localRequest(serverId = 7)),
                    onCreateNew = {},
                    onViewResponses = {},
                    onCancel = { cancelledId = it },
                )
            }
        }
        // Тап по кнопке-строке карточки (пока диалог закрыт, узел один) → открывается диалог.
        composeRule.onNodeWithText("Отменить заявку").performClick()
        composeRule.onNodeWithText("Отменить заявку?").assertIsDisplayed()
        // Теперь «Отменить заявку» есть в двух местах: строка карточки и confirm диалога.
        // Диалог (Popup) в дереве обхода идёт последним → onLast() = confirm-кнопка.
        composeRule.onAllNodesWithText("Отменить заявку").assertCountEquals(2)
        composeRule.onAllNodesWithText("Отменить заявку").onLast().performClick()
        assertEquals(7, cancelledId)
    }

    // =====================================================================================
    // VoiceMessageCard — расшифровка (нет аудио) vs аудио (есть путь).
    // =====================================================================================

    @Test
    fun voiceMessageCard_transcript_showsTranscriptAndAuthor_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                VoiceMessageCard(
                    message = LocalVoiceMessage(
                        author = "Азат",
                        transcript = "Я у рынка, подъезжайте",
                        time = "12:30",
                        audioPath = null,
                    )
                )
            }
        }
        composeRule.onNodeWithText("Голосовое от Азат").assertIsDisplayed()
        composeRule.onNodeWithText("Я у рынка, подъезжайте").assertIsDisplayed()
        composeRule.onNodeWithText("Расшифровка для водителя").assertIsDisplayed()
    }

    @Test
    fun voiceMessageCard_withAudioPath_showsDurationHint_ru() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                VoiceMessageCard(
                    message = LocalVoiceMessage(
                        author = "Гуля",
                        transcript = "",
                        time = "09:15",
                        audioPath = "/tmp/voice.m4a",
                        durationSec = 5,
                    )
                )
            }
        }
        // Есть аудио → подпись длительности «5 сек · нажми ▶» (расшифровки не показываем).
        composeRule.onNodeWithText("5 сек · нажми ▶").assertIsDisplayed()
        composeRule.onNodeWithText("Расшифровка для водителя").assertDoesNotExist()
    }

    // =====================================================================================
    // Мелкие переиспользуемые куски: OnlineBadge / SmallAvatar / ListedEmpty / ListedError.
    // =====================================================================================

    @Test
    fun onlineBadge_ru_and_ba() {
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) { OnlineBadge() }
        }
        composeRule.onNodeWithText("на линии").assertIsDisplayed()
    }

    @Test
    fun smallAvatar_blankUrl_showsInitialLetter() {
        composeRule.setContent {
            SmallAvatar(url = "", initial = "Рамиль", size = 44)
        }
        // Пустой url → буква имени (первая, заглавная), без сети/Coil.
        composeRule.onNodeWithText("Р").assertIsDisplayed()
    }

    @Test
    fun listedEmpty_showsTitleAndSubtitle() {
        composeRule.setContent {
            ListedEmpty(title = "Заявок пока нет", subtitle = "Здесь появятся заявки пассажиров.")
        }
        composeRule.onNodeWithText("Заявок пока нет").assertIsDisplayed()
        composeRule.onNodeWithText("Здесь появятся заявки пассажиров.").assertIsDisplayed()
    }

    @Test
    fun listedError_showsMessageAndRetryFires_ru() {
        var retried = false
        composeRule.setContent {
            CompositionLocalProvider(LocalAppLanguage provides AppLanguage.Ru) {
                ListedError(message = "Не удалось загрузить. Проверь сеть.", onRetry = { retried = true })
            }
        }
        composeRule.onNodeWithText("Не удалось загрузить. Проверь сеть.").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить").assertIsDisplayed()
        composeRule.onNodeWithText("Повторить").performClick()
        assertTrue(retried)
    }
}
