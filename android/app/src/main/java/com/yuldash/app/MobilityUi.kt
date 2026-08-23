package com.yuldash.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
            shape = RoundedCornerShape(14.dp),
            color = accent.copy(alpha = 0.14f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.padding(12.dp).size(25.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Заголовок экрана по шкале: 24 Bold вместо 27 Black. Он и так самый крупный
                // текст на экране — крик сверху не добавляет ему веса, а только шума.
                Text(
                    title,
                    color = CanonText,
                    style = CanonTitle,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (!badge.isNullOrBlank()) {
                    Surface(shape = CircleShape, color = accent.copy(alpha = 0.14f)) {
                        Text(
                            badge,
                            // Не `accent`: в режиме «Такси» акцент жёлтый, и подпись на своей же
                            // жёлтой подложке давала 1.97:1 — слово «Такси» было почти не видно.
                            color = canonChipInk(accent),
                            style = CanonMicro,   // метка режима — подпись, а не второй заголовок
                            modifier = Modifier.padding(horizontal = CanonSpace.sm, vertical = CanonSpace.xs),
                        )
                    }
                }
            }
            Text(subtitle, color = CanonMutedStrong, style = CanonCaption)
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
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.raised),
        border = BorderStroke(1.dp, CanonBorder),
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(Modifier.fillMaxWidth().height(208.dp)) {
            map()
            Surface(
                color = CanonSurface.copy(alpha = 0.96f),
                shape = CircleShape,
                shadowElevation = CanonDepth.raised,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                shadowElevation = CanonDepth.raised,
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonTaxi, modifier = Modifier.size(15.dp))
                    Text(appText("Подача по ETA", "ETA буйынса килеү"), color = CanonMutedStrong, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                    .background(CanonTaxi, RoundedCornerShape(8.dp))
                    .padding(4.dp),
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = CanonMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(
            value,
            color = CanonText,
            fontSize = if (compact) 14.sp else 16.sp,
            lineHeight = if (compact) 20.sp else 23.sp,
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
    /** Картинка машины этого класса. null → рисуем прежний контурный значок. */
    iconRes: Int? = null,
    /** Ночная версия картинки — для классов, у которых дневная тонет в тёмном фоне. */
    iconNightRes: Int? = null,
    /** Через сколько подъедет машина ИМЕННО этого класса. null → минут не пишем:
     *  выдуманное «2 мин» на классе, которого рядом нет, — это обещание, а не подсказка. */
    pickupEtaMin: Int? = null,
) {
    // Выбранный тариф выделяем ПОДЛОЖКОЙ, а не рамкой (образец — Яндекс). Рамка у каждой
    // плитки превращала ряд тарифов в таблицу: пять прямоугольников с обводкой, где у одного
    // обводка вдвое толще. Подложка читается мгновенно и не добавляет линий.
    val bg by animateColorAsState(
        if (selected) CanonTaxiBg else CanonBg,
        tween(CanonMotion.QUICK),
        label = "taxiClassBg",
    )
    // Невыбранные машины приглушаем — взгляд должен сам находить выбранную (образец Яндекс).
    // Не обесцвечиваем совсем: чёрный седан Бизнеса и жёлтый Эконом обязаны остаться
    // узнаваемыми, иначе картинка перестаёт отвечать на вопрос «что за мной приедет».
    val carAlpha by animateFloatAsState(
        if (selected) 1f else 0.6f, tween(CanonMotion.QUICK), label = "taxiClassCar")
    val selectionState = if (selected) appText("Выбрано", "Һайланған")
    else appText("Не выбрано", "Һайланмаған")
    val dark = appIsDark()
    val car = (if (dark) iconNightRes else null) ?: iconRes
    Surface(
        onClick = onClick,
        shape = CanonItemShape,
        color = bg,
        modifier = modifier
            .heightIn(min = 88.dp)
            .semantics(mergeDescendants = true) {
                role = Role.RadioButton
                this.selected = selected
                stateDescription = selectionState
            },
    ) {
        Column(
            Modifier.padding(horizontal = CanonSpace.sm, vertical = CanonSpace.md),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.xs),
        ) {
            // Пропорция кадра = пропорция подготовленной картинки (tools/tariff_icons.py):
            // машина заполняет его целиком, без полей по бокам. Разъедутся числа — в ряду
            // снова появится воздух вокруг машин, и они станут выглядеть уменьшенными.
            Box(Modifier.fillMaxWidth().aspectRatio(440f / 300f)) {
                if (car != null) {
                    // Ночью фон карточки почти чёрный, и чёрный седан Бизнеса растворяется
                    // в нём — остаются фары в пустоте. Мягкое световое пятно под машиной
                    // возвращает силуэт и заодно ставит её на «землю», а не в воздух.
                    if (dark) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth(0.9f)
                                .height(20.dp)
                                .background(
                                    Brush.radialGradient(
                                        listOf(CanonSurface, CanonSurface.copy(alpha = 0f)),
                                    ),
                                ),
                        )
                    }
                    Image(
                        painter = painterResource(car),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().align(Alignment.Center).alpha(carAlpha),
                    )
                } else {
                    // Запасной путь: картинки класса нет — рисуем прежний значок, чтобы
                    // плитка осталась рабочей, а не пустой.
                    Icon(
                        Icons.Default.DirectionsCar,
                        contentDescription = null,
                        tint = if (selected) CanonTaxiText else CanonMutedStrong,
                        modifier = Modifier.size(30.dp).align(Alignment.Center),
                    )
                }
                if (pickupEtaMin != null) {
                    // «Когда приедет» — пилюлей поверх машины, как у Яндекса. Отдельной строкой
                    // оно отодвинуло бы цену вниз, а цена на этой карточке главнее.
                    Surface(
                        color = CanonSurface,
                        shape = CanonTinyShape,
                        modifier = Modifier.align(Alignment.TopStart),
                    ) {
                        Text(
                            appText("$pickupEtaMin мин", "$pickupEtaMin мин"),
                            color = if (selected) CanonText else CanonMutedStrong,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = CanonSpace.xs),
                        )
                    }
                }
            }
            Text(
                title,
                color = CanonText,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Цена — самое крупное число на карточке: «что» уже сказала картинка.
            if (price != null) {
                Text("$price ₽", color = CanonText, fontSize = 16.sp, lineHeight = 23.sp,
                     fontWeight = FontWeight.ExtraBold, maxLines = 1)
            } else if (subtitle.isNotBlank()) {
                // Подпись остаётся ровно для одного случая — «скоро», когда цены ещё нет.
                Text(subtitle, color = CanonMutedStrong, fontSize = 12.sp, lineHeight = 17.sp,
                     maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
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
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(appText("Стоимость поездки", "Сәфәр хаҡы"), color = CanonMutedStrong, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    when {
                        loading -> Text(appText("Считаем…", "Иҫәпләйбеҙ…"), color = CanonText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        error != null -> Text(appText("Цена недоступна", "Хаҡ әлегә юҡ"), color = CanonRed, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        price != null -> Text("$price ₽", color = CanonText, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
                        else -> Text("—", color = CanonMuted, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Surface(shape = RoundedCornerShape(14.dp), color = CanonTaxiBg) {
                    Icon(Icons.Default.Payments, contentDescription = null, tint = CanonTaxiText, modifier = Modifier.padding(12.dp).size(24.dp))
                }
            }
            if (error != null) {
                Text(error, color = CanonRed, fontSize = 14.sp, lineHeight = 20.sp)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MobilityMetricChip(Icons.Default.LocationOn, distance ?: "—", Modifier.weight(1f))
                    MobilityMetricChip(Icons.Default.Schedule, duration ?: "—", Modifier.weight(1f))
                }
                if (!pickup.isNullOrBlank()) {
                    Surface(shape = RoundedCornerShape(14.dp), color = CanonMint) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.size(7.dp).background(CanonGreen2, CircleShape))
                            Text(pickup, color = CanonText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
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
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = CanonMutedStrong, modifier = Modifier.size(15.dp))
            Text(text, color = CanonText, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

// Постоянное место под кружок шага. Берём по САМОМУ крупному состоянию (18dp) с небольшим
// запасом, чтобы линия рельса шла ровно на одной высоте на всех шагах.
private val RAIL_DOT_SLOT = 20.dp

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
                // Высота строки одинаковая у ВСЕХ шагов (RAIL_DOT_SLOT). Иначе получалась
                // «лесенка»: у текущего шага кружок крупнее (18dp против 14dp), строка из-за
                // него выше, а линия, выровненная по центру, оказывалась на 2dp ниже соседней —
                // на экране рельс заметно ломался посередине (замечено Александром на скриншоте
                // «Мои», 2026-08-03). Заодно уходит вторая половина беды: пока место под кружок
                // «плавало», отрезки линии слева и справа получались разной длины.
                Row(
                    Modifier.fillMaxWidth().height(RAIL_DOT_SLOT),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (index > 0) {
                        Box(
                            Modifier.weight(1f).height(2.dp)
                                .background(if (index <= currentIndex) accent else CanonBorder),
                        )
                    } else Spacer(Modifier.weight(1f))
                    // Внешняя коробка — постоянного размера, меняется только сам кружок внутри.
                    Box(Modifier.size(RAIL_DOT_SLOT), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(if (index == currentIndex) 18.dp else 14.dp)
                                .background(
                                    if (index <= currentIndex) accent else CanonSurface,
                                    CircleShape,
                                )
                                // Будущим шагам — обводка. Без неё белый кружок на белой карточке
                                // не виден вообще, и рельс читается как «линия, дыра, линия»:
                                // именно это бросилось в глаза на скриншоте (2026-08-03).
                                // Пройденным и текущему обводка не нужна — они залиты цветом.
                                .then(
                                    if (index > currentIndex)
                                        Modifier.border(2.dp, CanonBorder, CircleShape)
                                    else Modifier,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (index < currentIndex) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonOnAccent, modifier = Modifier.size(12.dp))
                            } else if (index == currentIndex) {
                                Box(Modifier.size(6.dp).background(CanonOnAccent, CircleShape))
                            }
                        }
                    }
                    if (index < labels.lastIndex) {
                        Box(
                            Modifier.weight(1f).height(2.dp)
                                .background(if (index < currentIndex) accent else CanonBorder),
                        )
                    } else Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    label,
                    color = if (index == currentIndex) CanonText else CanonMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontWeight = if (index == currentIndex) FontWeight.Bold else FontWeight.Medium,
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
                    Surface(shape = RoundedCornerShape(14.dp), color = if (online) CanonOnAccent.copy(alpha = 0.14f) else CanonBg) {
                        Icon(
                            Icons.Default.LocalShipping,
                            contentDescription = null,
                            tint = if (online) CanonOnAccent else accent,
                            modifier = Modifier.padding(12.dp).size(24.dp),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, color = if (online) CanonOnAccent else CanonText, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(subtitle, color = if (online) CanonOnAccent.copy(alpha = 0.76f) else CanonMutedStrong, fontSize = 12.sp, lineHeight = 17.sp)
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
                            checkedThumbColor = CanonOnAccent,
                            checkedTrackColor = CanonGreen2,
                            uncheckedThumbColor = CanonMuted,
                            uncheckedTrackColor = CanonBg,
                            uncheckedBorderColor = CanonBorder,
                        ),
                    )
                }
            }
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = zoneContent)
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
    val bg by animateColorAsState(if (active) CanonText else Color.Transparent, tween(CanonMotion.QUICK), label = "mobilitySegment")
    Surface(
        onClick = onClick,
        color = bg,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .height(48.dp)
            .semantics {
                role = Role.Tab
                selected = active
            },
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = if (active) CanonBg else CanonMutedStrong, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            if (!badge.isNullOrBlank()) {
                Spacer(Modifier.width(4.dp))
                Surface(shape = CircleShape, color = if (active) CanonTaxi else CanonMint) {
                    Text(
                        badge,
                        color = if (active) CanonTaxiInk else CanonGreen2,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
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
    /** Подпись над суммой. Не всегда «Твой доход»: у бесплатной посылки денег нет вовсе,
     *  и подпись про доход над словом «По-соседски» читается как обман. Решает вызывающий. */
    priceCaption: String,
    priceLabel: String,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppCard(modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(14.dp), color = CanonMint) {
                    Icon(Icons.Default.Inventory2, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(22.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(priceCaption, color = CanonMutedStrong, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(priceLabel, color = CanonText, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                }
                Surface(shape = RoundedCornerShape(14.dp), color = CanonGreen2.copy(alpha = 0.12f)) {
                    Text(deliveryLabel, color = CanonGreen2, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            MobilityRouteTimeline(from = from, to = to, compact = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MobilitySmallTag(sizeLabel)
                // Раньше тут стояло «Адреса видны» — и это перестало быть правдой, как только
                // у посылки появился точный адрес: в открытой ленте его нет, он раскрывается
                // только принявшему курьеру (приватность, §8). Тег теперь обещает ровно то,
                // что произойдёт, а не то, что уже есть.
                MobilitySmallTag(appText("Адрес — когда возьмёшь", "Алғас — адрес күренә"))
            }
            if (description.isNotBlank()) {
                Text(description, color = CanonText, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
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
    val bg by animateColorAsState(if (selected) CanonMint else CanonSurface, tween(CanonMotion.QUICK), label = "courierServiceBg")
    val border by animateColorAsState(if (selected) CanonGreen2 else CanonBorder, tween(CanonMotion.QUICK), label = "courierServiceBorder")
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
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (selected) CanonGreen2 else CanonBg,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (selected) CanonOnAccent else CanonGreen2,
                    modifier = Modifier.padding(12.dp).size(22.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = CanonMutedStrong, fontSize = 12.sp, lineHeight = 23.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (selected) {
                Surface(shape = CircleShape, color = CanonGreen2) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonOnAccent, modifier = Modifier.padding(4.dp).size(16.dp))
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
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(appText("Стоимость доставки", "Доставка хаҡы"), color = CanonMutedStrong, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(total, color = CanonText, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold)
                }
                Surface(shape = RoundedCornerShape(14.dp), color = CanonMint) {
                    Icon(Icons.Default.LocalShipping, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(25.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MobilityValueTile(appText("Курьеру", "Курьерға"), courierGets, CanonGreen2, Modifier.weight(1f))
                MobilityValueTile(appText("Сервис", "Сервис"), fee, CanonMutedStrong, Modifier.weight(1f))
                MobilityValueTile(appText("Маршрут", "Юл"), distance, CanonMutedStrong, Modifier.weight(1f))
            }
            Surface(shape = CanonItemShape, color = CanonMint) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = breakdown)
            }
        }
    }
}

@Composable
private fun MobilityValueTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(14.dp), color = CanonBg, border = BorderStroke(1.dp, CanonBorder), modifier = modifier) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = CanonMuted, fontSize = 12.sp, maxLines = 1)
            Text(value, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MobilitySmallTag(text: String) {
    Surface(shape = CircleShape, color = CanonBg, border = BorderStroke(1.dp, CanonBorder)) {
        Text(text, color = CanonMutedStrong, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
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

/** Цена доставки включает комиссию; курьеру остаётся разница, но никогда не отрицательная. */
internal fun courierNetKop(priceKop: Int, commissionKop: Int): Int =
    (priceKop - commissionKop).coerceAtLeast(0)

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
