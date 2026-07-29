package com.yuldash.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.mergeDescendants
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.InstantOrderDto

/**
 * Общий визуальный язык режимов «Такси» и «Курьер».
 *
 * Компоненты намеренно не знают о сети и навигации: экраны сохраняют существующую
 * машину состояний и API-контракты, а этот слой отвечает только за иерархию,
 * читаемость с одного взгляда и одинаковое поведение в светлой/тёмной теме.
 */

internal enum class MobilityMode { Taxi, Courier }

@Composable
internal fun MobilityScreenIntro(
    mode: MobilityMode,
    title: String,
    subtitle: String,
    badge: String? = null,
    modifier: Modifier = Modifier,
) {
    val accent = if (mode == MobilityMode.Taxi) CanonTaxi else CanonGreen2
    val icon = if (mode == MobilityMode.Taxi) Icons.Default.DirectionsCar else Icons.Default.LocalShipping
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "$title. $subtitle" },
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = accent.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.padding(12.dp).size(25.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    color = CanonText,
                    fontSize = 27.sp,
                    lineHeight = 31.sp,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!badge.isNullOrBlank()) {
                    Surface(shape = CircleShape, color = accent.copy(alpha = 0.14f)) {
                        Text(
                            badge,
                            color = accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            Text(subtitle, color = CanonMutedStrong, fontSize = 14.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
internal fun TaxiMapFrame(
    nearbyCount: Int,
    modifier: Modifier = Modifier,
    map: @Composable BoxScope.() -> Unit,
) {
    Card(
        shape = CanonCardShape,
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = BorderStroke(1.dp, CanonBorder),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(Modifier.fillMaxWidth().height(208.dp)) {
            map()
            Surface(
                color = CanonSurface.copy(alpha = 0.96f),
                shape = CircleShape,
                shadowElevation = 4.dp,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(Modifier.size(8.dp).background(if (nearbyCount > 0) CanonGreen2 else CanonMuted, CircleShape))
                    Text(
                        if (nearbyCount > 0) appText("$nearbyCount машин рядом", "$nearbyCount машина яҡында")
                        else appText("Ищем машины рядом", "Яҡындағы машиналарҙы эҙләйбеҙ"),
                        color = CanonText,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Surface(
                color = CanonSurface.copy(alpha = 0.96f),
                shape = CircleShape,
                shadowElevation = 4.dp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonTaxi, modifier = Modifier.size(15.dp))
                    Text(appText("Подача по ETA", "ETA буйынса килеү"), color = CanonMutedStrong, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
internal fun MobilityRouteTimeline(
    from: String,
    to: String,
    fromLabel: String = appText("Откуда", "Ҡайҙан"),
    toLabel: String = appText("Куда", "Ҡайҙа"),
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val safeFrom = from.ifBlank { appText("Точка А", "А нөктәһе") }
    val safeTo = to.ifBlank { appText("Точка Б", "Б нөктәһе") }
    val rowGap = if (compact) 9.dp else 13.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$fromLabel: $safeFrom. $toLabel: $safeTo"
            },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(13.dp).background(CanonGreen2, CircleShape))
            Box(Modifier.width(2.dp).height(if (compact) 29.dp else 39.dp).background(CanonBorder))
            Box(
                Modifier.size(13.dp)
                    .background(CanonTaxi, RoundedCornerShape(4.dp))
                    .padding(2.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(rowGap)) {
            MobilityRouteText(fromLabel, safeFrom, compact)
            MobilityRouteText(toLabel, safeTo, compact)
        }
    }
}

@Composable
private fun MobilityRouteText(label: String, value: String, compact: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, color = CanonMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(
            value,
            color = CanonText,
            fontSize = if (compact) 14.sp else 15.sp,
            lineHeight = if (compact) 18.sp else 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun TaxiServiceClassTile(
    title: String,
    subtitle: String,
    price: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(
        if (selected) CanonTaxiBg else CanonSurface,
        tween(180),
        label = "taxiClassBg",
    )
    val border by animateColorAsState(
        if (selected) CanonTaxi else CanonBorder,
        tween(180),
        label = "taxiClassBorder",
    )
    val selectionState = if (selected) appText("Выбрано", "Һайланған")
    else appText("Не выбрано", "Һайланмаған")
    Surface(
        onClick = onClick,
        shape = CanonItemShape,
        color = bg,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        modifier = modifier
            .heightIn(min = 94.dp)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
                stateDescription = selectionState
            },
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) CanonTaxi else CanonBg,
                ) {
                    Icon(
                        Icons.Default.DirectionsCar,
                        contentDescription = null,
                        tint = if (selected) CanonTaxiInk else CanonMutedStrong,
                        modifier = Modifier.padding(8.dp).size(19.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (selected) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonTaxiInk, modifier = Modifier.size(18.dp))
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(title, color = CanonText, fontSize = 15.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                if (price != null) Text("$price ₽", color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Black)
            }
            Text(subtitle, color = CanonMutedStrong, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun TaxiFareSummary(
    price: Int?,
    distance: String?,
    duration: String?,
    pickup: String?,
    loading: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        border = BorderStroke(1.dp, CanonBorder),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(appText("Стоимость поездки", "Сәфәр хаҡы"), color = CanonMutedStrong, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    when {
                        loading -> Text(appText("Считаем…", "Иҫәпләйбеҙ…"), color = CanonText, fontSize = 24.sp, fontWeight = FontWeight.Black)
                        error != null -> Text(appText("Цена недоступна", "Хаҡ әлегә юҡ"), color = CanonRed, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        price != null -> Text("$price ₽", color = CanonText, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Black)
                        else -> Text("—", color = CanonMuted, fontSize = 30.sp, fontWeight = FontWeight.Black)
                    }
                }
                Surface(shape = RoundedCornerShape(18.dp), color = CanonTaxiBg) {
                    Icon(Icons.Default.Payments, contentDescription = null, tint = CanonTaxiInk, modifier = Modifier.padding(13.dp).size(24.dp))
                }
            }
            if (error != null) {
                Text(error, color = CanonRed, fontSize = 13.sp, lineHeight = 18.sp)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MobilityMetricChip(Icons.Default.LocationOn, distance ?: "—", Modifier.weight(1f))
                    MobilityMetricChip(Icons.Default.Schedule, duration ?: "—", Modifier.weight(1f))
                }
                if (!pickup.isNullOrBlank()) {
                    Surface(shape = RoundedCornerShape(14.dp), color = CanonMint) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.size(7.dp).background(CanonGreen2, CircleShape))
                            Text(pickup, color = CanonText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MobilityMetricChip(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(14.dp), color = CanonBg, border = BorderStroke(1.dp, CanonBorder), modifier = modifier) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(icon, contentDescription = null, tint = CanonMutedStrong, modifier = Modifier.size(15.dp))
            Text(text, color = CanonText, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
internal fun MobilityProgressRail(
    labels: List<String>,
    currentIndex: Int,
    modifier: Modifier = Modifier,
    accent: Color = CanonGreen2,
) {
    if (labels.isEmpty()) return
    val safeIndex = currentIndex.coerceIn(0, labels.lastIndex)
    val progressDescription = appText(
        "Этап ${safeIndex + 1} из ${labels.size}: ${labels[safeIndex]}",
        "${safeIndex + 1}/${labels.size} этап: ${labels[safeIndex]}",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = progressDescription
            },
        verticalAlignment = Alignment.Top,
    ) {
        labels.forEachIndexed { index, label ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (index > 0) {
                        Box(
                            Modifier.weight(1f).height(2.dp)
                                .background(if (index <= currentIndex) accent else CanonBorder),
                        )
                    } else Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(if (index == currentIndex) 18.dp else 14.dp)
                            .background(
                                if (index <= currentIndex) accent else CanonSurface,
                                CircleShape,
                            )
                            .then(
                                if (index > currentIndex) Modifier.background(CanonSurface, CircleShape)
                                else Modifier,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (index < currentIndex) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                        } else if (index == currentIndex) {
                            Box(Modifier.size(6.dp).background(Color.White, CircleShape))
                        }
                    }
                    if (index < labels.lastIndex) {
                        Box(
                            Modifier.weight(1f).height(2.dp)
                                .background(if (index < currentIndex) accent else CanonBorder),
                        )
                    } else Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    label,
                    color = if (index == currentIndex) CanonText else CanonMuted,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    fontWeight = if (index == currentIndex) FontWeight.Black else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun taxiProgressIndex(status: String): Int = when (status) {
    "arriving" -> 1
    "onboard" -> 2
    "done" -> 3
    else -> 0
}

@Composable
internal fun TaxiTripProgress(status: String, modifier: Modifier = Modifier) {
    MobilityProgressRail(
        labels = listOf(
            appText("Едет", "Килә"),
            appText("На месте", "Урында"),
            appText("В пути", "Юлда"),
            appText("Готово", "Әҙер"),
        ),
        currentIndex = taxiProgressIndex(status),
        modifier = modifier,
        accent = CanonTaxi,
    )
}

@Composable
internal fun CourierLineHero(
    online: Boolean,
    toggling: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    zoneContent: @Composable ColumnScope.() -> Unit,
) {
    val accent = if (online) CanonGreen2 else CanonMutedStrong
    val title = if (online) appText("Ты на линии", "Һин линияла") else appText("Готов к заказам?", "Заказдарға әҙерме?")
    val subtitle = if (online) appText("Показываем подходящие доставки", "Яраҡлы доставкаларҙы күрһәтәбеҙ")
    else appText("Включи линию, когда будешь готов", "Әҙер булғас, линияны ҡабыҙ")
    Card(
        shape = CanonCardShape,
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        border = BorderStroke(1.dp, if (online) CanonGreen2.copy(alpha = 0.55f) else CanonBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = if (online) 3.dp else 0.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().background(
                    if (online) Brush.horizontalGradient(listOf(CanonGreenInk, CanonGreenInkDark))
                    else Brush.horizontalGradient(listOf(CanonSurface, CanonSurface)),
                ),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(shape = RoundedCornerShape(17.dp), color = if (online) Color.White.copy(alpha = 0.14f) else CanonBg) {
                        Icon(
                            Icons.Default.LocalShipping,
                            contentDescription = null,
                            tint = if (online) Color.White else accent,
                            modifier = Modifier.padding(11.dp).size(24.dp),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(title, color = if (online) Color.White else CanonText, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Text(subtitle, color = if (online) Color.White.copy(alpha = 0.76f) else CanonMutedStrong, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                    val switchLabel = appText("Работа курьера", "Курьер эше")
                    val switchState = if (online) appText("На линии", "Линияла")
                    else appText("Не на линии", "Линияла түгел")
                    Switch(
                        checked = online,
                        onCheckedChange = onToggle,
                        enabled = !toggling,
                        modifier = Modifier.semantics {
                            contentDescription = switchLabel
                            stateDescription = switchState
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = CanonGreen2,
                            uncheckedThumbColor = CanonMuted,
                            uncheckedTrackColor = CanonBg,
                            uncheckedBorderColor = CanonBorder,
                        ),
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp), content = zoneContent)
        }
    }
}

@Composable
internal fun MobilitySegmentTab(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    val bg by animateColorAsState(if (active) CanonText else Color.Transparent, tween(180), label = "mobilitySegment")
    Surface(
        onClick = onClick,
        color = bg,
        shape = RoundedCornerShape(15.dp),
        modifier = modifier
            .height(48.dp)
            .semantics {
                role = Role.Tab
                selected = active
            },
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = if (active) CanonBg else CanonMutedStrong, fontSize = 13.sp, fontWeight = FontWeight.Black, maxLines = 1)
            if (!badge.isNullOrBlank()) {
                Spacer(Modifier.width(5.dp))
                Surface(shape = CircleShape, color = if (active) CanonTaxi else CanonMint) {
                    Text(
                        badge,
                        color = if (active) CanonTaxiInk else CanonGreen2,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
internal fun CourierOfferCard(
    from: String,
    to: String,
    sizeLabel: String,
    deliveryLabel: String,
    priceLabel: String,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard(modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(15.dp), color = CanonMint) {
                    Icon(Icons.Default.Inventory2, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(10.dp).size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(appText("Твой доход", "Һинең килем"), color = CanonMutedStrong, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(priceLabel, color = CanonText, fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
                Surface(shape = RoundedCornerShape(12.dp), color = CanonGreen2.copy(alpha = 0.12f)) {
                    Text(deliveryLabel, color = CanonGreen2, fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                }
            }
            MobilityRouteTimeline(from = from, to = to, compact = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MobilitySmallTag(sizeLabel)
                MobilitySmallTag(appText("Адреса видны", "Адреслар күренә"))
            }
            if (description.isNotBlank()) {
                Text(description, color = CanonText, fontSize = 14.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            content()
        }
    }
}

@Composable
internal fun CourierServiceTypeTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg by animateColorAsState(if (selected) CanonMint else CanonSurface, tween(180), label = "courierServiceBg")
    val border by animateColorAsState(if (selected) CanonGreen2 else CanonBorder, tween(180), label = "courierServiceBorder")
    val selectionState = if (selected) appText("Выбрано", "Һайланған")
    else appText("Не выбрано", "Һайланмаған")
    Surface(
        onClick = onClick,
        color = bg,
        shape = CanonItemShape,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 78.dp)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
                stateDescription = selectionState
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(15.dp),
                color = if (selected) CanonGreen2 else CanonBg,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) Color.White else CanonGreen2,
                    modifier = Modifier.padding(11.dp).size(22.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = CanonText, fontSize = 15.sp, fontWeight = FontWeight.Black)
                Text(subtitle, color = CanonMutedStrong, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (selected) {
                Surface(shape = CircleShape, color = CanonGreen2) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.padding(4.dp).size(16.dp))
                }
            }
        }
    }
}

@Composable
internal fun CourierFareSummary(
    total: String,
    courierGets: String,
    fee: String,
    distance: String,
    modifier: Modifier = Modifier,
    breakdown: @Composable ColumnScope.() -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        border = BorderStroke(1.dp, CanonGreen2.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(appText("Стоимость доставки", "Доставка хаҡы"), color = CanonMutedStrong, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(total, color = CanonText, fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black)
                }
                Surface(shape = RoundedCornerShape(18.dp), color = CanonMint) {
                    Icon(Icons.Default.LocalShipping, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(13.dp).size(25.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MobilityValueTile(appText("Курьеру", "Курьерға"), courierGets, CanonGreen2, Modifier.weight(1f))
                MobilityValueTile(appText("Сервис", "Сервис"), fee, CanonMutedStrong, Modifier.weight(1f))
                MobilityValueTile(appText("Маршрут", "Маршрут"), distance, CanonMutedStrong, Modifier.weight(1f))
            }
            Surface(shape = CanonItemShape, color = CanonMint) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp), content = breakdown)
            }
        }
    }
}

@Composable
private fun MobilityValueTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(14.dp), color = CanonBg, border = BorderStroke(1.dp, CanonBorder), modifier = modifier) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = CanonMuted, fontSize = 10.sp, maxLines = 1)
            Text(value, color = color, fontSize = 12.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MobilitySmallTag(text: String) {
    Surface(shape = CircleShape, color = CanonBg, border = BorderStroke(1.dp, CanonBorder)) {
        Text(text, color = CanonMutedStrong, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
    }
}

internal fun courierProgressIndex(status: String): Int = when (status) {
    "in_transit", "returning" -> 1
    "delivered", "returned" -> 2
    else -> 0
}

internal fun isParcelTerminal(status: String): Boolean =
    status == "delivered" || status == "returned" ||
        status == "canceled" || status == "cancelled"

internal fun canSenderCancelParcel(
    status: String,
    deliveryType: String,
    goodsActualKop: Int,
): Boolean {
    val cancellableStatus =
        status == "created" || status == "accepted" || status == "in_transit"
    val goodsAlreadyBought = deliveryType == "buy_bring" && goodsActualKop > 0
    return cancellableStatus && !goodsAlreadyBought
}

internal fun canCourierDeliverParcel(status: String): Boolean =
    status == "accepted" || status == "in_transit"

internal fun canCourierResolveParcelTrouble(status: String): Boolean =
    status == "accepted" || status == "in_transit" || status == "returning"

internal fun canOpenParcelDispute(status: String): Boolean =
    status == "accepted" || status == "in_transit" || status == "returning" ||
        status == "delivered" || status == "returned"


@Composable
internal fun CourierDeliveryProgress(status: String, modifier: Modifier = Modifier) {
    MobilityProgressRail(
        labels = listOf(
            appText("Забрать", "Алыу"),
            if (status == "returning") appText("Возврат", "Кире илтеү") else appText("В пути", "Юлда"),
            if (status == "returned") appText("Возвращено", "Кире бирелде") else appText("Вручить", "Тапшырыу"),
        ),
        currentIndex = courierProgressIndex(status),
        modifier = modifier,
        accent = CanonGreen2,
    )
}

@Composable
internal fun TaxiSearchingExperience(order: InstantOrderDto, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MobilityScreenIntro(
            mode = MobilityMode.Taxi,
            title = appText("Ищем машину", "Машина эҙләйбеҙ"),
            subtitle = appText("Предложение видят ближайшие проверенные водители", "Тәҡдимде яҡындағы тикшерелгән водителдәр күрә"),
            badge = appText("в эфире", "эфирҙа"),
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = CanonSurface),
            shape = CanonCardShape,
            border = BorderStroke(1.dp, CanonBorder),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(18.dp), color = CanonTaxiBg) {
                        Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonTaxiInk, modifier = Modifier.padding(13.dp).size(25.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(appText("Цена для водителя", "Водитель өсөн хаҡ"), color = CanonMutedStrong, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text("≈ ${order.priceEstimate} ₽", color = CanonText, fontSize = 26.sp, fontWeight = FontWeight.Black)
                    }
                    Box(Modifier.size(10.dp).background(CanonGreen2, CircleShape))
                }
                MobilityRouteTimeline(from = order.fromText, to = order.toText)
                MobilityProgressRail(
                    labels = listOf(
                        appText("Запрос", "Һорау"),
                        appText("Водитель", "Водитель"),
                        appText("Подача", "Килеү"),
                    ),
                    currentIndex = 0,
                    accent = CanonTaxi,
                )
            }
        }
        Surface(shape = CanonItemShape, color = CanonMint) {
            Text(
                appText("Можно свернуть приложение — статус заказа сохранится.", "Ҡушымтаны ябып торорға мөмкин — заказ һаҡланыр."),
                color = CanonText,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(14.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        AppButton(
            text = appText("Отменить заказ", "Заказды кире алыу"),
            onClick = onCancel,
            style = AppButtonStyle.Secondary,
            height = 52.dp,
        )
    }
}
