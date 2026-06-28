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
    onHospitalTrips: () -> Unit,
    onToggleLanguage: () -> Unit,
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
    LaunchedEffect(Unit) {
        ApiClient.me().onSuccess { o ->
            myRating = if (o.isNull("rating")) null else o.optDouble("rating")
            o.optString("name").takeIf { it.isNotBlank() }?.let { displayName = it }
            o.optString("avatar_url").takeIf { it.isNotBlank() }?.let { avatarUrl = it }
            role = o.optString("role")
        }
    }
    val editCtx = LocalContext.current
    val editScope = rememberCoroutineScope()
    val avatarSavedMsg = appText("Фото обновлено", "Фото яңыртылды")
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { u ->
            editScope.launch {
                val bytes = runCatching { editCtx.contentResolver.openInputStream(u)?.use { it.readBytes() } }.getOrNull()
                if (bytes != null) ApiClient.uploadChatPhoto(bytes).onSuccess { url ->
                    ApiClient.updateAvatar(url).onSuccess { avatarUrl = url; Toast.makeText(editCtx, avatarSavedMsg, Toast.LENGTH_SHORT).show() }
                }
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
                        editScope.launch { ApiClient.updateName(n).onSuccess { displayName = n; Toast.makeText(editCtx, nameSavedMsg, Toast.LENGTH_SHORT).show() } }
                        showEditName = false
                    }
                }) { Text(appText("Сохранить", "Һаҡлау"), color = CanonGreen2, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showEditName = false }) { Text(appText("Отмена", "Баш тартыу"), color = CanonMuted) } },
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
                                Brush.linearGradient(listOf(CanonGreen, Color(0xFF0E6C3F))),
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
                                        Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFFFD54A), modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text(r.toString(), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
                                Text(appText("Профиль подтверждён", "Профиль раҫланған"), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
            item { Box(Modifier.appearIn(5)) { ProfileActionCard(appText("Поездки в больницу", "Больницаға сәфәрҙәр"), appText("Быстрый фильтр для важных поездок", "Мөһим сәфәрҙәр өсөн тиҙ фильтр"), Icons.Default.LocalHospital, onHospitalTrips) } }
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
            item { Box(Modifier.appearIn(12)) { ProfileActionCard(appText("Помощь", "Ярдам"), appText("Ответы на частые вопросы", "Йыш һорауҙарға яуаптар"), Icons.Default.Help, onHelp) } }
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
            item { Spacer(Modifier.height(92.dp)) }
        }
    }
}

@Composable
private fun ProfileActionCard(
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
    onFindRide: () -> Unit,
    onCreateRequest: () -> Unit,
    onSafety: () -> Unit
) {
    val activeRide = rides.firstOrNull()
    var myRating by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) { ApiClient.me().onSuccess { o -> myRating = if (o.isNull("rating")) null else o.optDouble("rating") } }
    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Кабинет пассажира", "Пассажир кабинеты"), onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Text(appText("Ваши поездки и заявки", "Һеҙҙең сәфәрҙәр һәм заявкалар"), color = CanonGreen, fontSize = 25.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black)
                Text(appText("Быстрый доступ к бронированиям, заявкам и защите поездки.", "Брондәргә, заявкаларға һәм хәүефһеҙлеккә тиҙ инеү."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CabinetMetric(appText("Активные", "Актив"), rides.size.toString(), Modifier.weight(1f))
                    CabinetMetric(appText("Заявки", "Заявкалар"), requests.size.toString(), Modifier.weight(1f))
                    CabinetMetric(appText("Рейтинг", "Рейтинг"), myRating?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—", Modifier.weight(1f))
                }
            }
            activeRide?.let { ride ->
                item {
                    MyTripCard(
                        ride = ride,
                        status = appText("Ближайшая", "Яҡындағы"),
                        statusColor = CanonMint,
                        icon = Icons.Default.EventSeat,
                        primaryAction = appText("Найти похожую", "Оҡшашын табыу"),
                        secondaryAction = appText("Безопасность", "Хәүефһеҙлек"),
                        onPrimary = onFindRide,
                        onSecondary = onSafety
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
}

@Composable
internal fun DriverCabinetScreen(
    rides: List<Ride>,
    onBack: () -> Unit,
    onCreateRide: () -> Unit,
    onVerifyDriver: () -> Unit,
    onBoost: () -> Unit,
    onRequestsFeed: () -> Unit = {}
) {
    val driverRides = remember(rides) { rides.filter { it.driver == (ApiClient.cachedName() ?: "Я") } }
    val ctx = LocalContext.current
    val rateScope = rememberCoroutineScope()
    var driverBookings by remember { mutableStateOf<List<com.yuldash.app.data.DriverBookingDto>>(emptyList()) }
    var driverRating by remember { mutableStateOf<Double?>(null) }
    var online by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        ApiClient.getDriverBookings().onSuccess { driverBookings = it }
        ApiClient.me().onSuccess { o -> driverRating = if (o.isNull("rating")) null else o.optDouble("rating") }
        ApiClient.getDriverStatus().onSuccess { online = it.online }
    }
    val thanksMsg = appText("Спасибо за оценку", "Баһа өсөн рәхмәт")
    val rateFailMsg = appText("Не получилось оценить", "Баһалап булманы")
    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Кабинет водителя", "Водитель кабинеты"), onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Text(appText("Маршруты и проверка", "Маршруттар һәм тикшереү"), color = CanonGreen, fontSize = 25.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black)
                Text(appText("Публикуйте поездки, проходите проверку и поднимайте маршрут выше.", "Сәфәр баҫтырығыҙ, тикшереү үтегеҙ һәм маршрутты өҫкә күтәрегеҙ."), color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp)
            }
            item {
                SettingsGroup {
                    SettingSwitchRow(
                        Icons.Default.DirectionsCar,
                        appText("Я на линии", "Мин эштә"),
                        appText("Пассажиры видят, что вы готовы везти сейчас", "Пассажирҙар хәҙер әҙер икәнегеҙҙе күрә"),
                        online,
                    ) { v -> online = v; rateScope.launch { ApiClient.setOnline(v) } }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CabinetMetric(appText("Мои маршруты", "Минең маршруттар"), driverRides.size.toString(), Modifier.weight(1f))
                    CabinetMetric(appText("Свободно", "Буш"), driverRides.sumOf { it.seats }.toString(), Modifier.weight(1f))
                    CabinetMetric(appText("Рейтинг", "Рейтинг"), driverRating?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—", Modifier.weight(1f))
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
                                    Icon(Icons.Default.Star, contentDescription = null, tint = Color(0xFFE7A921), modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text(it.toString(), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                (1..5).forEach { n ->
                                    Icon(
                                        Icons.Default.Star,
                                        contentDescription = "$n",
                                        tint = if (n <= stars) Color(0xFFE7A921) else CanonBorder,
                                        modifier = Modifier.size(34.dp).clickable {
                                            stars = n
                                            rateScope.launch {
                                                ApiClient.rateBooking(b.bookingId, n)
                                                    .onSuccess { Toast.makeText(ctx, thanksMsg, Toast.LENGTH_SHORT).show() }
                                                    .onFailure { Toast.makeText(ctx, rateFailMsg, Toast.LENGTH_SHORT).show() }
                                            }
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
}

@Composable
private fun CabinetMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = CanonSurface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = CanonGreen2, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 1)
            Text(label, color = CanonMuted, fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

@Composable
internal fun AdsCabinetScreen(
    ads: List<PartnerAd>,
    adStats: Map<String, AdStats>,
    onBack: () -> Unit
) {
    // Реальная статистика с сервера (/ads/stats) перекрывает локальные счётчики сессии.
    var serverStats by remember { mutableStateOf<Map<String, AdStats>>(emptyMap()) }
    LaunchedEffect(Unit) {
        ApiClient.getAdStats().onSuccess { s -> serverStats = s.mapValues { AdStats(it.value.impressions, it.value.clicks) } }
    }
    val stats = adStats + serverStats
    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Кабинет рекламы", "Реклама кабинеты"), onBack) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Text(
                    appText("Партнёрские объявления Юлдаш", "Юлдаш партнёр иғландары"),
                    color = CanonGreen,
                    fontSize = 24.sp,
                    lineHeight = 27.sp,
                    fontWeight = FontWeight.Black
                )
                Text(
                    appText("Создание, сроки, erid, показы и клики собраны отдельно от профиля пользователя.", "Иғлан, ваҡыт, erid, күрһәтеү һәм баҫыу айырым кабинетта."),
                    color = CanonMuted,
                    fontSize = 14.sp,
                    lineHeight = 19.sp
                )
            }
            item { AdsAdminPreview(ads = ads, adStats = stats) }
        }
    }
}

@Composable
private fun AdsAdminPreview(ads: List<PartnerAd>, adStats: Map<String, AdStats>) {
    val activeCount = ads.count { it.status == AdStatus.Active }
    val moderationCount = ads.count { it.status == AdStatus.Moderation }
    val totalImpressions = ads.sumOf { adStats[it.id]?.impressions ?: 0 }
    val totalClicks = ads.sumOf { adStats[it.id]?.clicks ?: 0 }
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, Color(0x1A0B6B3A))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(11.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(appText("Кабинет рекламы", "Реклама кабинеты"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 17.sp)
                    Text(appText("Создание, сроки, erid, показы и клики", "Булдырыу, ваҡыт, erid, күрһәтеү һәм баҫыу"), color = CanonMuted, fontSize = 13.sp, lineHeight = 17.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdSummaryMetric(appText("Активно", "Актив"), activeCount.toString(), Modifier.weight(1f))
                AdSummaryMetric(appText("Модерация", "Модерация"), moderationCount.toString(), Modifier.weight(1f))
                AdSummaryMetric(appText("Клики", "Баҫыу"), totalClicks.toString(), Modifier.weight(1f))
            }
            Text(
                appText("Всего показов: $totalImpressions · общий CTR: ${if (totalImpressions == 0) 0 else (totalClicks * 100) / totalImpressions}%", "Бөтә күрһәтеү: $totalImpressions · дөйөм CTR: ${if (totalImpressions == 0) 0 else (totalClicks * 100) / totalImpressions}%"),
                color = CanonMuted,
                fontSize = 12.sp
            )
            Text(appText("Пакеты размещения", "Урынлаштырыу пакеттары"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
            AdPackageRow(appText("Город", "Ҡала"), appText("Показы в одном городе", "Бер ҡалала күрһәтеү"), "1 000–3 000 ₽ / мес")
            AdPackageRow(appText("Маршрут", "Маршрут"), appText("Показы на выбранном направлении", "Һайланған йүнәлештә күрһәтеү"), "2 000–5 000 ₽ / мес")
            AdPackageRow(appText("Главный партнёр", "Төп партнёр"), appText("Выше обычных партнёров маршрута", "Маршрут партнёрҙарынан юғарыраҡ"), "5 000–15 000 ₽ / мес")
            Text(
                appText("Цены — стартовая гипотеза, не рыночный факт.", "Хаҡтар — башланғыс фараз, баҙар факты түгел."),
                color = CanonMuted,
                fontSize = 11.sp
            )
            Text(appText("Запуск рекламы", "Рекламаны башлау"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
            AdsLaunchChecklist()
            Text(appText("Объявления", "Иғландар"), color = CanonText, fontWeight = FontWeight.Black, fontSize = 15.sp)
            val topAds = remember(ads) { ads.take(4) }
            topAds.forEach { ad ->
                val stats = adStats[ad.id] ?: AdStats()
                Surface(color = CanonSurface, shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, CanonBorder)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ad.titleText(), modifier = Modifier.weight(1f), color = CanonText, fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Surface(color = ad.status.color().copy(alpha = 0.12f), shape = RoundedCornerShape(999.dp)) {
                                Text(ad.status.label(), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = ad.status.color(), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            }
                        }
                        Text("erid: ${ad.eridText()} · ${ad.advertiserName}", color = CanonMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            appText(
                                "Пакет: ${ad.packageText()} · бюджет: ${ad.budgetText()}",
                                "Пакет: ${ad.packageText()} · бюджет: ${ad.budgetText()}"
                            ),
                            color = CanonMuted,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                        Text(appText("Показы: ${ad.placementsLabel()}", "Күрһәтә: ${ad.placementsLabel()}"), color = CanonMuted, fontSize = 11.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(appText("Точка: ${ad.mapPoint} · ${ad.contact}", "Нөктә: ${ad.mapPoint} · ${ad.contact}"), color = CanonMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${ad.startDate} — ${ad.endDate} · impressions_count ${stats.impressions} · clicks_count ${stats.clicks}", color = CanonMuted, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AdSummaryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = CanonMint, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 18.sp)
            Text(label, color = CanonMuted, fontSize = 10.sp, maxLines = 1)
        }
    }
}

@Composable
private fun AdsLaunchChecklist() {
    SettingsGroup {
        AdChecklistRow(appText("Креатив", "Креатив"), appText("Название, описание, адрес, кнопка", "Исем, аңлатма, адрес, төймә"), true)
        AdChecklistRow(appText("Таргетинг", "Таргетинг"), appText("Город, маршрут или категория", "Ҡала, маршрут йәки категория"), true)
        AdChecklistRow(appText("Маркировка", "Билдәләү"), appText("Рекламодатель и erid", "Реклама биреүсе һәм erid"), true)
        AdChecklistRow(appText("Модерация", "Модерация"), appText("Проверка перед показами", "Күрһәткәнгә тиклем тикшереү"), false)
    }
}

@Composable
private fun AdChecklistRow(title: String, subtitle: String, done: Boolean) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = if (done) CanonMint else CanonWarnBg, shape = CircleShape) {
            Icon(if (done) Icons.Default.CheckCircle else Icons.Default.Schedule, contentDescription = null, tint = if (done) CanonGreen2 else CanonWarn, modifier = Modifier.padding(9.dp).size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 14.sp)
            Text(subtitle, color = CanonMuted, fontSize = 12.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun AdPackageRow(title: String, subtitle: String, price: String) {
    Surface(color = CanonSurface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, CanonBorder)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Payments, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Black, fontSize = 13.sp)
                Text(subtitle, color = CanonMuted, fontSize = 11.sp, lineHeight = 14.sp)
            }
            Text(price, color = CanonGreen2, fontWeight = FontWeight.Black, fontSize = 12.sp)
        }
    }
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, Color(0x12000000))
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
                TextButton(onClick = { onClick(ad) }) {
                    Text(ad.primaryButtonText(), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
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
    onClick: (PartnerAd) -> Unit
) {
    LaunchedEffect(ad.id) {
        onImpression(ad)
    }
    val labelText = label ?: appText("Партнёр рядом", "Яҡындағы партнёр")

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, Color(0x1A0B6B3A))
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
                    onClick = { onClick(ad) },
                    modifier = Modifier.weight(1f).height(44.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2)
                ) {
                    Text(ad.primaryButtonText(), fontWeight = FontWeight.Black, fontSize = 13.sp, maxLines = 1)
                }
                ad.secondaryButtonText()?.let { button ->
                    OutlinedButton(
                        onClick = { onClick(ad) },
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
private fun AdChip(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
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
        AdStatus.Draft -> appText("Черновик", "Черновик")
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
        AdStatus.Paused -> Color(0xFF7C5C00)
        AdStatus.Finished -> Color(0xFF6F7570)
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
        border = BorderStroke(1.dp, Color(0x2235A363))
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
