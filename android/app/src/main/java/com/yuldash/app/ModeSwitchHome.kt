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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextAlign
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
    // Подсказка при ПЕРВОМ входе — один раз (флаг в prefs). Ссылка «Чем отличается?» открывает её повторно.
    var showHint by remember { mutableStateOf(!prefs.getBoolean(MODE_HINT_PREF, false)) }

    // Запоминаем выбор режима одним швом — и для кнопки, и для аппаратной «Назад».
    val selectMode: (RideMode) -> Unit = { picked ->
        mode = picked
        prefs.edit().putString(MODE_LAST_PREF, picked.prefValue()).apply()
    }

    // Аппаратная «Назад» из любого не-основного режима → возвращаемся к попутке (а не выходим из приложения).
    BackHandler(enabled = mode != RideMode.Pooling) { selectMode(RideMode.Pooling) }

    Column(Modifier.fillMaxSize()) {
        ModeSwitchBar(
            mode = mode,
            onSelect = selectMode,
            onExplain = { showHint = true },
            onCourierMode = onCourierMode,
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    // Едем в ту сторону, где сегмент стоит на панели: слева направо Попутка → Такси → Курьер.
                    val forward = targetState.ordinal > initialState.ordinal
                    (fadeIn(tween(240)) + slideInHorizontally(tween(280)) { if (forward) it / 6 else -it / 6 })
                        .togetherWith(fadeOut(tween(160)) + slideOutHorizontally(tween(280)) { if (forward) -it / 6 else it / 6 })
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
            prefs.edit().putBoolean(MODE_HINT_PREF, true).apply()
        })
    }
}

/** Два больших сегмента + маленькая ссылка «Чем отличается?». */
@Composable
private fun ModeSwitchBar(
    mode: RideMode,
    onSelect: (RideMode) -> Unit,
    onExplain: () -> Unit,
    onCourierMode: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp)) {
        // Три режима в ряд — подписи короткие: в треть ширины длинная фраза не читается.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ModeSegment(
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.yu_mode_rideshare,
                title = appText("Попутка", "Юлдаш"),
                subtitle = appText("по пути, дешевле", "юл уҙа, арзаныраҡ"),
                active = mode == RideMode.Pooling,
                accent = CanonPooling,
                activeBg = CanonPoolingBg,
                onClick = { onSelect(RideMode.Pooling) },
            )
            ModeSegment(
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.yu_mode_taxi,
                title = appText("Такси", "Такси"),
                subtitle = appText("машина сейчас", "машина хәҙер"),
                active = mode == RideMode.Taxi,
                accent = CanonTaxi,
                activeBg = CanonTaxiBg,
                onClick = { onSelect(RideMode.Taxi) },
            )
            ModeSegment(
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.yu_mode_courier,
                title = appText("Курьер", "Курьер"),
                subtitle = appText("отправить посылку", "бандероль ебәреү"),
                active = mode == RideMode.Courier,
                accent = CanonCourier,
                activeBg = CanonCourierBg,
                onClick = { onSelect(RideMode.Courier) },
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Вторая сторона курьера — сама работа. Раньше «Возить» жило вкладкой внутри «Посылок»,
            // но там оно показывалось всем подряд и работало по другому списку заказов. Теперь работа
            // курьера — отдельный экран, и дверь к ней должна быть там же, где человек выбрал «Курьер»,
            // а не только в глубине профиля: иначе курьер свою работу просто не найдёт.
            AnimatedVisibility(visible = mode == RideMode.Courier) {
                TextButton(onClick = onCourierMode) {
                    Text(
                        appText("Хочу возить", "Йөрөтөргә теләйем"),
                        color = CanonCourier, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onExplain) {
                Text(appText("Чем отличается?", "Айырмаһы нимәлә?"), color = CanonMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Один сегмент переключателя: крупный (≥64dp), эмодзи+заголовок+подпись, активный подсвечен цветом режима. */
@Composable
private fun ModeSegment(
    modifier: Modifier = Modifier,
    iconRes: Int,
    title: String,
    subtitle: String,
    active: Boolean,
    accent: Color,
    activeBg: Color,
    onClick: () -> Unit,
) {
    val container by animateColorAsState(if (active) activeBg else CanonSurface, tween(220), label = "seg_bg")
    val borderColor by animateColorAsState(if (active) accent else CanonBorder, tween(220), label = "seg_border")
    val borderW by animateDpAsState(if (active) 2.dp else 1.dp, tween(220), label = "seg_bw")
    val cd = appText(
        if (active) "$title, выбрано" else "$title, выбрать",
        if (active) "$title, һайланды" else "$title, һайлау",
    )
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 66.dp).semantics { contentDescription = cd },
        shape = RoundedCornerShape(20.dp),
        color = container,
        border = BorderStroke(borderW, borderColor),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Иконка НАД заголовком, а не рядом: сегментов стало три, и в треть ширины экрана
            // строка «иконка + текст» не помещается — заголовок обрезался бы многоточием.
            Icon(painterResource(iconRes), contentDescription = null,
                tint = if (active) accent else CanonMuted, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(4.dp))
            Text(title, color = CanonText, fontSize = 15.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = if (active) accent else CanonMuted,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            // Индикатор-полоска активного режима (акцентный цвет).
            val stripW by animateDpAsState(if (active) 26.dp else 0.dp, tween(240), label = "seg_strip")
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.height(3.dp).width(stripW).clip(RoundedCornerShape(2.dp)).background(accent)
            )
        }
    }
}

/** Дружелюбная подсказка простыми словами и крупным текстом: чем Такси отличается от Попутки. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeHintSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = CanonSurface) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                appText("Три режима", "Өс режим"),
                color = CanonText, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black,
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
            Spacer(Modifier.height(2.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CanonPooling),
            ) {
                Text(appText("Понятно", "Аңлашылды"), fontSize = 17.sp, fontWeight = FontWeight.Black)
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
            Text(title, color = accent, fontSize = 18.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(2.dp))
            Text(body, color = CanonText, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
        }
    }
}
