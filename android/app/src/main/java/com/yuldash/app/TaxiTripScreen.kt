package com.yuldash.app

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yandex.mapkit.geometry.Point
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.InstantOrderDto
import kotlinx.coroutines.launch

/**
 * Активная поездка пассажира — карта во весь экран, поверх неё шторка.
 *
 * ЗАЧЕМ ОТДЕЛЬНЫЙ ЭКРАН (волна 160). Старая карточка жила внутри вкладки «Такси»: карта была
 * прямоугольником сверху, под ней — простыня из блоков, а сверху висел переключатель
 * «Попутка / Такси / Курьер». Живой прогон на эмуляторе показал три вещи, которых не видно
 * в коде:
 *
 *  • палец двигал КАРТУ, а не шторку — карта уезжала в чистое поле, и вернуть машину
 *    в кадр было нечем;
 *  • две шкалы фаз подряд, с разными наборами шагов, — их никто не читает, а место они
 *    занимают вдвоём;
 *  • переключатель сервисов и нижнее меню съедали пятую часть экрана у человека, который
 *    уже едет и выбор давно сделал.
 *
 * Отсюда устройство: поездка занимает экран целиком, у шторки ТРИ положения, и высоту она
 * выбирает сама по фазе — ищем машину нужна карта, приехал водитель нужен он сам.
 * Тянуть можно всегда, но угадывать за человека дешевле, чем заставлять его тянуть.
 *
 * Приёмы намеренно привычные (решение Александра: «люди привыкли к интерфейсам Яндекса»).
 * Своего здесь ровно два: SOS остаётся СНАРУЖИ, а не прячется под «Безопасность» — это
 * на один тап меньше, чем у Яндекса, и ровно тот тап, которого может не хватить. И землячество
 * в карточке водителя — то, чего у федеральной службы быть не может.
 */

/** Какое положение уместно на этой фазе — то самое «шторка подстраивается сама». */
private fun defaultStopFor(status: String): TaxiSheetStop = when (status) {
    // Машину ещё ищут или она едет — главное на экране карта.
    "searching", "created", "offered" -> TaxiSheetStop.Peek
    // Приехал и ждёт — нужен он сам: имя, номер, кнопка позвонить.
    "arriving" -> TaxiSheetStop.Half
    else -> TaxiSheetStop.Half
}

@Composable
internal fun TaxiTripScreen(
    order: InstantOrderDto,
    onCancel: () -> Unit,
    onMinimize: () -> Unit = {},
    enableLiveTracking: Boolean = true,
    /** Открыть выбор способа расчёта. Про наличные человек вспоминает уже сидя в машине. */
    onOpenPayments: () -> Unit = {},
    mapContent: (@Composable (Modifier) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Ночная поездка: после заката экран и карта приглушаются сами, даже если в телефоне
    // светлая тема. Такси нужнее всего ночью, и белый экран в лицо в половине первого —
    // это то, за что достаётся Яндексу. Считаем по солнцу в точке подачи, а не по часам:
    // в июне здесь светло до одиннадцати, в декабре темнеет в половине пятого.
    val darkOutside = remember(order.id) {
        SunTime.isDark(order.fromLat, order.fromLng, System.currentTimeMillis())
    }
    // Человек может вернуть светлый вид: кому-то с плохим зрением тёмное читать труднее.
    // Насильно ничего не навязываем — только предлагаем разумное по умолчанию.
    var wantsLight by rememberSaveable(order.id) { mutableStateOf(false) }
    val nightRide = darkOutside && !wantsLight

    // Карту можно увести пальцем — и потерять машину из кадра. Кнопка возвращает вид
    // к маршруту: без неё человек оставался с пустым полем вместо своей поездки.
    var recenterTick by remember(order.id) { mutableStateOf(0) }

    // Положение шторки: сначала подходящее фазе, дальше человек командует сам. Смена фазы
    // возвращает подсказку — но только если он не трогал шторку руками: дёргать её под
    // пальцем было бы хамством.
    var stop by remember { mutableStateOf(defaultStopFor(order.status)) }
    var userMoved by remember { mutableStateOf(false) }
    LaunchedEffect(order.status) {
        if (!userMoved) stop = defaultStopFor(order.status)
    }

    var showChangeDestination by remember { mutableStateOf(false) }
    var addingStop by remember { mutableStateOf(false) }
    var showSafety by remember { mutableStateOf(false) }
    var showShare by remember(order.id) { mutableStateOf(false) }
    var confirmPaidCancel by remember { mutableStateOf(false) }

    // Живой трек машины: держим сокет заказа, пока экран на виду. Колбэк приходит с потока
    // OkHttp — snapshot-state потокобезопасен. Ушли с экрана → закрываем, иначе сокет живёт
    // и греет батарею у человека, который уже приехал.
    var carPoint by remember(order.id) { mutableStateOf<Point?>(null) }
    var carBearing by remember(order.id) { mutableStateOf<Double?>(null) }
    DisposableEffect(order.id, enableLiveTracking) {
        if (!enableLiveTracking) return@DisposableEffect onDispose { }
        val socket = com.yuldash.app.data.InstantLocationSocket(order.id, onPeer = { peer ->
            if (peer.role == "driver") {
                carPoint = Point(peer.lat, peer.lng)
                carBearing = peer.bearing
            }
        }).also { it.connect() }
        onDispose { socket.close() }
    }

    CompositionLocalProvider(LocalNightRide provides if (nightRide) true else null) {
        TaxiSheetScaffold(
            stop = stop,
            onStopChange = { userMoved = true; stop = it },
            map = { m ->
                if (mapContent != null) {
                    mapContent(m)
                } else {
                    InstantRouteMap(
                        from = Point(order.fromLat, order.fromLng),
                        to = Point(order.toLat, order.toLng),
                        modifier = m,
                        car = carPoint,
                        carBearing = carBearing,
                        recenterTick = recenterTick,
                    )
                }
            },
            header = { TripStatusHeader(order) },
            body = {
                TripDriverCard(
                    order = order,
                    onChat = { NavSignals.openInstantChat.value = order.id },
                    onCall = {
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_DIAL, Uri.parse("tel:${order.driverPhone}"))
                            )
                        }
                    },
                    onSafety = { showSafety = true },
                )
                // «Я на месте» → живой таймер: бесплатное окно, потом платно.
                if (order.status == "arriving") {
                    InstantWaitingRow(order)
                    TripImComingButton(order.id)
                }
                TripPriceRow(order, onOpenPayments)
            },
            extra = {
                TripRouteBlock(
                    order = order,
                    onChangeDestination = { showChangeDestination = true },
                    onAddStop = { addingStop = true },
                    onRemoveStop = { i ->
                        scope.launch {
                            ApiClient.setWaypoints(
                                order.id,
                                order.stops.filter { !it.done }.filterIndexed { j, _ -> j != i },
                            )
                        }
                    },
                )
                // Виден только ночью: днём переключать нечего.
                if (darkOutside) {
                    TripLightSwitch(
                        lightNow = wantsLight,
                        onToggle = { wantsLight = !wantsLight },
                    )
                }
                TripCancelButton(
                    order = order,
                    onCancel = onCancel,
                    onConfirmNeeded = { confirmPaidCancel = true },
                )
            },
            overlay = {
                // SOS — поверх всего и всегда на виду. Единственная кнопка, которую мы НЕ прячем
                // под «Безопасность»: у Яндекса до 112 два тапа, а это на один больше, чем есть
                // у человека в беде.
                TripSosButton(
                    orderId = order.id,
                    modifier = Modifier.align(Alignment.TopEnd).padding(CanonSpace.lg),
                )
                TripRecenterButton(
                    onClick = { recenterTick++ },
                    modifier = Modifier.align(Alignment.TopStart).padding(CanonSpace.lg),
                )
            },
        )
    }

    // ---- всё, что открывается поверх ----
    if (showChangeDestination) {
        ChangeDestinationSheet(
            order = order,
            onDismiss = { showChangeDestination = false },
            // Экран сам переспросит сервер через пару секунд — новую цену и адрес возьмёт
            // оттуда, а не из нашего локального предположения.
            onChanged = {},
        )
    }
    if (addingStop) {
        PickStopSheet(
            onDismiss = { addingStop = false },
            onPicked = { hit ->
                addingStop = false
                scope.launch {
                    ApiClient.setWaypoints(
                        order.id,
                        order.stops.filter { !it.done } +
                            com.yuldash.app.data.TaxiStop(hit.lat, hit.lon, hit.title),
                    )
                }
            },
        )
    }
    if (showSafety) {
        TripSafetySheet(
            orderId = order.id,
            onShare = { showSafety = false; showShare = true },
            onDismiss = { showSafety = false },
        )
    }
    if (showShare) {
        InstantShareDialog(orderId = order.id, onDismiss = { showShare = false })
    }
    if (confirmPaidCancel) {
        TripPaidCancelDialog(
            order = order,
            onDismiss = { confirmPaidCancel = false },
            onConfirm = { confirmPaidCancel = false; onCancel() },
        )
    }
}

/**
 * Статус — одной крупной строкой и минутами. Шкалы прогресса убраны обе: их было две подряд,
 * с разными наборами шагов, и человек всё равно следит за одним числом «сколько ждать».
 */
@Composable
private fun TripStatusHeader(order: InstantOrderDto) {
    val title = when (order.status) {
        "accepted" -> appText("Водитель едет к тебе", "Йөрөтөүсе һиңә килә")
        "arriving" -> appText("Машина на месте", "Машина урынында")
        "onboard" -> appText("В пути", "Юлда")
        else -> appText("Водитель едет", "Йөрөтөүсе килә")
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Смена фазы — главное событие экрана. Мгновенная подмена надписи читалась как сбой,
        // поэтому это движение вверх, а не щелчок.
        AnimatedContent(
            targetState = title,
            transitionSpec = {
                (fadeIn(tween(CanonMotion.NORMAL)) + slideInVertically(tween(CanonMotion.SLOW)) { it / 3 })
                    .togetherWith(fadeOut(tween(CanonMotion.QUICK)))
            },
            modifier = Modifier.weight(1f),
            label = "tripPhaseTitle",
        ) { t ->
            Text(
                t,
                style = CanonTitle,
                color = CanonText,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (order.etaMin > 0 && order.status != "onboard") {
            Spacer(Modifier.width(CanonSpace.sm))
            // Минуты меняются плавно: скачущее число выглядит как обрыв связи.
            AnimatedContent(targetState = order.etaMin.toInt(), label = "tripEta") { m ->
                Text(
                    appText("$m мин", "$m мин"),
                    style = CanonHeading,
                    color = CanonGreen2,
                )
            }
        } else if (order.status == "onboard") {
            // В пути минуты подачи прячутся — машина уже пришла, — и справа от «В пути»
            // оставалась пустота в половину строки. Ставим цену: в дороге это ровно то
            // число, которое человек держит в голове до высадки.
            // Имя латиницей не для красоты: сторож двуязычия видит кириллицу внутри
            // "$..." и считает её надписью мимо appText.
            val rub = order.priceFinal ?: order.priceEstimate
            if (rub > 0) {
                Spacer(Modifier.width(CanonSpace.sm))
                Text("$rub ₽", style = CanonHeading, color = CanonText)
            }
        }
    }
}

/**
 * Кто едет. Фото, имя, стаж, землячество, машина, госномер — и три кнопки связи.
 *
 * Стаж и «свой» здесь не украшение: человек садится в чужую машину, часто ночью, часто
 * в райцентре, где такси приезжает одно на весь город. «312 поездок» и «из Баймака» говорят
 * ему больше, чем звёздочка рейтинга, — и это ровно то, чего федеральная служба показать
 * не может.
 */
@Composable
private fun TripDriverCard(
    order: InstantOrderDto,
    onChat: () -> Unit,
    onCall: () -> Unit,
    onSafety: () -> Unit,
) {
    val nameFallback = appText("Водитель", "Йөрөтөүсе")
    val verifiedLabel = appText("Проверен", "Тикшерелгән")
    val chatLabel = appText("Написать водителю", "Йөрөтөүсегә яҙырға")
    val callLabel = appText("Позвонить водителю", "Йөрөтөүсегә шылтыратыу")
    val safetyLabel = appText("Безопасность", "Именлек")
    val fromLabel = order.driverFrom.trim()

    Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TripDriverAvatar(url = order.driverAvatar, name = order.driverName)
            Spacer(Modifier.width(CanonSpace.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        order.driverName.ifBlank { nameFallback },
                        style = CanonHeading,
                        color = CanonText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (order.driverVerified) {
                        Spacer(Modifier.width(CanonSpace.xs))
                        Icon(
                            Icons.Default.Verified,
                            contentDescription = verifiedLabel,
                            tint = CanonGreen2,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                // Доверие двумя строками, а не одной. Одной — не влезало: «★ 4.8 · 312 поездок ·
                // Бай…» обрывалось ровно на городе, то есть на самом ценном. Рядом ещё госномер,
                // и места на всё в одну строку нет ни на одном телефоне.
                val rated = buildList {
                    if (order.driverRating > 0) {
                        add("★ " + String.format(java.util.Locale.US, "%.1f", order.driverRating))
                    }
                    if (order.driverTrips > 0) add(tripsPhrase(order.driverTrips))
                }.joinToString("  ·  ")
                if (rated.isNotBlank()) {
                    Text(rated, style = CanonCaption, color = CanonMuted,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Машина и откуда водитель. Землячество — то, чего у федеральной службы быть
                // не может, поэтому оно стоит рядом с маркой, а не теряется в хвосте строки.
                val carAndHome = buildList {
                    if (order.driverCar.isNotBlank()) add(order.driverCar)
                    if (fromLabel.isNotBlank()) add(fromLabel)
                }.joinToString("  ·  ")
                if (carAndHome.isNotBlank()) {
                    Text(carAndHome, style = CanonCaption, color = CanonMuted,
                         maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // ГОСНОМЕР — по нему узнают машину во дворе. «Белая Гранта» не помогает, когда
            // во дворе три белых Гранты.
            if (order.driverPlate.isNotBlank()) {
                Spacer(Modifier.width(CanonSpace.sm))
                Surface(color = CanonTaxiBg, shape = RoundedCornerShape(8.dp)) {
                    Text(
                        order.driverPlate,
                        // CanonTaxiText, а не CanonTaxiInk: ink рассчитан на жёлтый CanonTaxi,
                        // здесь подложка CanonTaxiBg — в тёмной теме контраст падал до 1.05,
                        // то есть номер был не виден совсем.
                        style = CanonBodyStrong,
                        color = CanonTaxiText,
                        modifier = Modifier.padding(horizontal = CanonSpace.sm, vertical = CanonSpace.xs),
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            TripCircleAction(Icons.Default.ChatBubble, chatLabel, CanonMint, CanonGreen2, onChat)
            if (order.driverPhone.isNotBlank()) {
                TripCircleAction(Icons.Default.Phone, callLabel, CanonGreen2, CanonOnAccent, onCall)
            }
            TripCircleAction(Icons.Default.Shield, safetyLabel, CanonMint, CanonGreen2, onSafety)
        }
    }
}

/** Фото водителя, а без фото — буква имени. Пустой серый кружок читается как «нет данных». */
@Composable
private fun TripDriverAvatar(url: String, name: String) {
    val label = appText("Фото водителя", "Йөрөтөүсе фотоһы")
    Surface(shape = CircleShape, color = CanonMint, modifier = Modifier.size(56.dp)) {
        if (url.isNotBlank()) {
            coil.compose.AsyncImage(
                model = url,
                contentDescription = label,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    name.trim().take(1).uppercase().ifBlank { "?" },
                    style = CanonHeading,
                    color = CanonGreen2,
                )
            }
        }
    }
}

/** Круглая кнопка связи. Тач-цель 48dp: пассажир жмёт это на улице, часто в перчатках. */
@Composable
private fun TripCircleAction(
    icon: ImageVector,
    label: String,
    bg: androidx.compose.ui.graphics.Color,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = bg,
        modifier = Modifier.minimumInteractiveComponentSize().size(48.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * Сколько отдать водителю. Раньше в активном заказе суммы не было вообще: человек садился
 * в машину и вспоминал цену по памяти, а со скидкой по промокоду это прямой спор на дороге.
 */
@Composable
private fun TripPriceRow(order: InstantOrderDto, onOpenPayments: () -> Unit = {}) {
    if (order.hasPromoDiscount) {
        TaxiPromoPayRow(order = order, forDriver = false)
        return
    }
    Surface(
        onClick = onOpenPayments,
        color = CanonBg, shape = CanonItemShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(appText("К оплате водителю", "Йөрөтөүсегә түләргә"), style = CanonCaption, color = CanonMuted)
                // Раньше здесь стояло безликое «наличными или переводом» — при том, что способ
                // человек уже выбрал. Приложение размывало ту самую договорённость, ради
                // которой всё и затевалось. Называем её словами.
                Text(
                    PayMethods.title(order.paymentMethod),
                    style = CanonBodyStrong, color = CanonText,
                )
            }
            Spacer(Modifier.width(CanonSpace.sm))
            Text(formatTaxiKop(order.passengerPayKop), style = CanonHeading, color = CanonText)
            Spacer(Modifier.width(CanonSpace.xs))
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = appText("Сменить способ", "Ысулды алмаштырырға"),
                tint = CanonMuted, modifier = Modifier.size(20.dp),
            )
        }
    }
    // Сменил способ, а водитель минуту не подтверждает. Галочка на экране говорит «готово»,
    // и человек уверен, что вопрос закрыт, — а водитель за рулём пуш не открыл и везёт,
    // рассчитывая на прежнее. Молчать тут — значит готовить спор на высадке.
    if (order.paymentAckOverdue) {
        Spacer(Modifier.height(CanonSpace.xs))
        Text(
            appText(
                "Водитель ещё не подтвердил новый способ — скажи ему голосом, так надёжнее.",
                "Йөрөтөүсе яңы ысулды әле раҫламаны — уға тауыш менән әйт, шулай ышаныслыраҡ.",
            ),
            style = CanonCaption, color = CanonWarn,
            modifier = Modifier.padding(horizontal = CanonSpace.md),
        )
    }
}

/** «Уже выхожу» — водитель узнаёт, что человек спускается, и не начинает считать простой зря. */
@Composable
private fun TripImComingButton(orderId: Int) {
    val scope = rememberCoroutineScope()
    var sent by remember(orderId) { mutableStateOf(false) }
    val label = if (sent) appText("Водитель предупреждён", "Йөрөтөүсегә әйтелде")
                else appText("Уже выхожу", "Сығып киләм")
    OutlinedButton(
        onClick = {
            if (sent) return@OutlinedButton
            sent = true
            scope.launch { runCatching { ApiClient.instantImComing(orderId) } }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = CanonFieldShape,
        border = BorderStroke(1.dp, CanonGreen2),
    ) {
        Icon(Icons.Default.DirectionsWalk, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(CanonSpace.sm))
        Text(label, style = CanonButton, color = CanonGreen2)
    }
}

/** Маршрут: откуда, остановки, куда. Отсюда же меняют адрес и добавляют остановку. */
@Composable
private fun TripRouteBlock(
    order: InstantOrderDto,
    onChangeDestination: () -> Unit,
    onAddStop: () -> Unit,
    onRemoveStop: (Int) -> Unit,
) {
    val active = order.status in setOf("accepted", "arriving", "onboard")
    val removeLabel = appText("Убрать остановку", "Туҡталышты алыу")
    val changeLabel = appText("Изменить адрес", "Адресты үҙгәртеү")
    Surface(color = CanonBg, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(CanonSpace.md),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
        ) {
            MobilityRouteTimeline(from = order.fromText, to = order.toText, compact = true)
            // Проеденную остановку убрать нельзя — уже проехали, спорить не о чем.
            order.stops.forEachIndexed { i, st ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
                ) {
                    Icon(
                        Icons.Default.Place,
                        contentDescription = null,
                        tint = if (st.done) CanonMuted else CanonGreen2,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        st.text,
                        style = CanonBody,
                        color = if (st.done) CanonMuted else CanonText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!st.done) {
                        Surface(
                            onClick = { onRemoveStop(i) },
                            shape = CircleShape,
                            color = CanonBg,
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = removeLabel,
                                tint = CanonMuted,
                                modifier = Modifier.padding(CanonSpace.md).size(18.dp),
                            )
                        }
                    }
                }
            }
            if (active) {
                Column {
                    TextButton(onClick = onAddStop, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(CanonSpace.xs))
                        Text(
                            if (order.stops.isEmpty()) appText("Заехать по пути", "Юлда инеп сығыу")
                            else appText("Ещё остановка", "Тағы туҡталыш"),
                            style = CanonBody,
                            color = CanonGreen2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = onChangeDestination, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(CanonSpace.xs))
                        Text(changeLabel, style = CanonBody, color = CanonGreen2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Отмена. Поздняя стоит денег — говорим это ДО тапа, а не после. */
@Composable
private fun TripCancelButton(
    order: InstantOrderDto,
    onCancel: () -> Unit,
    onConfirmNeeded: () -> Unit,
) {
    if (order.status == "onboard") return
    val fee = formatTaxiKop(order.cancelFeeNowKop)
    val label = if (order.cancelFeeNowKop > 0) appText("Отменить · $fee", "Кире алыу · $fee")
                else appText("Отменить заказ", "Заказды кире алыу")
    OutlinedButton(
        onClick = { if (order.cancelFeeNowKop > 0) onConfirmNeeded() else onCancel() },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = CanonFieldShape,
        border = BorderStroke(1.dp, CanonRed.copy(alpha = 0.4f)),
    ) {
        Text(label, style = CanonButton, color = CanonRed)
    }
}

@Composable
private fun TripPaidCancelDialog(
    order: InstantOrderDto,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val fee = formatTaxiKop(order.cancelFeeNowKop)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CanonSurface,
        title = {
            Text(appText("Отмена сейчас платная", "Хәҙер кире алыу түләүле"), style = CanonHeading, color = CanonText)
        },
        text = {
            Text(
                appText(
                    "Водитель уже приехал и ждёт. Отмена — $fee (подача), переведи водителю напрямую. Частые платные отмены ставят такси на паузу.",
                    "Йөрөтөүсе килде инде һәм көтә. Кире алыу — $fee (килеү хаҡы), йөрөтөүсегә туранан күсер. Йыш түләүле кире алыуҙар таксиҙы паузаға ҡуя.",
                ),
                style = CanonCaption,
                color = CanonMuted,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(appText("Всё равно отменить", "Барыбер кире алыу"), style = CanonButton, color = CanonRed)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(appText("Я выхожу", "Мин сығам"), style = CanonButton, color = CanonGreen2)
            }
        },
    )
}

/** SOS поверх карты. Всегда на виду и всегда в одном месте — его ищут не глазами, а рукой. */
@Composable
private fun TripSosButton(orderId: Int, modifier: Modifier = Modifier) {
    val label = appText("Экстренная помощь", "Ашығыс ярҙам")
    Surface(
        onClick = { NavSignals.openSosForOrder.value = orderId },
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

/**
 * «Безопасность» — всё, кроме SOS.
 *
 * Здесь мы намеренно расходимся с Яндексом. У них под этой кнопкой лежит и 112 тоже, то есть
 * до вызова полиции два тапа. Мы вынесли SOS наружу, а внутрь убрали то, что делают спокойно
 * и осознанно: отправить маршрут близкому и позвать помощь на трассе.
 */
@Composable
private fun TripSafetySheet(
    orderId: Int,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = appText("Безопасность", "Именлек")
    val shareTitle = appText("Поделиться поездкой", "Сәфәр менән бүлешеү")
    val shareBody = appText(
        "Близкий увидит на карте, где ты едешь, и когда приедешь.",
        "Яҡының картала ҡайҙа барғаныңды һәм ҡасан етеүеңде күрер.",
    )
    val closeLabel = appText("Закрыть", "Ябырға")
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CanonSurface,
        title = { Text(title, style = CanonHeading, color = CanonText) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
                Surface(
                    onClick = onShare,
                    color = CanonBg,
                    shape = CanonItemShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(CanonSpace.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(CanonSpace.md),
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                        Column(Modifier.weight(1f)) {
                            Text(shareTitle, style = CanonBodyStrong, color = CanonText)
                            Text(shareBody, style = CanonCaption, color = CanonMuted)
                        }
                    }
                }
                // Зимний протокол: мягче SOS, но настоящий. Четыре часа трассы Сибай–Уфа
                // в метель — это как раз такси, а не попутка.
                RoadsideHelpAction(key = orderId) { lat, lng ->
                    ApiClient.instantRoadsideHelp(orderId, lat, lng)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(closeLabel, style = CanonButton, color = CanonGreen2)
            }
        },
    )
}

/**
 * Тонкая полоска «Ильдар едет · 3 мин» поверх приложения.
 *
 * Появляется, когда поездка идёт, а человек ушёл с её экрана — в чат, в профиль, в попутку.
 * Смысл именно в этом: за четыре часа трассы Сибай–Уфа он туда точно уйдёт, и терять из виду
 * машину при этом не должен. Тап возвращает обратно.
 *
 * Здесь же живёт наблюдатель: пока сигнал активной поездки не погашен, раз в пятнадцать секунд
 * спрашиваем сервер, не закончилась ли она. Опрос идёт ТОЛЬКО во время поездки — в остальное
 * время компонент не делает ни одного запроса.
 */
@Composable
internal fun ActiveTripBar(onOpen: () -> Unit) {
    val tripId = NavSignals.activeTaxiTrip.value
    val onScreen = NavSignals.taxiTripOnScreen.value
    var order by remember(tripId) { mutableStateOf<InstantOrderDto?>(null) }

    // Через RepeatWhileVisible, а не голым LaunchedEffect: цикл обязан засыпать вместе
    // с приложением. Иначе телефон ходит в сеть каждые пятнадцать секунд у человека,
    // который на экран не смотрит, — за этим следит PollingSourceGuardTest.
    RepeatWhileVisible(tripId) {
        if (tripId == 0) {
            order = null
            return@RepeatWhileVisible
        }
        while (true) {
            val fresh = ApiClient.getInstantOrder(tripId).getOrNull()
            if (fresh != null) {
                order = fresh
                if (fresh.isTerminal) {
                    NavSignals.activeTaxiTrip.value = 0
                    return@RepeatWhileVisible
                }
            }
            // Сервер не ответил — поездку по одной неудаче не гасим: человек в машине,
            // а связь в дороге рвётся постоянно. Просто пробуем ещё раз.
            kotlinx.coroutines.delay(15_000)
        }
    }

    val o = order
    AnimatedVisibility(
        visible = tripId != 0 && !onScreen && o != null,
        enter = fadeIn(tween(CanonMotion.NORMAL)) + slideInVertically(tween(CanonMotion.NORMAL)) { -it },
        exit = fadeOut(tween(CanonMotion.QUICK)),
    ) {
        if (o == null) return@AnimatedVisibility
        val whoDefault = appText("Водитель", "Йөрөтөүсе")
        val phase = when (o.status) {
            "arriving" -> appText("на месте", "урынында")
            "onboard" -> appText("в пути", "юлда")
            else -> appText("едет", "килә")
        }
        val who = o.driverName.ifBlank { whoDefault }
        val eta = if (o.etaMin > 0 && o.status != "onboard") "  ·  ${o.etaMin.toInt()} " + appText("мин", "мин") else ""
        Surface(
            onClick = onOpen,
            color = CanonGreen2,
            shape = CanonFieldShape,
            shadowElevation = CanonDepth.raised,
            modifier = Modifier.fillMaxWidth().padding(horizontal = CanonSpace.lg, vertical = CanonSpace.sm),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = CanonSpace.md, vertical = CanonSpace.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
            ) {
                Text("$who $phase$eta", style = CanonBodyStrong, color = CanonOnAccent,
                     maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(appText("Открыть", "Асырға"), style = CanonCaption, color = CanonOnAccent)
            }
        }
    }
}

/**
 * «42 поездки», а не «42 поездок».
 *
 * Мелочь, по которой отличают своё приложение от переведённого машиной: русское числительное
 * согласуется с существительным, и человек это слышит, даже когда не читает вслух.
 * В башкирском счётное слово после числа не меняется — там форма одна.
 */
@Composable
private fun tripsPhrase(n: Int): String {
    val ru = when {
        n % 100 in 11..14 -> "поездок"
        n % 10 == 1 -> "поездка"
        n % 10 in 2..4 -> "поездки"
        else -> "поездок"
    }
    return "$n " + appText(ru, "сәфәр")
}

/**
 * «Светлее» — вернуть светлый вид ночью.
 *
 * Тёмный экран ночью правильнее почти для всех, но не для всех: при некоторых проблемах
 * со зрением светлый текст на тёмном читается хуже, а не лучше. Поэтому автоматика решает
 * за человека только по умолчанию, а последнее слово оставляет ему.
 */
@Composable
private fun TripLightSwitch(lightNow: Boolean, onToggle: () -> Unit) {
    val label = if (lightNow) appText("Ночной вид", "Төнгө күренеш")
                else appText("Светлее", "Яҡтыраҡ")
    val hint = if (lightNow) appText("Вернуть тёмный экран", "Ҡараңғы экранға ҡайтыу")
               else appText("Если тёмный экран читать труднее", "Ҡараңғы экранды уҡыуы ауырыраҡ булһа")
    Surface(
        onClick = onToggle,
        color = CanonBg,
        shape = CanonItemShape,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CanonSpace.md),
        ) {
            Icon(
                if (lightNow) Icons.Default.DarkMode else Icons.Default.LightMode,
                contentDescription = null,
                tint = CanonGreen2,
                modifier = Modifier.size(20.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(label, style = CanonBodyStrong, color = CanonText)
                Text(hint, style = CanonCaption, color = CanonMuted)
            }
        }
    }
}

/** «Вернуть карту» — снова показать весь маршрут, если увёл вид пальцем. */
@Composable
private fun TripRecenterButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = appText("Показать маршрут", "Юлды күрһәтеү")
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = CanonSurface,
        shadowElevation = CanonDepth.raised,
        modifier = modifier.minimumInteractiveComponentSize().size(48.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.MyLocation,
                contentDescription = label,
                tint = CanonGreen2,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
