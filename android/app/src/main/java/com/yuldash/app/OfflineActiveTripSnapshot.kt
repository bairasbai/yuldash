package com.yuldash.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.OfflineTripSnapshot
import com.yuldash.app.data.OfflineTripSnapshots
import com.yuldash.app.data.SnapshotPersistence
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

internal data class OfflineTripSnapshotMode(val snapshot: OfflineTripSnapshot, val revision: Long)

/** Both navigation boundaries use the same wire-origin and optional-cache rule. */
internal suspend fun readBookingNavigation(bookingId: Int): BookingNavigationRead {
    val read = ApiClient.getTripStateForNavigation(bookingId)
    val saved = if (read.result.isFailure && read.fallbackAllowed) read.ticket?.let {
        OfflineTripSnapshots.load(it.owner, bookingId)
    } else null
    return BookingNavigationRead(read.result, saved)
}

internal data class BookingNavigationRead(
    val live: Result<com.yuldash.app.data.TripStateDto>, val saved: OfflineTripSnapshot?,
)

/** Never mounts ActiveTripScreen, its polling, action bar, chat or location effects. */
@Composable
internal fun OfflineActiveTripSnapshot(
    mode: OfflineTripSnapshotMode,
    isCurrent: () -> Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val app = LocalContext.current.applicationContext
    val current by rememberUpdatedState(isCurrent)
    val owner by OfflineTripSnapshots.owner.collectAsState()
    val version by OfflineTripSnapshots.version.collectAsState()
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    val timeChanged = remember(mode) { AtomicBoolean(false) }
    var timeRevision by remember(mode) { mutableLongStateOf(0L) }
    var displayed by remember(mode) { mutableStateOf<OfflineTripSnapshot?>(null) }
    var displayedVersion by remember(mode) { mutableLongStateOf(-1L) }
    var loading by remember(mode) { mutableStateOf(true) }

    DisposableEffect(lifecycle, mode) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (!resumed) { displayed = null; loading = true }
        }
        lifecycle.addObserver(observer)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_TIME_CHANGED) {
                    timeChanged.set(true); displayed = null; loading = false; timeRevision++
                }
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(Intent.ACTION_TIME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { lifecycle.removeObserver(observer); app.unregisterReceiver(receiver) }
    }
    LaunchedEffect(mode, owner, version, resumed, timeRevision) {
        displayed = null
        loading = true
        if (resumed && !timeChanged.get() && current() && OfflineTripSnapshots.current(mode.snapshot.owner)) {
            val readVersion = version
            val value = OfflineTripSnapshots.load(mode.snapshot.owner, mode.snapshot.bookingId)
            if (resumed && !timeChanged.get() && current() && OfflineTripSnapshots.current(mode.snapshot.owner) &&
                OfflineTripSnapshots.version.value == readVersion) { displayedVersion = readVersion; displayed = value }
        }
        loading = false
    }
    LaunchedEffect(displayed, resumed) {
        val value = displayed ?: return@LaunchedEffect
        while (resumed && current()) {
            val remaining = OfflineTripSnapshots.clockInBoot(value.stateStamp.boot)?.let(value::remaining) ?: break
            delay(remaining)
        }
        displayed = null
    }
    fun visible(value: OfflineTripSnapshot): Boolean = resumed && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        !timeChanged.get() && current() && OfflineTripSnapshots.current(value.owner) && OfflineTripSnapshots.version.value == displayedVersion &&
        OfflineTripSnapshots.clockInBoot(value.stateStamp.boot)?.let(value::remaining) != null
    val value = displayed?.takeIf(::visible)
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(CanonBg).safeDrawingPadding()) {
        ScreenTopBar(appText("Последние сохранённые данные", "Һуңғы һаҡланған мәғлүмәт"), onBack)
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(CanonSpace.lg),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.lg)) {
            Text(appText("Показываем сохранённую поездку. Состояние могло измениться.",
                "Һаҡланған сәфәрҙе күрһәтәбеҙ. Хәл үҙгәргән булыуы мөмкин."), style = CanonBody, color = CanonMutedStrong)
            // No fade-out of expired/private pixels. The draw guard also covers a delayed timer/frame.
            AnimatedVisibility(value != null, enter = fadeIn(tween(CanonMotion.QUICK)), exit = fadeOut(tween(CanonMotion.QUICK))) {
                if (value != null) AppCard(Modifier.drawWithContent { if (visible(value)) drawContent() }) {
                    val route = requireNotNull(value.route)
                    Column(Modifier.padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
                        Text(if (value.state.role == "driver") appText("Ты водитель", "Һин йөрөтөүсе")
                            else appText("Ты пассажир", "Һин пассажир"), style = CanonCaption, color = CanonMutedStrong)
                        Text("${route.from} → ${route.to}", style = CanonTitle, color = CanonText)
                        Text(formatDepart(route.departAt), style = CanonBody, color = CanonText)
                        BookingSegmentNote(route.pickup, route.dropoff, route.hasStops, route.from, route.to, showSegmentLabel = false)
                        val stage = when {
                            value.state.status == "onboard" -> appText("Пассажир сел", "Пассажир ултырҙы")
                            value.state.phase == "arriving" -> appText("Водитель подъезжает", "Йөрөтөүсе яҡынлаша")
                            value.state.phase == "departed" -> appText("Водитель выехал", "Йөрөтөүсе юлға сыҡты")
                            else -> appText("Поездка подтверждена", "Сәфәр раҫланды")
                        }
                        Text(appText("Последнее состояние: $stage", "Һуңғы хәл: $stage"), style = CanonBodyStrong, color = CanonText)
                        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value.stateStamp.wall))
                        Text(appText("Сохранено: $time", "Һаҡланған ваҡыт: $time"), style = CanonCaption, color = CanonMutedStrong)
                    }
                }
            }
            if (value == null) {
                if (loading) CircularProgressIndicator(color = CanonGreen2)
                else Text(appText("Сохранённые данные устарели или недоступны", "Һаҡланған мәғлүмәт иҫкергән йәки уны асып булмай"),
                    style = CanonBodyStrong, color = CanonText)
            }
            AppButton(appText("Повторить", "Ҡабатлау"), onClick = onRetry, enabled = isCurrent())
        }
    }
}

/** A cache failure is reported separately; it must never make a successful business POST repeat. */
@Composable
internal fun OfflineSnapshotSaveNotice() {
    val result by OfflineTripSnapshots.persistence.collectAsState()
    val app = LocalContext.current
    val message = appText("Не удалось сохранить копию поездки. Без сети она недоступна.",
        "Сәфәр күсермәһен һаҡлап булманы. Селтәрһеҙ уны асып булмай.")
    LaunchedEffect(result) {
        if (result == SnapshotPersistence.NOT_DURABLE) Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }
}
