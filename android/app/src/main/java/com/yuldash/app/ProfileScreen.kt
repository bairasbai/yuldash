package com.yuldash.app

// Профиль + кабинет рекламы. Вынесено из MainActivity (Фаза 2). Импорты целиком — лишние = варнинги.

import com.yuldash.app.R
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateIntAsState
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Woman
import androidx.compose.material.icons.filled.SmokingRooms
import androidx.compose.material.icons.filled.Luggage
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Autorenew
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
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.ui.draw.clip
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
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Bookmark
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
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.ReceiptLong
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
// Платформенный ExifInterface, а не androidx: конструктор от InputStream есть с API 24,
// minSdk у нас 26 — значит новая зависимость не нужна (CLAUDE.md §10).
import android.media.ExifInterface
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
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Canvas
// decodeToJpeg: ImageDecoder сам применяет EXIF-поворот (API 28+), Matrix доворачивает вручную
// на более старых. Без этих двух импортов файл не компилировался — сборка CI, 2026-07-30.
import android.graphics.ImageDecoder
import android.graphics.Matrix
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
import com.yandex.mapkit.map.IconStyle
import com.yandex.mapkit.map.MapObjectTapListener
import com.yandex.mapkit.mapview.MapView
import com.yandex.runtime.image.ImageProvider
import com.yuldash.app.data.DriverScheduleDto
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material.icons.filled.Storefront
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.MyAdDto
import com.yuldash.app.data.MyAdStatsDto
import com.yuldash.app.data.AdPackageDto
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

/**
 * Человекочитаемая роль пользователя (с сервера: passenger/driver/admin) на двух языках.
 * Пустая/неизвестная роль → «Пассажир» (роль по умолчанию на бэкенде). Города в профиле нет —
 * подписи вида «Пассажир · Баймаҡ» были захардкожены у всех, теперь показываем только настоящую роль.
 */
@Composable
internal fun roleLabel(role: String): String = when (role) {
    "driver" -> appText("Водитель", "Йөрөтөүсе")
    "admin" -> appText("Администратор", "Администратор")
    else -> appText("Пассажир", "Пассажир")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProfileScreen(
    ads: List<PartnerAd>,
    adStats: Map<String, AdStats>,
    onSupport: () -> Unit,
    onVerifyDriver: () -> Unit,
    onSafety: () -> Unit,
    onSettings: () -> Unit,
    onPrivacy: () -> Unit,
    onTrust: () -> Unit,
    // «Центр справедливости» — вход в двусторонний разбор споров. С дефолтом: старые вызовы
    // (в т.ч. тесты Content) собираются без правок.
    onFairness: () -> Unit = {},
    onConsents: () -> Unit,
    onHelp: () -> Unit,
    onReview: () -> Unit,
    onAdminReviews: () -> Unit,
    onAdminAds: () -> Unit,
    onPassengerCabinet: () -> Unit,
    onDriverCabinet: () -> Unit,
    onSimpleMode: () -> Unit,
    onTrustedContacts: () -> Unit,
    onCallbackHelp: () -> Unit,
    onAdsCabinet: () -> Unit,
    onMyStats: () -> Unit = {},
    onCoupons: () -> Unit = {},
    onPromo: () -> Unit = {},
    onParcels: () -> Unit = {},
    onCourier: () -> Unit = {},
    onPartnerCabinet: () -> Unit = {},
    onToggleLanguage: () -> Unit,
    onAccountDeleted: () -> Unit,
    onAdImpression: (PartnerAd) -> Unit,
    onAdClick: (PartnerAd) -> Unit
) {
    val isBashkir = LocalAppLanguage.current == AppLanguage.Ba
    // Реклама профиля: у пользователя нет города в данных → не фильтруем по хардкод-городу
    // (иначе объявление показывалось лишь при совпадении с «Баймаҡ» — у всех остальных пусто). Берём любое.
    val profileAd = ads.forPlacement(AdPlacement.Profile).firstOrNull()
    // Свой рейтинг (как пассажира) — из реальных оценок водителей. null, пока никто не оценил.
    var myRating by remember { mutableStateOf<Double?>(null) }
    var displayName by remember { mutableStateOf(ApiClient.cachedName() ?: (if (isBashkir) "Мин" else "Я")) }
    var avatarUrl by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }   // родной город: показываем в шапке, редактируется тапом
    var profileConfirmed by remember { mutableStateOf(ApiClient.isLoggedIn()) }
    // Три честных состояния шапки: грузим / пришло / сеть упала. Раньше сбой /me был немым —
    // человек видел кэшированное имя и не понимал, что данные устарели и что делать.
    var meLoading by remember { mutableStateOf(true) }
    var meError by remember { mutableStateOf(false) }
    var meReload by remember { mutableIntStateOf(0) }
    LaunchedEffect(meReload) {
        meLoading = true
        ApiClient.me()
            .onSuccess { o ->
                meError = false
                profileConfirmed = true
                myRating = if (o.isNull("rating")) null else o.optDouble("rating")
                o.optString("name").takeIf { it.isNotBlank() }?.let { displayName = it }
                o.optString("avatar_url").takeIf { it.isNotBlank() }?.let { avatarUrl = it }
                role = o.optString("role")
                city = o.optString("city")
            }
            // Сбой /me: сеть упала (таймаут/нет связи) — НЕ роняем залогиненного в «демо».
            // Не подтверждён только если реально не вошёл ИЛИ токен отвергнут (401).
            .onFailure { e ->
                profileConfirmed = ApiClient.isLoggedIn() && (e as? com.yuldash.app.data.ApiException)?.status != 401
                // 401 — это «не вошёл» (обычный демо-режим), а не сбой сети: ошибку не показываем.
                meError = ApiClient.isLoggedIn() && (e as? com.yuldash.app.data.ApiException)?.status != 401
            }
        meLoading = false
    }
    val editCtx = LocalContext.current
    val editScope = rememberCoroutineScope()
    val avatarSavedMsg = appText("Фото обновлено", "Фото яңыртылды")
    val saveErrMsg = appText("Не удалось сохранить. Проверь сеть.", "Һаҡлап булманы. Селтәрҙе тикшерегеҙ.")
    // Реферал «позови своего»: код, бонусы, ввод кода друга.
    var referral by remember { mutableStateOf<com.yuldash.app.data.ReferralDto?>(null) }
    var referralReload by remember { mutableStateOf(0) }
    LaunchedEffect(referralReload) { ApiClient.getReferral().onSuccess { referral = it } }
    var showRedeem by remember { mutableStateOf(false) }
    var redeemCode by remember { mutableStateOf("") }
    val redeemOkMsg = appText("Бонус начислен — вам и другу", "Бонус яҙылды — һеҙгә һәм дуҫҡа")
    val redeemErrMsg = appText("Код не подошёл", "Код тура килмәне")
    if (showRedeem) {
        AlertDialog(
            onDismissRequest = { showRedeem = false },
            containerColor = CanonSurface,
            title = { Text(appText("Код друга", "Дуҫ коды"), color = CanonText, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(appText("Введи код того, кто тебя позвал. Бонус получите оба.", "Һине саҡырған кешенең кодын индер. Бонусты икәүегеҙ ҙә алырһығыҙ."), color = CanonMuted, fontSize = 13.sp)
                    OutlinedTextField(redeemCode, { redeemCode = it.uppercase().take(12) }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("ABC123") }, shape = RoundedCornerShape(14.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = redeemCode.isNotBlank(), onClick = {
                    val c = redeemCode.trim()
                    editScope.launch {
                        ApiClient.redeemReferral(c)
                            .onSuccess { showRedeem = false; redeemCode = ""; referralReload++; Toast.makeText(editCtx, redeemOkMsg, Toast.LENGTH_SHORT).show() }
                            // Серверная причина (свой код / уже активирован / нет такого) информативнее общего «код не подошёл».
                            .onFailure { e -> Toast.makeText(editCtx, (e as? com.yuldash.app.data.ApiException)?.message ?: redeemErrMsg, Toast.LENGTH_LONG).show() }
                    }
                }) { Text(appText("Применить", "Ҡулланыу"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showRedeem = false }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) } },
        )
    }
    // Загрузка фото занимает секунды на слабой сети — показываем это прямо на аватаре,
    // иначе тап выглядит как «ничего не произошло» и человек жмёт ещё раз.
    var avatarUploading by remember { mutableStateOf(false) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u ->
            editScope.launch {
                avatarUploading = true
                // Перекодируем в компактный JPEG на IO-потоке: нормализует формат (png/webp/heic → jpg,
                // иначе сервер отвергал не-jpeg) и уменьшает тяжёлое фото до размера аватара.
                val bytes = withContext(Dispatchers.IO) { decodeToJpeg(editCtx, u) }
                if (bytes == null) { avatarUploading = false; Toast.makeText(editCtx, saveErrMsg, Toast.LENGTH_SHORT).show(); return@launch }
                ApiClient.uploadChatPhoto(bytes)
                    .onSuccess { url ->
                        ApiClient.updateAvatar(url)
                            .onSuccess { avatarUrl = url; Toast.makeText(editCtx, avatarSavedMsg, Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(editCtx, saveErrMsg, Toast.LENGTH_SHORT).show() }
                    }
                    .onFailure { Toast.makeText(editCtx, saveErrMsg, Toast.LENGTH_SHORT).show() }
                avatarUploading = false
            }
        }
    }
    var showEditName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf(displayName) }
    val nameSavedMsg = appText("Имя обновлено", "Исем яңыртылды")
    if (showEditName) {
        AlertDialog(
            onDismissRequest = { showEditName = false },
            containerColor = CanonSurface,
            title = { Text(appText("Ваше имя", "Исемегеҙ"), color = CanonText, fontWeight = FontWeight.Black) },
            text = { OutlinedTextField(nameDraft, { nameDraft = it.take(120) }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text(appText("Как вас зовут?", "Исемегеҙ нисек?")) }, shape = RoundedCornerShape(14.dp)) },
            confirmButton = {
                TextButton(onClick = {
                    val n = nameDraft.trim()
                    if (n.isNotBlank()) {
                        editScope.launch {
                            ApiClient.updateName(n)
                                .onSuccess { displayName = n; Toast.makeText(editCtx, nameSavedMsg, Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(editCtx, saveErrMsg, Toast.LENGTH_SHORT).show() }
                        }
                        showEditName = false
                    }
                }) { Text(appText("Сохранить", "Һаҡлау"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showEditName = false }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) } },
        )
    }
    // Родной город: свободный ввод + подсказки из справочника населённых пунктов.
    // Нужен, чтобы «Скидки по пути» и посылки сразу показывали «в моём городе».
    var showEditCity by remember { mutableStateOf(false) }
    var cityDraft by remember { mutableStateOf(city) }
    var cityPicked by remember { mutableStateOf(true) }   // предзаполненный город не подсказываем
    var cityHits by remember { mutableStateOf<List<com.yuldash.app.data.SettlementDto>>(emptyList()) }
    val citySavedMsg = appText("Город обновлён", "Ҡала яңыртылды")
    LaunchedEffect(cityDraft, showEditCity) {
        if (!showEditCity) return@LaunchedEffect
        if (cityPicked) { cityPicked = false; return@LaunchedEffect }
        if (cityDraft.isBlank()) { cityHits = emptyList(); return@LaunchedEffect }
        delay(250)
        ApiClient.searchSettlements(cityDraft, 5).onSuccess { cityHits = it }.onFailure { cityHits = emptyList() }
    }
    if (showEditCity) {
        AlertDialog(
            onDismissRequest = { showEditCity = false },
            containerColor = CanonSurface,
            title = { Text(appText("Мой город", "Минең ҡалам"), color = CanonText, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(appText("Покажем скидки и посылки рядом с тобой.", "Яныңдағы ташламаларҙы һәм тапшырыуҙарҙы күрһәтербеҙ."), color = CanonMuted, fontSize = 13.sp)
                    OutlinedTextField(
                        cityDraft, { cityDraft = it.take(80) }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(appText("Например, Сибай", "Мәҫәлән, Сибай")) },
                        shape = RoundedCornerShape(14.dp),
                    )
                    AnimatedVisibility(cityHits.isNotEmpty()) {
                        Column {
                            cityHits.forEach { s ->
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { cityPicked = true; cityDraft = s.nameRu; cityHits = emptyList() }
                                        .heightIn(min = 48.dp)
                                        .padding(horizontal = 8.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(settlementTitle(s), color = CanonText, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(s.region, color = CanonMuted, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val c = cityDraft.trim()
                    editScope.launch {
                        ApiClient.updateCity(c)
                            .onSuccess { city = c; Toast.makeText(editCtx, citySavedMsg, Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(editCtx, saveErrMsg, Toast.LENGTH_SHORT).show() }
                    }
                    showEditCity = false
                }) { Text(appText("Сохранить", "Һаҡлау"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showEditCity = false }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) } },
        )
    }
    // Удаление аккаунта (необратимо): подтверждение + лоадер + ошибка. Стирает данные и на сервере.
    var showDeleteAccount by remember { mutableStateOf(false) }
    var deletingAccount by remember { mutableStateOf(false) }
    val deleteOkMsg = appText("Аккаунт удалён", "Иҫәп бөтөрөлдө")
    val deleteErrMsg = appText("Не удалось удалить. Проверь сеть и попробуй снова.", "Бөтөрөп булманы. Селтәрҙе тикшереп ҡабатла.")
    if (showDeleteAccount) {
        AlertDialog(
            onDismissRequest = { if (!deletingAccount) showDeleteAccount = false },
            containerColor = CanonSurface,
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = CanonRed) },
            title = { Text(appText("Удалить аккаунт?", "Иҫәпте бөтөрәһегеҙме?"), color = CanonText, fontWeight = FontWeight.Black) },
            text = {
                Text(
                    appText(
                        "Это навсегда удалит твой профиль, поездки, заявки, брони, сообщения и рейтинг с наших серверов. Отменить нельзя.",
                        "Был һинең профилде, сәфәрҙәрҙе, заявкаларҙы, брондәрҙе, хәбәрҙәрҙе һәм рейтингты серверҙарҙан бөтөнләй бөтөрә. Кире ҡайтарып булмай.",
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !deletingAccount,
                    onClick = {
                        deletingAccount = true
                        editScope.launch {
                            ApiClient.deleteAccount()
                                .onSuccess {
                                    Toast.makeText(editCtx, deleteOkMsg, Toast.LENGTH_SHORT).show()
                                    showDeleteAccount = false
                                    deletingAccount = false
                                    onAccountDeleted()
                                }
                                .onFailure {
                                    deletingAccount = false
                                    Toast.makeText(editCtx, deleteErrMsg, Toast.LENGTH_LONG).show()
                                }
                        }
                    },
                ) {
                    if (deletingAccount) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = CanonRed, strokeWidth = 2.dp)
                    } else {
                        Text(appText("Удалить навсегда", "Мәңгегә бөтөрөү"), color = CanonRed, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = { TextButton(enabled = !deletingAccount, onClick = { showDeleteAccount = false }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) } },
        )
    }
    Scaffold(containerColor = CanonBg) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Spacer(Modifier.height(2.dp)) }
            item { Text(appText("Профиль", "Профиль"), color = CanonGreen, fontSize = 29.sp, lineHeight = 31.sp, fontWeight = FontWeight.Black) }
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                    shape = CanonCardShape,
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .background(
                                // Фикс тёмная тема: CanonGreen/CanonGreen2 инвертируются в светлую мяту →
                                // белый текст шапки становился нечитаем. Ink-зелёные фиксированы в обеих темах.
                                Brush.linearGradient(listOf(CanonGreenInk, CanonGreenInkDark)),
                                CanonCardShape
                            )
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(13.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(70.dp)
                                    .background(Color.White.copy(alpha = 0.18f), CircleShape)
                                    .bounceClick { if (!avatarUploading) avatarPicker.launch("image/*") },
                                contentAlignment = Alignment.Center
                            ) {
                                if (avatarUrl.isBlank()) {
                                    Text(displayName.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Black, fontSize = 28.sp)
                                } else {
                                    coil.compose.AsyncImage(
                                        model = avatarUrl,
                                        contentDescription = appText("Фото профиля", "Профиль фотоһы"),
                                        modifier = Modifier.size(70.dp).clip(CircleShape),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                                // Фото уходит на сервер — честно показываем это на самом аватаре.
                                AvatarUploadOverlay(uploading = avatarUploading)
                                // BA-draft
                                Icon(Icons.Default.Edit, contentDescription = appText("Изменить фото", "Фотоны үҙгәртеү"), tint = Color.White, modifier = Modifier.size(15.dp).align(Alignment.BottomEnd))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        displayName, color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp,
                                        lineHeight = 26.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    // Тач-цель = сам Box 48dp: раньше minimumInteractiveComponentSize() стоял
                                    // ПЕРЕД clickable и раздвигал раскладку вокруг 18dp-области, а не её саму.
                                    Box(
                                        modifier = Modifier.size(48.dp).bounceClick { nameDraft = displayName; showEditName = true },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = appText("Изменить имя", "Исемде үҙгәртеү"), tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Настоящая роль с сервера (пассажир/водитель/админ).
                                    Text(roleLabel(role), color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp)
                                    myRating?.let { r ->
                                        Spacer(Modifier.width(8.dp))
                                        Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text(String.format(java.util.Locale.US, "%.1f", r), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                // Родной город — тап открывает редактирование (48dp тач-цель).
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .bounceClick { cityDraft = city; cityPicked = true; cityHits = emptyList(); showEditCity = true }
                                ) {
                                    Icon(Icons.Default.LocationOn, contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (city.isBlank()) appText("Указать город", "Ҡаланы күрһәтергә") else city,
                                        color = Color.White.copy(alpha = if (city.isBlank()) 0.78f else 0.95f),
                                        fontSize = 13.sp,
                                        fontWeight = if (city.isBlank()) FontWeight.Normal else FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Icon(Icons.Default.Edit, contentDescription = appText("Изменить город", "Ҡаланы үҙгәртеү"), tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
                                }
                                Text(appText("Телефон скрыт до подтверждения поездки", "Телефон сәфәр раҫланғанға тиклем йәшерелгән"), color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp, lineHeight = 16.sp)
                            }
                        }
                        ProfileStatusPill(
                            loading = meLoading,
                            error = meError,
                            confirmed = profileConfirmed,
                            onRetry = { meReload++ },
                        )
                    }
                }
            }
            // Реферала ещё нет (не загрузился/не вошёл) → item вообще не создаём: пустой item
            // всё равно съедал 14dp из spacedBy и оставлял дыру под шапкой.
            referral?.let { ref ->
                item {
                    Box(Modifier.appearIn(0)) {
                        Card(colors = CardDefaults.cardColors(containerColor = CanonMint), shape = CanonCardShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2)
                                    Spacer(Modifier.width(10.dp))
                                    Text(appText("Позови своего", "Үҙеңдекен саҡыр"), color = CanonGreen, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.weight(1f))
                                }
                                Text(appText("Пригласил соседа → вы оба получаете бонус (бесплатное поднятие поездки).", "Күршеңде саҡырҙың → икәүегеҙ ҙә бонус (сәфәрҙе бушлай күтәреү) аласаҡ."), color = CanonGreen2, fontSize = 13.sp, lineHeight = 18.sp)
                                // Три равные колонки вместо жёсткого spacedBy(20): длинные
                                // башкирские подписи больше не выталкивают код за край карточки.
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    ReferralStat(appText("Позвал", "Саҡырҙы"), ref.invited.toString(), Modifier.weight(1f))
                                    ReferralStat(appText("Бонусов", "Бонус"), ref.credits.toString(), Modifier.weight(1f))
                                    ReferralStat(appText("Твой код", "Кодың"), ref.code, Modifier.weight(1f), code = true)
                                }
                                val shareTxt = appText(
                                    "Я в Юлдаше — попутки между своими по Башкортостану. Мой код: ${ref.code}. Введи его в профиле — получим бонусы. Скачать: https://yulbash.ru",
                                    "Мин Юлдашта — Башҡортостан буйлап үҙебеҙ араһында юлдаштар. Кодым: ${ref.code}. Профилдә индер — бонус алырбыҙ. Йөкләргә: https://yulbash.ru"
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        runCatching { editCtx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareTxt), null)) }
                                    }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)) {
                                        Text(appText("Пригласить", "Саҡырыу"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    }
                                    if (!ref.redeemed) {
                                        OutlinedButton(onClick = { showRedeem = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                                            Text(appText("Ввести код", "Код индереү"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                ProfileSectionLabel(appText("Личный кабинет", "Шәхси кабинет"))
            }
            item { Box(Modifier.appearIn(1)) { ProfileActionCard(appText("Мой Юлдаш", "Минең Юлдаш"), appText("Твои километры, поездки и звание", "Километрҙарың, сәфәрҙәрең һәм исемең"), Icons.Default.Insights, onMyStats) } }
            item { Box(Modifier.appearIn(1)) { ProfileActionCard(appText("Скидки по пути", "Юл буйынса ташламалар"), appText("Скидки от местных заведений по маршруту", "Маршрут буйынса ерле урындарҙан ташлама"), Icons.Default.LocalOffer, onCoupons) } }
            item { Box(Modifier.appearIn(1)) { ProfileActionCard(appText("Промокод", "Промокод"), appText("Ввести код друга или акции", "Дуҫ йәки акция кодын индереү"), Icons.Default.Redeem, onPromo) } }
            item { Box(Modifier.appearIn(1)) { ProfileActionCard(appText("Посылки", "Бандеролдәр"), appText("Отправить с попутчиком или подвезти", "Юлдаш менән ебәреү йәки илтеү"), R.drawable.yu_mode_parcel, onParcels) } }
            item { Box(Modifier.appearIn(1)) { ProfileActionCard(appText("Режим курьера", "Курьер режимы"), appText("Возить заказы или стать курьером", "Заказ илтеү йәки курьер булыу"), R.drawable.yu_mode_courier, onCourier) } }
            item { Box(Modifier.appearIn(1)) { ProfileActionCard(appText("Кабинет пассажира", "Пассажир кабинеты"), appText("Мои брони, заявки и безопасность", "Брондәр, заявкалар һәм хәүефһеҙлек"), Icons.Default.EventSeat, onPassengerCabinet) } }
            item { Box(Modifier.appearIn(2)) { ProfileActionCard(appText("Кабинет водителя", "Водитель кабинеты"), appText("Маршруты, проверка и поднятие", "Маршруттар, тикшереү һәм күтәреү"), Icons.Default.DirectionsCar, onDriverCabinet) } }
            item { Box(Modifier.appearIn(3)) { ProfileActionCard(appText("Язык", "Тел"), if (isBashkir) "Башҡортса / Русский" else "Русский / Башҡортса", Icons.Default.Language, onToggleLanguage) } }
            item { Box(Modifier.appearIn(4)) { ProfileActionCard(appText("Проверка водителя", "Водителде тикшереү"), appText("Права, машина, фото авто", "Права, машина, авто фотоһы"), Icons.Default.Verified, onVerifyDriver) } }
            item { Box(Modifier.appearIn(5)) { ProfileActionCard(appText("Доверие", "Ышаныс"), appText("Твой уровень и круг «своих»", "Кимәлең һәм «үҙебеҙҙекеләр» түңәрәге"), Icons.Default.Handshake, onTrust) } }
            item { Box(Modifier.appearIn(6)) { ProfileActionCard(appText("Безопасность", "Хәүефһеҙлек"), appText("SOS, скрытый телефон, подтверждённые участники", "SOS, йәшерен телефон, раҫланған ҡатнашыусылар"), R.drawable.yu_safe_trip, onSafety) } }
            // Разбор споров: обещание «обе стороны слышимы» должно быть достижимо в два тапа,
            // а не жить только на сервере (аудит 2026-07-26).
            item { Box(Modifier.appearIn(7)) { ProfileActionCard(appText("Центр справедливости", "Ғәҙеллек үҙәге"), appText("Спорные ситуации: обе стороны слышимы", "Бәхәсле хәлдәр: ике яҡ та ишетелә"), Icons.Default.Shield, onFairness) } }
            item { Box(Modifier.appearIn(7)) { ProfileActionCard(appText("Поддержать Юлдаш", "Юлдашҡа ярҙам итеү"), appText("Серверы, карты, SMS и поддержка", "Серверҙар, карталар, SMS һәм ярҙам"), Icons.Default.VolunteerActivism, onSupport) } }
            item {
                ProfileSectionLabel(appText("Для родителей и близких", "Ата-әсә һәм яҡындар өсөн"))
            }
            item { Box(Modifier.appearIn(8)) { SeniorAccessCard(onSimpleMode = onSimpleMode) } }
            item { Box(Modifier.appearIn(9)) { ProfileActionCard(appText("Доверенные контакты", "Ышаныслы контакттар"), appText("Кому отправлять статус поездки", "Сәфәр статусын кемгә ебәрергә"), Icons.Default.Person, onTrustedContacts) } }
            item { Box(Modifier.appearIn(10)) { ProfileActionCard(appText("Попросить звонок", "Шылтыратыу һорау"), appText("Помощь без чата и сложных форм", "Чатһыҙ һәм ҡатмарлы формаларһыҙ ярҙам"), R.drawable.yu_support, onCallbackHelp) } }
            item {
                ProfileSectionLabel(appText("Настройки и помощь", "Көйләүҙәр һәм ярҙам"))
            }
            item { Box(Modifier.appearIn(11)) { ProfileActionCard(appText("Настройки", "Көйләүҙәр"), appText("Уведомления, карта, предпочтения", "Хәбәрҙәр, карта, өҫтөнлөктәр"), Icons.Default.Settings, onSettings) } }
            item { Box(Modifier.appearIn(11)) { ProfileActionCard(appText("Конфиденциальность", "Хосусилыҡ"), appText("Геолокация и разрешения", "Геолокация һәм рөхсәттәр"), Icons.Default.Shield, onPrivacy) } }
            item { Box(Modifier.appearIn(11)) { ProfileActionCard(appText("Согласия и данные", "Ризалыҡтар һәм мәғлүмәт"), appText("Оферта, политика, геолокация", "Оферта, сәйәсәт, геолокация"), Icons.Default.Description, onConsents) } }
            item { Box(Modifier.appearIn(12)) { ProfileActionCard(appText("Помощь", "Ярҙам"), appText("Ответы на частые вопросы", "Йыш һорауҙарға яуаптар"), R.drawable.yu_support, onHelp) } }
            item { Box(Modifier.appearIn(12)) { ProfileActionCard(appText("Оставить отзыв", "Фекер ҡалдырыу"), appText("Оцени приложение — лучшие попадут на сайт", "Ҡушымтаны баһала — иң яҡшылары сайтҡа эләгер"), R.drawable.yu_star, onReview) } }
            if (role == "admin") {
                item { Box(Modifier.appearIn(12)) { ProfileActionCard(appText("Модерация отзывов", "Фекерҙәрҙе модерациялау"), appText("Одобрить отзывы для сайта", "Сайт өсөн фекерҙәрҙе раҫларға"), Icons.Default.Verified, onAdminReviews) } }
                item { Box(Modifier.appearIn(12)) { ProfileActionCard(appText("Управление рекламой", "Реклама идаралау"), appText("Объявления партнёров: публикация, пауза, удаление", "Партнёр иғландары: баҫтырыу, пауза, бөтөрөү"), Icons.Default.AdminPanelSettings, onAdminAds) } }
            }
            item {
                ProfileSectionLabel(appText("Партнёры Юлдаш", "Юлдаш партнёрҙары"))
            }
            item { Box(Modifier.appearIn(13)) { ProfileActionCard(appText("Мой бизнес", "Минең бизнес"), appText("Разместить купоны и привлечь клиентов", "Купон ҡуйып клиент йыйыу"), Icons.Default.Storefront, onPartnerCabinet) } }
            item { Box(Modifier.appearIn(13)) { ProfileActionCard(appText("Кабинет рекламы", "Реклама кабинеты"), appText("Объявления, erid, показы и клики", "Иғландар, erid, күрһәтеү һәм баҫыу"), Icons.Default.Payments, onAdsCabinet) } }
            profileAd?.let { ad ->
                item {
                    Box(Modifier.appearIn(12)) {
                        PartnerAdCard(
                            ad = ad,
                            stats = adStats[ad.id] ?: AdStats(),
                            compact = true,
                            label = appText("Городской партнёр", "Ҡала партнёры"),
                            onImpression = onAdImpression,
                            onClick = onAdClick
                        )
                    }
                }
            }
            // Danger zone: удаление аккаунта. Только для залогиненных (в демо нечего удалять).
            if (ApiClient.isLoggedIn()) {
                item {
                    ProfileSectionLabel(appText("Аккаунт", "Иҫәп"))
                }
                item {
                    Box(Modifier.appearIn(14)) {
                        DangerActionCard(
                            title = appText("Удалить аккаунт", "Иҫәпте бөтөрөү"),
                            text = appText("Навсегда удалить профиль и все данные", "Профилде һәм бөтә мәғлүмәтте мәңгегә бөтөрөү"),
                            onClick = { showDeleteAccount = true },
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(92.dp)) }
        }
    }
}

/**
 * Подпись раздела профиля: спокойная, с воздухом сверху — сгруппированный список, а не
 * заголовки вперемешку с карточками. Цвет — токен CanonMuted (был MaterialTheme, вне палитры).
 */
@Composable
private fun ProfileSectionLabel(text: String) {
    Text(
        text,
        modifier = Modifier.padding(top = 10.dp, start = 4.dp),
        color = CanonMuted,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
    )
}

/** Затемнение поверх аватара, пока фото уходит на сервер. Плавно появляется и уходит. */
@Composable
private fun AvatarUploadOverlay(uploading: Boolean) {
    // Отдельная функция, а не AnimatedVisibility по месту: внутри Box, у которого лексически
    // выше есть Column, компилятор выбрал бы ColumnScope-версию и получателя не нашёл.
    AnimatedVisibility(visible = uploading, enter = fadeIn(tween(160)), exit = fadeOut(tween(220))) {
        Box(
            Modifier.size(70.dp).background(CanonGreenInkDark.copy(alpha = 0.55f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
        }
    }
}

/** Одна цифра реферальной карточки: подпись + значение. Равные колонки, ничего не выпирает. */
@Composable
private fun ReferralStat(label: String, value: String, modifier: Modifier = Modifier, code: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, color = CanonGreen2, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            value, color = CanonGreen, fontWeight = FontWeight.Black,
            fontSize = if (code) 18.sp else 20.sp, lineHeight = 24.sp,
            letterSpacing = if (code) 1.5.sp else 0.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Состояние профиля одной плашкой: грузим → сеть упала («Обновить») → подтверждён / демо.
 * Смена состояний — мягкий кросс-фейд, без прыжка раскладки.
 */
@Composable
private fun ProfileStatusPill(loading: Boolean, error: Boolean, confirmed: Boolean, onRetry: () -> Unit) {
    val state = when {
        loading -> 0
        error -> 1
        confirmed -> 2
        else -> 3
    }
    AnimatedContent(
        targetState = state,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        label = "profileStatus",
    ) { s ->
        val bg = if (s == 1) CanonWarnBg else CanonMint
        val fg = if (s == 1) CanonWarn else CanonGreen2
        Surface(color = bg, shape = RoundedCornerShape(999.dp)) {
            Row(
                modifier = Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (s) {
                    0 -> CircularProgressIndicator(color = fg, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    1 -> Icon(Icons.Default.Refresh, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
                    else -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    when (s) {
                        0 -> appText("Обновляем профиль…", "Профилде яңыртабыҙ…")
                        1 -> appText("Нет связи с сервером", "Сервер менән бәйләнеш юҡ")
                        2 -> appText("Профиль подтверждён", "Профиль раҫланған")
                        else -> appText("Демо-режим без входа", "Инеүһеҙ демо-режим")
                    },
                    color = fg, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 17.sp,
                    modifier = Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                if (s == 1) {
                    Spacer(Modifier.width(4.dp))
                    // Тач-цель 48dp — сам Box, а не текст: маленькая надпись «Обновить»
                    // в состоянии «нет связи» должна попадаться пальцем с первого раза.
                    Box(
                        modifier = Modifier.heightIn(min = 48.dp).bounceClick(onRetry).padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(appText("Обновить", "Яңыртыу"), color = fg, fontWeight = FontWeight.Black, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

// Красная карточка опасного действия (удаление аккаунта). Отдельно от ProfileActionCard —
// красный акцент + рамка, чтобы визуально отделить необратимое действие.
@Composable
internal fun DangerActionCard(title: String, text: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.bounceClick(onClick).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, CanonRed.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = CanonRed.copy(alpha = 0.12f), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Delete, contentDescription = null, tint = CanonRed, modifier = Modifier.padding(9.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = CanonRed, fontWeight = FontWeight.Black, fontSize = 16.sp, lineHeight = 19.sp)
                Text(text, color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
        }
    }
}

@Composable
internal fun ProfileActionCard(
    title: String,
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: (() -> Unit)?
) {
    val clickModifier = if (onClick != null) Modifier.bounceClick(onClick) else Modifier
    Card(
        modifier = clickModifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(9.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp, lineHeight = 19.sp)
                Text(text, color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
        }
    }
}

/** То же, но иконка из брендового пака (vector-drawable). */
@Composable
internal fun ProfileActionCard(
    title: String,
    text: String,
    iconRes: Int,
    onClick: (() -> Unit)?
) {
    val clickModifier = if (onClick != null) Modifier.bounceClick(onClick) else Modifier
    Card(
        modifier = clickModifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                Icon(painterResource(iconRes), contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(9.dp).size(24.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp, lineHeight = 19.sp)
                Text(text, color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
        }
    }
}

@Composable
internal fun PassengerCabinetScreen(
    rides: List<Ride>,
    requests: List<LocalRequest>,
    onBack: () -> Unit,
    onMyTrips: () -> Unit,
    onOpenBooking: (Ride, String) -> Unit,
    onFindRide: () -> Unit,
    onCreateRequest: () -> Unit,
    onInstantOrder: () -> Unit = {},
    onScheduledOrders: () -> Unit = {},
    onWallet: () -> Unit = {},
    onSavedPlaces: () -> Unit = {},
    onSafety: () -> Unit
) {
    // Реальные брони и заявки пользователя (раньше метрики и карточка брались из демо-списка).
    var bookings by remember { mutableStateOf<List<com.yuldash.app.data.BookingMineDto>>(emptyList()) }
    var bookingsLoading by remember { mutableStateOf(true) }
    var bookingsError by remember { mutableStateOf(false) }
    var bookingsReload by remember { mutableIntStateOf(0) }
    var serverReqCount by remember { mutableStateOf<Int?>(null) }
    var myRating by remember { mutableStateOf<Double?>(null) }
    // Ограничения качества (§9): пауза такси-заказов за страйки/жалобы — карточка в кабинете.
    var restrictions by remember { mutableStateOf<com.yuldash.app.data.RestrictionsDto?>(null) }
    LaunchedEffect(bookingsReload) {
        bookingsLoading = true
        if (ApiClient.isLoggedIn()) ApiClient.getMyRestrictions().onSuccess { restrictions = it }
        ApiClient.getMyBookingsDetailed()
            .onSuccess { bookings = it; bookingsError = false }
            .onFailure { e -> bookingsError = ApiClient.isLoggedIn() && (e as? com.yuldash.app.data.ApiException)?.status != 401 }
        ApiClient.getMyRequests().onSuccess { serverReqCount = it.size }
        ApiClient.me().onSuccess { o -> myRating = if (o.isNull("rating")) null else o.optDouble("rating") }
        bookingsLoading = false
    }
    val activeBookings = bookings.filter { it.status == "pending" || it.status == "confirmed" || it.status == "onboard" }
    val activeBooking = activeBookings.firstOrNull()
    // Готовим отображаемую поездку в @Composable-обёртке (тут доступен appText/язык), передаём в чистый Content.
    val activeRide = activeBooking?.let { b ->
        Ride(
            id = b.id.toString(),
            from = b.fromCity.ifBlank { appText("Поездка", "Сәфәр") },
            to = b.toCity.ifBlank { "№${b.rideId}" },
            time = formatDepart(b.departAt),
            driver = b.driverName,
            car = "",
            price = b.price,
            seats = b.seats,
            rating = 0.0,
            verified = b.driverVerified,
            boosted = false
        )
    }
    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Кабинет пассажира", "Пассажир кабинеты"), onBack) }
    ) { padding ->
        PassengerCabinetContent(
            loading = bookingsLoading,
            error = bookingsError,
            activeCount = activeBookings.size,
            requestCount = serverReqCount ?: requests.size,
            ratingText = myRating?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—",
            activeRide = activeRide,
            activeStatus = activeBooking?.status,
            onRetry = { bookingsReload++ },
            onMyTrips = onMyTrips,
            onOpenBooking = onOpenBooking,
            onFindRide = onFindRide,
            onCreateRequest = onCreateRequest,
            onInstantOrder = onInstantOrder,
            onScheduledOrders = onScheduledOrders,
            onWallet = onWallet,
            onSavedPlaces = onSavedPlaces,
            onSafety = onSafety,
            modifier = Modifier.padding(padding),
            restrictions = restrictions,
        )
    }
}

/**
 * Чистый рендер кабинета пассажира: все состояния (загрузка-скелетон / ошибка+повтор / пусто / активная поездка).
 * Данные и колбэки приходят параметрами → без сети/стейта/эффектов → тестируется на JVM (Robolectric).
 * Поведение 1-в-1 с обёрткой [PassengerCabinetScreen].
 */
@Composable
internal fun PassengerCabinetContent(
    loading: Boolean,
    error: Boolean,
    activeCount: Int,
    requestCount: Int,
    ratingText: String,
    activeRide: Ride?,
    activeStatus: String?,
    onRetry: () -> Unit,
    onMyTrips: () -> Unit,
    onOpenBooking: (Ride, String) -> Unit,
    onFindRide: () -> Unit,
    onCreateRequest: () -> Unit,
    onInstantOrder: () -> Unit,
    onSafety: () -> Unit,
    onScheduledOrders: () -> Unit = {},
    onWallet: () -> Unit = {},
    onSavedPlaces: () -> Unit = {},
    modifier: Modifier = Modifier,
    // Ограничения качества (§9): карточка «Мои ограничения» (пусто → не показывается).
    restrictions: com.yuldash.app.data.RestrictionsDto? = null,
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text(appText("Ваши поездки и заявки", "Һеҙҙең сәфәрҙәр һәм заявкалар"), color = CanonGreen, fontSize = 25.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black)
            Text(appText("Быстрый доступ к бронированиям, заявкам и защите поездки.", "Брондәргә, заявкаларға һәм хәүефһеҙлеккә тиҙ инеү."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp)
        }
        // §9 Качество: активные ограничения (пауза такси-заказов) + «написать в поддержку».
        if (restrictions != null && restrictions.items.isNotEmpty()) {
            item { RestrictionsCard(restrictions) }
        }
        item {
            // Флагман Фазы 2 — вызвать машину сейчас (такси-режим). Заметная зелёная карточка.
            Card(
                onClick = onInstantOrder,
                modifier = Modifier.fillMaxWidth().appearIn(0),
                colors = CardDefaults.cardColors(containerColor = CanonGreen2),
                shape = CanonCardShape,
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = CanonBg.copy(alpha = 0.22f)) {
                        Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonBg, modifier = Modifier.padding(11.dp).size(24.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(appText("Быстрый заказ", "Тиҙ заказ"), color = CanonBg, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Text(appText("Вызвать машину сейчас — цену видно заранее", "Хәҙер машина саҡырыу — хаҡ алдан күренә"), color = CanonBg.copy(alpha = 0.9f), fontSize = 13.sp, lineHeight = 17.sp)
                    }
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonBg, modifier = Modifier.size(22.dp))
                }
            }
        }
        item {
            // Пока брони не пришли — «—», а не честные на вид нули: ноль активных поездок
            // и «ещё не загрузилось» для человека выглядят одинаково, но значат разное.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CabinetMetric(appText("Активные", "Актив"), if (loading) "—" else activeCount.toString(), Modifier.weight(1f))
                CabinetMetric(appText("Заявки", "Заявкалар"), if (loading) "—" else requestCount.toString(), Modifier.weight(1f))
                CabinetMetric(appText("Рейтинг", "Рейтинг"), ratingText, Modifier.weight(1f))
            }
        }
        item {
            SettingsGroup {
                SettingsNavRow(
                    Icons.Default.EventSeat,
                    appText("Мои поездки", "Минең сәфәрҙәр"),
                    appText("Активные брони, история и чат по поездке", "Актив брондәр, тарих һәм сәфәр чаты"),
                    onClick = onMyTrips
                )
            }
        }
        if (loading) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    repeat(2) { SkeletonCard(lines = 3) }
                }
            }
        }
        if (error) {
            item {
                EmptyStateCard(
                    title = appText("Не удалось загрузить поездки", "Сәфәрҙәрҙе йөкләп булманы"),
                    text = appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла"),
                    icon = Icons.Default.Refresh,
                    action = appText("Повторить", "Ҡабатлау"),
                    onAction = onRetry
                )
            }
        }
        // Пусто — тоже состояние: раньше при отсутствии активной брони человек видел просто
        // список ссылок и не понимал, «загрузилось ли» и что делать дальше.
        if (!loading && !error && activeRide == null) {
            item {
                EmptyStateCard(
                    title = appText("Активных поездок нет", "Актив сәфәрҙәр юҡ"),
                    text = appText(
                        "Найди попутку рядом или оставь заявку — водители увидят её и откликнутся.",
                        "Яҡындағы юлдашты тап йәки заявка ҡалдыр — водителдәр күреп яуап бирер.",
                    ),
                    icon = Icons.Default.EventSeat,
                    // Формулировка намеренно отличается от строки списка ниже: два одинаковых
                    // «Найти поездку» на одном экране читаются как дубль.
                    action = appText("Смотреть попутки рядом", "Яҡындағы юлдаштарҙы ҡарау"),
                    onAction = onFindRide,
                )
            }
        }
        if (activeRide != null && activeStatus != null) {
            item {
                val opensActiveTrip = activeStatus == "confirmed" || activeStatus == "onboard"
                MyTripCard(
                    ride = activeRide,
                    status = appText("Ближайшая", "Яҡындағы"),
                    statusColor = CanonMint,
                    icon = Icons.Default.EventSeat,
                    primaryAction = if (opensActiveTrip) appText("Открыть поездку", "Сәфәрҙе асыу") else appText("Подробнее", "Ентекле"),
                    secondaryAction = appText("Все поездки", "Бөтә сәфәрҙәр"),
                    onPrimary = { onOpenBooking(activeRide, activeStatus) },
                    onSecondary = onMyTrips
                )
            }
        }
        item {
            SettingsGroup {
                SettingsNavRow(Icons.Default.Search, appText("Найти поездку", "Сәфәр табыу"), appText("Открыть список ближайших маршрутов", "Яҡындағы маршруттарҙы асыу"), onClick = onFindRide)
                SettingsNavRow(Icons.Default.Schedule, appText("Мои предзаказы", "Минең алдан заказдар"), appText("Такси «на время»: обратный отсчёт и поиск", "«Ваҡытҡа» такси: кире иҫәп һәм эҙләү"), onClick = onScheduledOrders)
                SettingsNavRow(Icons.Default.AddRoad, appText("Создать заявку", "Заявка булдырыу"), appText("Если готовой поездки нет", "Әҙер сәфәр булмаһа"), onClick = onCreateRequest)
                SettingsNavRow(Icons.Default.Bookmark, appText("Мои адреса", "Минең адрестар"), appText("Дом, работа и любимые места", "Өй, эш һәм яратҡан урындар"), onClick = onSavedPlaces)
                SettingsNavRow(Icons.Default.AccountBalanceWallet, appText("Кошелёк", "Янсыҡ"), appText("Баланс и история операций", "Баланс һәм операциялар тарихы"), onClick = onWallet)
                SettingsNavRow(Icons.Default.Shield, appText("Безопасность поездки", "Сәфәр хәүефһеҙлеге"), appText("SOS, скрытый номер и доверенные контакты", "SOS, йәшерен номер һәм ышаныслы контакттар"), onClick = onSafety)
            }
        }
    }
}

@Composable
internal fun DriverCabinetScreen(
    rides: List<Ride>,
    onBack: () -> Unit,
    onCreateRide: () -> Unit,
    onVerifyDriver: () -> Unit,
    onBoost: () -> Unit,
    onRequestsFeed: () -> Unit = {},
    onInstantTrip: (Int) -> Unit = {},   // «Быстрый заказ»: принял входящий оффер → экран поездки водителя
    onTaxiOnboarding: () -> Unit = {},   // гейт такси (580-ФЗ): нет одобренной заявки → «Стать таксистом»
    onWallet: () -> Unit = {},           // Кошелёк: баланс + история операций (ledger)
    onEarnings: () -> Unit = {},         // «Мой заработок»: заработок по периодам + по дням
    onTaxiRides: () -> Unit = {},        // «Мои поездки такси»: цена → комиссия → чистыми по каждой
    onTaxiDocs: () -> Unit = {},         // 580-ФЗ: сроки ОСАГО/разрешения/техосмотра + продление
    onPretrip: () -> Unit = {},          // 580-ФЗ: готовность к работе на сегодня
    onMyResponses: () -> Unit = {},      // «Мои отклики»: торг о цене по заявкам пассажиров
) {
    // Реальные опубликованные поездки водителя с сервера (раньше фильтровали демо-список по имени → всегда пусто).
    var driverRides by remember { mutableStateOf<List<Ride>>(emptyList()) }
    // Отличаем «маршрутов нет» от «сеть упала»: иначе при обрыве водитель видит ложное «нет маршрутов».
    var ridesError by remember { mutableStateOf(false) }
    var ridesLoading by remember { mutableStateOf(true) }   // первая загрузка → скелетон вместо ложного «пусто»
    val ctx = LocalContext.current
    val rateScope = rememberCoroutineScope()
    var driverBookings by remember { mutableStateOf<List<com.yuldash.app.data.DriverBookingDto>>(emptyList()) }
    var driverRating by remember { mutableStateOf<Double?>(null) }
    var online by remember { mutableStateOf(false) }
    var onlineLoaded by remember { mutableStateOf(false) }   // статус пришёл с сервера → можно синкать фоновый сервис
    var debt by remember { mutableStateOf<com.yuldash.app.data.DriverDebtDto?>(null) }
    // Гейт такси (580-ФЗ): без одобренной заявки тумблер «Я на линии» заменяется CTA «Стать таксистом».
    var taxiApp by remember { mutableStateOf<com.yuldash.app.data.TaxiApplicationDto?>(null) }
    var taxiAppLoaded by remember { mutableStateOf(false) }
    // Зона работы таксиста (география, волна 2): чип у тумблера + шторка выбора.
    var zone by remember { mutableStateOf<com.yuldash.app.data.InstantZoneDto?>(null) }
    var showZoneSheet by remember { mutableStateOf(false) }
    // Смена такси (волна 2, §8 Отдых): прогресс к 8-часовому лимиту / блок отдыха.
    var workday by remember { mutableStateOf<com.yuldash.app.data.TaxiWorkdayDto?>(null) }
    // Ограничения качества (§9): пауза такси по жалобам — карточка «Мои ограничения».
    var restrictions by remember { mutableStateOf<com.yuldash.app.data.RestrictionsDto?>(null) }
    suspend fun reloadDebt() { ApiClient.getDriverDebt().onSuccess { debt = it } }
    // Архив: прошлые поездки водителя (done + cancelled) для раздела «Архив» + счётчиков.
    var archive by remember { mutableStateOf<List<com.yuldash.app.data.RideDto>>(emptyList()) }
    var archiveLoading by remember { mutableStateOf(true) }
    var archiveError by remember { mutableStateOf(false) }
    fun loadArchive() {
        archiveLoading = true; archiveError = false
        rateScope.launch {
            ApiClient.getDriverRides("all")
                .onSuccess { list -> archive = list.filter { it.status == "done" || it.status == "cancelled" } }
                .onFailure { archiveError = true }
            archiveLoading = false
        }
    }
    var isWomanDriver by remember { mutableStateOf(false) }   // F9: opt-in «я — женщина за рулём»
    var bookingsReload by remember { mutableStateOf(0) }   // F2: bump после подтверждения/отклонения брони
    var ridesReload by remember { mutableStateOf(0) }   // F1: bump после отмены/завершения → список свежий
    LaunchedEffect(bookingsReload, ridesReload) {
        if (ApiClient.isLoggedIn()) ApiClient.getMyRestrictions().onSuccess { restrictions = it }
        ApiClient.getDriverRides()
            .onSuccess { driverRides = it.map { dto -> dto.toUiRide() }; ridesError = false }
            // 401 (не вошёл) → это не сеть, показываем обычное «пусто». Иначе — ошибка сети + «Повторить».
            .onFailure { e -> ridesError = ApiClient.isLoggedIn() && (e as? com.yuldash.app.data.ApiException)?.status != 401 }
        ridesLoading = false
        ApiClient.getDriverBookings().onSuccess { driverBookings = it }
        ApiClient.me().onSuccess { o -> driverRating = if (o.isNull("rating")) null else o.optDouble("rating") }
        ApiClient.getDriverStatus().onSuccess { online = it.online; onlineLoaded = true; isWomanDriver = it.gender == "female" }
        ApiClient.getInstantZone().onSuccess { zone = it }
        ApiClient.getMyTaxiApplication()
            .onSuccess { taxiApp = it; taxiAppLoaded = true }
            .onFailure { e ->
                // 404 = заявки нет (показываем CTA). Сетевая ошибка → статус неизвестен,
                // оставляем тумблер как раньше (сервер всё равно гейтит presence/accept).
                if ((e as? com.yuldash.app.data.ApiException)?.status == 404) { taxiApp = null; taxiAppLoaded = true }
            }
        reloadDebt()
        loadArchive()
    }
    // Прогресс смены живёт, пока водитель «на линии»: presence капает время на сервере —
    // мягко переопрашиваем сводку раз в минуту (вне линии хватает разовой загрузки выше).
    LaunchedEffect(online) {
        if (!ApiClient.isLoggedIn()) return@LaunchedEffect
        ApiClient.getTaxiWorkday().onSuccess { workday = it }
        while (online) {
            kotlinx.coroutines.delay(60_000)
            ApiClient.getTaxiWorkday().onSuccess { workday = it }
        }
    }
    val debtPaidMsg = appText("Спасибо! Ждём подтверждения — можно возить такси.", "Рәхмәт! Раҫлауҙы көтәбеҙ — такси йөрөтөргә мөмкин.")
    val debtPaidErrMsg = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    val thanksMsg = appText("Спасибо за оценку", "Баһа өсөн рәхмәт")
    val rateFailMsg = appText("Не получилось оценить", "Баһалап булманы")
    val bookingConfirmedMsg = appText("Бронь подтверждена — пассажиру открыты телефон и точка сбора", "Бронь раҫланды — пассажирға телефон һәм йыйылыу урыны асылды")
    val bookingRejectedMsg = appText("Бронь отклонена", "Бронь кире ҡағылды")
    val bookingActionFailMsg = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Сетте тикшереп ҡабатла.")
    val rideCancelledMsg = appText("Поездка снята. Пассажиры уведомлены.", "Сәфәр алынды. Пассажирҙар хәбәрҙар ителде.")
    val rideDoneMsg = appText("Рейс завершён. Хорошей дороги домой!", "Рейс тамамланды. Юлың уң булһын!")
    val rideActionFailMsg = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Сетте тикшереп ҡабатла.")
    val editSavedMsg = appText("Поездка обновлена", "Сәфәр яңыртылды")
    // Точные тексты под причину отказа (сервер различает их HTTP-кодом), все на двух языках.
    val editPriceDownMsg = appText("Поездку уже забронировали — цену можно только снизить.", "Сәфәр брондалған — хаҡты кәметергә генә була.")
    val editNotActiveMsg = appText("Менять можно только активную поездку.", "Тик актив сәфәрҙе генә үҙгәртеп була.")
    val editNetMsg = appText("Не получилось изменить. Проверь интернет и повтори.", "Үҙгәртеп булманы. Интернетты тикшереп ҡабатла.")
    val onlineErrMsg = appText("Не удалось изменить статус. Проверь сеть.", "Статусты үҙгәртеп булманы. Селтәрҙе тикшерегеҙ.")
    val onlineLoginMsg = appText("Войдите, чтобы выйти на линию", "Линияға сығыр өсөн инегеҙ")
    // D1/D2: выход «на линии» требует геолокации (без неё водитель невидим) и включённого такси в городе.
    val geoOnlineMsg = appText("Включи геолокацию — без неё заказы не придут и тебя не видно на карте.",
        "Геолокацияны ҡабыҙ — унһыҙ заказ килмәй, һине картала ла күренмәйһең.")
    val taxiCityOffMsg = appText("Такси в твоём городе пока не запущено. Сообщим, как только откроем.",
        "Ҡалаңда такси әле эшләмәй. Асылыу менән хәбәр итербеҙ.")
    fun goOnlineConfirmed() {
        val prev = online
        online = true; onlineLoaded = true
        rateScope.launch {
            // D2: если такси в этой точке выключено (глобально/город) — не выходим, объясняем.
            val lat = LocationPrefs.lastLat; val lng = LocationPrefs.lastLng
            if (lat != null && lng != null) {
                val av = ApiClient.getTaxiAvailability(lat, lng).getOrNull()
                if (av != null && !av.enabled) {
                    online = prev
                    Toast.makeText(ctx, taxiCityOffMsg, Toast.LENGTH_LONG).show()
                    return@launch
                }
            }
            ApiClient.setOnline(true).onFailure {
                online = prev
                Toast.makeText(ctx, onlineErrMsg, Toast.LENGTH_SHORT).show()
            }
        }
        if (zone?.workZone == null) showZoneSheet = true
    }
    val locPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) goOnlineConfirmed()
        else Toast.makeText(ctx, geoOnlineMsg, Toast.LENGTH_LONG).show()
    }
    val womanLoginMsg = appText("Войдите, чтобы изменить профиль", "Профильде үҙгәртер өсөн инегеҙ")
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Кабинет водителя", "Водитель кабинеты"), onBack) }
    ) { padding ->
        DriverCabinetContent(
            online = online,
            isWomanDriver = isWomanDriver,
            driverRides = driverRides,
            ridesError = ridesError,
            ridesLoading = ridesLoading,
            onRetryRides = { ridesReload++ },
            driverBookings = driverBookings,
            ratingText = driverRating?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—",
            debt = debt,
            onDeclareDebtPaid = {
                rateScope.launch {
                    ApiClient.declareDebtPaid()
                        .onSuccess onPaid@{ res ->
                            // ЮKassa (по флажку): уходим в браузер оплаты, статус проверяем поллингом.
                            if (res.method == "yookassa" && res.status == "pending" && !res.confirmationUrl.isNullOrBlank()) {
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(res.confirmationUrl))) }
                                repeat(6) {
                                    kotlinx.coroutines.delay(4000)
                                    if (ApiClient.getPaymentStatus(res.paymentId).getOrNull()?.status == "succeeded") {
                                        Toast.makeText(ctx, debtPaidMsg, Toast.LENGTH_LONG).show(); reloadDebt(); return@onPaid
                                    }
                                }
                                reloadDebt()
                                return@onPaid
                            }
                            // СБП «на доверии» / уже succeeded — прежнее поведение.
                            Toast.makeText(ctx, debtPaidMsg, Toast.LENGTH_LONG).show(); reloadDebt()
                        }
                        .onFailure { Toast.makeText(ctx, debtPaidErrMsg, Toast.LENGTH_SHORT).show() }
                }
            },
            onToggleWoman = onToggleWoman@{ v ->
                if (!ApiClient.isLoggedIn()) {
                    Toast.makeText(ctx, womanLoginMsg, Toast.LENGTH_SHORT).show()
                    return@onToggleWoman
                }
                val prev = isWomanDriver
                isWomanDriver = v
                rateScope.launch {
                    ApiClient.setDriverGender(if (v) "female" else "").onFailure {
                        isWomanDriver = prev
                        Toast.makeText(ctx, onlineErrMsg, Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onToggleOnline = onToggleOnline@{ v ->
                // Демо/без входа → не дёргаем API (там 401 → ложная «проверь сеть»), даём понятное «войдите».
                if (!ApiClient.isLoggedIn()) {
                    Toast.makeText(ctx, onlineLoginMsg, Toast.LENGTH_SHORT).show()
                    return@onToggleOnline
                }
                if (!v) {   // выключение — просто оффлайн, без проверок
                    val prev = online
                    online = false; onlineLoaded = true
                    rateScope.launch {
                        ApiClient.setOnline(false).onFailure {
                            online = prev
                            Toast.makeText(ctx, onlineErrMsg, Toast.LENGTH_SHORT).show()
                        }
                    }
                    return@onToggleOnline
                }
                // D1: выход на линию без геолокации = водитель невидим и молча без заказов. Просим разрешение.
                val hasGeo = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (!hasGeo) {
                    Toast.makeText(ctx, geoOnlineMsg, Toast.LENGTH_LONG).show()
                    locPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    return@onToggleOnline
                }
                goOnlineConfirmed()
            },
            onRate = { bookingId, n, onDone ->
                rateScope.launch {
                    ApiClient.rateBooking(bookingId, n)
                        .onSuccess {
                            Toast.makeText(ctx, thanksMsg, Toast.LENGTH_SHORT).show()
                            onDone(true)
                            // Перечитываем список: сервер вернёт my_stars, и карточка покажет
                            // «Вы поставили ★N» вместо пустых звёзд после любой перезагрузки.
                            bookingsReload++
                        }
                        .onFailure {
                            Toast.makeText(ctx, rateFailMsg, Toast.LENGTH_SHORT).show()
                            onDone(false)
                        }
                }
            },
            onCreateRide = onCreateRide,
            onVerifyDriver = onVerifyDriver,
            onBoost = onBoost,
            onRequestsFeed = onRequestsFeed,
            onMyResponses = onMyResponses,
            archive = archive,
            archiveLoading = archiveLoading,
            archiveError = archiveError,
            onRetryArchive = { loadArchive() },
            // F2: подтвердить/отклонить бронь — ждём сервер, потом обновляем списки.
            onConfirmBooking = { bookingId ->
                rateScope.launch {
                    ApiClient.confirmBooking(bookingId)
                        .onSuccess { Toast.makeText(ctx, bookingConfirmedMsg, Toast.LENGTH_LONG).show(); bookingsReload++ }
                        .onFailure { Toast.makeText(ctx, bookingActionFailMsg, Toast.LENGTH_SHORT).show() }
                }
            },
            onRejectBooking = { bookingId ->
                rateScope.launch {
                    ApiClient.cancelBooking(bookingId)
                        .onSuccess { Toast.makeText(ctx, bookingRejectedMsg, Toast.LENGTH_SHORT).show(); bookingsReload++ }
                        .onFailure { Toast.makeText(ctx, bookingActionFailMsg, Toast.LENGTH_SHORT).show() }
                }
            },
            // F1: отмена/завершение рейса — ждём сервер, потом обновляем список (bump ridesReload).
            onCancelRide = { rideId ->
                rateScope.launch {
                    ApiClient.cancelRide(rideId)
                        .onSuccess { Toast.makeText(ctx, rideCancelledMsg, Toast.LENGTH_LONG).show(); ridesReload++ }
                        .onFailure { Toast.makeText(ctx, rideActionFailMsg, Toast.LENGTH_SHORT).show() }
                }
            },
            onCompleteRide = { rideId ->
                rateScope.launch {
                    ApiClient.completeRide(rideId)
                        .onSuccess { Toast.makeText(ctx, rideDoneMsg, Toast.LENGTH_LONG).show(); ridesReload++ }
                        .onFailure { Toast.makeText(ctx, rideActionFailMsg, Toast.LENGTH_SHORT).show() }
                }
            },
            onEditRide = { rideId, price, comment ->
                rateScope.launch {
                    ApiClient.editRide(rideId, price, comment)
                        .onSuccess {
                            Toast.makeText(ctx, editSavedMsg, Toast.LENGTH_SHORT).show()
                            ridesReload++
                        }
                        .onFailure { e ->
                            // Понятная причина под ошибку: 409 — цена вверх при бронях, 400 — поездка уже неактивна,
                            // остальное (нет сети и т.п.) — общий текст с «повтори».
                            val msg = when ((e as? ApiException)?.status) {
                                409 -> editPriceDownMsg
                                400 -> editNotActiveMsg
                                else -> editNetMsg
                            }
                            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        }
                }
            },
            modifier = Modifier.padding(padding),
            taxiApplication = taxiApp,
            taxiAppLoaded = taxiAppLoaded,
            onTaxiOnboarding = onTaxiOnboarding,
            zone = zone,
            onZoneClick = { showZoneSheet = true },
            workday = workday,
            restrictions = restrictions,
            scheduleSection = { DriverScheduleSection() },
            // Спрос рядом — только одобренному таксисту; читает online как State (реагирует на тумблер).
            demandSection = if (!taxiAppLoaded || taxiApp?.status == "approved") {
                { DriverDemandSection(online = online) }
            } else null,
            onWallet = onWallet,
            onEarnings = onEarnings,
            onTaxiRides = onTaxiRides,
            onTaxiDocs = onTaxiDocs,
            onPretrip = onPretrip,
        )
    }
    // Шторка выбора зоны работы (география, волна 2): открывается с чипа или при выходе на линию без зоны.
    if (showZoneSheet) {
        DriverZoneSheet(
            current = zone,
            onSaved = { zone = it; showZoneSheet = false },
            onDismiss = { showZoneSheet = false },
        )
    }
    // Пока водитель «на линии» — presence-heartbeat + опрос входящего оффера; оффер рисуется поверх.
    // Гейт (580-ФЗ): точно знаем, что заявки-approved нет → зря сервер не дёргаем (там всё равно 403).
    val taxiAllowed = !taxiAppLoaded || taxiApp?.status == "approved"
    InstantDriverOnlineController(online = online && taxiAllowed, onOpenTrip = onInstantTrip)
    // Фоновый режим линии (B7a-1): экран погас/приложение свёрнуто → TaxiLineService держит
    // presence (~15с) и ловит офферы (~5с). Синкаем ТОЛЬКО после ответа сервера (onlineLoaded),
    // чтобы не глушить живой сервис из-за ещё не загрузившегося статуса. Тумблер выключен /
    // такси не одобрено → стоп; блок долгом/8ч/выходом сервис ловит сам (presence 401/403/409).
    val appLang = LocalAppLanguage.current
    LaunchedEffect(online, onlineLoaded, taxiAllowed, appLang) {
        if (!onlineLoaded) return@LaunchedEffect
        if (online && taxiAllowed && ApiClient.isLoggedIn()) TaxiLineService.start(ctx, appLang)
        else TaxiLineService.stop(ctx)
    }
    }
}

/**
 * Подтверждение статуса линии под тумблером: пока водитель онлайн — спокойная мятная плашка
 * «Ты на линии». Выносим отдельной функцией: AnimatedVisibility по месту внутри Column взял бы
 * ColumnScope-версию, а внутри Box получателя бы не нашёл (ловили на карте).
 */
@Composable
private fun DriverOnlineHint(online: Boolean) {
    AnimatedVisibility(
        visible = online,
        enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { -it / 3 },
        exit = fadeOut(tween(160)),
    ) {
        Surface(color = CanonMint, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(CanonGreen2))
                Spacer(Modifier.width(10.dp))
                Text(
                    appText(
                        "Ты на линии — заказы придут сюда, экран можно погасить.",
                        "Һин линияла — заказдар бында килә, экранды һүндерергә була.",
                    ),
                    color = CanonGreen2, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** ISO-дата ("2026-07-16T…") → "16.07.2026" для показа срока. Кривой ввод — вернём как есть (первые 10). */
private fun debtDueLabel(iso: String): String {
    val d = iso.take(10).split("-")
    return if (d.size == 3) "${d[2]}.${d[1]}.${d[0]}" else iso.take(10)
}

/**
 * «Мои ограничения» (§9 Качество, право объяснения). Показывается ТОЛЬКО если
 * /me/restrictions не пуст: что ограничено (пауза такси / заказов), категория жалобы
 * (БЕЗ автора — анонимность), до какого времени, «попутка работает» и кнопка
 * «Написать в поддержку» (диалог → запрос звонка админу). Тексты — с сервера, двуязычно.
 */
@Composable
internal fun RestrictionsCard(data: com.yuldash.app.data.RestrictionsDto) {
    if (data.items.isEmpty()) return
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var showSupport by remember { mutableStateOf(false) }
    var supportText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val sentMsg = appText("Отправлено. Мы перезвоним и разберёмся.", "Ебәрелде. Шылтыратып асыҡлайбыҙ.")
    val failMsg = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    val supportPrefix = appText("Об ограничении (§9): ", "Сикләү тураһында (§9): ")   // вне лямбды: appText только в composition
    Surface(color = CanonWarnBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonWarn.copy(alpha = 0.35f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Lock, contentDescription = appText("Ограничение", "Сикләү"), tint = CanonWarn, modifier = Modifier.size(20.dp))
                Text(appText("Мои ограничения", "Минең сикләүҙәр"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
            data.items.forEach { it ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(appText(it.titleRu, it.titleBa), color = CanonWarn, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    val catRu = it.categoryRu; val catBa = it.categoryBa
                    if (catRu.isNotBlank() || catBa.isNotBlank()) {
                        Text(appText("Причина: $catRu", "Сәбәп: $catBa"), color = CanonText, fontSize = 13.sp)
                    }
                    val until = it.until
                    Text(
                        if (until != null) appText("До ", "Тиклем: ") + debtDueLabel(until)
                        else appText("До разбора — решает живой человек", "Тикшергәнсе — тере кеше хәл итә"),
                        color = CanonMuted, fontSize = 13.sp,
                    )
                    Text(appText(it.noteRu, it.noteBa), color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
                }
            }
            Text(appText(data.supportRu, data.supportBa), color = CanonMuted, fontSize = 12.sp, lineHeight = 16.sp)
            OutlinedButton(
                onClick = { showSupport = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text(appText("Написать в поддержку", "Ярҙамға яҙыу"), color = CanonText, fontWeight = FontWeight.Bold) }
        }
    }
    if (showSupport) {
        AlertDialog(
            onDismissRequest = { showSupport = false },
            containerColor = CanonSurface,
            title = { Text(appText("Твоя версия событий", "Һинең яғыңдан ҡараш"), color = CanonText, fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(appText("Расскажи, как было — поддержка перезвонит и разберётся по-человечески.", "Нисек булғанын һөйлә — ярҙам шылтыратып кешеләрсә асыҡлар."), color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
                    OutlinedTextField(
                        value = supportText, onValueChange = { supportText = it },
                        placeholder = { Text(appText("Что случилось на самом деле?", "Ысынында ни булды?")) },
                        modifier = Modifier.fillMaxWidth(), minLines = 3,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !sending && supportText.isNotBlank(), onClick = {
                    sending = true
                    scope.launch {
                        ApiClient.requestCallback(supportPrefix + supportText.trim())
                            .onSuccess { Toast.makeText(ctx, sentMsg, Toast.LENGTH_LONG).show(); showSupport = false; supportText = "" }
                            .onFailure { Toast.makeText(ctx, failMsg, Toast.LENGTH_SHORT).show() }
                        sending = false
                    }
                }) { Text(appText("Отправить", "Ебәреү"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showSupport = false }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) } },
        )
    }
}

/**
 * Баннер долга по комиссии за такси (Модель А «на доверии»). Три состояния:
 *  • заблокирован (просрочка / выше порога) — красный: «Такси заблокировано» + понятно, что попутка работает;
 *  • есть долг, не заблокирован — жёлтый: «Долг сервису: X ₽, оплати до <дата>»;
 *  • всё в pending (нажал «Я оплатил») — мятный: «Ждём подтверждения».
 * Реквизиты СБП приходят с сервера (owner_sbp_phone/name из .env) — НЕ хардкод.
 */
@Composable
private fun DriverDebtBanner(debt: com.yuldash.app.data.DriverDebtDto, onDeclarePaid: () -> Unit) {
    val onlyPending = debt.unpaidKop == 0 && debt.pendingKop > 0
    val bg = when { debt.blocked -> CanonDangerBg; onlyPending -> CanonMint; else -> CanonWarnBg }
    val accent = when { debt.blocked -> CanonRed; onlyPending -> CanonGreen2; else -> CanonWarn }
    val title = when {
        debt.blocked -> appText("Такси заблокировано", "Такси блокланған")
        onlyPending -> appText("Ждём подтверждения оплаты", "Түләү раҫлауын көтәбеҙ")
        else -> appText("Долг сервису", "Сервисҡа бурыс")
    }
    Surface(color = bg, shape = CanonItemShape, border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (debt.blocked) Icons.Default.Lock else Icons.Default.Payments,
                    contentDescription = if (debt.blocked) appText("Заблокировано", "Блокланған") else appText("Долг", "Бурыс"),
                    tint = accent, modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(title, color = accent, fontWeight = FontWeight.Black, fontSize = 17.sp)
            }
            // Сумма к оплате (или сумма в ожидании подтверждения).
            if (debt.unpaidKop > 0) {
                Text(
                    appText("К оплате: ", "Түләргә: ") + "${debt.unpaidRub} ₽",
                    color = CanonText, fontWeight = FontWeight.Black, fontSize = 22.sp
                )
            } else if (onlyPending) {
                Text(
                    appText("В обработке: ", "Эшкәртеүҙә: ") + "${debt.pendingRub} ₽",
                    color = CanonText, fontWeight = FontWeight.Black, fontSize = 22.sp
                )
            }
            // Пояснение по состоянию.
            val explain = when {
                debt.blocked -> appText(
                    "Оплати долг сервису, чтобы снова возить такси. Попутка (плановые поездки) работает как обычно.",
                    "Такси йөрөтөр өсөн сервисҡа бурысты түлә. Юлдаш (планлы сәфәрҙәр) ғәҙәттәгесә эшләй."
                )
                onlyPending -> appText(
                    "Александр проверит перевод и подтвердит. Такси уже работает.",
                    "Александр күсереүҙе тикшереп раҫлар. Такси инде эшләй."
                )
                debt.dueAt != null -> appText("Оплати до ", "Түлә: ") + debtDueLabel(debt.dueAt!!)
                else -> appText("Переведи долг по реквизитам ниже.", "Түбәндәге реквизиттар буйынса бурысты күсер.")
            }
            Text(explain, color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp)
            // Реквизиты СБП Александра (с сервера). Пока не заданы — мягкая заглушка.
            if (debt.unpaidKop > 0) {
                Surface(color = CanonSurface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(appText("Перевод по СБП", "СБП аша күсереү"), color = CanonMuted, fontSize = 12.sp)
                        if (debt.sbpPhone.isNotBlank()) {
                            Text(debt.sbpPhone, color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp)
                            if (debt.sbpName.isNotBlank()) Text(debt.sbpName, color = CanonText, fontSize = 14.sp)
                        } else {
                            Text(appText("Реквизиты уточняются — напиши в поддержку.", "Реквизиттар аныҡлана — ярҙамға яҙ."), color = CanonMuted, fontSize = 13.sp)
                        }
                    }
                }
                Button(
                    onClick = onDeclarePaid,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) { Text(appText("Я оплатил", "Мин түләнем"), fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** Секунды смены → «6 ч 20 мин» / «6 сәғ 20 мин» (меньше часа — только минуты). */
private fun shiftTimeRu(sec: Int): String {
    val h = sec / 3600; val m = (sec % 3600) / 60
    return if (h > 0) "$h ч $m мин" else "$m мин"
}
private fun shiftTimeBa(sec: Int): String {
    val h = sec / 3600; val m = (sec % 3600) / 60
    return if (h > 0) "$h сәғ $m мин" else "$m мин"
}

/** unlock_at (ISO, наивный UTC с сервера) → локальное «06:00» на устройстве. Кривое — null. */
private fun unlockTimeLabel(iso: String?): String? = runCatching {
    if (iso.isNullOrBlank()) return null
    val local = java.time.LocalDateTime.parse(iso.removeSuffix("Z"))
        .atOffset(java.time.ZoneOffset.UTC)
        .atZoneSameInstant(java.time.ZoneId.systemDefault())
    String.format(java.util.Locale.US, "%02d:%02d", local.hour, local.minute)
}.getOrNull()

/**
 * Прогресс смены такси (волна 2, §8 Отдых): «На линии 6 ч 20 мин из 8». Спокойный зелёный,
 * ближе к лимиту (остался ≤1 ч) — тёплый оранжевый; цвет и полоса анимируются плавно.
 * Попутка в лимит не входит — честно говорим об этом подписью.
 */
/** Процент без хвоста «.0»: 3.0 → «3%», 2.5 → «2.5%». */
private fun feePct(p: Double): String =
    (if (p % 1.0 == 0.0) p.toInt().toString() else p.toString()) + "%"

/**
 * Дашборд таксиста (экран «на линии»): заработок и заказы ЗА СЕГОДНЯ крупной тёмно-зелёной
 * картой (белый текст читаем в обеих темах — фикс. CanonGreenInk, не адаптивный) + честная
 * ЛЕСЕНКА КОМИССИИ по стажу (3/5/8%): подсвечена текущая ступень, подпись «через N дней станет Y%».
 * Данные — из /instant/workday (debt.driver_dashboard). Лесенка по дням стажа, НЕ по деньгам.
 */
@Composable
private fun TaxiDashboardCard(wd: com.yuldash.app.data.TaxiWorkdayDto) {
    // animate*AsState на ПЕРВОМ кадре берёт цель как есть — счётчик просто появлялся готовым.
    // Стартуем с нуля и включаем цель после первой композиции. Источник — netTodayKop:
    // дашборд показывает водителю чистый доход после комиссии с точностью до копейки.
    var netEarnTargetKop by remember { mutableIntStateOf(0) }
    LaunchedEffect(wd.netTodayKop) { netEarnTargetKop = wd.netTodayKop }
    val netEarnKop by animateIntAsState(netEarnTargetKop, tween(700), label = "netEarnKop")
    val tiers = wd.feeTiers.ifEmpty { listOf(3.0, 5.0, 8.0) }
    // Индекс текущей ступени по стажу (границы feeTierDays = [30,60]).
    val activeIdx = when {
        wd.feeTierDays.size < 2 -> 0
        wd.tenureDays <= wd.feeTierDays[0] -> 0
        wd.tenureDays <= wd.feeTierDays[1] -> 1
        else -> 2
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // ── Заработок за сегодня (тёмно-зелёная плашка, белый текст читаем в обеих темах) ──
            Surface(color = CanonGreenInk, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(appText("Чистыми сегодня", "Бөгөн таҙа килем"), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(formatTaxiKop(netEarnKop), color = Color.White, fontWeight = FontWeight.Black, fontSize = 34.sp)
                        Text(
                            appText("${wd.ordersToday} ${pluralOrdersRu(wd.ordersToday)}", "${wd.ordersToday} заказ"),
                            color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
                        )
                        Text(
                            appText(
                                "Пассажиры: ${formatTaxiKop(wd.grossTodayKop)} · комиссия: ${formatTaxiKop(wd.feeTodayKop)}",
                                "Пассажирҙар: ${formatTaxiKop(wd.grossTodayKop)} · комиссия: ${formatTaxiKop(wd.feeTodayKop)}",
                            ),
                            color = Color.White.copy(alpha = 0.72f), fontSize = 11.sp, lineHeight = 15.sp,
                        )
                    }
                    Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(30.dp))
                }
            }
            // ── Лесенка комиссии (по стажу) ──
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(appText("Комиссия сервиса", "Сервис комиссияһы"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(feePct(wd.feePercent), color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 18.sp)
                }
                // Три сегмента-ступени: подсвечена текущая.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tiers.forEachIndexed { i, t ->
                        val active = i == activeIdx
                        Surface(
                            color = if (active) CanonGreen2 else CanonMint,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f).height(34.dp),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(feePct(t), color = if (active) Color.White else CanonMuted,
                                    fontWeight = if (active) FontWeight.Black else FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
                // Честная подпись: когда ступень поднимется (или уже верхняя).
                val note = if (wd.feeNextPercent != null && wd.feeDaysToNext != null) {
                    val dn = wd.feeDaysToNext
                    appText(
                        "Сейчас ${feePct(wd.feePercent)} — стартовая ставка. Через $dn ${pluralDaysRu(dn)} станет ${feePct(wd.feeNextPercent)}. Всё равно ниже, чем у агрегаторов.",
                        "Хәҙер ${feePct(wd.feePercent)} — башланғыс. $dn көндән ${feePct(wd.feeNextPercent)} булыр. Барыбер агрегаторҙарҙан түбәнерәк.",
                    )
                } else {
                    appText("Максимальная ставка ${feePct(wd.feePercent)} — ниже, чем у агрегаторов (22–30%).",
                        "Иң юғары ставка ${feePct(wd.feePercent)} — агрегаторҙарҙан (22–30%) түбәнерәк.")
                }
                Text(note, color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

/** RU-плюрал «заказ/заказа/заказов». */
private fun pluralOrdersRu(n: Int): String {
    val m10 = n % 10; val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "заказ"
        m10 in 2..4 && m100 !in 12..14 -> "заказа"
        else -> "заказов"
    }
}

/** RU-плюрал «день/дня/дней». */
private fun pluralDaysRu(n: Int): String {
    val m10 = n % 10; val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "день"
        m10 in 2..4 && m100 !in 12..14 -> "дня"
        else -> "дней"
    }
}

@Composable
private fun TaxiShiftProgressCard(wd: com.yuldash.app.data.TaxiWorkdayDto) {
    val warm = wd.remainingSec <= 3600                     // последний час — мягкое предупреждение
    val accent by animateColorAsState(if (warm) CanonWarn else CanonGreen2, tween(500), label = "shiftAccent")
    // Полоса заполняется на глазах: с нуля к реальной доле смены. Без этого на первом кадре
    // animateFloatAsState брал цель как есть и «рост» не был виден вообще.
    var progressTarget by remember { mutableStateOf(0f) }
    LaunchedEffect(wd.secondsOnline, wd.limitSec) {
        progressTarget = (wd.secondsOnline.toFloat() / wd.limitSec.coerceAtLeast(1)).coerceIn(0f, 1f)
    }
    val progress by animateFloatAsState(progressTarget, tween(700), label = "shiftProgress")
    Surface(color = if (warm) CanonWarnBg else CanonSurface, shape = CanonItemShape,
        border = BorderStroke(1.dp, if (warm) CanonWarn.copy(alpha = 0.35f) else CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Schedule, contentDescription = appText("Смена такси", "Такси сменаһы"),
                    tint = accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(appText("Смена такси", "Такси сменаһы"), color = CanonText,
                    fontWeight = FontWeight.Black, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Text(appText("из ${wd.limitHours} ч", "${wd.limitHours} сәғәттән"), color = CanonMuted, fontSize = 13.sp)
            }
            Text(
                appText("На линии ${shiftTimeRu(wd.secondsOnline)}", "Линияла ${shiftTimeBa(wd.secondsOnline)}"),
                color = CanonText, fontWeight = FontWeight.Black, fontSize = 22.sp,
            )
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = accent, trackColor = CanonBg,
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            Text(
                if (warm) appText(
                    "До отдыха меньше часа 🌙 Спокойно заверши дела на линии.",
                    "Ялға бер сәғәттән дә әҙерәк ҡалды 🌙 Линиялағы эштәреңде тыныс ҡына тамамла."
                ) else appText(
                    "После ${wd.limitHours} часов на линии — отдых до утра. Попутка в лимит не входит.",
                    "Линияла ${wd.limitHours} сәғәттән һуң — иртәнгә тиклем ял. Юлдаш сәфәрҙәре иҫәпкә инмәй."
                ),
                color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
            )
        }
    }
}

/**
 * Карточка отдыха (блок §8): «Ты сегодня за рулём 8 часов 🌙» + когда снова на линию +
 * «Возьми одного попутчика домой» (одна публикация попутки → создание поездки), пока
 * return_ride_used=false. Спокойная мятная палитра — отдых, а не наказание.
 */
@Composable
private fun TaxiRestCard(wd: com.yuldash.app.data.TaxiWorkdayDto, onCreateRide: () -> Unit) {
    Surface(color = CanonMint, shape = CanonItemShape, border = BorderStroke(1.dp, CanonGreen2.copy(alpha = 0.35f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = CanonGreen2) {
                    Icon(Icons.Default.Bedtime, contentDescription = appText("Отдых", "Ял"),
                        tint = CanonBg, modifier = Modifier.padding(10.dp).size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    appText("Ты сегодня за рулём ${wd.limitHours} часов 🌙", "Һин бөгөн ${wd.limitHours} сәғәт руль артында 🌙"),
                    color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp, lineHeight = 22.sp,
                )
            }
            val unlock = unlockTimeLabel(wd.unlockAt)
            Text(
                if (unlock != null) appText(
                    "Отдохни — завтра с $unlock снова на линию. Хорошо поработал 👏",
                    "Ял ит — иртәгә $unlock-тан йәнә линияға. Яҡшы эшләнең 👏"
                ) else appText(
                    "Отдохни — завтра с 6 утра снова на линию. Хорошо поработал 👏",
                    "Ял ит — иртәгә иртәнге 6-нан йәнә линияға. Яҡшы эшләнең 👏"
                ),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp,
            )
            if (!wd.returnRideUsed) {
                Surface(color = CanonSurface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(appText("Возьми одного попутчика домой", "Бер юлдашты өйгә алып ҡайт"),
                            color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
                        Text(
                            appText(
                                "Машина всё равно едет назад — подвези земляка. Одна публикация попутки до конца отдыха.",
                                "Машина барыбер кире ҡайта — яҡташыңды ултыртып ҡайт. Ял бөткәнсе бер генә юлдаш сәфәре."
                            ),
                            color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                        )
                        Button(
                            onClick = onCreateRide,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                        ) {
                            Icon(Icons.Default.DirectionsCar, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(appText("Опубликовать поездку домой", "Өйгә сәфәр баҫтырыу"), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Text(
                    appText("Попутчик домой уже опубликован 💚 Лёгкой дороги!", "Өйгә юлдаш инде баҫтырылған 💚 Юлың еңел булһын!"),
                    color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                )
            }
        }
    }
}

/**
 * CTA гейта такси (580-ФЗ) вместо тумблера «Я на линии», пока заявка таксиста не одобрена.
 * Три состояния: не подавал («Стать таксистом»), pending («на проверке»), rejected («подать снова»).
 * Нажатие всюду ведёт на TaxiOnboardingScreen — там форма/статус/комментарий админа.
 */
@Composable
private fun TaxiOnboardingCta(app: com.yuldash.app.data.TaxiApplicationDto?, onClick: () -> Unit) {
    val (title, sub) = when (app?.status) {
        "pending" -> appText("Заявка таксиста на проверке", "Таксист заявкаһы тикшереүҙә") to
            appText("Проверяем документы — скоро откроем такси. Нажми, чтобы посмотреть статус.", "Документтарҙы тикшерәбеҙ — тиҙҙән таксины асабыҙ. Статусты ҡарар өсөн баҫ.")
        "rejected" -> appText("Заявку таксиста отклонили", "Таксист заявкаһы кире ҡағылды") to
            appText("Открой — там комментарий и кнопка «Подать снова».", "Ас — унда комментарий һәм «Ҡабат биреү» төймәһе.")
        else -> appText("Стать таксистом Юлдаша", "Юлдаш таксисы булыу") to
            appText("Комиссия 3–8% и заказы рядом. Пройди проверку — и выходи на линию.", "Комиссия 3–8% һәм яҡындағы заказдар. Тикшереү үт — һәм линияға сыҡ.")
    }
    val accent = if (app?.status == "rejected") CanonRed else CanonTaxi
    Surface(
        onClick = onClick,
        color = CanonTaxiBg, shape = CanonItemShape,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = CanonTaxi) {
                Icon(Icons.Default.LocalTaxi, contentDescription = appText("Такси", "Такси"), tint = CanonTaxiInk, modifier = Modifier.padding(10.dp).size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp)
                Text(sub, color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = appText("Открыть", "Асыу"), tint = CanonMuted)
        }
    }
}

/**
 * «Спрос рядом» для водителя — где сейчас чаще ищут попутку. Анонимно: только агрегированные зоны
 * с сервера (координаты + вес + число заявок), без личности пассажиров. Показываем компактным списком
 * «Зона N · ищут: M», отсортированным по весу; чем выше вес — тем ярче/крупнее зелёный индикатор.
 * Загружаем при входе и, пока водитель на линии, мягко обновляем раз в 60с (вне линии — «Пока тихо»).
 */
@Composable
internal fun DriverDemandSection(online: Boolean) {
    var zones by remember { mutableStateOf<List<com.yuldash.app.data.DemandZoneDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    // Вне линии сервер не дёргаем (экономим квоту) — показываем спокойное «Пока тихо».
    // На линии: разовая загрузка + мягкий авто-refresh раз в минуту, пока секция в композиции.
    LaunchedEffect(online, reload) {
        if (!online) { zones = emptyList(); loading = false; loadError = false; return@LaunchedEffect }
        loading = zones.isEmpty()
        while (true) {
            ApiClient.getInstantDemand()
                .onSuccess { zones = it.zones; loadError = false }
                .onFailure { if (zones.isEmpty()) loadError = true }
            loading = false
            delay(60_000)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(
            appText("Спрос рядом", "Яҡында ихтыяж"),
            appText("Где сейчас чаще ищут попутку", "Хәҙер юлдашты нисә ерҙә йышыраҡ эҙләй"),
        )
        when {
            !online -> EmptyStateCard(
                title = appText("Пока тихо", "Әлегә тыныс"),
                text = appText("Выйди на линию — покажем, где сейчас ищут попутку.", "Линияға сыҡ — юлдашты ҡайҙа эҙләгәнен күрһәтербеҙ."),
                icon = Icons.Default.TravelExplore,
            )
            loading && zones.isEmpty() -> SkeletonCard(lines = 3)
            loadError && zones.isEmpty() -> AppErrorState(
                onRetry = { reload++ },
                title = appText("Не удалось загрузить спрос", "Ихтыяжды тейеп булманы"),
            )
            zones.isEmpty() -> EmptyStateCard(
                title = appText("Пока тихо", "Әлегә тыныс"),
                text = appText("Рядом никто не ищет попутку. Мы сообщим, как появятся заказы.", "Яҡында бер кем дә юлдаш эҙләмәй. Заказ килеү менән хәбәр итербеҙ."),
                icon = Icons.Default.TravelExplore,
            )
            else -> {
                val maxWeight = zones.maxOf { it.weight }.coerceAtLeast(0.0001)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CanonSurface),
                    shape = CanonCardShape,
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    border = BorderStroke(1.dp, CanonBorder),
                ) {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        zones.take(6).forEachIndexed { i, z ->
                            val norm = (z.weight / maxWeight).toFloat().coerceIn(0f, 1f)
                            // Индикатор веса: размер и насыщенность зелёного ∝ спросу (Canon-зелёный).
                            // Цель включаем после первой композиции и с лёгким каскадом сверху вниз —
                            // иначе точки просто «есть», рост спроса не читается.
                            var dotTarget by remember { mutableStateOf(0f) }
                            LaunchedEffect(norm) { delay(60L * i); dotTarget = norm }
                            val dot by animateFloatAsState(targetValue = dotTarget, animationSpec = tween(520), label = "demandDot")
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    Modifier
                                        .size((12 + 12 * dot).dp)
                                        .clip(CircleShape)
                                        .background(CanonGreen.copy(alpha = 0.35f + 0.55f * dot)),
                                )
                                Text(
                                    appText("Зона ${i + 1}", "${i + 1}-се зона"),
                                    color = CanonText, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    appText("ищут: ${z.requests}", "эҙләй: ${z.requests}"),
                                    color = if (norm > 0.66f) CanonGreen else CanonMuted,
                                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                )
                            }
                            if (i < zones.take(6).lastIndex) {
                                Box(Modifier.fillMaxWidth().padding(start = 40.dp).height(1.dp).background(CanonBorder))
                            }
                        }
                    }
                }
                Text(
                    appText("Где ярче — там чаще ищут. Показываем только зоны, без личных данных.",
                        "Ҡайҙа яҡтыраҡ — шунда йышыраҡ эҙләй. Тик зоналар, шәхси мәғлүмәтһеҙ."),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 16.sp,
                )
            }
        }
    }
}

/** Шапка карточки пассажира в кабинете водителя: буква-аватар, имя, маршрут, его рейтинг. */
@Composable
private fun PassengerRow(b: com.yuldash.app.data.DriverBookingDto) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).background(CanonMint, CircleShape), contentAlignment = Alignment.Center) {
            Text(b.passengerName.take(1).uppercase(), fontWeight = FontWeight.Black, color = CanonGreen2)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(b.passengerName, fontWeight = FontWeight.Bold, color = CanonText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (b.route.isNotBlank()) {
                Text(b.route, color = CanonMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        b.passengerRating?.let { r ->
            Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(3.dp))
            Text(String.format(java.util.Locale.US, "%.1f", r), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CanonText)
        }
    }
}

/**
 * Чистый рендер кабинета водителя: тумблер «на линии», метрики (маршруты/свободно/рейтинг),
 * пусто-заглушка или список опубликованных маршрутов, блок «оцените пассажиров» и нижние действия.
 * Сеть/стейт (online-переключение, оценка) вынесены в колбэки → без сети/эффектов → тестируется на JVM.
 * Поведение 1-в-1 с обёрткой [DriverCabinetScreen]. `stars` — локальный UI-стейт звёзд, сети не трогает.
 */
@Composable
internal fun DriverCabinetContent(
    online: Boolean,
    driverRides: List<Ride>,
    ridesError: Boolean = false,           // true → загрузка маршрутов упала по сети (не «пусто»)
    ridesLoading: Boolean = false,         // true → идёт первая загрузка → скелетон (не «пусто»)
    onRetryRides: () -> Unit = {},
    driverBookings: List<com.yuldash.app.data.DriverBookingDto>,
    ratingText: String,
    onToggleOnline: (Boolean) -> Unit,
    // (bookingId, звёзды, обратный вызов «ушло/не ушло») — карточка должна знать исход,
    // иначе после сбоя сети на экране остаётся оценка, которой на сервере нет.
    onRate: (Int, Int, (Boolean) -> Unit) -> Unit,
    isWomanDriver: Boolean = false,                       // F9: opt-in «женщина за рулём»
    onToggleWoman: (Boolean) -> Unit = {},
    onCreateRide: () -> Unit,
    onVerifyDriver: () -> Unit,
    onBoost: () -> Unit,
    onRequestsFeed: () -> Unit,
    onMyResponses: () -> Unit = {},
    archive: List<com.yuldash.app.data.RideDto> = emptyList(),
    archiveLoading: Boolean = false,
    archiveError: Boolean = false,
    onRetryArchive: () -> Unit = {},
    onConfirmBooking: (Int) -> Unit = {},   // F2: подтвердить бронь (id) — пассажиру откроются телефон/точка
    onRejectBooking: (Int) -> Unit = {},    // F2: отклонить бронь (id) — места вернутся в поездку
    onCancelRide: (Int) -> Unit = {},     // F1: снять поездку (id) — сервер уведомит пассажиров
    onCompleteRide: (Int) -> Unit = {},   // F1: завершить рейс (id)
    onEditRide: (Int, Int?, String?) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
    debt: com.yuldash.app.data.DriverDebtDto? = null,
    onDeclareDebtPaid: () -> Unit = {},
    // Гейт такси (580-ФЗ): taxiAppLoaded=true и заявка не approved → вместо тумблера CTA «Стать таксистом».
    taxiApplication: com.yuldash.app.data.TaxiApplicationDto? = null,
    taxiAppLoaded: Boolean = false,
    onTaxiOnboarding: () -> Unit = {},
    // Зона работы таксиста (география, волна 2): чип «Где вожу» под тумблером «Я на линии».
    zone: com.yuldash.app.data.InstantZoneDto? = null,
    onZoneClick: () -> Unit = {},
    // Смена такси (волна 2, §8 Отдых): прогресс «На линии X из 8» / карточка отдыха.
    workday: com.yuldash.app.data.TaxiWorkdayDto? = null,
    // Ограничения качества (§9): карточка «Мои ограничения» (пусто → не показывается).
    restrictions: com.yuldash.app.data.RestrictionsDto? = null,
    // F17: слот «Регулярные маршруты» (сеть/стейт снаружи → Content остаётся чистым и тестируемым).
    scheduleSection: (@Composable () -> Unit)? = null,
    // «Спрос рядом» — карта/список зон, где сейчас чаще ищут попутку (только для одобренного таксиста).
    demandSection: (@Composable () -> Unit)? = null,
    onWallet: () -> Unit = {},       // Кошелёк: баланс + история операций
    onEarnings: () -> Unit = {},     // «Мой заработок»: по периодам + по дням
    onTaxiRides: () -> Unit = {},    // «Мои поездки такси»: расшифровка денег по каждой поездке
    onTaxiDocs: () -> Unit = {},     // 580-ФЗ: сроки документов
    onPretrip: () -> Unit = {},      // 580-ФЗ: готовность к работе на сегодня
) {
    // Счётчики архива: рейсов сделано = завершённые; пассажиров отвезено = сумма занятых мест по завершённым.
    val ridesDone = archive.count { it.status == "done" }
    val passengersServed = archive.filter { it.status == "done" }.sumOf { (it.seatsTotal - it.seatsLeft).coerceAtLeast(0) }
    // F1: подтверждение отмены — отмена каскадно снимает брони пассажиров, случайный тап недопустим.
    var cancelTarget by remember { mutableStateOf<Ride?>(null) }
    cancelTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            title = { Text(appText("Снять поездку?", "Сәфәрҙе алырғамы?"), fontWeight = FontWeight.Black) },
            text = { Text(appText(
                "${target.from} → ${target.to}. Все брони пассажиров будут отменены, им придёт уведомление.",
                "${target.from} → ${target.to}. Пассажирҙарҙың бөтә брондары кире алына, уларға хәбәр килә."
            )) },
            confirmButton = {
                Button(
                    onClick = { target.id.toIntOrNull()?.let(onCancelRide); cancelTarget = null },
                    colors = ButtonDefaults.buttonColors(containerColor = CanonRed)
                ) { Text(appText("Снять поездку", "Сәфәрҙе алыу"), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                OutlinedButton(onClick = { cancelTarget = null }) { Text(appText("Оставить", "Ҡалдырыу")) }
            }
        )
    }
    // F3: какая поездка правится сейчас (null = диалог закрыт).
    var editing by remember { mutableStateOf<Ride?>(null) }
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text(appText("Маршруты и проверка", "Маршруттар һәм тикшереү"), color = CanonGreen, fontSize = 25.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black)
            Text(appText("Публикуйте поездки, проходите проверку и поднимайте маршрут выше.", "Сәфәр баҫтырығыҙ, тикшереү үтегеҙ һәм маршрутты өҫкә күтәрегеҙ."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp)
        }
        // Долг по комиссии за такси (Модель А «на доверии»): баннер только если есть что платить/подтверждать.
        if (debt != null && (debt.unpaidKop > 0 || debt.pendingKop > 0)) {
            item { DriverDebtBanner(debt, onDeclareDebtPaid) }
        }
        // §9 Качество: активные ограничения (пауза такси по жалобам) + «написать в поддержку».
        if (restrictions != null && restrictions.items.isNotEmpty()) {
            item { RestrictionsCard(restrictions) }
        }
        item {
            // Гейт такси (580-ФЗ): «на линию» может выйти только одобренный таксист.
            // Пока статус заявки не загружен — тумблер как раньше (сервер всё равно гейтит).
            if (!taxiAppLoaded || taxiApplication?.status == "approved") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsGroup {
                        SettingSwitchRow(
                            Icons.Default.DirectionsCar,
                            appText("Я на линии", "Мин эштә"),
                            appText("Пассажиры видят, что вы готовы везти сейчас", "Пассажирҙар хәҙер әҙер икәнегеҙҙе күрә"),
                            online,
                            onToggleOnline,
                        )
                    }
                    // Тумблер сам по себе — это «галочка включена», а не «меня видят».
                    // Плашка отвечает на настоящий вопрос водителя: заказы точно придут сюда?
                    DriverOnlineHint(online = online)
                    // Зона работы (география, волна 2): «🏙 Мой город / 🛣 Межгород / 🌍 Соседний регион».
                    DriverZoneChip(zone = zone, onClick = onZoneClick)
                }
            } else {
                TaxiOnboardingCta(taxiApplication, onTaxiOnboarding)
            }
        }
        // «Спрос рядом» — где сейчас чаще ищут попутку (подсказка водителю, куда ехать). Анонимно.
        demandSection?.let { section -> item { section() } }
        // Смена такси (волна 2, §8 Отдых): блок отдыха («8 часов за рулём» + «один попутчик
        // домой») или прогресс к лимиту — показываем только одобренному таксисту и только
        // когда есть что показать (на линии / время уже капало / отдых).
        if (workday != null && (!taxiAppLoaded || taxiApplication?.status == "approved")) {
            // Дашборд: заработок за сегодня + лесенка комиссии — когда таксист активен сегодня.
            if (online || workday.secondsOnline > 0 || workday.ordersToday > 0) {
                item { TaxiDashboardCard(workday) }
            }
            if (workday.blocked) {
                item { TaxiRestCard(workday, onCreateRide) }
            } else if (online || workday.secondsOnline > 0) {
                item { TaxiShiftProgressCard(workday) }
            }
        }
        // F9 «Женщинам — водитель-женщина»: строго по желанию. Женщина за рулём может
        // показать это пассажиркам; мужской пол нигде не запрашиваем и не показываем.
        item {
            SettingsGroup {
                SettingSwitchRow(
                    Icons.Default.Woman,
                    appText("Я — женщина за рулём", "Мин — рулдә ҡатын-ҡыҙ"),
                    appText("По желанию: пассажирки увидят бейдж «за рулём женщина»", "Теләк буйынса: пассажир ҡатын-ҡыҙҙар «рулдә ҡатын-ҡыҙ» билдәһен күрер"),
                    isWomanDriver,
                    onToggleWoman,
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CabinetMetric(appText("Мои маршруты", "Минең маршруттар"), driverRides.size.toString(), Modifier.weight(1f))
                CabinetMetric(appText("Свободно", "Буш"), driverRides.sumOf { it.seats }.toString(), Modifier.weight(1f))
                CabinetMetric(appText("Рейтинг", "Рейтинг"), ratingText, Modifier.weight(1f))
            }
        }
        if (driverRides.isEmpty() && ridesLoading) {
            item { SkeletonCard(lines = 2) }
        } else if (driverRides.isEmpty() && ridesError) {
            // Сеть упала — не выдаём это за «нет маршрутов», даём «Повторить».
            item {
                EmptyStateCard(
                    title = appText("Не удалось загрузить маршруты", "Маршруттарҙы йөкләп булманы"),
                    text = appText("Проверь интернет и попробуй ещё раз.", "Интернетты тикшереп, яңынан ҡабатла."),
                    icon = Icons.Default.DirectionsCar,
                    action = appText("Повторить", "Ҡабатлау"),
                    onAction = onRetryRides
                )
            }
        } else if (driverRides.isEmpty()) {
            item {
                EmptyStateCard(
                    title = appText("Ваших маршрутов пока нет", "Һеҙҙең маршруттар әлегә юҡ"),
                    text = appText("Опубликуйте поездку, чтобы пассажиры могли откликнуться.", "Пассажирҙар яуап бирһен өсөн сәфәр баҫтырығыҙ."),
                    icon = Icons.Default.DirectionsCar,
                    action = appText("Опубликовать маршрут", "Маршрут баҫтырыу"),
                    onAction = onCreateRide
                )
            }
        } else {
            items(driverRides, key = { it.id }) { ride ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MyTripCard(
                        ride = ride,
                        status = appText("Опубликована", "Баҫтырылды"),
                        statusColor = CanonMint,
                        icon = Icons.Default.DirectionsCar,
                        primaryAction = appText("Поднять", "Күтәреү"),
                        secondaryAction = appText("Новый маршрут", "Яңы маршрут"),
                        onPrimary = onBoost,
                        onSecondary = onCreateRide
                    )
                    // F1: управление рейсом — завершить (брони → done, пассажирам «оцените») или снять (с подтверждением).
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { ride.id.toIntOrNull()?.let(onCompleteRide) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(appText("Завершить рейс", "Рейсты тамамлау"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                        OutlinedButton(
                            onClick = { cancelTarget = ride },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(appText("Снять поездку", "Сәфәрҙе алыу"), color = CanonRed, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                    }
                    // F3: правка цены и комментария. Если поездку уже забронировали — сервер
                    // разрешит только снизить цену и поправить комментарий (иначе понятная ошибка).
                    TextButton(
                        onClick = { editing = ride },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = CanonGreen2)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = appText("Изменить поездку", "Сәфәрҙе үҙгәртеү"), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(appText("Изменить цену и комментарий", "Хаҡ һәм аңлатма үҙгәртеү"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
        // F2: брони, ждущие подтверждения водителя. Пока не подтвердишь — пассажир не видит
        // твой телефон и точку сбора, а live-гео не подключится. Главная кнопка кабинета.
        val pendingBookings = driverBookings.filter { it.status == "pending" }
        if (pendingBookings.isNotEmpty()) {
            item {
                // Тот же SectionHeader, что в «Архиве» — единая типографика разделов.
                // Подпись объясняет, почему это главная кнопка кабинета.
                SectionHeader(
                    appText("Ждут подтверждения", "Раҫлауҙы көтәләр"),
                    appText(
                        "Пока не подтвердишь — пассажир не видит телефон и точку сбора.",
                        "Раҫламағансы — пассажир телефонды ла, йыйылыу урынын да күрмәй.",
                    ),
                )
            }
            items(pendingBookings, key = { "pend-${it.bookingId}" }) { b ->
                Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).background(CanonMint, CircleShape), contentAlignment = Alignment.Center) {
                                Text(b.passengerName.take(1).uppercase(), fontWeight = FontWeight.Black, color = CanonGreen2)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(b.passengerName, fontWeight = FontWeight.Bold, color = CanonText)
                                Text(b.route, color = CanonMuted, fontSize = 13.sp)
                            }
                            b.passengerRating?.let { r ->
                                Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(2.dp))
                                Text(String.format(java.util.Locale.US, "%.1f", r), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CanonText)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { onConfirmBooking(b.bookingId) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                            ) { Text(appText("Подтвердить", "Раҫлау"), fontWeight = FontWeight.Bold) }
                            OutlinedButton(
                                onClick = { onRejectBooking(b.bookingId) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text(appText("Отклонить", "Кире ҡағыу"), color = CanonRed, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
        }
        // Едут прямо сейчас: подтверждённые и уже в пути. Оценивать их нельзя (поездка не
        // состоялась — сервер вернёт 409), а вот «пассажир не вышел» нужно именно тут.
        val ridingBookings = driverBookings.filter { it.status == "confirmed" || it.status == "onboard" }
        if (ridingBookings.isNotEmpty()) {
            item {
                SectionHeader(
                    appText("Едут с тобой", "Һинең менән баралар"),
                    appText(
                        "Поездка ещё не закончилась. Оценить сможешь, когда завершишь рейс.",
                        "Сәфәр әле тамамланманы. Рейсты тамамлағас баһалай алаһың.",
                    ),
                )
            }
            items(ridingBookings, key = { "ride-${it.bookingId}" }) { b ->
                Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PassengerRow(b)
                        // «Не явился» — пассажир не пришёл на посадку.
                        NoShowButton(bookingId = b.bookingId)
                    }
                }
            }
        }
        // Оценивать можно ТОЛЬКО завершённые поездки: раньше в списке висели брони, которые
        // ещё не состоялись, и тап по звезде отвечал «Не получилось оценить» — сервер их
        // не принимает (иначе рейтинг можно накрутить, не съездив).
        val ratableBookings = driverBookings.filter { it.status == "done" }
        if (ratableBookings.isNotEmpty()) {
            item {
                SectionHeader(
                    appText("Пассажиры — оцените после поездки", "Пассажирҙар — сәфәрҙән һуң баһалағыҙ"),
                    appText(
                        "Честные оценки берегут круг «своих» — их видят другие водители.",
                        "Ғәҙел баһалар «үҙебеҙҙекеләр» түңәрәген һаҡлай — башҡа водителдәр ҙә күрә.",
                    ),
                )
            }
            items(ratableBookings, key = { it.bookingId }) { b ->
                // Оценка ставится в ДВА шага. Раньше первое касание сразу уходило на сервер:
                // промахнулся пальцем по первой звезде — человеку упал единицей рейтинг,
                // и вернуть было нечем. Теперь звёзды выбираешь, потом подтверждаешь.
                var stars by remember(b.bookingId, b.myStars) { mutableStateOf(b.myStars) }
                var editing by remember(b.bookingId, b.myStars) { mutableStateOf(b.myStars == 0) }
                var sending by remember(b.bookingId, b.myStars) { mutableStateOf(false) }
                Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PassengerRow(b)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            (1..5).forEach { n ->
                                val lit = n <= stars
                                // Звезда «зажигается»: цвет и размер догоняют выбор — оценка
                                // ощущается нажатием, а не молчаливой сменой картинки.
                                val starTint by animateColorAsState(if (lit) CanonStar else CanonMuted, tween(240), label = "starTint")
                                val starScale by animateFloatAsState(if (lit) 1.14f else 1f, tween(240), label = "starScale")
                                // Тач-цель ≥48dp (иконка визуально 34dp внутри).
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .then(
                                            if (editing && !sending) Modifier.clickable { stars = n } else Modifier
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = starsText(n),
                                        tint = starTint,
                                        modifier = Modifier.size(34.dp).scale(starScale),
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            // Уже оценил — показываем сколько и даём переставить. Сервер оценку
                            // разрешает изменить, значит и в приложении это не тупик.
                            if (!editing) {
                                TextButton(
                                    onClick = { editing = true },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                    colors = ButtonDefaults.textButtonColors(contentColor = CanonGreen2),
                                ) { Text(appText("Изменить", "Үҙгәртеү"), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                            }
                        }
                        // Подпись под звёздами = состояние словами. Незрячим она же читает оценку.
                        Text(
                            when {
                                !editing -> appText("Вы поставили ${starsText(stars)}", "Һеҙ ${starsText(stars)} ҡуйҙығыҙ")
                                stars == 0 -> appText("Выберите оценку", "Баһа һайлағыҙ")
                                else -> appText("Выбрано ${starsText(stars)} — подтвердите", "${starsText(stars)} һайланды — раҫлағыҙ")
                            },
                            color = CanonMuted, fontSize = 12.sp,
                        )
                        AnimatedVisibility(visible = editing && stars > 0) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        sending = true
                                        onRate(b.bookingId, stars) { ok ->
                                            sending = false
                                            // Не ушло — возвращаем как было, чтобы на экране не
                                            // осталась «поставленная» оценка, которой нет на сервере.
                                            if (ok) editing = false else stars = b.myStars
                                        }
                                    },
                                    enabled = !sending,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                                ) {
                                    Text(
                                        if (sending) appText("Отправляем…", "Ебәрәбеҙ…")
                                        else appText("Отправить оценку", "Баһаны ебәреү"),
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                // Отмена нужна только когда есть что отменять — уже поставленную раньше оценку.
                                if (b.myStars > 0) {
                                    OutlinedButton(
                                        onClick = { stars = b.myStars; editing = false },
                                        enabled = !sending,
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        shape = RoundedCornerShape(12.dp),
                                    ) { Text(appText("Отмена", "Кире алыу"), color = CanonMuted) }
                                }
                            }
                        }
                        // B8-7: «пассажир не заплатил» одним тапом — только по завершённой поездке.
                        UnpaidReportButton(bookingId = b.bookingId)
                    }
                }
            }
        }
        // ─── Архив: прошлые поездки + счётчики (рейсов сделано / пассажиров отвезено) ───
        item {
            SectionHeader(
                appText("Архив поездок", "Сәфәрҙәр архивы"),
                appText("Что уже отъездил — рейсы, пассажиры, история.", "Нимә инде үткәрелгән — рейстар, пассажирҙар, тарих."),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CabinetMetric(appText("Рейсов сделано", "Рейс эшләнде"), ridesDone.toString(), Modifier.weight(1f))
                CabinetMetric(appText("Пассажиров отвезено", "Пассажир йөрөтөлдө"), passengersServed.toString(), Modifier.weight(1f))
            }
        }
        when {
            archiveLoading && archive.isEmpty() -> item { SkeletonCard(lines = 2) }
            archiveError && archive.isEmpty() -> item { AppErrorState(onRetry = onRetryArchive) }
            archive.isEmpty() -> item {
                EmptyStateCard(
                    title = appText("Архив пока пуст", "Архив әлегә буш"),
                    text = appText("Завершённые и отменённые поездки появятся здесь.", "Тамамланған һәм баш тартылған сәфәрҙәр бында күренер."),
                    icon = Icons.Default.History,
                )
            }
            else -> items(archive, key = { "arch-${it.id}" }) { ride -> ArchiveRideCard(ride) }
        }
        scheduleSection?.let { section -> item { section() } }
        // «Мой заработок» + «Кошелёк» — рядом с Архивом: деньги водителя одним разделом.
        item {
            SettingsGroup {
                SettingsNavRow(Icons.Default.Insights, appText("Мой заработок", "Минең табыш"), appText("Заработок по неделям, месяцам и дням", "Аҙна, ай һәм көн буйынса табыш"), onClick = onEarnings)
                // Расшифровка по каждой поездке — закрывает «Юлдаш говорит 4200, я насчитал 4600».
                SettingsNavRow(Icons.Default.ReceiptLong, appText("Мои поездки такси", "Такси сәфәрҙәрем"), appText("Цена, комиссия и сколько осталось тебе", "Хаҡ, комиссия һәм һиңә күпме ҡалды"), onClick = onTaxiRides)
                SettingsNavRow(Icons.Default.AccountBalanceWallet, appText("Кошелёк", "Янсыҡ"), appText("Баланс и история операций", "Баланс һәм операциялар тарихы"), onClick = onWallet)
            }
        }
        item {
            SettingsGroup {
                SettingsNavRow(Icons.Default.ListAlt, appText("Заявки пассажиров", "Пассажир заявкалары"), appText("Откликнуться и предложить поездку", "Яуап биреп сәфәр тәҡдим итеү"), onClick = onRequestsFeed)
                // Торг о цене: раньше водитель после отклика не видел ничего — встречную цену пассажира
                // он мог узнать только из пуша, и, пропустив его, терял поездку.
                SettingsNavRow(Icons.Default.Handshake, appText("Мои отклики", "Минең яуаптарым"), appText("Торг о цене: принять встречную или предложить свою", "Хаҡ буйынса һатыулашыу: ҡаршы хаҡты ҡабул итеү йәки үҙеңдекен тәҡдим итеү"), onClick = onMyResponses)
                SettingsNavRow(Icons.Default.AddRoad, appText("Создать поездку", "Сәфәр булдырыу"), appText("Маршрут, места, цена и время", "Маршрут, урын, хаҡ һәм ваҡыт"), onClick = onCreateRide)
                SettingsNavRow(Icons.Default.Verified, appText("Проверка водителя", "Водителде тикшереү"), appText("Права, машина, фото и госномер", "Права, машина, фото һәм номер"), onClick = onVerifyDriver)
                // 580-ФЗ: проверка перестала быть разовой — сроки живут и напоминают о себе сами.
                SettingsNavRow(Icons.Default.Shield, appText("Документы и сроки", "Документтар һәм ваҡыттар"), appText("ОСАГО, разрешение, техосмотр — продлить без новой заявки", "ОСАГО, рөхсәт, техник ҡарау — яңы заявкаһыҙ оҙайтыу"), onClick = onTaxiDocs)
                SettingsNavRow(Icons.Default.MonitorHeart, appText("Готовность к работе", "Эшкә әҙерлек"), appText("Отметить перед выходом на линию: самочувствие, машина", "Линияға сығыр алдынан билдәләү: һаулыҡ, машина"), onClick = onPretrip)
                SettingsNavRow(Icons.Default.TrendingUp, appText("Поднять маршрут", "Маршрутты күтәреү"), appText("Показать выше в списке поездок", "Сәфәрҙәр исемлегендә өҫтәрәк күрһәтеү"), onClick = onBoost)
            }
        }
    }

    // F3: диалог правки поездки. Цена — число; комментарий — свободный текст (напр. «заеду через Темясово»).
    editing?.let { ride ->
        var priceText by remember(ride.id) { mutableStateOf(ride.price.toString()) }
        var commentText by remember(ride.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editing = null },
            containerColor = CanonSurface,
            shape = CanonCardShape,
            title = { Text(appText("Изменить поездку", "Сәфәрҙе үҙгәртеү"), fontWeight = FontWeight.Black, fontSize = 20.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${ride.from} → ${ride.to}", color = CanonMuted, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { s -> priceText = s.filter { it.isDigit() }.take(6) },
                        label = { Text(appText("Цена, ₽", "Хаҡ, ₽")) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = commentText,
                        onValueChange = { if (it.length <= 2000) commentText = it },
                        label = { Text(appText("Комментарий (по желанию)", "Аңлатма (теләк буйынса)")) },
                        placeholder = { Text(appText("Напр. заеду через Темясово", "Мәҫ. Темәс аша үтәм")) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        appText("Если поездку уже забронировали — цену можно только снизить.", "Әгәр сәфәр брондалған булһа — хаҡты кәметергә генә була."),
                        color = CanonMuted, fontSize = 12.sp, lineHeight = 16.sp
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val id = ride.id.toIntOrNull()
                        if (id != null) {
                            val newPrice = priceText.toIntOrNull()?.takeIf { it != ride.price }
                            val newComment = commentText.trim().takeIf { it.isNotEmpty() }
                            onEditRide(id, newPrice, newComment)
                        }
                        editing = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = CanonGreen2)
                ) { Text(appText("Сохранить", "Һаҡлау"), fontWeight = FontWeight.Black) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }, colors = ButtonDefaults.textButtonColors(contentColor = CanonMuted)) {
                    Text(appText("Отмена", "Баш тартыу"))
                }
            }
        )
    }
}

/** Карточка прошлой поездки в «Архиве»: маршрут, дата, цена + бейдж статуса (завершена/отменена). */
@Composable
internal fun ArchiveRideCard(ride: com.yuldash.app.data.RideDto) {
    val done = ride.status == "done"
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(38.dp).background(if (done) CanonMint else CanonDangerBg, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (done) Icons.Default.CheckCircle else Icons.Default.Cancel,
                    contentDescription = if (done) appText("Завершена", "Тамамланды") else appText("Отменена", "Баш тартылды"),
                    tint = if (done) CanonGreen2 else CanonRed,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${ride.fromCity} → ${ride.toCity}", color = CanonText, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    formatDepart(ride.departAt) + " · " + (if (done) appText("Завершена", "Тамамланды") else appText("Отменена", "Баш тартылды")),
                    color = CanonMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (ride.price > 0) Text("${ride.price} ₽", color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 15.sp)
        }
    }
}

@Composable
internal fun CabinetMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = CanonSurface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = CanonGreen2, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Text(label, color = CanonMuted, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

// --- F17: Постоянные (регулярные) маршруты водителя ---
// ISO-дни недели 1=Пн..7=Вс. Короткие подписи для чипов. BA — черновик (docs/tasks.md).
private val WEEKDAY_ORDER = listOf(1, 2, 3, 4, 5, 6, 7)

@Composable
private fun weekdayShort(iso: Int): String = when (iso) {
    1 -> appText("Пн", "Дш"); 2 -> appText("Вт", "Шш"); 3 -> appText("Ср", "Шр")
    4 -> appText("Чт", "Кс"); 5 -> appText("Пт", "Йм"); 6 -> appText("Сб", "Шб")
    else -> appText("Вс", "Йҡ")
}

/** CSV "1,3,5" → "Пн · Ср · Пт" (или «Каждый день» / «По будням» / «Выходные»). */
@Composable
private fun weekdaysSummary(csv: String): String {
    val days = csv.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..7 }.sorted()
    if (days.isEmpty()) return appText("—", "—")
    if (days == WEEKDAY_ORDER) return appText("Каждый день", "Һәр көн")
    if (days == listOf(1, 2, 3, 4, 5)) return appText("По будням", "Эш көндәре")
    if (days == listOf(6, 7)) return appText("Выходные", "Ял көндәре")
    // Ярлыки считаем в @Composable-теле (appText требует контекста), лямбда joinToString — только индекс.
    val short = listOf(
        appText("Пн", "Дш"), appText("Вт", "Шш"), appText("Ср", "Шр"),
        appText("Чт", "Кс"), appText("Пт", "Йм"), appText("Сб", "Шб"), appText("Вс", "Йҡ"),
    )
    return days.joinToString(" · ") { short[it - 1] }
}

/**
 * Блок «Регулярные маршруты» в кабинете водителя (F17): свои постоянные направления
 * (маршрут + дни недели + время), добавить/удалить. Сам держит стейт и сеть, поэтому
 * подключается слотом в [DriverCabinetContent] (Content остаётся чистым/тестируемым).
 */
@Composable
internal fun DriverScheduleSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val loggedIn = ApiClient.isLoggedIn()
    var schedules by remember { mutableStateOf<List<DriverScheduleDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    val delFailMsg = appText("Не получилось удалить. Повтори.", "Юйып булманы. Ҡабатла.")

    LaunchedEffect(reload) {
        if (!loggedIn) { loading = false; error = false; return@LaunchedEffect }
        loading = true; error = false
        ApiClient.getMyDriverSchedules()
            .onSuccess { schedules = it; loading = false }
            .onFailure { error = true; loading = false }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(CanonMint, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Schedule, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(appText("Регулярные маршруты", "Даими маршруттар"), fontWeight = FontWeight.Black, fontSize = 17.sp, color = CanonText)
                    Text(appText("Езжу постоянно — покажем в профиле и поиске", "Даими йөрөйөм — профилдә һәм эҙләүҙә күрһәтәбеҙ"), color = CanonMuted, fontSize = 12.sp, lineHeight = 15.sp)
                }
            }

            when {
                loading -> {
                    SkeletonBox(height = 54.dp)
                    SkeletonBox(height = 54.dp)
                }
                error -> {
                    Text(appText("Не удалось загрузить маршруты.", "Маршруттарҙы йөкләп булманы."), color = CanonMuted, fontSize = 13.sp)
                    AppButton(
                        text = appText("Повторить", "Ҡабатларға"),
                        onClick = { reload++ },
                        style = AppButtonStyle.Secondary,
                        icon = Icons.Default.Refresh,
                        height = 48.dp,
                    )
                }
                !loggedIn -> {
                    Text(
                        appText("Войди, чтобы добавить постоянный маршрут.", "Даими маршрут өҫтәр өсөн ин."),
                        color = CanonMuted, fontSize = 13.sp,
                    )
                }
                schedules.isEmpty() -> {
                    Text(
                        appText("Пока нет регулярных маршрутов. Добавь, чтобы пассажиры знали, когда ты ездишь.",
                            "Әле даими маршруттар юҡ. Пассажирҙар ҡасан йөрөгәнеңде белһен өсөн өҫтә."),
                        color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                }
                else -> {
                    schedules.forEach { s ->
                        ScheduleRow(
                            schedule = s,
                            onDelete = {
                                val prev = schedules
                                schedules = schedules.filterNot { it.id == s.id }   // оптимистично
                                scope.launch {
                                    ApiClient.deleteDriverSchedule(s.id).onFailure {
                                        schedules = prev
                                        Toast.makeText(ctx, delFailMsg, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                        )
                    }
                }
            }

            if (loggedIn && !loading) {
                AppButton(
                    text = appText("Добавить маршрут", "Маршрут өҫтәү"),
                    onClick = { showAdd = true },
                    style = AppButtonStyle.Secondary,
                    icon = Icons.Default.Add,
                    height = 50.dp,
                )
            }
        }
    }

    if (showAdd) {
        AddScheduleDialog(
            onDismiss = { showAdd = false },
            onSaved = { created ->
                schedules = listOf(created) + schedules
                showAdd = false
            },
        )
    }
}

@Composable
private fun ScheduleRow(schedule: DriverScheduleDto, onDelete: () -> Unit) {
    Surface(color = CanonBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Row(
            Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${schedule.fromCity} → ${schedule.toCity}",
                    fontWeight = FontWeight.Bold, fontSize = 15.sp, color = CanonText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(weekdaysSummary(schedule.weekdays), color = CanonGreen2, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (schedule.time.isNotBlank()) {
                        Text("  ·  ${schedule.time}", color = CanonMuted, fontSize = 12.sp)
                    }
                }
                if (schedule.comment.isNotBlank()) {
                    Text(schedule.comment, color = CanonMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Delete, contentDescription = appText("Удалить", "Юйыу"), tint = CanonMuted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddScheduleDialog(onDismiss: () -> Unit, onSaved: (DriverScheduleDto) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    val selectedDays = remember { mutableStateListOf<Int>() }
    var saving by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = CanonGreen2, cursorColor = CanonGreen2,
        focusedLabelColor = CanonGreen2,
    )
    val canSave = from.isNotBlank() && to.isNotBlank() && selectedDays.isNotEmpty() &&
        time.matches(Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")) && !saving
    val errMsg = appText("Не получилось сохранить. Проверь поля.", "Һаҡлап булманы. Ҡырҙарҙы тикшер.")

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CanonSurface,
        shape = CanonCardShape,
        title = { Text(appText("Регулярный маршрут", "Даими маршрут"), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    from, { from = it.take(80) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(appText("Откуда", "Ҡайҙан")) }, placeholder = { Text(appText("Баймак", "Баймаҡ")) },
                    shape = RoundedCornerShape(14.dp), colors = fieldColors,
                )
                OutlinedTextField(
                    to, { to = it.take(80) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(appText("Куда", "Ҡайҙа")) }, placeholder = { Text(appText("Уфа", "Өфө")) },
                    shape = RoundedCornerShape(14.dp), colors = fieldColors,
                )
                Text(appText("Дни недели", "Аҙна көндәре"), color = CanonMuted, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    WEEKDAY_ORDER.forEach { d ->
                        val on = selectedDays.contains(d)
                        // 48dp — сам чип. Раньше minimumInteractiveComponentSize() стоял ПЕРЕД
                        // clickable: раздвигал раскладку вокруг 44dp-области, но не саму область.
                        val chipBg by animateColorAsState(if (on) CanonGreen2 else CanonBg, tween(180), label = "dayBg")
                        Surface(
                            color = chipBg,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, if (on) CanonGreen2 else CanonBorder),
                            modifier = Modifier.size(48.dp).clickable {
                                if (on) selectedDays.remove(d) else selectedDays.add(d)
                            },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(weekdayShort(d), color = if (on) Color.White else CanonText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                OutlinedTextField(
                    time, { time = it.take(5) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(appText("Время выезда", "Сығыу ваҡыты")) }, placeholder = { Text("08:00") },
                    shape = RoundedCornerShape(14.dp), colors = fieldColors,
                )
                OutlinedTextField(
                    comment, { comment = it.take(200) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(appText("Комментарий (необязательно)", "Аңлатма (мотлаҡ түгел)")) },
                    shape = RoundedCornerShape(14.dp), colors = fieldColors,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    saving = true
                    val days = selectedDays.sorted().joinToString(",")
                    scope.launch {
                        ApiClient.createDriverSchedule(from.trim(), to.trim(), days, time.trim(), comment.trim())
                            .onSuccess { saving = false; onSaved(it) }
                            .onFailure { saving = false; Toast.makeText(ctx, errMsg, Toast.LENGTH_SHORT).show() }
                    }
                },
            ) { Text(appText("Сохранить", "Һаҡлау"), color = if (canSave) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(appText("Отмена", "Кире"), color = CanonMuted) }
        },
    )
}

/**
 * F17-хук для пассажира: публичные регулярные маршруты водителя (read-only) + кнопка
 * «Следить» (связка с route-watch F13). В этой ветке экрана-профиля водителя для
 * пассажира нет — компонент готов к подключению, когда появится. onWatch — заглушка F13.
 */
@Composable
internal fun PublicDriverSchedulesCard(driverId: Int, onWatch: (DriverScheduleDto) -> Unit = {}) {
    var schedules by remember(driverId) { mutableStateOf<List<DriverScheduleDto>>(emptyList()) }
    LaunchedEffect(driverId) {
        ApiClient.getPublicDriverSchedules(driverId).onSuccess { schedules = it }
    }
    if (schedules.isEmpty()) return
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(appText("Ездит регулярно", "Даими йөрөй"), fontWeight = FontWeight.Black, fontSize = 16.sp, color = CanonText)
            schedules.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${s.fromCity} → ${s.toCity}", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = CanonText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(weekdaysSummary(s.weekdays) + (if (s.time.isNotBlank()) "  ·  ${s.time}" else ""), color = CanonGreen2, fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { onWatch(s) }, shape = RoundedCornerShape(12.dp)) {
                        Text(appText("Следить", "Күҙәтеү"), color = CanonGreen2, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// Кабинет рекламодателя (self-serve). Гейт по факту владения: грузим /ads/mine —
// пусто → витрина «стать партнёром», есть → мои объявления со статусами модерации.
@Composable
internal fun AdsCabinetScreen(
    onBack: () -> Unit,
    onCreateAd: () -> Unit,
    onEditAd: (MyAdDto) -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var ads by remember { mutableStateOf<List<MyAdDto>>(emptyList()) }
    var stats by remember { mutableStateOf<Map<String, MyAdStatsDto>>(emptyMap()) }
    var packages by remember { mutableStateOf<List<AdPackageDto>>(emptyList()) }
    var reloadKey by remember { mutableStateOf(0) }
    var submittingId by remember { mutableStateOf<String?>(null) }
    var payingAd by remember { mutableStateOf<MyAdDto?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val errLoad = appText("Не удалось загрузить кабинет", "Кабинетты йөкләп булманы")
    val errSubmit = appText("Не отправилось. Повтори.", "Ебәрелмәне. Ҡабатла.")
    val errPay = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Сеткәне тикшер, ҡабатла.")
    LaunchedEffect(reloadKey) {
        loading = true; error = null
        ApiClient.getAdPackages().onSuccess { packages = it }
        ApiClient.getMyAds()
            .onSuccess { ads = it; loading = false }
            .onFailure { error = errLoad; loading = false }
        // Статистика — второстепенно: не роняет кабинет, если не подгрузилась (плитки просто не покажутся).
        ApiClient.getMyAdsStats().onSuccess { stats = it }
    }
    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Кабинет рекламы", "Реклама кабинеты"), onBack) }
    ) { padding ->
        AdsCabinetContent(
            loading = loading,
            error = error,
            ads = ads,
            packages = packages,
            stats = stats,
            submittingId = submittingId,
            onRetry = { reloadKey++ },
            onCreateAd = onCreateAd,
            onEditAd = onEditAd,
            onSubmitAd = { ad ->
                submittingId = ad.id
                scope.launch {
                    ApiClient.submitMyAd(ad.id)
                        .onSuccess { submittingId = null; reloadKey++ }
                        .onFailure {
                            submittingId = null
                            Toast.makeText(ctx, errSubmit, Toast.LENGTH_SHORT).show()
                        }
                }
            },
            onPayAd = { ad ->
                scope.launch {
                    ApiClient.payAd(ad.id)
                        .onSuccess { payingAd = ad }
                        .onFailure { Toast.makeText(ctx, errPay, Toast.LENGTH_SHORT).show() }
                }
            },
            // Продление: тот же СБП-перевод «на доверии», что и первичная оплата (админ продлит срок вручную).
            onRenewAd = { ad -> payingAd = ad },
            modifier = Modifier.padding(padding),
        )
    }
    // Лист СБП для оплаты своего размещения (переиспользуем общий SbpTransferSheet с QR).
    payingAd?.let { ad ->
        SbpTransferSheet(
            amountRub = ad.budgetKop / 100,
            onPaid = { payingAd = null; reloadKey++ },
            onDismiss = { payingAd = null },
        )
    }
}

/**
 * Чистый рендер кабинета рекламы: все состояния (загрузка / ошибка+повтор / пусто-витрина / список объявлений).
 * Данные и колбэки приходят параметрами → без сети/стейта/эффектов → тестируется на JVM (Robolectric).
 */
@Composable
internal fun AdsCabinetContent(
    loading: Boolean,
    error: String?,
    ads: List<MyAdDto>,
    packages: List<AdPackageDto>,
    stats: Map<String, MyAdStatsDto> = emptyMap(),
    submittingId: String?,
    onRetry: () -> Unit,
    onCreateAd: () -> Unit,
    onEditAd: (MyAdDto) -> Unit,
    onSubmitAd: (MyAdDto) -> Unit,
    onPayAd: (MyAdDto) -> Unit,
    onRenewAd: (MyAdDto) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        when {
            // Скелетон «формой кабинета» вместо голого спиннера: видно, что грузится
            // (кнопка + карточки объявлений), а не пустой экран с кружком посередине.
            loading -> Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SkeletonBox(height = 52.dp, shape = RoundedCornerShape(16.dp))
                SkeletonCard(lines = 3)
                SkeletonCard(lines = 2)
            }
            error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ListedError(error) { onRetry() }
            }
            ads.isEmpty() -> AdsShowcase(packages, Modifier, onCreateAd)
            else -> LazyColumn(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                item {
                    Button(
                        onClick = onCreateAd,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                    ) {
                        Icon(Icons.Default.AddBox, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(appText("Новое объявление", "Яңы иғлан"), fontWeight = FontWeight.Black, color = Color.White)
                    }
                }
                items(ads, key = { it.id }) { ad ->
                    MyAdCard(
                        ad = ad,
                        stats = stats[ad.id],
                        submitting = submittingId == ad.id,
                        onEdit = { onEditAd(ad) },
                        onSubmit = { onSubmitAd(ad) },
                        onPay = { onPayAd(ad) },
                        onRenew = { onRenewAd(ad) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun AdStatusBadge(status: String) {
    val (label, fg, bg) = when (status) {
        "active" -> Triple(appText("Активно", "Актив"), CanonGreen2, CanonMint)
        "pending_review" -> Triple(appText("На модерации", "Тикшереүҙә"), CanonWarn, CanonWarnBg)
        // Подложка отказа — токен CanonDangerBg: пара «CanonRed на CanonDangerBg» проверена
        // по WCAG (4.68/4.70), самодельная прозрачность красного — нет.
        "rejected" -> Triple(appText("Отклонено", "Кире ҡағылды"), CanonRed, CanonDangerBg)
        "paused" -> Triple(appText("На паузе", "Туҡталышта"), CanonMuted, CanonMint)
        "draft" -> Triple(appText("Черновик", "Ҡаралама"), CanonMuted, CanonMint)
        else -> Triple(appText("Завершено", "Тамамланды"), CanonMuted, CanonMint)
    }
    Surface(color = bg, shape = RoundedCornerShape(999.dp)) {
        Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = fg, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
internal fun MyAdCard(ad: MyAdDto, stats: MyAdStatsDto? = null, submitting: Boolean, onEdit: () -> Unit, onSubmit: () -> Unit, onPay: () -> Unit, onRenew: () -> Unit = {}) {
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ad.title.ifBlank { appText("Без названия", "Исемһеҙ") },
                    modifier = Modifier.weight(1f), color = CanonText, fontWeight = FontWeight.Black,
                    fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.width(8.dp))
                AdStatusBadge(ad.status)
            }
            if (ad.text.isNotBlank()) {
                Text(ad.text, color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (ad.pkgTitle.isNotBlank()) {
                Text(
                    appText("Тариф: ${ad.pkgTitle} · ${ad.budgetKop / 100} ₽ / ${ad.periodDays} дн",
                            "Тариф: ${ad.pkgTitle} · ${ad.budgetKop / 100} ₽ / ${ad.periodDays} көн"),
                    color = CanonMuted, fontSize = 12.sp
                )
            }
            // Статистика размещения: показы / клики / CTR / остаток срока. Только для запущенных
            // объявлений (active) — у черновика/на модерации показов ещё нет.
            if (stats != null && ad.status == "active") {
                AdStatsTiles(stats)
            }
            if (ad.status == "rejected" && ad.rejectReason.isNotBlank()) {
                // Тот же проверенный по контрасту токен, что и в бейдже статуса.
                Surface(color = CanonDangerBg, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CanonDangerBorder)) {
                    Text(
                        appText("Причина отказа: ${ad.rejectReason}", "Кире ҡағыу сәбәбе: ${ad.rejectReason}"),
                        modifier = Modifier.padding(10.dp), color = CanonRed, fontSize = 12.sp, lineHeight = 16.sp
                    )
                }
            }
            if (ad.status == "active" && !ad.paid) {
                Text(appText("Одобрено! Оплати размещение — и объявление пойдёт в показы.", "Раҫланды! Урынлаштырыуҙы түлә — иғлан күрһәтелә башлай."), color = CanonMuted, fontSize = 12.sp, lineHeight = 16.sp)
                Button(
                    onClick = onPay,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    Icon(Icons.Default.Payments, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(appText("Оплатить размещение · ${ad.budgetKop / 100} ₽", "Урынлаштырыуҙы түләү · ${ad.budgetKop / 100} ₽"), fontWeight = FontWeight.Black, color = Color.White)
                }
            }
            if (ad.status == "active" && ad.paid) {
                val endingSoon = stats?.daysLeft != null && stats.daysLeft <= 5
                Text(appText("Оплачено · объявление показывается.", "Түләнде · иғлан күрһәтелә."), color = CanonGreen2, fontSize = 12.sp)
                if (endingSoon) {
                    Text(
                        appText("Размещение скоро закончится. Продли, чтобы показы не прервались.",
                                "Урынлаштырыу тиҙҙән бөтә. Күрһәтеү өҙөлмәһен өсөн оҙайт."),
                        color = CanonWarn, fontSize = 12.sp, lineHeight = 16.sp
                    )
                }
                // Продление размещения — тот же СБП-флоу, что и первичная оплата (перевод «на доверии»).
                OutlinedButton(
                    onClick = onRenew,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.5.dp, CanonGreen2),
                ) {
                    Icon(Icons.Default.Autorenew, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(appText("Продлить размещение · ${ad.budgetKop / 100} ₽", "Урынлаштырыуҙы оҙайтыу · ${ad.budgetKop / 100} ₽"), fontWeight = FontWeight.Black, color = CanonGreen2)
                }
            }
            if (ad.status == "draft" || ad.status == "rejected") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                        Text(appText("Изменить", "Үҙгәртергә"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onSubmit, enabled = !submitting && ad.pkg.isNotBlank(),
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                    ) {
                        if (submitting) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        else Text(appText("На модерацию", "Модерацияға"), fontWeight = FontWeight.Black, color = Color.White)
                    }
                }
            }
        }
    }
}

// Плитки статистики объявления в кабинете рекламодателя: показы / клики / CTR / остаток срока.
// Данные из AdEvent (реальные), считает сервер (GROUP BY). Стиль — CanonMetric, как в кабинете водителя.
@Composable
internal fun AdStatsTiles(stats: MyAdStatsDto) {
    val daysValue = stats.daysLeft?.let { "$it" } ?: "∞"
    val daysLabel = if (stats.daysLeft != null) appText("осталось дней", "көн ҡалды")
                    else appText("бессрочно", "сикһеҙ")
    // Две строки по две плитки вместо четырёх в ряд: на узком экране четыре плитки давали
    // ~45dp под текст — «1 250» и «осталось дней» просто обрезались.
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            CabinetMetric(appText("показы", "күрһәтеү"), "${stats.impressions}", Modifier.weight(1f))
            CabinetMetric(appText("клики", "баҫыу"), "${stats.clicks}", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            CabinetMetric(appText("CTR", "CTR"), "${stats.ctr}%", Modifier.weight(1f))
            CabinetMetric(daysLabel, daysValue, Modifier.weight(1f))
        }
    }
}

// Витрина «рекламируйся у нас» — когда своих объявлений ещё нет.
@Composable
internal fun AdsShowcase(packages: List<AdPackageDto>, modifier: Modifier, onCreate: () -> Unit) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text(appText("Реклама в Юлдаше", "Юлдашта реклама"), color = CanonGreen, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(6.dp))
            Text(
                appText("Покажи своё дело землякам по маршрутам и городам. Создай объявление, пройди модерацию и оплати размещение.",
                        "Эшеңде яҡташтарға маршруттар һәм ҡалалар буйынса күрһәт. Иғлан төҙө, модерация үт һәм урынлаштырыуҙы түлә."),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp
            )
        }
        item { Text(appText("Тарифы", "Тарифтар"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp) }
        // Тарифы не пришли (сеть/сервер) — под заголовком не должно оставаться пустое место.
        if (packages.isEmpty()) {
            item {
                InfoCard(
                    title = appText("Тарифы подгружаются", "Тарифтар йөкләнә"),
                    text = appText(
                        "Если так и не появились — проверь интернет. Объявление можно составить и сейчас, тариф выберешь потом.",
                        "Барыбер күренмәһә — интернетты тикшер. Иғланды хәҙер ҙә яҙып була, тарифты һуңынан һайларһың.",
                    ),
                    icon = Icons.Default.Refresh,
                )
            }
        }
        items(packages, key = { it.code }) { p ->
            Surface(color = CanonSurface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, CanonBorder)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(appText(p.title, p.titleBa.ifBlank { p.title }), color = CanonText, fontWeight = FontWeight.Black, fontSize = 14.sp)
                        Text(appText("${p.periodDays} дней показов", "${p.periodDays} көн күрһәтеү"), color = CanonMuted, fontSize = 12.sp)
                    }
                    Text("${p.amountKop / 100} ₽", color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 15.sp)
                }
            }
        }
        item {
            Text(appText("Цены — стартовая гипотеза, обсуждаемо.", "Хаҡтар — башланғыс фараз, һөйләшеп була."), color = CanonMuted, fontSize = 11.sp)
        }
        item {
            Button(
                onClick = onCreate,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
            ) {
                Text(appText("Разместить рекламу", "Реклама урынлаштырырға"), fontWeight = FontWeight.Black, color = Color.White, fontSize = 16.sp)
            }
        }
    }
}

// Редактор объявления партнёра: создание (initial=null) или правка своего draft/rejected.
@Composable
internal fun AdEditorScreen(initial: MyAdDto?, onBack: () -> Unit, onSaved: () -> Unit) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var text by remember { mutableStateOf(initial?.text ?: "") }
    var button by remember { mutableStateOf(initial?.button ?: "") }
    var target by remember { mutableStateOf(initial?.target ?: "") }
    var cities by remember { mutableStateOf(initial?.cities ?: "") }
    var pkg by remember { mutableStateOf(initial?.pkg ?: "") }
    var packages by remember { mutableStateOf<List<AdPackageDto>>(emptyList()) }
    // Тарифы не загрузились → без выбора нельзя отправить на модерацию (тупик). Даём «Повторить».
    var packagesError by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val errNet = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Сеткәне тикшер, ҡабатла.")
    val needTitle = appText("Впиши заголовок", "Башлыҡ яҙ")
    val needPkg = appText("Выбери тариф", "Тариф һайла")
    fun loadPackages() {
        packagesError = false
        scope.launch { ApiClient.getAdPackages().onSuccess { packages = it; packagesError = false }.onFailure { packagesError = true } }
    }
    LaunchedEffect(Unit) { loadPackages() }

    fun save(submit: Boolean) {
        if (title.isBlank()) { Toast.makeText(ctx, needTitle, Toast.LENGTH_SHORT).show(); return }
        if (submit && pkg.isBlank()) { Toast.makeText(ctx, needPkg, Toast.LENGTH_SHORT).show(); return }
        busy = true
        scope.launch {
            val res = if (initial == null) ApiClient.createMyAd(title, text, button, target, pkg, cities)
                      else ApiClient.updateMyAd(initial.id, title, text, button, target, pkg, cities)
            res.onSuccess { saved ->
                if (submit) {
                    ApiClient.submitMyAd(saved.id)
                        .onSuccess { busy = false; onSaved() }
                        .onFailure { busy = false; Toast.makeText(ctx, errNet, Toast.LENGTH_SHORT).show() }
                } else { busy = false; onSaved() }
            }.onFailure { busy = false; Toast.makeText(ctx, errNet, Toast.LENGTH_SHORT).show() }
        }
    }

    // Пока идёт сохранение — не отпускаем «Назад»: иначе create успеет, а submit нет → застрянет черновиком.
    BackHandler(busy) {}

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText(if (initial == null) "Новое объявление" else "Изменить объявление", if (initial == null) "Яңы иғлан" else "Иғланды үҙгәртергә")) { if (!busy) onBack() } }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item { AdField(appText("Заголовок", "Башлыҡ"), title, { title = it }) }
            item { AdField(appText("Описание", "Аңлатма"), text, { text = it }, singleLine = false) }
            item { AdField(appText("Текст кнопки (напр. «Позвонить»)", "Төймә тексты (мәҫ. «Шылтыратырға»)"), button, { button = it }) }
            item { AdField(appText("Ссылка или телефон", "Һылтанма йәки телефон"), target, { target = it }) }
            item { AdField(appText("Город(а) через запятую — пусто = все", "Ҡала(лар) өтөр аша — буш = бөтәһе"), cities, { cities = it }) }
            item { Text(appText("Тариф", "Тариф"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp) }
            if (packages.isEmpty() && packagesError) {
                // Без тарифов кнопка «На модерацию» не сработает — честно объясняем и даём повтор.
                item {
                    Surface(color = CanonSurface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, CanonBorder)) {
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(appText("Не удалось загрузить тарифы", "Тарифтарҙы йөкләп булманы"), color = CanonMuted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { loadPackages() }) { Text(appText("Повторить", "Ҡабатлау"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
            items(packages, key = { it.code }) { p ->
                val selected = pkg == p.code
                Surface(
                    color = if (selected) CanonMint else CanonSurface,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) CanonGreen2 else CanonBorder),
                    modifier = Modifier.fillMaxWidth().clickable { pkg = p.code }
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null, tint = if (selected) CanonGreen2 else CanonMuted
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(appText(p.title, p.titleBa.ifBlank { p.title }), modifier = Modifier.weight(1f), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("${p.amountKop / 100} ₽ / ${p.periodDays}${appText(" дн", " көн")}", color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 13.sp)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { save(submit = false) }, enabled = !busy, modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp)) {
                        Text(appText("Сохранить", "Һаҡларға"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = { save(submit = true) }, enabled = !busy,
                        modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                    ) {
                        if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        else Text(appText("На модерацию", "Модерацияға"), fontWeight = FontWeight.Black, color = Color.White)
                    }
                }
            }
            item {
                Text(
                    appText("После отправки объявление проверит модератор. Затем оплатишь размещение — и оно пойдёт в показы.",
                            "Ебәргәс, иғланды модератор тикшерә. Аҙаҡ урынлаштырыуҙы түләйһең — һәм ул күрһәтелә башлай."),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp
                )
            }
        }
    }
}

@Composable
internal fun AdField(label: String, value: String, onValueChange: (String) -> Unit, singleLine: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CanonGreen2,
            unfocusedBorderColor = CanonBorder,
            focusedLabelColor = CanonGreen2,
            cursorColor = CanonGreen2,
            focusedTextColor = CanonText,
            unfocusedTextColor = CanonText,
        )
    )
}

@Composable
internal fun InfoCard(
    title: String,
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: (() -> Unit)? = null
) {
    val clickModifier = if (onClick != null) Modifier.bounceClick(onClick) else Modifier
    Card(
        modifier = clickModifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(10.dp))
            }
            Spacer(Modifier.width(12.dp))
            // weight(1f) обязателен: без него длинный текст (башкирский почти всегда длиннее
            // русского) уезжал за край карточки и обрезался. Размеры — в sp, чтобы уважать
            // системный крупный шрифт.
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 16.sp, lineHeight = 20.sp)
                Text(text, color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
internal fun EmptyStateCard(
    title: String,
    text: String,
    icon: ImageVector,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, CanonBorder)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(15.dp).size(30.dp))
            }
            Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 18.sp, textAlign = TextAlign.Center)
            Text(text, color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
            if (action != null && onAction != null) {
                AppButton(
                    text = action,
                    onClick = onAction,
                    style = AppButtonStyle.Primary
                )
            }
        }
    }
}

@Composable
internal fun InlinePartnerAdCard(
    ad: PartnerAd,
    label: String,
    onImpression: (PartnerAd) -> Unit,
    onClick: (PartnerAd) -> Unit
) {
    LaunchedEffect(ad.id) {
        onImpression(ad)
    }
    val context = LocalContext.current
    val adLabel = ad.titleText()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, CanonBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    appText("Реклама · erid: ${ad.eridText()}", "Реклама · erid: ${ad.eridText()}"),
                    modifier = Modifier.weight(1f),
                    color = CanonMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(color = CanonMint, shape = RoundedCornerShape(999.dp), border = BorderStroke(1.dp, CanonBorder)) {
                    Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = CanonGreen2, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
                    Icon(ad.icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(ad.titleText(), color = CanonText, fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(ad.descriptionText(), color = CanonMuted, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { openAdTarget(context, ad, adLabel); onClick(ad) }) {
                    Text(ad.primaryButtonText(), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}

// «Открыть» партнёра: сайт магазина (если задана ссылка) → иначе звонок партнёру → иначе подсказка.
internal fun openAdTarget(context: Context, ad: PartnerAd, label: String) {
    val link = ad.linkUrl.trim()
    val phoneDigits = ad.contact.filter { it.isDigit() || it == '+' }
    runCatching {
        when {
            link.startsWith("http://") || link.startsWith("https://") ->
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            phoneDigits.count { it.isDigit() } >= 10 ->
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneDigits")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else -> Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
        }
    }.onFailure { Toast.makeText(context, label, Toast.LENGTH_SHORT).show() }
}

// «Маршрут» до партнёра: дорога на авто (Яндекс.Карты; нет приложения → откроется в браузере).
internal fun routeToAd(context: Context, ad: PartnerAd, label: String) {
    val pt = ad.mapPoint.replace(" ", "")
    val uri = if (pt.isNotBlank())
        Uri.parse("https://yandex.ru/maps/?rtext=~$pt&rtt=auto")              // ~ = «от меня», rtt=auto → на авто
    else
        Uri.parse("https://yandex.ru/maps/?text=" + Uri.encode(ad.address))   // нет координат → поиск по адресу
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Toast.makeText(context, label, Toast.LENGTH_SHORT).show() }
}

@Composable
internal fun PartnerAdCard(
    ad: PartnerAd,
    stats: AdStats,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    label: String? = null,
    showAdminDetails: Boolean = false,
    onImpression: (PartnerAd) -> Unit,
    onClick: (PartnerAd) -> Unit,
    onRoute: ((PartnerAd) -> Unit)? = null   // задан (экран с картой) → «Маршрут» рисуем в приложении; иначе внешние карты
) {
    LaunchedEffect(ad.id) {
        onImpression(ad)
    }
    val labelText = label ?: appText("Партнёр рядом", "Яҡындағы партнёр")
    val context = LocalContext.current
    val adLabel = ad.titleText()   // язык резолвим тут (в @Composable), чтобы отдать в действие-хелпер

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, CanonHairlineGreen)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    appText("Реклама · erid: ${ad.eridText()}", "Реклама · erid: ${ad.eridText()}"),
                    modifier = Modifier.weight(1f),
                    color = CanonMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                    Text(labelText, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = CanonGreen2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AdChip(ad.packageText(), Icons.Default.Payments, Modifier.weight(0.9f))
                AdChip(if (showAdminDetails) ad.placementsLabel() else (ad.categoryText() ?: ad.city), Icons.Default.Map, Modifier.weight(1.2f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                    Icon(ad.icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(12.dp).size(if (compact) 22.dp else 28.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(ad.titleText(), color = CanonText, fontWeight = FontWeight.Black, fontSize = if (compact) 16.sp else 18.sp, lineHeight = 20.sp)
                    Text(ad.descriptionText(), color = CanonText, fontSize = 14.sp, lineHeight = 18.sp, maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis)
                    Text(ad.addressText(), color = CanonMuted, fontSize = 13.sp, lineHeight = 16.sp)
                }
            }
            Text(
                if (showAdminDetails) {
                    appText("Рекламодатель: ${ad.advertiserName}", "Реклама биреүсе: ${ad.advertiserName}")
                } else {
                    appText("Партнёр Юлдаш · ${ad.city}", "Юлдаш партнёры · ${ad.city}")
                },
                color = CanonMuted,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
            if (showAdminDetails) {
                Text(
                    appText("Действие: ${ad.targetActionText()} · контакт: ${ad.contact}", "Ғәмәл: ${ad.targetActionText()} · бәйләнеш: ${ad.contact}"),
                    color = CanonMuted,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = { openAdTarget(context, ad, adLabel); onClick(ad) },   // «Открыть» → сайт/звонок партнёра
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    Text(ad.primaryButtonText(), fontWeight = FontWeight.Black, fontSize = 13.sp, maxLines = 1)
                }
                ad.secondaryButtonText()?.let { button ->
                    OutlinedButton(
                        onClick = { if (onRoute != null) onRoute(ad) else routeToAd(context, ad, adLabel); onClick(ad) },   // «Маршрут»: в приложении (если есть карта) или внешние карты
                        modifier = Modifier.weight(1f).height(44.dp),
                        shape = RoundedCornerShape(15.dp),
                        border = BorderStroke(1.dp, CanonGreen2)
                    ) {
                        Text(button, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
            if (showAdminDetails) {
                Text(
                    appText(
                        "Период: ${ad.startDate} — ${ad.endDate} · показы ${stats.impressions} · клики ${stats.clicks} · CTR ${stats.ctrPercent}%",
                        "Ваҡыт: ${ad.startDate} — ${ad.endDate} · күрһәтеү ${stats.impressions} · баҫыу ${stats.clicks} · CTR ${stats.ctrPercent}%"
                    ),
                    color = CanonMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
internal fun AdChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = CanonSurface, shape = RoundedCornerShape(999.dp), border = BorderStroke(1.dp, CanonBorder)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, color = CanonGreen2, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun PartnerAd.matchesRoute(from: String, to: String): Boolean {
    return routeFrom == null || routeTo == null || (routeFrom == from && routeTo == to)
}

@Composable
private fun PartnerAd.titleText(): String = appText(title, titleBa ?: title)

@Composable
private fun PartnerAd.descriptionText(): String = appText(description, descriptionBa ?: description)

@Composable
private fun PartnerAd.addressText(): String = appText(address, addressBa ?: address)

@Composable
private fun PartnerAd.categoryText(): String? = category?.let { appText(it, categoryBa ?: it) }

@Composable
private fun PartnerAd.packageText(): String = appText(packageName, packageNameBa ?: packageName)

@Composable
private fun PartnerAd.budgetText(): String = appText(budgetLabel, budgetLabelBa ?: budgetLabel)

@Composable
private fun PartnerAd.targetActionText(): String = appText(targetAction, targetActionBa ?: targetAction)

@Composable
private fun PartnerAd.primaryButtonText(): String = appText(primaryButton, primaryButtonBa ?: primaryButton)

@Composable
private fun PartnerAd.secondaryButtonText(): String? = secondaryButton?.let { appText(it, secondaryButtonBa ?: it) }

@Composable
private fun PartnerAd.eridText(): String = appText(
    erid,
    if (erid == "ожидает присвоения") "бирелеүен көтә" else erid
)

private fun List<PartnerAd>.activeAds(): List<PartnerAd> = filter { it.status == AdStatus.Active }

internal fun List<PartnerAd>.forPlacement(placement: AdPlacement): List<PartnerAd> {
    return activeAds().filter { placement in it.placements }
}

internal fun List<PartnerAd>.forRoute(from: String, to: String): List<PartnerAd> {
    return activeAds().filter { it.matchesRoute(from, to) }
}

internal fun List<PartnerAd>.forCity(city: String): List<PartnerAd> {
    return activeAds().filter { it.city == city }
}

internal fun List<PartnerAd>.forCategory(category: String): List<PartnerAd> {
    return activeAds().filter { it.category == category }
}

@Composable
private fun AdStatus.label(): String {
    return when (this) {
        AdStatus.Draft -> appText("Черновик", "Ҡаралама")
        AdStatus.Moderation -> appText("На модерации", "Модерацияла")
        AdStatus.Active -> appText("Активна", "Актив")
        AdStatus.Paused -> appText("Пауза", "Туҡтатылған")
        AdStatus.Finished -> appText("Завершена", "Тамамланған")
    }
}

@Composable
private fun AdStatus.color(): Color {
    return when (this) {
        AdStatus.Active -> CanonGreen2
        AdStatus.Moderation -> CanonWarn
        AdStatus.Draft -> CanonMuted
        AdStatus.Paused -> CanonWarn
        AdStatus.Finished -> CanonMuted
    }
}

@Composable
private fun AdPlacement.label(): String {
    val isBashkir = LocalAppLanguage.current == AppLanguage.Ba
    return labelForLanguage(isBashkir)
}

@Composable
private fun PartnerAd.placementsLabel(): String {
    val isBashkir = LocalAppLanguage.current == AppLanguage.Ba
    return placements.joinToString(" · ") { it.labelForLanguage(isBashkir) }
}

private fun AdPlacement.labelForLanguage(isBashkir: Boolean): String {
    return when (this) {
        AdPlacement.Nearby -> if (isBashkir) "Яҡындағы партнёр" else "Партнёр рядом"
        AdPlacement.Route -> "Маршрут"
        AdPlacement.RidesList -> if (isBashkir) "Сәфәрҙәр исемлеге" else "Список поездок"
        AdPlacement.TripDetails -> if (isBashkir) "Сәфәр тураһында" else "Детали поездки"
        AdPlacement.Profile -> "Профиль"
        AdPlacement.Help -> if (isBashkir) "Ярҙам" else "Помощь"
    }
}

@Composable
internal fun DetailMeta(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = CanonMuted, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun RouteMiniIcon() {
    val routeColor = CanonGreen2  // читаем адаптивный цвет ДО DrawScope (там @Composable недоступен)
    Canvas(Modifier.size(width = 34.dp, height = 70.dp)) {
        val start = Offset(size.width * 0.55f, size.height * 0.12f)
        val end = Offset(size.width * 0.35f, size.height * 0.88f)
        val path = Path().apply {
            moveTo(start.x, start.y)
            cubicTo(size.width * 0.08f, size.height * 0.30f, size.width * 0.86f, size.height * 0.55f, end.x, end.y)
        }
        drawPath(path, color = routeColor, style = Stroke(width = 5f, cap = StrokeCap.Round))
        drawCircle(routeColor, radius = 10f, center = start)
        drawCircle(routeColor, radius = 10f, center = end)
    }
}

@Composable
internal fun TripInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Surface(
        color = CanonSurface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, CanonHairlineGreen)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(label, color = CanonMuted, fontSize = 14.sp)
                Text(value, color = CanonText, fontWeight = FontWeight.Medium, fontSize = 18.sp)
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
        }
    }
}

/**
 * Декодирует выбранное фото и перекодирует в компактный JPEG: нормализует формат
 * (png/webp/heic → jpg — сервер иначе отвергал не-jpeg, фото «не сохранялось») и уменьшает
 * тяжёлое фото до размера аватара (экономит трафик и квоту). null при ошибке. Тяжёлое —
 * звать на IO-потоке. Даунсэмпл через inSampleSize, чтобы не поймать OOM на больших снимках.
 *
 * ⚠️ Ориентация. `BitmapFactory` игнорирует EXIF-тег поворота, а перекодированный JPEG его уже
 * не несёт — селфи с камеры (портрет) сохранялся аватаром «на боку». Поэтому на API 28+ идём
 * через `ImageDecoder`: он применяет EXIF сам. На старых версиях — читаем тег `ExifInterface`
 * и доворачиваем матрицей вручную (аудит 2026-07-26).
 */
internal fun decodeToJpeg(
    context: Context,
    uri: Uri,
    maxSize: Int = 1024,
    quality: Int = 88,
): ByteArray? = runCatching {
    val decoded: Bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        // ImageDecoder сам применяет EXIF-ориентацию и умеет HEIC; ограничиваем размер в колбэке.
        val src = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.isMutableRequired = false
            val longest = maxOf(info.size.width, info.size.height, 1)
            if (longest > maxSize) {
                val k = maxSize.toFloat() / longest
                dec.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1),
                                  (info.size.height * k).toInt().coerceAtLeast(1))
            }
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxSize * 2 || bounds.outHeight / sample > maxSize * 2) sample *= 2
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching null
        // До API 28 EXIF применяем руками — иначе портретное фото уедет на бок.
        val degrees = context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        if (degrees != 0f) {
            val m = Matrix().apply { postRotate(degrees) }
            Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        } else raw
    }
    val scale = maxSize.toFloat() / maxOf(decoded.width, decoded.height, 1)
    val bmp = if (scale < 1f) {
        Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
    } else decoded
    java.io.ByteArrayOutputStream().use { bos ->
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        bos.toByteArray()
    }
}.getOrNull()
