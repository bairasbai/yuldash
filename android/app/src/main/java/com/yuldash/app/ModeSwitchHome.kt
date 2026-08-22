package com.yuldash.app

// Переключатель режимов пассажира: 🚗 Попутка ↔ 🚕 Такси.
// Стоит вверху главного экрана (вкладка «Карта»). Тап по сегменту переключает ВЕСЬ поиск:
//  • Попутка (по умолчанию — сердце Юлдаша) → обычный поиск плановых поездок (MapScreen).
//  • Такси → экран быстрого заказа (InstantOrderScreen, встроенный режим — без своей шапки).
// Смена — плавная (AnimatedContent). Цвета — токены CanonTaxi*/CanonPooling* (жёлтый/зелёный).
// Деревне-дружелюбно: крупные сегменты ≥64dp, эмодзи+подпись, простой bottom-sheet «Чем отличается?».

import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.yuldash.app.R
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Режим поиска пассажира. Попутка — по умолчанию (сердце Юлдаша).
 *  Порядок объявления = порядок сегментов слева направо (важен для направления анимации). */
internal enum class RideMode { Pooling, Taxi, Courier }

private const val MODE_HINT_PREF = "mode_hint_shown"

/** Последний выбранный режим. Постоянный таксист/отправитель не должен каждый холодный
 *  старт тратить лишний тап — так же запоминают выбор Яндекс Go и inDrive. */
private const val MODE_LAST_PREF = "mode_last"
private const val MODE_VALUE_TAXI = "taxi"
private const val MODE_VALUE_POOLING = "pooling"
private const val MODE_VALUE_COURIER = "courier"

/** Строка для prefs ↔ режим. Держим в одном месте, чтобы запись и чтение не разъехались. */
private fun RideMode.prefValue(): String = when (this) {
    RideMode.Pooling -> MODE_VALUE_POOLING
    RideMode.Taxi -> MODE_VALUE_TAXI
    RideMode.Courier -> MODE_VALUE_COURIER
}

private fun rideModeFromPref(value: String?): RideMode = when (value) {
    MODE_VALUE_TAXI -> RideMode.Taxi
    MODE_VALUE_COURIER -> RideMode.Courier
    else -> RideMode.Pooling
}

/**
 * Обёртка главного экрана пассажира: переключатель режимов сверху + плавная смена содержимого.
 * Все параметры MapScreen прокидываются как есть (поток попутки не меняем — только оборачиваем).
 */
@Composable
internal fun PassengerModeHome(
    rides: List<Ride>,
    activeTrip: Ride?,
    ads: List<PartnerAd>,
    adStats: Map<String, AdStats>,
    onBookRide: (Ride) -> Unit,
    onShareRide: (Ride) -> Unit,
    onAdImpression: (PartnerAd) -> Unit,
    onAdClick: (PartnerAd) -> Unit,
    onSos: () -> Unit,
    onOpenPopular: (PopularRoute) -> Unit,
    onDriver: () -> Unit,
    onBoost: () -> Unit,
    onInstantLogin: () -> Unit,
    onTaxiOnboarding: () -> Unit = {},   // §11: из заглушки «Такси скоро» — в онбординг таксиста
    onClinicRides: () -> Unit = {},       // F22: раздел «Поездки к клинике» (проброс в карту попутки)
    onRouteWatch: (String?, String?) -> Unit = { _, _ -> },   // F13: «карауль поездку» из карты попутки
    onOpenScheduled: () -> Unit = {},     // «На время»: предзаказ создан → «Мои предзаказы»
    onCourierMode: () -> Unit = {},       // «Хочу возить» → работа курьера (заказы, линия, заработок)
    onSeasonalPublish: (String) -> Unit = {},   // F15: баннер «на праздник» → форма создания поездки (аргумент — дата-шаблон)
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE) }
    // Стартуем с последнего выбранного режима (по умолчанию — попутка). Экономит тап тем,
    // кто пользуется одним режимом постоянно.
    var mode by rememberSaveable {
        mutableStateOf(rideModeFromPref(prefs.getString(MODE_LAST_PREF, null)))
    }
    // Подсказка при ПЕРВОМ входе — один раз (флаг в prefs).
    var showHint by remember { mutableStateOf(!prefs.getBoolean(MODE_HINT_PREF, false)) }
    // Тот же флаг решает и судьбу знака вопроса в контроле: пока подсказку не прочитали —
    // знак есть, прочитали — уходит и контрол становится тремя ровными сегментами.
    // Повторно то же объяснение лежит в «Помощи» — оно не теряется, просто перестаёт
    // занимать место на главном экране.
    var hintSeen by remember { mutableStateOf(prefs.getBoolean(MODE_HINT_PREF, false)) }

    // Запоминаем выбор режима одним швом — и для кнопки, и для аппаратной «Назад».
    val selectMode: (RideMode) -> Unit = { picked ->
        mode = picked
        prefs.edit().putString(MODE_LAST_PREF, picked.prefValue()).apply()
    }

    // Аппаратная «Назад» из любого не-основного режима → возвращаемся к попутке (а не выходим из приложения).
    BackHandler(enabled = mode != RideMode.Pooling) { selectMode(RideMode.Pooling) }

    // Волна 160: пока идёт поездка, переключатель сервисов прячется. Человек уже в машине —
    // выбор между попуткой, такси и курьером он сделал, а место на экране нужнее статусу.
    val tripOnScreen = NavSignals.taxiTripOnScreen.value
    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = !tripOnScreen,
            enter = fadeIn(tween(CanonMotion.NORMAL)),
            exit = fadeOut(tween(CanonMotion.QUICK)),
        ) {
            ModeSwitchBar(
                mode = mode,
                onSelect = selectMode,
                onExplain = { showHint = true },
                showExplain = !hintSeen,
                onCourierMode = onCourierMode,
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    // Едем в ту сторону, где сегмент стоит на панели: слева направо Попутка → Такси → Курьер.
                    val forward = targetState.ordinal > initialState.ordinal
                    (fadeIn(tween(CanonMotion.NORMAL)) + slideInHorizontally(tween(CanonMotion.NORMAL)) { if (forward) it / 6 else -it / 6 })
                        .togetherWith(fadeOut(tween(CanonMotion.QUICK)) + slideOutHorizontally(tween(CanonMotion.NORMAL)) { if (forward) -it / 6 else it / 6 })
                },
                label = "rideMode",
            ) { m ->
                when (m) {
                    RideMode.Pooling -> MapScreen(
                        rides = rides,
                        activeTrip = activeTrip,
                        ads = ads,
                        adStats = adStats,
                        onBookRide = onBookRide,
                        onShareRide = onShareRide,
                        onAdImpression = onAdImpression,
                        onAdClick = onAdClick,
                        onSos = onSos,
                        onOpenPopular = onOpenPopular,
                        onDriver = onDriver,
                        onBoost = onBoost,
                        onClinicRides = onClinicRides,
                        onRouteWatch = onRouteWatch,
                        onSeasonalPublish = onSeasonalPublish,
                    )
                    RideMode.Taxi -> InstantOrderScreen(
                        onBack = { selectMode(RideMode.Pooling) },   // «назад» из встроенного такси → к попутке
                        onLoginRequired = onInstantLogin,
                        embedded = true,
                        onTaxiOnboarding = onTaxiOnboarding,
                        onOpenScheduled = onOpenScheduled,
                    )
                    // Курьер: раньше «Посылки» лежали 9-м пунктом профиля — до отправки было 9–11 касаний.
                    // Теперь это равноправный режим хаба: два касания от старта приложения.
                    RideMode.Courier -> ParcelsScreen(
                        onBack = { selectMode(RideMode.Pooling) },
                        embedded = true,
                    )
                }
            }
        }
    }

    if (showHint) {
        ModeHintSheet(onDismiss = {
            showHint = false
            hintSeen = true
            prefs.edit().putBoolean(MODE_HINT_PREF, true).apply()
        })
    }
}

/** Переключатель режимов — сегментный контрол, как в iOS.
 *
 * Было: три отдельные карточки высотой 66dp+ (иконка над заголовком, под ним подпись, снизу
 * полоска-индикатор) плюс строка со ссылками — вместе больше 120dp, то есть шестая часть
 * экрана, ЗАНЯТАЯ НАВСЕГДА. А это не контент, это переключатель: человек трогает его раз
 * за сессию, а смотрит на него всё время. Три обведённые плитки к тому же спорят по весу
 * с настоящим содержимым карточек ниже.
 *
 * Стало: одна цельная пилюля, внутри которой активный сегмент ПЛАВНО ЕЗДИТ на своё место.
 * Иконка и заголовок встали в строку, подписи («по пути, дешевле») уехали из плиток в одну
 * строку под контролом — они полезны, но занимать три плитки не должны. Итог ~80dp вместо
 * ~120dp, и блок перестал перетягивать внимание на себя.
 */
@Composable
private fun ModeSwitchBar(
    mode: RideMode,
    onSelect: (RideMode) -> Unit,
    onExplain: () -> Unit,
    showExplain: Boolean,
    onCourierMode: () -> Unit = {},
) {
    val items = listOf(
        Triple(RideMode.Pooling, R.drawable.yu_mode_rideshare, appText("Попутка", "Юлдаш")),
        Triple(RideMode.Taxi, R.drawable.yu_mode_taxi, appText("Такси", "Такси")),
        Triple(RideMode.Courier, R.drawable.yu_mode_courier, appText("Курьер", "Курьер")),
    )
    val index = items.indexOfFirst { it.first == mode }.coerceAtLeast(0)
    val accent = when (mode) {
        RideMode.Pooling -> CanonPooling
        RideMode.Taxi -> CanonTaxi
        RideMode.Courier -> CanonCourier
    }
    val activeBg = when (mode) {
        RideMode.Pooling -> CanonPoolingBg
        RideMode.Taxi -> CanonTaxiBg
        RideMode.Courier -> CanonCourierBg
    }
    val subtitle = when (mode) {
        RideMode.Pooling -> appText("по пути, дешевле", "юл уҙа, арзаныраҡ")
        RideMode.Taxi -> appText("машина сейчас", "машина хәҙер")
        RideMode.Courier -> appText("отправить посылку", "бандероль ебәреү")
    }
    val explainCd = appText("Чем отличается?", "Айырмаһы нимәлә?")
    // Пауза снизу, а не только сверху: переключатель — отдельная вещь, и содержимое вкладки
    // не должно начинаться впритык к нему. Отступ здесь, а не в каждой вкладке: иначе три
    // экрана разойдутся по воздуху, и это будет видно при переключении.
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)) {
      Surface(
          shape = RoundedCornerShape(14.dp),
          color = CanonSurface,
          modifier = Modifier.fillMaxWidth(),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(1f)) {
                // Ширина одного сегмента известна только здесь — от неё считается сдвиг пилюли.
                val segW = maxWidth / items.size
                val offset by animateDpAsState(segW * index, tween(CanonMotion.NORMAL), label = "mode_pill")
                // ПОДЛОЖКА выбранного режима. Тонкая рамка цветом режима — не украшение.
                // Проверка контраста (2026-08-03): подложка и фон контрола — соседние токены,
                // в тёмной теме CanonPoolingBg на CanonSurface дают 1.02:1, то есть пилюли
                // не видно вообще. Раньше выбор держали рамка 2dp и полоска снизу, при
                // переписывании в сегментный контрол их убрали и опереться стало не на что.
                // Рамка возвращает пилюле собственную границу, не делая её кричащей.
                Box(
                    Modifier
                        .offset(x = offset)
                        .width(segW)
                        .height(MODE_SEG_HEIGHT)
                        .clip(RoundedCornerShape(14.dp))
                        .background(activeBg)
                )
                Row(Modifier.fillMaxWidth()) {
                    items.forEachIndexed { i, (m, iconRes, title) ->
                        val on = i == index
                        val tint by animateColorAsState(if (on) accent else CanonMuted, tween(CanonMotion.QUICK), label = "mode_tint")
                        val cd = appText(
                            if (on) "$title, выбрано" else "$title, выбрать",
                            if (on) "$title, һайланды" else "$title, һайлау",
                        )
                        Row(
                            Modifier
                                .weight(1f)
                                .height(MODE_SEG_HEIGHT)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable(onClickLabel = cd) { onSelect(m) }
                                .semantics { contentDescription = cd },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(iconRes), contentDescription = null,
                                tint = tint, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                title,
                                color = if (on) CanonText else CanonMuted,
                                fontSize = 14.sp,
                                fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            // «Чем отличается?» — знаком вопроса, и только пока подсказку не прочитали.
            //
            // Подсказка открывается сама при первом входе. После неё знак — хвост справа:
            // симметрию трёх сегментов он ломает каждый день, а нажимают его почти никогда.
            // Прочитал — уходит, и контрол становится ровной пилюлей. То же объяснение
            // остаётся в «Помощи», так что дверь назад есть.
            //
            // Уезжает вместе с шириной, а не просто гаснет: иначе на его месте осталась бы
            // дыра, и сегменты дёрнулись бы скачком.
            AnimatedVisibility(
                visible = showExplain,
                enter = fadeIn(tween(CanonMotion.NORMAL)) + expandHorizontally(tween(CanonMotion.NORMAL)),
                exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkHorizontally(tween(CanonMotion.NORMAL)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Тонкая черта отделяет справку от выбора режима: это разные по смыслу
                    // вещи, и без неё вопрос выглядел бы четвёртым режимом.
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(20.dp)
                            .background(CanonBorder)
                    )
                    Box(
                        Modifier
                            .minimumInteractiveComponentSize()
                            .size(MODE_SEG_HEIGHT)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClickLabel = explainCd) { onExplain() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("?", color = CanonMuted, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
      }
      // Вторая сторона курьера — сама работа. Дверь к ней стоит там же, где человек выбрал
      // «Курьер», а не в глубине профиля: иначе курьер свою работу просто не найдёт.
      AnimatedVisibility(visible = mode == RideMode.Courier) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
              TextButton(onClick = onCourierMode, contentPadding = PaddingValues(horizontal = 8.dp)) {
                  Text(appText("Хочу возить", "Йөрөтөргә теләйем"),
                      color = CanonCourier, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
              }
          }
      }
    }
}

// Высота сегмента = высота тач-цели, потому что кликабелен именно сегмент.
// Было 44dp с оговоркой «48 доберётся зазором контейнера» — оговорка неверна: 3dp паддинга
// лежат СНАРУЖИ кликабельной области, а `clickable` (в отличие от готовых Material-кнопок)
// сам минимальную тач-цель не расширяет. То есть палец имел ровно 44dp — ниже правила §4.5.
// 48dp, и никаких рассуждений про то, где добирается остальное.
private val MODE_SEG_HEIGHT = 48.dp

/** Дружелюбная подсказка простыми словами и крупным текстом: чем Такси отличается от Попутки. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeHintSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = CanonSurface) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                appText("Три режима", "Өс режим"),
                color = CanonText, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
            )
            HintRow(
                iconRes = R.drawable.yu_mode_taxi,
                accent = CanonTaxi,
                title = appText("Такси", "Такси"),
                body = appText(
                    "Быстро. Машина едет прямо за тобой. Чуть дороже.",
                    "Тиҙ. Машина тап һинең артыңдан килә. Бер аҙ ҡиммәтерәк.",
                ),
            )
            HintRow(
                iconRes = R.drawable.yu_mode_rideshare,
                accent = CanonPooling,
                title = appText("Попутка", "Юлдаш"),
                body = appText(
                    "Дешевле. Подсаживаешься к тому, кто и так едет туда.",
                    "Арзаныраҡ. Барыбер шунда барған кешегә ултыраһың.",
                ),
            )
            HintRow(
                iconRes = R.drawable.yu_mode_courier,
                accent = CanonCourier,
                title = appText("Курьер", "Курьер"),
                body = appText(
                    "Едешь не ты, а посылка. Отвезёт тот, кто и так в пути.",
                    "Һин түгел, бандеролең бара. Юлда булған кеше илтә.",
                ),
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CanonPooling),
            ) {
                Text(appText("Понятно", "Аңлашылды"), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HintRow(iconRes: Int, accent: Color, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Surface(shape = RoundedCornerShape(14.dp), color = accent.copy(alpha = 0.16f), modifier = Modifier.size(48.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(iconRes), contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = accent, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(body, color = CanonText, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium)
        }
    }
}
