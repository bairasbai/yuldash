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

/** Режим поиска пассажира. Попутка — по умолчанию (сердце Юлдаша). */
internal enum class RideMode { Pooling, Taxi }

private const val MODE_HINT_PREF = "mode_hint_shown"

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
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("yuldash_prefs", Context.MODE_PRIVATE) }
    var mode by rememberSaveable { mutableStateOf(RideMode.Pooling) }   // Попутка первой/по умолчанию
    // Подсказка при ПЕРВОМ входе — один раз (флаг в prefs). Ссылка «Чем отличается?» открывает её повторно.
    var showHint by remember { mutableStateOf(!prefs.getBoolean(MODE_HINT_PREF, false)) }

    // Аппаратная «Назад» в режиме такси → возвращаемся к попутке (а не выходим из приложения).
    BackHandler(enabled = mode == RideMode.Taxi) { mode = RideMode.Pooling }

    Column(Modifier.fillMaxSize()) {
        ModeSwitchBar(
            mode = mode,
            onSelect = { mode = it },
            onExplain = { showHint = true },
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AnimatedContent(
                targetState = mode,
                transitionSpec = {
                    val forward = targetState == RideMode.Taxi   // Попутка(лево) → Такси(право)
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
                    )
                    RideMode.Taxi -> InstantOrderScreen(
                        onBack = { mode = RideMode.Pooling },        // «назад» из встроенного такси → к попутке
                        onLoginRequired = onInstantLogin,
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
) {
    Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            ModeSegment(
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.yu_mode_rideshare,
                title = appText("Попутка", "Юлдаш"),
                subtitle = appText("дешевле, кто-то едет по пути", "арзаныраҡ, кемдер юл уҙа"),
                active = mode == RideMode.Pooling,
                accent = CanonPooling,
                activeBg = CanonPoolingBg,
                onClick = { onSelect(RideMode.Pooling) },
            )
            ModeSegment(
                modifier = Modifier.weight(1f),
                iconRes = R.drawable.yu_mode_taxi,
                title = appText("Такси", "Такси"),
                subtitle = appText("машина за тобой сейчас", "машина хәҙер һинең артыңдан"),
                active = mode == RideMode.Taxi,
                accent = CanonTaxi,
                activeBg = CanonTaxiBg,
                onClick = { onSelect(RideMode.Taxi) },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(painterResource(iconRes), contentDescription = null,
                    tint = if (active) accent else CanonMuted, modifier = Modifier.size(24.dp))
                Text(title, color = CanonText, fontSize = 18.sp, fontWeight = FontWeight.Black, maxLines = 1)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = if (active) accent else CanonMuted,
                fontSize = 12.sp,
                lineHeight = 15.sp,
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
                appText("Два способа доехать", "Барыуҙың ике юлы"),
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
