package com.yuldash.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.TipInfoDto
import com.yuldash.app.data.TripReceiptDto
import kotlinx.coroutines.launch

/**
 * Отдельный экран после завершения попутки — утверждённый вариант B «Рәхмәт прежде всего».
 *
 * В отличие от активной поездки здесь нет карты, SOS и чата: поездка уже закончилась.
 * Остаются только действия после дороги — оценка, благодарность, чек, потерянная вещь и помощь.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RideshareCompletedScreen(
    bookingId: Int,
    ride: Ride?,
    fallbackRole: String,
    fallbackPayMethod: String,
    fallbackPayAmount: Int?,
    onClose: () -> Unit,
    onOpenReceipt: () -> Unit,
    onSupport: () -> Unit,
    loadReceipt: suspend (Int) -> Result<TripReceiptDto> = { ApiClient.getTripReceipt(it) },
    rateBooking: suspend (Int, Int, String, List<String>) -> Result<Unit> = { id, stars, text, tags ->
        ApiClient.rateBooking(id, stars, text, tags)
    },
    loadThanks: suspend (Int) -> Result<TipInfoDto> = { ApiClient.getBookingTipInfo(it) },
    sendThanks: suspend (Int) -> Result<Unit> = { ApiClient.sayBookingThanks(it) },
    openLostItem: suspend (Int) -> Result<Unit> = { ApiClient.bookingLostItem(it).map { Unit } },
) {
    BackHandler(onBack = onClose)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var receipt by remember(bookingId) { mutableStateOf<TripReceiptDto?>(null) }
    var receiptLoading by remember(bookingId) { mutableStateOf(true) }
    var receiptError by remember(bookingId) { mutableStateOf<String?>(null) }
    var receiptTick by remember(bookingId) { mutableIntStateOf(0) }
    var ratingTouched by rememberSaveable(bookingId) { mutableStateOf(false) }
    var stars by rememberSaveable(bookingId) { mutableIntStateOf(0) }
    var selectedTags by rememberSaveable(bookingId) { mutableStateOf(emptyList<String>()) }
    var reviewText by rememberSaveable(bookingId) { mutableStateOf("") }
    var reviewExpanded by rememberSaveable(bookingId) { mutableStateOf(false) }
    var ratingBusy by rememberSaveable(bookingId) { mutableStateOf(false) }
    var ratingSent by rememberSaveable(bookingId) { mutableStateOf(false) }
    var actionError by remember(bookingId) { mutableStateOf<String?>(null) }
    var thanked by rememberSaveable(bookingId) { mutableStateOf(false) }
    var thanksBusy by rememberSaveable(bookingId) { mutableStateOf(false) }
    var lostOpened by rememberSaveable(bookingId) { mutableStateOf(false) }
    var lostBusy by rememberSaveable(bookingId) { mutableStateOf(false) }

    val receiptFail = appText(
        "Не получилось загрузить детали. Основные данные поездки сохранены.",
        "Ентеклектәрҙе йөкләп булманы. Сәфәрҙең төп мәғлүмәттәре һаҡланған.",
    )
    val ratingFail = appText(
        "Не получилось отправить оценку. Проверь сеть и повтори.",
        "Баһаны ебәреп булманы. Селтәрҙе тикшереп ҡабатла.",
    )
    val thanksFail = appText(
        "Не получилось передать благодарность. Повтори ещё раз.",
        "Рәхмәтте ебәреп булманы. Тағы бер тапҡыр ҡабатла.",
    )
    val lostFail = appText(
        "Не получилось открыть чат поездки. Проверь сеть и повтори.",
        "Сәфәр чатын асып булманы. Селтәрҙе тикшереп ҡабатла.",
    )

    LaunchedEffect(bookingId, receiptTick) {
        receiptLoading = true
        receiptError = null
        loadReceipt(bookingId)
            .onSuccess { value ->
                receipt = value
                if (!ratingTouched) {
                    stars = value.myStars.coerceIn(0, 5)
                    selectedTags = value.myRatingTags.split(',').map(String::trim).filter(String::isNotBlank)
                    ratingSent = value.myStars in 1..5
                }
            }
            .onFailure { receiptError = serverSaid(it, receiptFail) }
        receiptLoading = false
    }

    val effectiveRole = receipt?.role?.takeIf(String::isNotBlank) ?: fallbackRole
    val isDriver = effectiveRole == "driver"
    LaunchedEffect(bookingId, isDriver) {
        if (!isDriver) {
            loadThanks(bookingId).onSuccess { thanked = it.alreadyThanked }
        }
    }

    val from = receipt?.fromCity?.takeIf(String::isNotBlank) ?: ride?.from.orEmpty()
    val to = receipt?.toCity?.takeIf(String::isNotBlank) ?: ride?.to.orEmpty()
    val counterpartyName = receipt?.counterpartyName?.takeIf(String::isNotBlank)
        ?: if (isDriver) appText("Попутчик", "Юлдаш")
        else ride?.driver?.takeIf(String::isNotBlank) ?: appText("Водитель", "Йөрөтөүсе")
    val amount = receipt?.amount?.takeIf { it > 0 }
        ?: fallbackPayAmount?.takeIf { it > 0 }
        ?: ride?.price?.takeIf { it > 0 }
    val payMethod = receipt?.payMethod?.takeIf(String::isNotBlank) ?: fallbackPayMethod
    val paid = receipt?.paid == true

    RideshareCompletedContent(
        isDriver = isDriver,
        from = from,
        to = to,
        counterpartyName = counterpartyName,
        counterpartyAvatar = if (isDriver) "" else ride?.driverAvatar.orEmpty(),
        counterpartyRating = if (isDriver) null else ride?.rating?.takeIf { it > 0.0 },
        counterpartyVerified = !isDriver && (receipt?.driverVerified ?: ride?.verified == true),
        car = if (isDriver) "" else ride?.car.orEmpty(),
        amount = amount,
        payMethod = payMethod,
        paid = paid,
        receiptLoading = receiptLoading,
        receiptError = receiptError,
        onRetryReceipt = { receiptTick++ },
        stars = stars,
        selectedTags = selectedTags,
        reviewText = reviewText,
        reviewExpanded = reviewExpanded,
        ratingBusy = ratingBusy,
        ratingSent = ratingSent,
        thanked = thanked,
        thanksBusy = thanksBusy,
        lostOpened = lostOpened,
        lostBusy = lostBusy,
        actionError = actionError,
        onStar = { value ->
            ratingTouched = true
            stars = value
            selectedTags = emptyList()
            ratingSent = false
            actionError = null
        },
        onTag = { code ->
            ratingTouched = true
            selectedTags = if (code in selectedTags) selectedTags - code else selectedTags + code
            ratingSent = false
            actionError = null
        },
        onReviewExpanded = { reviewExpanded = !reviewExpanded },
        onReviewText = { reviewText = it.take(500) },
        onSubmit = {
            if (ratingSent) {
                onClose()
            } else if (stars > 0 && !ratingBusy) {
                ratingBusy = true
                actionError = null
                scope.launch {
                    rateBooking(bookingId, stars, reviewText, selectedTags)
                        .onSuccess {
                            ratingSent = true
                            if (stars == 5 && !isDriver) maybeRequestStoreReview(context)
                        }
                        .onFailure { actionError = serverSaid(it, ratingFail) }
                    ratingBusy = false
                }
            }
        },
        onSkip = onClose,
        onThanks = {
            if (!thanked && !thanksBusy) {
                thanksBusy = true
                actionError = null
                scope.launch {
                    sendThanks(bookingId)
                        .onSuccess { thanked = true }
                        .onFailure { actionError = serverSaid(it, thanksFail) }
                    thanksBusy = false
                }
            }
        },
        onReceipt = onOpenReceipt,
        onLostItem = {
            if (!lostOpened && !lostBusy) {
                lostBusy = true
                actionError = null
                scope.launch {
                    openLostItem(bookingId)
                        .onSuccess { lostOpened = true }
                        .onFailure { actionError = serverSaid(it, lostFail) }
                    lostBusy = false
                }
            }
        },
        onSupport = onSupport,
        showOnlinePay = !isDriver && !paid,
        onlinePay = {
            PayOnlineCard(
                amountKop = amount?.times(100),
                pay = { method -> ApiClient.payBooking(bookingId, method) },
            )
        },
    )
}

@Composable
internal fun RideshareCompletedContent(
    isDriver: Boolean,
    from: String,
    to: String,
    counterpartyName: String,
    counterpartyAvatar: String,
    counterpartyRating: Double?,
    counterpartyVerified: Boolean,
    car: String,
    amount: Int?,
    payMethod: String,
    paid: Boolean,
    receiptLoading: Boolean,
    receiptError: String?,
    onRetryReceipt: () -> Unit,
    stars: Int,
    selectedTags: List<String>,
    reviewText: String,
    reviewExpanded: Boolean,
    ratingBusy: Boolean,
    ratingSent: Boolean,
    thanked: Boolean,
    thanksBusy: Boolean,
    lostOpened: Boolean,
    lostBusy: Boolean,
    actionError: String?,
    onStar: (Int) -> Unit,
    onTag: (String) -> Unit,
    onReviewExpanded: () -> Unit,
    onReviewText: (String) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
    onThanks: () -> Unit,
    onReceipt: () -> Unit,
    onLostItem: () -> Unit,
    onSupport: () -> Unit,
    showOnlinePay: Boolean = false,
    onlinePay: @Composable () -> Unit = {},
) {
    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Поездка завершена", "Сәфәр тамамланды"), onSkip) },
        bottomBar = {
            RideshareCompletedDecisionBar(
                submitted = ratingSent,
                loading = ratingBusy,
                enabled = ratingSent || stars > 0,
                onSkip = onSkip,
                onPrimary = onSubmit,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = CanonSpace.lg)
                .testTag("rideshareCompletedList"),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
        ) {
            if (receiptLoading) {
                item {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().testTag("rideshareReceiptLoading"),
                        color = CanonGreen2,
                        trackColor = CanonMint,
                    )
                }
            }
            item {
                RideshareCompletedHero(
                    isDriver = isDriver,
                    name = counterpartyName,
                    from = from,
                    to = to,
                )
            }
            item {
                RideshareCompletedPerson(
                    isDriver = isDriver,
                    name = counterpartyName,
                    avatar = counterpartyAvatar,
                    rating = counterpartyRating,
                    verified = counterpartyVerified,
                    car = car,
                )
            }
            item {
                RideshareRatingCard(
                    isDriver = isDriver,
                    stars = stars,
                    selectedTags = selectedTags,
                    reviewText = reviewText,
                    reviewExpanded = reviewExpanded,
                    busy = ratingBusy,
                    submitted = ratingSent,
                    onStar = onStar,
                    onTag = onTag,
                    onReviewExpanded = onReviewExpanded,
                    onReviewText = onReviewText,
                )
            }
            item {
                RideshareCompletedActionGrid(
                    isDriver = isDriver,
                    thanked = thanked,
                    thanksBusy = thanksBusy,
                    onThanks = onThanks,
                    onReceipt = onReceipt,
                    onSupport = onSupport,
                )
            }
            item {
                RideshareLostItemRow(
                    opened = lostOpened,
                    busy = lostBusy,
                    onClick = onLostItem,
                )
            }
            item {
                RideshareCompletedPayment(amount = amount, method = payMethod, paid = paid)
            }
            if (showOnlinePay) item { onlinePay() }
            AnimatedVisibilityItem(visible = receiptError != null) {
                CompletedInlineMessage(
                    text = receiptError.orEmpty(),
                    action = appText("Повторить", "Ҡабатлау"),
                    onAction = onRetryReceipt,
                )
            }
            AnimatedVisibilityItem(visible = actionError != null) {
                CompletedInlineMessage(text = actionError.orEmpty())
            }
            if (!isDriver) {
                item {
                    TextButton(
                        onClick = onSupport,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Default.HeadsetMic, contentDescription = null, tint = CanonMuted)
                        Spacer(Modifier.width(CanonSpace.sm))
                        Text(appText("Нужна помощь?", "Ярҙәм кәрәкме?"), style = CanonBodyStrong, color = CanonMuted)
                    }
                }
            }
            item { Spacer(Modifier.size(CanonSpace.sm)) }
        }
    }
}

@Composable
private fun RideshareCompletedHero(
    isDriver: Boolean,
    name: String,
    from: String,
    to: String,
) {
    val firstName = name.trim().substringBefore(' ').ifBlank { appText("друг", "юлдаш") }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("rideshareCompletedHero"),
        color = CanonMint,
        shape = CanonCardShape,
        border = BorderStroke(1.dp, CanonHairlineGreen),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(CanonSpace.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
        ) {
            Surface(color = CanonSurface, shape = CircleShape, modifier = Modifier.size(48.dp)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(28.dp))
                }
            }
            Text(
                if (isDriver) appText("Спасибо за компанию, $firstName!", "$firstName, юлдашлыҡ өсөн рәхмәт!")
                else appText("Рәхмәт, $firstName!", "$firstName, рәхмәт!") ,
                style = CanonTitle,
                color = CanonText,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (isDriver) appText("Ехать вместе было приятно", "Бергә барыуы күңелле булды")
                else appText("Спасибо за дорогу", "Юл өсөн рәхмәт"),
                style = CanonCaption,
                color = CanonMutedStrong,
                textAlign = TextAlign.Center,
            )
            Surface(color = CanonSurface, shape = CanonFieldShape) {
                Text(
                    "${from.ifBlank { "—" }}  →  ${to.ifBlank { "—" }}",
                    modifier = Modifier.padding(horizontal = CanonSpace.md, vertical = CanonSpace.sm),
                    style = CanonBodyStrong,
                    color = CanonGreen2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RideshareCompletedPerson(
    isDriver: Boolean,
    name: String,
    avatar: String,
    rating: Double?,
    verified: Boolean,
    car: String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, CanonBorder),
        shadowElevation = CanonDepth.card,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallAvatar(avatar, name, 56)
            Spacer(Modifier.width(CanonSpace.md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = CanonHeading, color = CanonText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (verified) {
                        Spacer(Modifier.width(CanonSpace.xs))
                        Icon(
                            Icons.Default.Verified,
                            contentDescription = appText("Проверен", "Тикшерелгән"),
                            tint = CanonGreen2,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Text(
                    if (isDriver) appText("Твой попутчик", "Һинең юлдашың")
                    else appText("Твой водитель", "Һинең йөрөтөүсең"),
                    style = CanonCaption,
                    color = CanonMuted,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    rating?.let {
                        Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(String.format("%.1f", it), style = CanonCaption, color = CanonText)
                    }
                    if (car.isNotBlank()) {
                        if (rating != null) Text("  ·  ", style = CanonCaption, color = CanonMuted)
                        Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(car, style = CanonCaption, color = CanonMutedStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private data class RideshareRatingTag(val code: String, val ru: String, val ba: String)

@Composable
private fun rideshareRatingTags(isDriver: Boolean, stars: Int): List<RideshareRatingTag> = when {
    stars >= 4 && isDriver -> listOf(
        RideshareRatingTag("polite", "Вежливый", "Итәғәтле"),
        RideshareRatingTag("ontime", "Вовремя вышел", "Ваҡытында сыҡты"),
        RideshareRatingTag("helpful", "Помог в дороге", "Юлда ярҙам итте"),
        RideshareRatingTag("comfortable", "Было легко общаться", "Аралашыуы еңел булды"),
    )
    stars >= 4 -> listOf(
        RideshareRatingTag("ontime", "Приехал вовремя", "Ваҡытында килде"),
        RideshareRatingTag("safe", "Ехал аккуратно", "Һаҡ йөрөттө"),
        RideshareRatingTag("clean", "Чистая машина", "Машина таҙа"),
        RideshareRatingTag("comfortable", "Было спокойно", "Тыныс булды"),
        RideshareRatingTag("helpful", "Помог в дороге", "Юлда ярҙам итте"),
    )
    isDriver -> listOf(
        RideshareRatingTag("late", "Опоздал", "Һуңланы"),
        RideshareRatingTag("rude", "Было некомфортно", "Уңайһыҙ булды"),
    )
    else -> listOf(
        RideshareRatingTag("late", "Опоздал", "Һуңланы"),
        RideshareRatingTag("unsafe", "Опасная езда", "Хәүефле йөрөтөү"),
        RideshareRatingTag("dirty", "Грязно в машине", "Машинала бысраҡ"),
        RideshareRatingTag("detour", "Лишний крюк", "Артыҡ урау"),
        RideshareRatingTag("rude", "Было некомфортно", "Уңайһыҙ булды"),
    )
}

@Composable
private fun RideshareRatingCard(
    isDriver: Boolean,
    stars: Int,
    selectedTags: List<String>,
    reviewText: String,
    reviewExpanded: Boolean,
    busy: Boolean,
    submitted: Boolean,
    onStar: (Int) -> Unit,
    onTag: (String) -> Unit,
    onReviewExpanded: () -> Unit,
    onReviewText: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("rideshareRatingCard"),
        color = CanonSurface,
        shape = CanonCardShape,
        border = BorderStroke(1.dp, CanonBorder),
        shadowElevation = CanonDepth.card,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(CanonSpace.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
        ) {
            AnimatedContent(
                targetState = submitted,
                transitionSpec = {
                    (fadeIn(tween(CanonMotion.NORMAL)) + scaleIn(initialScale = 0.96f)) togetherWith
                        (fadeOut(tween(CanonMotion.QUICK)) + scaleOut(targetScale = 0.96f))
                },
                label = "rideshareRatingTitle",
            ) { sent ->
                Text(
                    if (sent) appText("Оценка сохранена", "Баһа һаҡланды")
                    else appText("Оценишь поездку?", "Сәфәрҙе баһаларһыңмы?"),
                    style = CanonHeading,
                    color = CanonText,
                    textAlign = TextAlign.Center,
                )
            }
            Text(
                if (isDriver) appText("Как всё прошло с попутчиком", "Юлдаш менән сәфәр нисек үтте")
                else appText("Как всё прошло с водителем", "Йөрөтөүсе менән сәфәр нисек үтте"),
                style = CanonCaption,
                color = CanonMuted,
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                (1..5).forEach { value ->
                    val scale by animateFloatAsState(
                        targetValue = if (value == stars) 1.12f else 1f,
                        animationSpec = tween(CanonMotion.QUICK),
                        label = "rideshareStarScale$value",
                    )
                    androidx.compose.material3.IconButton(
                        onClick = { onStar(value) },
                        enabled = !busy,
                        modifier = Modifier.size(48.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                            .testTag("rideshareStar$value"),
                    ) {
                        Icon(
                            if (value <= stars) Icons.Default.Star else Icons.Outlined.StarOutline,
                            contentDescription = starsText(value),
                            tint = if (value <= stars) CanonStar else CanonMuted,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }
            }
            AnimatedVisibility(visible = stars > 0, enter = fadeIn(), exit = fadeOut()) {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
                ) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
                    ) {
                        rideshareRatingTags(isDriver, stars).forEach { tag ->
                            val selected = tag.code in selectedTags
                            val background by animateColorAsState(
                                if (selected) CanonMint else CanonSurface,
                                tween(CanonMotion.QUICK),
                                label = "rideshareTagBackground",
                            )
                            Surface(
                                onClick = { onTag(tag.code) },
                                enabled = !busy,
                                color = background,
                                shape = CanonFieldShape,
                                border = BorderStroke(1.dp, if (selected) CanonGreen2 else CanonBorder),
                                modifier = Modifier.padding(horizontal = 3.dp).heightIn(min = 48.dp)
                                    .semantics(mergeDescendants = true) {
                                        role = Role.Checkbox
                                        this.selected = selected
                                    },
                            ) {
                                Box(Modifier.padding(horizontal = 13.dp), contentAlignment = Alignment.Center) {
                                    Text(appText(tag.ru, tag.ba), style = CanonCaption, color = if (selected) CanonGreen2 else CanonMutedStrong)
                                }
                            }
                        }
                    }
                    TextButton(onClick = onReviewExpanded, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(
                            if (reviewExpanded) appText("Скрыть отзыв", "Фекерҙе йәшереү")
                            else appText("Добавить пару слов", "Бер-ике һүҙ өҫтәү"),
                            style = CanonBodyStrong,
                            color = CanonGreen2,
                        )
                    }
                    AnimatedVisibility(visible = reviewExpanded) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                            OutlinedTextField(
                                value = reviewText,
                                onValueChange = onReviewText,
                                modifier = Modifier.fillMaxWidth().testTag("rideshareReviewText"),
                                placeholder = { Text(appText("Что запомнилось?", "Нимә иҫтә ҡалды?")) },
                                minLines = 2,
                                maxLines = 4,
                                shape = CanonItemShape,
                            )
                            Text(
                                appText("Текст появится после проверки", "Текст тикшереүҙән һуң күренер"),
                                style = CanonMicro,
                                color = CanonMuted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RideshareCompletedActionGrid(
    isDriver: Boolean,
    thanked: Boolean,
    thanksBusy: Boolean,
    onThanks: () -> Unit,
    onReceipt: () -> Unit,
    onSupport: () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val first: @Composable (Modifier) -> Unit = { modifier ->
        CompletedActionCard(
            icon = if (isDriver) Icons.Default.ReceiptLong else Icons.Default.Favorite,
            title = if (isDriver) appText("Квитанция", "Сәфәр квитанцияһы")
            else if (thanked) appText("Рәхмәт передано", "Рәхмәт ебәрелде")
            else appText("Сказать «Рәхмәт»", "«Рәхмәт» әйтеү"),
            subtitle = if (isDriver) appText("Сумма и маршрут", "Сумма һәм юл")
            else if (thanked) appText("Тёплые слова уже у водителя", "Йылы һүҙҙәр йөрөтөүсегә барып етте")
            else appText("Тёплое спасибо водителю", "Йөрөтөүсегә йылы рәхмәт"),
            loading = !isDriver && thanksBusy,
            active = !isDriver && thanked,
            onClick = if (isDriver) onReceipt else onThanks,
            modifier = modifier,
        )
    }
    val second: @Composable (Modifier) -> Unit = { modifier ->
        CompletedActionCard(
            icon = if (isDriver) Icons.Default.HeadsetMic else Icons.Default.ReceiptLong,
            title = if (isDriver) appText("Поддержка", "Ярҙәм") else appText("Квитанция", "Сәфәр квитанцияһы"),
            subtitle = if (isDriver) appText("Разобраться с поездкой", "Сәфәрҙе асыҡларға")
            else appText("Сумма и маршрут", "Сумма һәм юл"),
            onClick = if (isDriver) onSupport else onReceipt,
            modifier = modifier,
        )
    }
    if (fontScale >= 1.25f) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            first(Modifier.fillMaxWidth())
            second(Modifier.fillMaxWidth())
        }
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    }
}

@Composable
private fun CompletedActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    active: Boolean = false,
) {
    val background by animateColorAsState(
        if (active) CanonMint else CanonSurface,
        tween(CanonMotion.QUICK),
        label = "completedActionBackground",
    )
    Surface(
        onClick = onClick,
        enabled = !loading,
        modifier = modifier.heightIn(min = 116.dp),
        color = background,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, if (active) CanonGreen2 else CanonBorder),
    ) {
        Column(
            Modifier.fillMaxSize().padding(CanonSpace.md),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(24.dp), color = CanonGreen2, strokeWidth = 2.dp)
            } else {
                Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.size(CanonSpace.sm))
            Text(title, style = CanonBodyStrong, color = CanonText, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = CanonMicro, color = CanonMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun RideshareLostItemRow(opened: Boolean, busy: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = !opened && !busy,
        modifier = Modifier.fillMaxWidth().alpha(if (opened) 0.86f else 1f).testTag("rideshareLostItem"),
        color = if (opened) CanonMint else CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, if (opened) CanonGreen2 else CanonBorder),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = CanonGreen2, strokeWidth = 2.dp)
            else Icon(if (opened) Icons.Default.CheckCircle else Icons.Default.Search, contentDescription = null, tint = CanonGreen2)
            Spacer(Modifier.width(CanonSpace.md))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (opened) appText("Чат поездки снова открыт", "Сәфәр чаты яңынан асылды")
                    else appText("Забыли вещь?", "Әйбер оноттоңмо?"),
                    style = CanonBodyStrong,
                    color = CanonText,
                )
                Text(
                    if (opened) appText("Можно написать второй стороне в течение 48 часов", "Икенсе яҡҡа 48 сәғәт эсендә яҙып була")
                    else appText("Откроем чат поездки на 48 часов", "Сәфәр чатын 48 сәғәткә асабыҙ"),
                    style = CanonCaption,
                    color = CanonMuted,
                )
            }
            if (!opened && !busy) Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
        }
    }
}

@Composable
private fun RideshareCompletedPayment(amount: Int?, method: String, paid: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("ridesharePaymentSummary"),
        color = CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, CanonBorder),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(appText("Итог поездки", "Сәфәр йомғағы"), style = CanonCaption, color = CanonMuted)
                Text(
                    amount?.let { "$it ₽" } ?: appText("По договорённости", "Килешеү буйынса"),
                    style = CanonHeading,
                    color = CanonText,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(payMethodLabel(method), style = CanonBodyStrong, color = CanonText, textAlign = TextAlign.End)
                Text(
                    if (paid) appText("Оплата подтверждена", "Түләү раҫланды")
                    else appText("Как договорились", "Килешкәнсә"),
                    style = CanonMicro,
                    color = if (paid) CanonGreen2 else CanonMuted,
                )
            }
        }
    }
}

@Composable
private fun CompletedInlineMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Surface(color = CanonDangerBg, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = CanonCaption, color = CanonRed, modifier = Modifier.weight(1f))
            if (action != null) {
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(action, style = CanonBodyStrong, color = CanonRed)
                }
            }
        }
    }
}

@Composable
private fun RideshareCompletedDecisionBar(
    submitted: Boolean,
    loading: Boolean,
    enabled: Boolean,
    onSkip: () -> Unit,
    onPrimary: () -> Unit,
) {
    val largeText = LocalDensity.current.fontScale >= 1.25f
    Surface(color = CanonSurface, shadowElevation = CanonDepth.raised) {
        val modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(CanonSpace.md)
        if (submitted) {
            AppButton(
                text = appText("Готово", "Әҙер"),
                onClick = onPrimary,
                modifier = modifier.testTag("rideshareRatingSubmit"),
                icon = Icons.Default.CheckCircle,
            )
        } else if (largeText) {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                AppButton(
                    text = appText("Отправить оценку", "Баһаны ебәреү"),
                    onClick = onPrimary,
                    loading = loading,
                    enabled = enabled,
                    modifier = Modifier.testTag("rideshareRatingSubmit"),
                )
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(appText("Пропустить", "Үткәреп ебәреү"), style = CanonBodyStrong, color = CanonMuted)
                }
            }
        } else {
            Row(modifier, horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSkip, modifier = Modifier.heightIn(min = 54.dp)) {
                    Text(appText("Пропустить", "Үткәреп ебәреү"), style = CanonBodyStrong, color = CanonMuted)
                }
                AppButton(
                    text = appText("Отправить оценку", "Баһаны ебәреү"),
                    onClick = onPrimary,
                    modifier = Modifier.weight(1f).testTag("rideshareRatingSubmit"),
                    loading = loading,
                    enabled = enabled,
                    fillWidth = false,
                )
            }
        }
    }
}

/** LazyColumn-вариант AnimatedVisibility без ручного Spacer и скачков ключей. */
private fun androidx.compose.foundation.lazy.LazyListScope.AnimatedVisibilityItem(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    if (visible) item { AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) { content() } }
}
