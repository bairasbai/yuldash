package com.yuldash.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Место человека для экрана SOS (SosVerifyScreens.kt): правило свежести и подписка на GPS.
// Вынесено из экрана, чтобы он рисовал, а не держал машинерию координат.

/** Сколько ждём координаты в ЧП, прежде чем сказать «не получилось». Не анимация — это срок
 *  ожидания железа, поэтому не со шкалы `CanonMotion`. 12 секунд: холодный GPS обычно успевает,
 *  а человек в беде дольше смотреть на спиннер не должен. */
private const val SOS_LOCATE_TIMEOUT_MS = 12_000L

// Ограничение свежести места для SOS, не анимация: за минуту машина уже может уехать.
// Кэш карты без времени не подтверждает текущее место и здесь не используется.
internal const val SOS_LOCATION_MAX_AGE_MS = 60_000L

internal fun freshSosLocation(
    location: Location?,
    hasPermission: Boolean,
    nowElapsedNanos: Long = SystemClock.elapsedRealtimeNanos(),
    nowWallMillis: Long = System.currentTimeMillis(),
): Location? {
    if (!hasPermission || location == null) return null
    if (!location.latitude.isFinite() || location.latitude !in -90.0..90.0 ||
        !location.longitude.isFinite() || location.longitude !in -180.0..180.0) return null
    // Не доверяем отсутствующим/будущим меткам. Монотонные часы защищают от перевода
    // системных часов назад; обе метки обязаны подтверждать свежесть одной точки.
    if (location.time <= 0L || location.time > nowWallMillis ||
        location.elapsedRealtimeNanos <= 0L || location.elapsedRealtimeNanos > nowElapsedNanos) return null
    if (nowWallMillis - location.time > SOS_LOCATION_MAX_AGE_MS ||
        nowElapsedNanos - location.elapsedRealtimeNanos > SOS_LOCATION_MAX_AGE_MS * 1_000_000L) return null
    return Location(location)
}

/**
 * Живая геолокация для ЧП — запрашиваем прямо на экране SOS (а не ждём кеш с карты). Главное в SOS.
 * Разрешение и свежесть проверяются и при получении места, и в момент действия ([forAction]).
 */
@Stable
internal class SosLocationState(private val context: Context, private val scope: CoroutineScope) {
    var location by mutableStateOf<Location?>(null)
        private set
    var locating by mutableStateOf(false)
        private set
    // Попытка определить место закончилась ничем. Без этого экран ЧП вечно висел на «Обновляю…»:
    // одноразовый запрос координат ждали без срока, и если фикса не приходило (в помещении,
    // холодный старт GPS, эмулятор) — надпись не менялась никогда. Человек в беде ждёт того,
    // чего не будет, вместо того чтобы продиктовать адрес словами (живая проверка 2026-08-12).
    var failed by mutableStateOf(false)
        private set
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null
    private var timeout: Job? = null
    internal var requestPermission: () -> Unit = {}

    fun hasPermission() = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    fun current() = freshSosLocation(location, hasPermission())

    // Снять подписку на координаты. Обязательна: без неё каждое нажатие «Обновить» оставляло
    // висеть ещё один слушатель GPS — на экране, который открывают в аварии.
    fun stop() {
        listener?.let { l -> try { lm.removeUpdates(l) } catch (e: SecurityException) {} }
        listener = null
        timeout?.cancel(); timeout = null
        locating = false
    }

    fun clear() {
        location = null
        stop()
        failed = true
    }

    private fun accept(loc: Location): Boolean {
        val fresh = freshSosLocation(loc, hasPermission()) ?: return false
        location = fresh
        LocationPrefs.lastLat = fresh.latitude; LocationPrefs.lastLng = fresh.longitude
        return true
    }

    /** Место для действия (сигнал, копирование): снимок прямо сейчас, а не из последней отрисовки. */
    fun forAction(): Location? {
        val fresh = current()
        if (!hasPermission()) { clear(); return null }
        else if (fresh == null && location != null) { location = null; failed = true }
        return fresh
    }

    fun fetch() {
        stop()          // повторное нажатие не должно плодить слушателей
        if (!hasPermission()) { clear(); return }
        location = current()
        try {
            // 1) Последняя известная точка допустима только со свежим временем.
            // Старый GPS не должен прятать более свежую точку другого источника.
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
                .mapNotNull { freshSosLocation(lm.getLastKnownLocation(it), hasPermission()) }
                .maxByOrNull { it.elapsedRealtimeNanos }?.let { accept(it) }
            // 2) Ждём разрешённую свежую точку, затем снимаем подписку.
            val provider = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider == null) {
                failed = current() == null
                return
            }
            locating = true; failed = false
            val next = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    if (!hasPermission()) clear()
                    else if (accept(loc)) stop()
                }
                override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }
            listener = next
            lm.requestLocationUpdates(provider, 0L, 0f, next, android.os.Looper.getMainLooper())
            // Ждём фикс ограниченное время. Не дождались — честно говорим и даём «Повторить»:
            // в ЧП лучше сразу перейти к «продиктуй адрес словами», чем смотреть на спиннер.
            timeout = scope.launch {
                delay(SOS_LOCATE_TIMEOUT_MS)
                val gotNothing = current() == null
                if (gotNothing) location = null
                stop()
                failed = gotNothing
            }
        } catch (e: SecurityException) { clear() }
        catch (e: Exception) { stop(); failed = current() == null }
    }

    /** «Обновить»/«Повторить»: без разрешения — спросить его снова, старое место не держим. */
    fun request() {
        if (hasPermission()) fetch()
        else { clear(); requestPermission() }
    }
}

@Composable
internal fun rememberSosLocation(): SosLocationState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { SosLocationState(context, scope) }
    // Ушли с экрана — подписку снимаем: GPS не должен работать «в никуда».
    DisposableEffect(Unit) { onDispose { state.stop() } }
    // Android 12+ требует совместный запрос FINE+COARSE; примерное место не выдаём за точное.
    val permissions = remember {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) state.fetch() else state.clear()
    }
    SideEffect { state.requestPermission = { launcher.launch(permissions) } }
    LaunchedEffect(Unit) {
        if (state.hasPermission()) state.fetch()
        else launcher.launch(permissions)
    }
    // Убрали разрешение в настройках и вернулись — старое место сразу исчезает.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.forAction()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // Место устаревает и без нажатий: через минуту координаты пропадают с экрана сами.
    LaunchedEffect(state.location) {
        val location = state.location ?: return@LaunchedEffect
        val ageMillis = maxOf(System.currentTimeMillis() - location.time,
            (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L)
        delay((SOS_LOCATION_MAX_AGE_MS - ageMillis).coerceAtLeast(0L) + 1L)
        state.forAction()
    }
    return state
}
