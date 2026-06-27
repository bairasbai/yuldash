package com.yuldash.app

// Экран «Создать поездку» (водитель публикует рейс). Вынесено из MainActivity (Фаза 2).
// Импорты скопированы целиком — лишние = варнинги.

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Woman
import androidx.compose.material.icons.filled.SmokingRooms
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.AddRoad
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneLocked
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.QuestionMark
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import android.Manifest
import android.app.Activity
import android.speech.RecognizerIntent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.Context
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.view.MotionEvent
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.viewinterop.AndroidView
import com.yandex.mapkit.MapKitFactory
import com.yandex.mapkit.geometry.Circle
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.geometry.Polyline
import com.yandex.mapkit.map.CameraPosition
import com.yandex.mapkit.Animation
import com.yandex.mapkit.map.CameraListener
import com.yandex.mapkit.map.CameraUpdateReason
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.NearMe
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.map.MapObjectTapListener
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.MessageDto
import com.yuldash.app.data.GeocoderClient
import com.yuldash.app.data.GeoHit
import com.yuldash.app.data.ConversationDto
import com.yuldash.app.data.PopularRouteDto
import com.yuldash.app.data.FeedDto
import com.yuldash.app.data.RequestDto
import com.yuldash.app.data.NotifDto
import com.yuldash.app.data.AdDto
import com.yuldash.app.ui.theme.YuldashTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateRideScreen(onBack: () -> Unit, onPublish: (Ride) -> Unit) {
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var dateTime by remember { mutableStateOf("") }
    var seats by remember { mutableStateOf("2") }
    var price by remember { mutableStateOf("300") }
    var comment by remember { mutableStateOf("") }
    var petsAllowed by remember { mutableStateOf(false) }
    var childSeat by remember { mutableStateOf(false) }
    var womenOnly by remember { mutableStateOf(false) }
    var smoking by remember { mutableStateOf(false) }
    var baggage by remember { mutableStateOf(false) }
    var airConditioner by remember { mutableStateOf(false) }
    var recurrence by remember { mutableStateOf("none") }
    var category by remember { mutableStateOf("regular") }
    var pickup by remember { mutableStateOf("") }
    var pickupLat by remember { mutableStateOf<Double?>(null) }
    var pickupLng by remember { mutableStateOf<Double?>(null) }
    var showPicker by remember { mutableStateOf(false) }
    var priceHint by remember { mutableStateOf(0) }
    var publishing by remember { mutableStateOf(false) }   // ждём ответ сервера, блок двойного нажатия
    var publishError by remember { mutableStateOf<String?>(null) }
    val publishScope = rememberCoroutineScope()
    LaunchedEffect(from, to) {
        priceHint = if (from.isNotBlank() && to.isNotBlank()) {
            delay(450)
            ApiClient.getPriceHint(from.trim(), to.trim()).getOrNull()?.takeIf { it.count > 0 }?.avg ?: 0
        } else 0
    }
    val defaultTime = appText("Сегодня, 18:00", "Бөгөн, 18:00")
    val defaultCar = appText("Моя машина", "Минең машина")
    val dropPinLabel = appText("Точка на карте", "Картала нөктә")
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { ScreenTopBar(appText("Создать поездку", "Сәфәр булдырыу"), onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(appText("Маршрут для своих", "Үҙ кешеләрең өсөн маршрут"), fontSize = 24.sp, fontWeight = FontWeight.Black)
                Text(appText("Укажите путь, места и цену. Контакты откроются после подтверждения.", "Юлды, урындарҙы һәм хаҡты күрһәтегеҙ. Контакттар раҫланғандан һуң асыла."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { AddressSuggestField(from, { from = it }, appText("Откуда", "Ҡайҙан"), Icons.Default.LocationOn) }
            item { AddressSuggestField(to, { to = it }, appText("Куда", "Ҡайҙа"), Icons.Default.NearMe) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(appText("Тип поездки", "Сәфәр төрө"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    val rideTypeKeys = remember { listOf("regular", "parcel", "cargo", "urgent") }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(rideTypeKeys, key = { it }) { key ->
                            val (icon, ru, ba) = rideTypeMeta(key)
                            FilledTonalButton(
                                onClick = { category = key },
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (category == key) CanonMint else CanonSurface,
                                    contentColor = if (category == key) CanonGreen2 else CanonText
                                )
                            ) {
                                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(appText(ru, ba), fontSize = 13.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
            item {
                val ctxDt = LocalContext.current
                Box {
                    OutlinedTextField(
                        value = dateTime,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(appText("Дата и время", "Дата һәм ваҡыт")) },
                        placeholder = { Text(appText("Выберите дату и время", "Дата һәм ваҡыт һайлағыҙ")) },
                        leadingIcon = { Icon(Icons.Default.Schedule, null) },
                        trailingIcon = { Icon(Icons.Default.CalendarMonth, contentDescription = appText("Выбрать дату", "Дата һайлау"), tint = CanonGreen2) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    )
                    Box(Modifier.matchParentSize().clickable { openDateTimePicker(ctxDt, "ru") { dateTime = it } })
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(appText("Повтор", "Ҡабатлау"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    val recOpts = listOf(
                        "none" to appText("Разово", "Бер тапҡыр"),
                        "weekdays" to appText("По будням", "Эш көндәрендә"),
                        "daily" to appText("Каждый день", "Һәр көн"),
                        "weekly" to appText("Еженедельно", "Аҙна һайын"),
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(recOpts, key = { it.first }) { (key, label) ->
                            FilledTonalButton(
                                onClick = { recurrence = key },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (recurrence == key) CanonMint else CanonSurface,
                                    contentColor = if (recurrence == key) CanonGreen2 else CanonText
                                )
                            ) { Text(label, fontSize = 13.sp, maxLines = 1) }
                        }
                    }
                    if (recurrence != "none") Text(appText("Создадим ближайшие 4 рейса этой серии.", "Был серияның иң яҡын 4 рейсын булдырабыҙ."), color = CanonMuted, fontSize = 12.sp)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = seats, onValueChange = { seats = it.filter(Char::isDigit) }, label = { Text(appText("Мест", "Урын")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp))
                    OutlinedTextField(value = price, onValueChange = { price = it.filter(Char::isDigit) }, label = { Text(appText("Цена, ₽", "Хаҡ, ₽")) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp))
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (priceHint > 0) {
                        Surface(
                            color = CanonMint, shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.clickable { price = priceHint.toString() }
                        ) {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.TrendingUp, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(appText("Обычно по маршруту ~$priceHint ₽ · нажми, чтобы подставить", "Был юл буйынса ғәҙәттә ~$priceHint ₽ · ҡуйыр өсөн баҫ"), color = CanonGreen2, fontSize = 12.sp, lineHeight = 16.sp)
                            }
                        }
                    }
                    Text(appText("Цену ставишь ты. Оплата — напрямую тебе после поездки. Юлдаш комиссию не берёт.", "Хаҡты үҙең ҡуяһың. Түләү — сәфәрҙән һуң тура һиңә. Юлдаш комиссия алмай."), color = CanonMuted, fontSize = 12.sp, lineHeight = 16.sp)
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pickup,
                        onValueChange = { pickup = it },
                        label = { Text(appText("Где встречаемся", "Ҡайҙа осрашабыҙ")) },
                        placeholder = { Text(appText("Напр.: у автовокзала, АЗС на выезде", "Мәҫәлән: автовокзал янында, сығыштағы АЗС")) },
                        leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    )
                    val pinned = pickupLat != null
                    OutlinedButton(
                        onClick = { showPicker = true },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, if (pinned) CanonGreen2 else CanonBorder)
                    ) {
                        Icon(if (pinned) Icons.Default.CheckCircle else Icons.Default.Map, contentDescription = null, tint = if (pinned) CanonGreen2 else CanonText, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (pinned) appText("Точка на карте отмечена · изменить", "Картала билдәләнде · үҙгәртергә") else appText("Отметить на карте", "Картала билдәләргә"), color = if (pinned) CanonGreen2 else CanonText)
                    }
                }
            }
            item {
                val isCargo = category == "parcel" || category == "cargo"
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(if (isCargo) appText("Что везёте", "Нимә алып бараһығыҙ") else appText("Комментарий", "Комментарий")) },
                    placeholder = { Text(if (isCargo) appText("Напр.: диван и 2 коробки, хрупкое", "Мәҫәлән: диван һәм 2 ҡумта, һынғыс") else appText("Например: могу взять посылку, заеду через Темясово", "Мәҫәлән: посылка ала алам, Темясово аша инәм")) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                )
            }
            item {
                Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(appText("Условия поездки", "Сәфәр шарттары"), modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), fontWeight = FontWeight.Black, color = CanonText, fontSize = 16.sp)
                        PrefToggleRow(Icons.Default.Woman, appText("Только женщины", "Тик ҡатын-ҡыҙ өсөн"), womenOnly) { womenOnly = it }
                        PrefToggleRow(Icons.Default.ChildCare, appText("Детское кресло / бустер", "Балалар ултырғысы / бустер"), childSeat) { childSeat = it }
                        PrefToggleRow(Icons.Default.Pets, appText("Можно с животным", "Хайуан менән"), petsAllowed) { petsAllowed = it }
                        PrefToggleRow(Icons.Default.Luggage, appText("Есть место под багаж", "Багаж урыны бар"), baggage) { baggage = it }
                        PrefToggleRow(Icons.Default.AcUnit, appText("Кондиционер", "Кондиционер"), airConditioner) { airConditioner = it }
                        PrefToggleRow(Icons.Default.SmokingRooms, appText("Можно курить", "Тартырға ярай"), smoking) { smoking = it }
                    }
                }
            }
            item {
                InfoCard(
                    title = appText("Платное поднятие", "Түләүле күтәреү"),
                    text = appText("Можно добавить после публикации. Обычные поездки остаются бесплатными.", "Баҫтырғандан һуң өҫтәп була. Ғәҙәти сәфәрҙәр бушлай ҡала."),
                    icon = Icons.Default.TrendingUp
                )
            }
            item {
                val errPublish = appText("Не удалось опубликовать. Проверь сеть и повтори.", "Баҫтырып булманы. Селтәрҙе тикшереп ҡабатла.")
                publishError?.let {
                    Text(it, color = CanonRed, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.padding(bottom = 8.dp))
                }
                Button(
                    onClick = {
                        if (publishing) return@Button
                        val fromVal = from.ifBlank { "Баймаҡ" }
                        val toVal = to.ifBlank { "Сибай" }
                        val priceVal = price.toIntOrNull() ?: 300
                        val seatsVal = seats.toIntOrNull() ?: 2
                        // Берём выбранную дату из пикера ("dd.MM.yyyy, HH:mm"); если пусто/не распарсилось — now+3ч.
                        val isoFmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                        val departIso = runCatching {
                            val picked = java.text.SimpleDateFormat("dd.MM.yyyy, HH:mm", java.util.Locale.US).parse(dateTime)
                            isoFmt.format(picked!!)
                        }.getOrElse { isoFmt.format(java.util.Date(System.currentTimeMillis() + 3 * 3600_000L)) }
                        val ride = Ride(
                            id = "local-${System.currentTimeMillis()}",
                            from = fromVal, to = toVal,
                            time = dateTime.ifBlank { defaultTime }, timeBa = dateTime.ifBlank { defaultTime },
                            driver = ApiClient.cachedName() ?: "Я",
                            car = comment.ifBlank { defaultCar }, carBa = comment.ifBlank { defaultCar },
                            price = priceVal, seats = seatsVal, rating = 5.0, verified = false, boosted = false,
                            petsAllowed = petsAllowed, childSeat = childSeat, womenOnly = womenOnly,
                            smoking = smoking, baggage = baggage, airConditioner = airConditioner,
                        )
                        publishError = null
                        publishing = true
                        // Ждём ответ сервера: успех → навигация, ошибка → сообщение (не уходим, не теряем ввод).
                        publishScope.launch {
                            ApiClient.publishRide(fromVal, toVal, departIso, seatsVal, priceVal, comment.trim(), petsAllowed, childSeat, womenOnly, smoking, baggage, airConditioner, recurrence, category, pickup.trim(), pickupLat, pickupLng)
                                .onSuccess { publishing = false; onPublish(ride) }
                                .onFailure { publishing = false; publishError = errPublish }
                        }
                    },
                    enabled = !publishing,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (publishing) CircularProgressIndicator(modifier = Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    else Text(appText("Опубликовать", "Баҫтырыу"))
                }
            }
            item {
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(appText("Отмена", "Кире алыу"))
                }
            }
        }
    }
        if (showPicker) {
            PickupPickerOverlay(
                initial = pickupLat?.let { la -> pickupLng?.let { ln -> Point(la, ln) } },
                onConfirm = { la, ln -> pickupLat = la; pickupLng = ln; if (pickup.isBlank()) pickup = dropPinLabel; showPicker = false },
                onDismiss = { showPicker = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivacyScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        LocationPrefs.sharingEnabled = granted
    }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Конфиденциальность", "Хосусилыҡ"), onBack) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(Modifier.height(6.dp))
            Text(appText("Управляй тем, что видят другие", "Башҡалар нимә күрә — үҙең хәл ит"), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp)
            Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonCardShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = CanonGreen2)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(appText("Моя геолокация", "Минең геолокация"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(appText("Показывать мою точку на карте", "Картала минең нөктәне күрһәтеү"), color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
                    }
                    Spacer(Modifier.width(10.dp))
                    Switch(
                        checked = LocationPrefs.sharingEnabled,
                        onCheckedChange = { on ->
                            when {
                                !on -> LocationPrefs.sharingEnabled = false
                                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED -> LocationPrefs.sharingEnabled = true
                                else -> launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        }
                    )
                }
            }
            InfoCard(
                title = appText("Геолокация скрыта по умолчанию", "Геолокация башта йәшерелгән"),
                text = appText("Точка видна только когда ползунок включён. Точный адрес — лишь после подтверждения поездки.", "Нөктә ползунок ҡабул булғанда ғына күренә. Теүәл адрес — сәфәр раҫланғандан һуң ғына."),
                icon = Icons.Default.Lock
            )
        }
    }
}
