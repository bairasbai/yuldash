package com.yuldash.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yandex.mapkit.geometry.Point
import com.yuldash.app.data.InstantOrderDto

/**
 * Навигационное состояние водителя после посадки пассажира.
 *
 * Это честный слой поверх MapKit full: карта следует за GPS и курсом, но манёвры и голос
 * не рисуются выдуманными. Для них оставлена явная кнопка внешнего голосового навигатора;
 * настоящий встроенный turn-by-turn подключается только после активации платного NaviKit.
 */
@Composable
internal fun TaxiDriverOnboardNavigator(
    order: InstantOrderDto,
    locationFix: TaxiLocationFix?,
    busy: Boolean,
    actionError: String?,
    onBack: () -> Unit,
    onChat: () -> Unit,
    onCall: () -> Unit,
    onSafety: () -> Unit,
    onOpenExternalNavigator: () -> Unit,
    onFinish: () -> Unit,
    mapContent: (@Composable (Modifier) -> Unit)? = null,
) {
    var stop by remember(order.id) { mutableStateOf(TaxiSheetStop.Half) }
    var following by rememberSaveable(order.id) { mutableStateOf(true) }
    var recenterTick by remember(order.id) { mutableIntStateOf(0) }

    TaxiSheetScaffold(
        stop = stop,
        onStopChange = { stop = it },
        map = { modifier ->
            if (mapContent != null) {
                mapContent(modifier)
            } else {
                InstantRouteMap(
                    from = Point(order.fromLat, order.fromLng),
                    to = Point(order.toLat, order.toLng),
                    car = locationFix?.point,
                    carBearing = locationFix?.headingDegrees?.toDouble(),
                    followPoint = locationFix?.point,
                    followBearing = locationFix?.headingDegrees?.toDouble(),
                    followEnabled = following && locationFix != null,
                    followFocusY = 0.28f,
                    onFollowInterrupted = { following = false },
                    recenterTick = recenterTick,
                    modifier = modifier,
                )
            }
        },
        halfBodyFraction = 0.42f,
        header = { DriverNavigatorHeader(order) },
        body = {
            DriverNavigatorPassengerCard(
                order = order,
                onChat = onChat,
                onCall = onCall,
                onSafety = onSafety,
            )
            DriverNavigatorDestination(order)
            DriverNavigatorPayment(order)
            OutlinedButton(
                onClick = onOpenExternalNavigator,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = CanonFieldShape,
                border = BorderStroke(1.dp, CanonBorder),
            ) {
                YuldashDirectionGlyph(contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(CanonSpace.sm))
                Text(
                    appText("Открыть голосовой навигатор", "Тауышлы навигаторҙы асыу"),
                    style = CanonButton,
                    color = CanonGreen2,
                )
            }
            if (!actionError.isNullOrBlank()) {
                Text(actionError, style = CanonCaption, color = CanonRed)
            }
        },
        extra = {
            Surface(color = CanonBg, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(CanonSpace.md),
                    verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
                ) {
                    MobilityRouteTimeline(from = order.fromText, to = order.toText, compact = true)
                    order.stops.filter { !it.done }.forEach { stopItem ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
                        ) {
                            Icon(Icons.Default.Place, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                            Text(
                                stopItem.text,
                                style = CanonBody,
                                color = CanonText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        footer = {
            AppButton(
                text = appText("Завершить поездку", "Сәфәрҙе тамамлау"),
                onClick = onFinish,
                loading = busy,
                enabled = !busy,
                style = AppButtonStyle.Primary,
                height = 54.dp,
            )
        },
        overlay = {
            DriverNavigatorCircleButton(
                icon = Icons.Default.KeyboardArrowDown,
                description = appText("Свернуть поездку", "Сәфәрҙе йыйыу"),
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(CanonSpace.lg),
            )
            DriverNavigatorSosButton(
                onClick = onSafety,
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(CanonSpace.lg),
            )
            DriverNavigatorCircleButton(
                icon = Icons.Default.MyLocation,
                description = if (following) {
                    appText("Камера следует за машиной", "Камера машина артынан бара")
                } else {
                    appText("Вернуться к движению", "Хәрәкәткә кире ҡайтыу")
                },
                active = following,
                onClick = { following = true; recenterTick++ },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()
                    .padding(top = 80.dp, end = CanonSpace.lg),
            )
        },
    )
}

@Composable
private fun DriverNavigatorHeader(order: InstantOrderDto) {
    val eta = order.etaMin.toInt().coerceAtLeast(0)
    val arrival = remember(order.id, eta) {
        if (eta > 0) java.time.LocalTime.now().plusMinutes(eta.toLong())
            .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) else null
    }
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CanonSpace.xs),
    ) {
        Text(
            if (eta > 0) appText("В пути · $eta мин", "Юлда · $eta мин")
            else appText("В пути", "Юлда"),
            style = CanonTitle,
            color = CanonText,
        )
        Text(
            arrival?.let { appText("Приедем около $it", "Яҡынса $it-тә барып етәбеҙ") }
                ?: appText("Следим за маршрутом", "Юлды күҙәтәбеҙ"),
            style = CanonCaption,
            color = CanonGreen2,
        )
    }
}

@Composable
private fun DriverNavigatorPassengerCard(
    order: InstantOrderDto,
    onChat: () -> Unit,
    onCall: () -> Unit,
    onSafety: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CanonSpace.md),
        ) {
            Surface(shape = CircleShape, color = CanonMint, modifier = Modifier.size(52.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        order.passengerName.trim().take(1).uppercase().ifBlank { "?" },
                        style = CanonHeading,
                        color = CanonGreen2,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    order.passengerName.ifBlank { appText("Пассажир", "Пассажир") },
                    style = CanonHeading,
                    color = CanonText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(appText("Пассажир в машине", "Пассажир машинала"), style = CanonCaption, color = CanonMuted)
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
        ) {
            DriverNavigatorAction(
                icon = Icons.Default.ChatBubble,
                label = appText("Чат", "Чат"),
                onClick = onChat,
                modifier = Modifier.weight(1f),
            )
            if (order.passengerPhone.isNotBlank()) {
                DriverNavigatorAction(
                    icon = Icons.Default.Phone,
                    label = appText("Звонок", "Шылтыратыу"),
                    onClick = onCall,
                    modifier = Modifier.weight(1f),
                )
            }
            DriverNavigatorAction(
                icon = Icons.Default.Shield,
                label = appText("Безопасность", "Именлек"),
                onClick = onSafety,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DriverNavigatorAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = CanonMint,
            modifier = Modifier.minimumInteractiveComponentSize().size(48.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = CanonGreen2, modifier = Modifier.size(19.dp))
            }
        }
        Text(label, style = CanonMicro, color = CanonMutedStrong, maxLines = 1)
    }
}

@Composable
private fun DriverNavigatorDestination(order: InstantOrderDto) {
    Surface(color = CanonBg, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CanonSpace.md),
        ) {
            Box(Modifier.size(40.dp).background(CanonTaxiBg, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Place, contentDescription = null, tint = CanonTaxiText, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                Text(appText("До места осталось", "Барып етергә ҡалды"), style = CanonMicro, color = CanonMuted)
                Text(
                    order.toText.ifBlank { appText("Точка назначения", "Барыу нөктәһе") },
                    style = CanonBodyStrong,
                    color = CanonText,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DriverNavigatorPayment(order: InstantOrderDto) {
    Surface(color = CanonBg, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
        ) {
            Text(appText("Пассажир платит", "Пассажир түләй"), style = CanonCaption, color = CanonMuted)
            Spacer(Modifier.weight(1f))
            Text(formatTaxiKop(order.passengerPayKop), style = CanonHeading, color = CanonText)
        }
    }
}

@Composable
private fun DriverNavigatorCircleButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = CanonSurface,
        shadowElevation = CanonDepth.raised,
        border = BorderStroke(1.dp, if (active) CanonTaxiText else CanonBorder),
        modifier = modifier.minimumInteractiveComponentSize().size(48.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = if (active) CanonTaxiText else CanonGreen2, modifier = Modifier.size(21.dp))
        }
    }
}

@Composable
private fun DriverNavigatorSosButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = CanonSurface,
        shadowElevation = CanonDepth.raised,
        border = BorderStroke(1.dp, CanonRed.copy(alpha = 0.5f)),
        modifier = modifier.minimumInteractiveComponentSize().size(52.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("SOS", style = CanonBodyStrong, color = CanonRed)
        }
    }
}
