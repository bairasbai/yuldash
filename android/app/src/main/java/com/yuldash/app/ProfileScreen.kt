package com.yuldash.app

// Профиль + кабинет рекламы. Вынесено из MainActivity (Фаза 2). Импорты целиком — лишние = варнинги.

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
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.LocalTaxi
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
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.yuldash.app.data.MyAdDto
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
    onToggleLanguage: () -> Unit,
    onAccountDeleted: () -> Unit,
    onAdImpression: (PartnerAd) -> Unit,
    onAdClick: (PartnerAd) -> Unit
) {
    val isBashkir = LocalAppLanguage.current == AppLanguage.Ba
    val profileAd = ads.forPlacement(AdPlacement.Profile).firstOrNull { it.city == "Баймаҡ" }
    // Свой рейтинг (как пассажира) — из реальных оценок водителей. null, пока никто не оценил.
    var myRating by remember { mutableStateOf<Double?>(null) }
    var displayName by remember { mutableStateOf(ApiClient.cachedName() ?: "Я") }
    var avatarUrl by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var profileConfirmed by remember { mutableStateOf(ApiClient.isLoggedIn()) }
    LaunchedEffect(Unit) {
        ApiClient.me()
            .onSuccess { o ->
                profileConfirmed = true
                myRating = if (o.isNull("rating")) null else o.optDouble("rating")
                o.optString("name").takeIf { it.isNotBlank() }?.let { displayName = it }
                o.optString("avatar_url").takeIf { it.isNotBlank() }?.let { avatarUrl = it }
                role = o.optString("role")
            }
            // Сбой /me: сеть упала (таймаут/нет связи) — НЕ роняем залогиненного в «демо».
            // Не подтверждён только если реально не вошёл ИЛИ токен отвергнут (401).
            .onFailure { e -> profileConfirmed = ApiClient.isLoggedIn() && (e as? com.yuldash.app.data.ApiException)?.status != 401 }
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
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u ->
            editScope.launch {
                val bytes = runCatching { editCtx.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull()
                if (bytes == null) { Toast.makeText(editCtx, saveErrMsg, Toast.LENGTH_SHORT).show(); return@launch }
                ApiClient.uploadChatPhoto(bytes)
                    .onSuccess { url ->
                        ApiClient.updateAvatar(url)
                            .onSuccess { avatarUrl = url; Toast.makeText(editCtx, avatarSavedMsg, Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(editCtx, saveErrMsg, Toast.LENGTH_SHORT).show() }
                    }
                    .onFailure { Toast.makeText(editCtx, saveErrMsg, Toast.LENGTH_SHORT).show() }
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
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
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
                                    .bounceClick { avatarPicker.launch("image/*") },
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
                                Icon(Icons.Default.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp).align(Alignment.BottomEnd))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(displayName, color = Color.White, fontWeight = FontWeight.Black, fontSize = 22.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Default.Edit, contentDescription = appText("Изменить имя", "Исемде үҙгәртеү"), tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp).bounceClick { nameDraft = displayName; showEditName = true })
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(appText("Пассажир · Баймаҡ", "Пассажир · Баймаҡ"), color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp)
                                    myRating?.let { r ->
                                        Spacer(Modifier.width(8.dp))
                                        Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text(String.format(java.util.Locale.US, "%.1f", r), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                Text(appText("Телефон скрыт до подтверждения поездки", "Телефон сәфәр раҫланғанға тиклем йәшерелгән"), color = Color.White.copy(alpha = 0.78f), fontSize = 13.sp, lineHeight = 16.sp)
                            }
                        }
                        Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (profileConfirmed) appText("Профиль подтверждён", "Профиль раҫланған") else appText("Демо-режим без входа", "Инеүһеҙ демо-режим"),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
            item {
                referral?.let { ref ->
                    Box(Modifier.appearIn(0)) {
                        Card(colors = CardDefaults.cardColors(containerColor = CanonMint), shape = CanonCardShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Person, contentDescription = null, tint = CanonGreen2)
                                    Spacer(Modifier.width(10.dp))
                                    Text(appText("Позови своего", "Үҙеңдекен саҡыр"), color = CanonGreen, fontWeight = FontWeight.Black, fontSize = 18.sp, modifier = Modifier.weight(1f))
                                }
                                Text(appText("Пригласил соседа → вы оба получаете бонус (бесплатное поднятие поездки).", "Күршеңде саҡырҙың → икәүегеҙ ҙә бонус (сәфәрҙе бушлай күтәреү) аласаҡ."), color = CanonGreen2, fontSize = 13.sp, lineHeight = 18.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    Column { Text(appText("Позвал", "Саҡырҙы"), color = CanonGreen2, fontSize = 12.sp); Text(ref.invited.toString(), color = CanonGreen, fontWeight = FontWeight.Black, fontSize = 20.sp) }
                                    Column { Text(appText("Бонусов", "Бонус"), color = CanonGreen2, fontSize = 12.sp); Text(ref.credits.toString(), color = CanonGreen, fontWeight = FontWeight.Black, fontSize = 20.sp) }
                                    Column { Text(appText("Твой код", "Кодың"), color = CanonGreen2, fontSize = 12.sp); Text(ref.code, color = CanonGreen, fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = 2.sp) }
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
                Text(appText("Личный кабинет", "Шәхси кабинет"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
            item { Box(Modifier.appearIn(1)) { ProfileActionCard(appText("Кабинет пассажира", "Пассажир кабинеты"), appText("Мои брони, заявки и безопасность", "Брондәр, заявкалар һәм хәүефһеҙлек"), Icons.Default.EventSeat, onPassengerCabinet) } }
            item { Box(Modifier.appearIn(2)) { ProfileActionCard(appText("Кабинет водителя", "Водитель кабинеты"), appText("Маршруты, проверка и поднятие", "Маршруттар, тикшереү һәм күтәреү"), Icons.Default.DirectionsCar, onDriverCabinet) } }
            item { Box(Modifier.appearIn(3)) { ProfileActionCard(appText("Язык", "Тел"), if (isBashkir) "Башҡортса / Русский" else "Русский / Башҡортса", Icons.Default.Language, onToggleLanguage) } }
            item { Box(Modifier.appearIn(4)) { ProfileActionCard(appText("Проверка водителя", "Водителде тикшереү"), appText("Права, машина, фото авто", "Права, машина, авто фотоһы"), Icons.Default.Verified, onVerifyDriver) } }
            item { Box(Modifier.appearIn(6)) { ProfileActionCard(appText("Безопасность", "Хәүефһеҙлек"), appText("SOS, скрытый телефон, подтверждённые участники", "SOS, йәшерен телефон, раҫланған ҡатнашыусылар"), Icons.Default.Shield, onSafety) } }
            item { Box(Modifier.appearIn(7)) { ProfileActionCard(appText("Поддержать Юлдаш", "Юлдашҡа ярҙам итеү"), appText("Серверы, карты, SMS и поддержка", "Серверҙар, карталар, SMS һәм ярҙам"), Icons.Default.VolunteerActivism, onSupport) } }
            item {
                Text(appText("Для родителей и близких", "Ата-әсә һәм яҡындар өсөн"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
            item { Box(Modifier.appearIn(8)) { SeniorAccessCard(onSimpleMode = onSimpleMode) } }
            item { Box(Modifier.appearIn(9)) { ProfileActionCard(appText("Доверенные контакты", "Ышаныслы контакттар"), appText("Кому отправлять статус поездки", "Сәфәр статусын кемгә ебәрергә"), Icons.Default.Person, onTrustedContacts) } }
            item { Box(Modifier.appearIn(10)) { ProfileActionCard(appText("Попросить звонок", "Шылтыратыу һорау"), appText("Помощь без чата и сложных форм", "Чатһыҙ һәм ҡатмарлы формаларһыҙ ярҙам"), Icons.Default.HeadsetMic, onCallbackHelp) } }
            item {
                Text(appText("Настройки и помощь", "Көйләүҙәр һәм ярҙам"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
            item { Box(Modifier.appearIn(11)) { ProfileActionCard(appText("Настройки", "Көйләүҙәр"), appText("Уведомления, карта, предпочтения", "Хәбәрҙәр, карта, өҫтөнлөктәр"), Icons.Default.Settings, onSettings) } }
            item { Box(Modifier.appearIn(11)) { ProfileActionCard(appText("Конфиденциальность", "Хосусилыҡ"), appText("Геолокация и разрешения", "Геолокация һәм рөхсәттәр"), Icons.Default.Shield, onPrivacy) } }
            item { Box(Modifier.appearIn(12)) { ProfileActionCard(appText("Помощь", "Ярҙам"), appText("Ответы на частые вопросы", "Йыш һорауҙарға яуаптар"), Icons.Default.Help, onHelp) } }
            item { Box(Modifier.appearIn(12)) { ProfileActionCard(appText("Оставить отзыв", "Фекер ҡалдырыу"), appText("Оцени приложение — лучшие попадут на сайт", "Ҡушымтаны баһала — иң яҡшылары сайтҡа эләгер"), Icons.Default.Star, onReview) } }
            if (role == "admin") {
                item { Box(Modifier.appearIn(12)) { ProfileActionCard(appText("Модерация отзывов", "Фекерҙәрҙе модерациялау"), appText("Одобрить отзывы для сайта", "Сайт өсөн фекерҙәрҙе раҫларға"), Icons.Default.Verified, onAdminReviews) } }
                item { Box(Modifier.appearIn(12)) { ProfileActionCard(appText("Управление рекламой", "Реклама идаралау"), appText("Объявления партнёров: публикация, пауза, удаление", "Партнёр иғландары: баҫтырыу, пауза, бөтөрөү"), Icons.Default.AdminPanelSettings, onAdminAds) } }
            }
            item {
                Text(appText("Партнёры Юлдаш", "Юлдаш партнёрҙары"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
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
                    Text(appText("Аккаунт", "Иҫәп"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
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
    onSafety: () -> Unit
) {
    // Реальные брони и заявки пользователя (раньше метрики и карточка брались из демо-списка).
    var bookings by remember { mutableStateOf<List<com.yuldash.app.data.BookingMineDto>>(emptyList()) }
    var bookingsLoading by remember { mutableStateOf(true) }
    var bookingsError by remember { mutableStateOf(false) }
    var bookingsReload by remember { mutableIntStateOf(0) }
    var serverReqCount by remember { mutableStateOf<Int?>(null) }
    var myRating by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(bookingsReload) {
        bookingsLoading = true
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
            onSafety = onSafety,
            modifier = Modifier.padding(padding),
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
    modifier: Modifier = Modifier,
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CabinetMetric(appText("Активные", "Актив"), activeCount.toString(), Modifier.weight(1f))
                CabinetMetric(appText("Заявки", "Заявкалар"), requestCount.toString(), Modifier.weight(1f))
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
                SettingsNavRow(Icons.Default.AddRoad, appText("Создать заявку", "Заявка булдырыу"), appText("Если готовой поездки нет", "Әҙер сәфәр булмаһа"), onClick = onCreateRequest)
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
    onTaxiOnboarding: () -> Unit = {}    // гейт такси (580-ФЗ): нет одобренной заявки → «Стать таксистом»
) {
    // Реальные опубликованные поездки водителя с сервера (раньше фильтровали демо-список по имени → всегда пусто).
    var driverRides by remember { mutableStateOf<List<Ride>>(emptyList()) }
    val ctx = LocalContext.current
    val rateScope = rememberCoroutineScope()
    var driverBookings by remember { mutableStateOf<List<com.yuldash.app.data.DriverBookingDto>>(emptyList()) }
    var driverRating by remember { mutableStateOf<Double?>(null) }
    var online by remember { mutableStateOf(false) }
    var debt by remember { mutableStateOf<com.yuldash.app.data.DriverDebtDto?>(null) }
    // Гейт такси (580-ФЗ): без одобренной заявки тумблер «Я на линии» заменяется CTA «Стать таксистом».
    var taxiApp by remember { mutableStateOf<com.yuldash.app.data.TaxiApplicationDto?>(null) }
    var taxiAppLoaded by remember { mutableStateOf(false) }
    // Зона работы таксиста (география, волна 2): чип у тумблера + шторка выбора.
    var zone by remember { mutableStateOf<com.yuldash.app.data.InstantZoneDto?>(null) }
    var showZoneSheet by remember { mutableStateOf(false) }
    suspend fun reloadDebt() { ApiClient.getDriverDebt().onSuccess { debt = it } }
    LaunchedEffect(Unit) {
        ApiClient.getDriverRides().onSuccess { driverRides = it.map { dto -> dto.toUiRide() } }
        ApiClient.getDriverBookings().onSuccess { driverBookings = it }
        ApiClient.me().onSuccess { o -> driverRating = if (o.isNull("rating")) null else o.optDouble("rating") }
        ApiClient.getDriverStatus().onSuccess { online = it.online }
        ApiClient.getInstantZone().onSuccess { zone = it }
        ApiClient.getMyTaxiApplication()
            .onSuccess { taxiApp = it; taxiAppLoaded = true }
            .onFailure { e ->
                // 404 = заявки нет (показываем CTA). Сетевая ошибка → статус неизвестен,
                // оставляем тумблер как раньше (сервер всё равно гейтит presence/accept).
                if ((e as? com.yuldash.app.data.ApiException)?.status == 404) { taxiApp = null; taxiAppLoaded = true }
            }
        reloadDebt()
    }
    val debtPaidMsg = appText("Спасибо! Ждём подтверждения — можно возить такси.", "Рәхмәт! Раҫлауҙы көтәбеҙ — такси йөрөтөргә мөмкин.")
    val debtPaidErrMsg = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    val thanksMsg = appText("Спасибо за оценку", "Баһа өсөн рәхмәт")
    val rateFailMsg = appText("Не получилось оценить", "Баһалап булманы")
    val onlineErrMsg = appText("Не удалось изменить статус. Проверь сеть.", "Статусты үҙгәртеп булманы. Селтәрҙе тикшерегеҙ.")
    val onlineLoginMsg = appText("Войдите, чтобы выйти на линию", "Линияға сығыр өсөн инегеҙ")
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Кабинет водителя", "Водитель кабинеты"), onBack) }
    ) { padding ->
        DriverCabinetContent(
            online = online,
            driverRides = driverRides,
            driverBookings = driverBookings,
            ratingText = driverRating?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—",
            debt = debt,
            onDeclareDebtPaid = {
                rateScope.launch {
                    ApiClient.declareDebtPaid()
                        .onSuccess { Toast.makeText(ctx, debtPaidMsg, Toast.LENGTH_LONG).show(); reloadDebt() }
                        .onFailure { Toast.makeText(ctx, debtPaidErrMsg, Toast.LENGTH_SHORT).show() }
                }
            },
            onToggleOnline = onToggleOnline@{ v ->
                // Демо/без входа → не дёргаем API (там 401 → ложная «проверь сеть»), даём понятное «войдите».
                if (!ApiClient.isLoggedIn()) {
                    Toast.makeText(ctx, onlineLoginMsg, Toast.LENGTH_SHORT).show()
                    return@onToggleOnline
                }
                val prev = online
                online = v
                rateScope.launch {
                    ApiClient.setOnline(v).onFailure {
                        online = prev
                        Toast.makeText(ctx, onlineErrMsg, Toast.LENGTH_SHORT).show()
                    }
                }
                // Вышел на линию, а зона ещё не выбрана → мягко предложим выбрать (не блокируя).
                if (v && zone?.workZone == null) showZoneSheet = true
            },
            onRate = { bookingId, n ->
                rateScope.launch {
                    ApiClient.rateBooking(bookingId, n)
                        .onSuccess { Toast.makeText(ctx, thanksMsg, Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(ctx, rateFailMsg, Toast.LENGTH_SHORT).show() }
                }
            },
            onCreateRide = onCreateRide,
            onVerifyDriver = onVerifyDriver,
            onBoost = onBoost,
            onRequestsFeed = onRequestsFeed,
            modifier = Modifier.padding(padding),
            taxiApplication = taxiApp,
            taxiAppLoaded = taxiAppLoaded,
            onTaxiOnboarding = onTaxiOnboarding,
            zone = zone,
            onZoneClick = { showZoneSheet = true },
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
    }
}

/** ISO-дата ("2026-07-16T…") → "16.07.2026" для показа срока. Кривой ввод — вернём как есть (первые 10). */
private fun debtDueLabel(iso: String): String {
    val d = iso.take(10).split("-")
    return if (d.size == 3) "${d[2]}.${d[1]}.${d[0]}" else iso.take(10)
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
 * Чистый рендер кабинета водителя: тумблер «на линии», метрики (маршруты/свободно/рейтинг),
 * пусто-заглушка или список опубликованных маршрутов, блок «оцените пассажиров» и нижние действия.
 * Сеть/стейт (online-переключение, оценка) вынесены в колбэки → без сети/эффектов → тестируется на JVM.
 * Поведение 1-в-1 с обёрткой [DriverCabinetScreen]. `stars` — локальный UI-стейт звёзд, сети не трогает.
 */
@Composable
internal fun DriverCabinetContent(
    online: Boolean,
    driverRides: List<Ride>,
    driverBookings: List<com.yuldash.app.data.DriverBookingDto>,
    ratingText: String,
    onToggleOnline: (Boolean) -> Unit,
    onRate: (Int, Int) -> Unit,
    onCreateRide: () -> Unit,
    onVerifyDriver: () -> Unit,
    onBoost: () -> Unit,
    onRequestsFeed: () -> Unit,
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
) {
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
                    // Зона работы (география, волна 2): «🏙 Мой город / 🛣 Межгород / 🌍 Соседний регион».
                    DriverZoneChip(zone = zone, onClick = onZoneClick)
                }
            } else {
                TaxiOnboardingCta(taxiApplication, onTaxiOnboarding)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CabinetMetric(appText("Мои маршруты", "Минең маршруттар"), driverRides.size.toString(), Modifier.weight(1f))
                CabinetMetric(appText("Свободно", "Буш"), driverRides.sumOf { it.seats }.toString(), Modifier.weight(1f))
                CabinetMetric(appText("Рейтинг", "Рейтинг"), ratingText, Modifier.weight(1f))
            }
        }
        if (driverRides.isEmpty()) {
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
            }
        }
        if (driverBookings.isNotEmpty()) {
            item {
                Text(appText("Пассажиры — оцените после поездки", "Пассажирҙар — сәфәрҙән һуң баһалағыҙ"), fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
            items(driverBookings, key = { it.bookingId }) { b ->
                var stars by remember(b.bookingId) { mutableStateOf(0) }
                Card(colors = CardDefaults.cardColors(containerColor = CanonSurface), shape = CanonItemShape, elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).background(CanonMint, CircleShape), contentAlignment = Alignment.Center) {
                                Text(b.passengerName.take(1).uppercase(), fontWeight = FontWeight.Black, color = CanonGreen2)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(b.passengerName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (b.route.isNotBlank()) Text(b.route, color = CanonMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            b.passengerRating?.let {
                                Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(3.dp))
                                Text(it.toString(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (1..5).forEach { n ->
                                Icon(
                                    Icons.Default.Star,
                                    contentDescription = "$n",
                                    tint = if (n <= stars) CanonStar else CanonBorder,
                                    modifier = Modifier.size(34.dp).clickable {
                                        stars = n
                                        onRate(b.bookingId, n)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            SettingsGroup {
                SettingsNavRow(Icons.Default.ListAlt, appText("Заявки пассажиров", "Пассажир заявкалары"), appText("Откликнуться и предложить поездку", "Яуап биреп сәфәр тәҡдим итеү"), onClick = onRequestsFeed)
                SettingsNavRow(Icons.Default.AddRoad, appText("Создать поездку", "Сәфәр булдырыу"), appText("Маршрут, места, цена и время", "Маршрут, урын, хаҡ һәм ваҡыт"), onClick = onCreateRide)
                SettingsNavRow(Icons.Default.Verified, appText("Проверка водителя", "Водителде тикшереү"), appText("Права, машина, фото и госномер", "Права, машина, фото һәм номер"), onClick = onVerifyDriver)
                SettingsNavRow(Icons.Default.TrendingUp, appText("Поднять маршрут", "Маршрутты күтәреү"), appText("Показать выше в списке поездок", "Сәфәрҙәр исемлегендә өҫтәрәк күрһәтеү"), onClick = onBoost)
            }
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
    submittingId: String?,
    onRetry: () -> Unit,
    onCreateAd: () -> Unit,
    onEditAd: (MyAdDto) -> Unit,
    onSubmitAd: (MyAdDto) -> Unit,
    onPayAd: (MyAdDto) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CanonGreen2)
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
                        submitting = submittingId == ad.id,
                        onEdit = { onEditAd(ad) },
                        onSubmit = { onSubmitAd(ad) },
                        onPay = { onPayAd(ad) },
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
        "rejected" -> Triple(appText("Отклонено", "Кире ҡағылды"), CanonRed, CanonRed.copy(alpha = 0.12f))
        "paused" -> Triple(appText("На паузе", "Туҡталышта"), CanonMuted, CanonMint)
        "draft" -> Triple(appText("Черновик", "Ҡаралама"), CanonMuted, CanonMint)
        else -> Triple(appText("Завершено", "Тамамланды"), CanonMuted, CanonMint)
    }
    Surface(color = bg, shape = RoundedCornerShape(999.dp)) {
        Text(label, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = fg, fontWeight = FontWeight.Bold, fontSize = 11.sp)
    }
}

@Composable
internal fun MyAdCard(ad: MyAdDto, submitting: Boolean, onEdit: () -> Unit, onSubmit: () -> Unit, onPay: () -> Unit) {
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
            if (ad.status == "rejected" && ad.rejectReason.isNotBlank()) {
                Surface(color = CanonRed.copy(alpha = 0.10f), shape = RoundedCornerShape(12.dp)) {
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
                Text(appText("Оплачено · объявление показывается.", "Түләнде · иғлан күрһәтелә."), color = CanonGreen2, fontSize = 12.sp)
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
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val errNet = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Сеткәне тикшер, ҡабатла.")
    val needTitle = appText("Впиши заголовок", "Башлыҡ яҙ")
    val needPkg = appText("Выбери тариф", "Тариф һайла")
    LaunchedEffect(Unit) { ApiClient.getAdPackages().onSuccess { packages = it } }

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
        modifier = clickModifier,
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
            Column {
                Text(title, color = CanonText, fontWeight = FontWeight.Bold)
                Text(text, color = CanonMuted)
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
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(color = CanonMint, shape = RoundedCornerShape(999.dp), border = BorderStroke(1.dp, CanonBorder)) {
                    Text(label, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = CanonGreen2, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
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
            Text(text, color = CanonGreen2, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
