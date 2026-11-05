package com.yuldash.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yuldash.app.data.LocationSocket
import com.yuldash.app.data.TripLocationBus
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

/**
 * Foreground-сервис live-позиции. Пока активна поездка: шлёт мой GPS в WS-канал брони
 * (в движении раз в ~7с; водитель на стоянке — раз в 45 с, см. [TripLocationPacer]) и принимает позицию
 * ДРУГОГО участника (кладёт в TripLocationBus → карта рисует
 * маркер). Foreground = работает с погасшим экраном (Android не душит фоновую отправку).
 * Приватность: стрим только во время активной поездки; запускаем/глушим из YuldashApp по
 * activeBookingId. Доступ к точке определяет сервер отдельно для каждой брони.
 *
 * Водителю нужно отдельное живое разрешение процесса; перезапуск службы его не создаёт.
 */
class TripLocationService : Service() {
    private var socket: LocationSocket? = null
    private var currentBookingId = -1          // какой брони сейчас служит сокет (для смены поездки)
    private var socketGeneration = 0L
    private var lm: LocationManager? = null
    private var listener: LocationListener? = null
    private var isDriver = false                 // роль в этой брони: пульс стоянки нужен только водителю
    private var pacer = TripLocationPacer(heartbeatMs = NO_HEARTBEAT_MS)   // когда слать (пересоздаётся под роль и бронь)
    private var pacerForDriver = false
    private var currentLang = AppLanguage.Ru   // язык нотификации; приходит из YuldashApp по текущему AppLanguage
    private val watchdog = Handler(Looper.getMainLooper())   // сторож: сам глушит сервис, если YuldashApp упал и не позвал stop()
    private val autoStop = Runnable { stopSelf() }
    private var foregroundStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopTracking(); stopSelf(); return START_NOT_STICKY }
        val bookingId = intent.getIntExtra(EXTRA_BOOKING, -1)
        intent.getStringExtra(EXTRA_LANG)?.let { currentLang = runCatching { AppLanguage.valueOf(it) }.getOrDefault(AppLanguage.Ru) }
        isDriver = intent.getBooleanExtra(EXTRA_DRIVER, false)
        if (bookingId <= 0 || !hasPrecise() || (isDriver && DriverGpsActions.current()?.carrier != bookingId)) {
            if (isDriver && !hasPrecise()) DriverGpsActions.permission(false)
            stopTracking(); stopSelf(); return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        foregroundStarted = true
        if (isDriver && !DriverGpsActions.allowed(this, bookingId)) {
            currentBookingId = bookingId
            pauseDriverChannel(); resolveDriverCarrier()
            watchdog.removeCallbacks(driverGuard); watchdog.postDelayed(driverGuard, DRIVER_GUARD_MS)
            watchdog.removeCallbacks(driverRefresh); watchdog.postDelayed(driverRefresh, DRIVER_REFRESH_MS)
            return START_NOT_STICKY
        }
        closedBookings.remove(bookingId)
        TripLocationBus.bookingId = bookingId
        // Пульс стоянки — только водителю: его точку сервер хранит для ссылки близким и для SOS. Точку пассажира
        // сервер лишь пересылает водителю, а у того она и так остаётся на карте — стоящему пассажиру слать незачем.
        // Новая бронь или роль — новый темп: первая точка уходит сразу, без паузы от прошлой поездки.
        if (currentBookingId != bookingId || pacerForDriver != isDriver) {
            pacer = TripLocationPacer(heartbeatMs = if (isDriver) HEARTBEAT_MS else NO_HEARTBEAT_MS)
            pacerForDriver = isDriver
        }
        // Смена брони (поездка A кончилась, тут же началась B) при переиспользовании живого сервиса:
        // пересоздаём канал. Иначе мой GPS продолжил бы литься в WS-канал СТАРОЙ брони A (приватность + не та поездка).
        if (currentBookingId != bookingId) {
            socket?.close()
            currentBookingId = bookingId
            val generation = ++socketGeneration
            socket = LocationSocket(
                bookingId,
                onPeer = { TripLocationBus.peer = it },
                // Канал (пере)подключился — следующая точка GPS уходит сразу (≤ 3 с), а не ждёт остаток паузы:
                // после обрыва связи стоящая машина иначе молчала бы до 45 с.
                onConnected = { connected ->
                    watchdog.post {
                        if (socketGeneration == generation) {
                            TripLocationBus.rideShareActive = isDriver && connected
                            if (connected) pacer.reset()
                            updateNotification()
                        }
                    }
                },
                onTerminated = {
                    watchdog.post {
                        if (socketGeneration == generation) {
                            // Не ждём onDestroy: отказ уже окончательный, GPS и восстановление больше не нужны.
                            if (isDriver) { closedBookings += currentBookingId; DriverGpsActions.grant.pauseCarrier(currentBookingId); pauseDriverChannel(); resolveDriverCarrier() }
                            else { stopTracking(); stopSelf() }
                        }
                    }
                },
                mayPublish = { hasPrecise() && (!isDriver || DriverGpsActions.allowed(this, bookingId)) },
            ).also { it.connect() }
        }
        if (listener == null) startLocationUpdates()   // GPS-слушатель один на сервис (шлёт в текущий socket)
        TripLocationBus.driverBookingClosed = if (isDriver) ({ id ->
            closedBookings += id
            if (currentBookingId == id) pauseDriverChannel()
            resolveDriverCarrier()
        }) else null
        watchdog.removeCallbacks(autoStop)
        if (isDriver) {
            watchdog.removeCallbacks(driverGuard)
            watchdog.postDelayed(driverGuard, DRIVER_GUARD_MS)
            watchdog.removeCallbacks(driverRefresh)
            watchdog.postDelayed(driverRefresh, DRIVER_REFRESH_MS)
        } else watchdog.postDelayed(autoStop, MAX_LIFETIME_MS)
        return START_NOT_STICKY
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)
    private val closedBookings = mutableSetOf<Int>()
    private var resolving = false
    private var resolveVersion = 0L
    internal var driverFeed: suspend (Long) -> Result<DriverBookingsFeed> = ::gpsDriverBookings
    private fun hasPrecise() = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private val driverGuard = object : Runnable {
        override fun run() {
            if (isDriver && (DriverGpsActions.current() == null || !hasPrecise())) {
                if (!hasPrecise()) DriverGpsActions.permission(false)
                stopTracking(); stopSelf(); return
            }
            if (isDriver && !DriverGpsActions.allowed(this@TripLocationService, currentBookingId)) pauseDriverChannel()
            watchdog.postDelayed(this, DRIVER_GUARD_MS)
        }
    }
    private val driverRefresh = object : Runnable {
        override fun run() { resolveDriverCarrier(); watchdog.postDelayed(this, DRIVER_REFRESH_MS) }
    }
    private fun pauseDriverChannel() {
        DriverGpsActions.grant.pauseCarrier(currentBookingId)
        socketGeneration++; socket?.close(); socket = null
        listener?.let { runCatching { lm?.removeUpdates(it) } }; listener = null; lm = null
        TripLocationBus.peer = null; TripLocationBus.rideShareActive = false
        updateNotification()
    }
    private fun updateNotification() {
        if (foregroundStarted) getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }
    private fun resolveDriverCarrier() {
        if (!isDriver || resolving) return
        val lease = DriverGpsActions.current() ?: run { stopTracking(); stopSelf(); return }
        resolving = true
        val version = ++resolveVersion
        scope.launch {
            try {
                val requestedAt = SystemClock.elapsedRealtime()
                val feed = driverFeed(lease.ticket.owner.session).getOrNull()
                if (version != resolveVersion || DriverGpsActions.current()?.ticket?.id != lease.ticket.id) return@launch
                if (feed == null) { pauseDriverChannel(); return@launch }
                val pick = pickRideCarrier(feed.rows, lease.rideId, closedBookings)
                val carrier = pick.carrier
                if (carrier == null) {
                    pauseDriverChannel()
                    if (!feed.partial) { DriverGpsActions.revoke(); stopTracking(); stopSelf() }
                    return@launch
                }
                val now = SystemClock.elapsedRealtime()
                val reconnect = carrier != currentBookingId || socket == null
                if (reconnect) pauseDriverChannel()
                if (!DriverGpsActions.grant.transfer(DriverGpsActions.owner(), lease.rideId, carrier,
                        pick.carrierTtlSec?.let { requestedAt + it * 1000L } ?: Long.MAX_VALUE, now, System.currentTimeMillis())) return@launch
                if (reconnect) {
                    currentBookingId = -1
                    onStartCommand(Intent(this@TripLocationService, TripLocationService::class.java)
                        .putExtra(EXTRA_BOOKING, carrier).putExtra(EXTRA_DRIVER, true).putExtra(EXTRA_LANG, currentLang.name), 0, 0)
                }
            } finally { resolving = false }
        }
    }

    private fun startLocationUpdates() {
        if (isDriver && !DriverGpsActions.allowed(this, currentBookingId)) return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = manager
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                // Гео отозвали посреди поездки — точку не шлём и сервис не держим (тот же гейт, что на старте).
                if (ContextCompat.checkSelfPermission(this@TripLocationService, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) { DriverGpsActions.permission(false); stopTracking(); stopSelf(); return }
                if (isDriver && !DriverGpsActions.allowed(this@TripLocationService, currentBookingId)) { pauseDriverChannel(); return }
                // Монотонные часы: перевод времени на телефоне назад не замораживает отправку на час.
                val now = SystemClock.elapsedRealtime()
                if (!pacer.due(now, loc.latitude, loc.longitude)) return
                val bearing = if (loc.hasBearing()) loc.bearing.toDouble() else null
                // Отмечаем только ушедшую точку: канал переподключался — следующая точка повторит сразу.
                if (socket?.sendLoc(loc.latitude, loc.longitude, bearing) == true) pacer.sent(now, loc.latitude, loc.longitude)
            }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }
        listener = l
        // GPS основной; NETWORK — только если GPS выключен (иначе двойной расход батареи от двух провайдеров).
        // Порог расстояния 0, а не 10 м: стоящая машина иначе не получала ни одной точки, сервер забывал её
        // через 120 с, и близкие по ссылке и SOS теряли машину на стоянке (проверка PR170). GPS с интервалом
        // 3 с работает непрерывно при любом пороге — порог лишь отсеивал точки; «10 м» теперь считает pacer.
        runCatching {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, FIX_INTERVAL_MS, 0f, l)
            } else if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, FIX_INTERVAL_MS, 0f, l)
            }
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, appTextFor(currentLang, "Поездка", "Сәфәр"), NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(appTextFor(currentLang, "Юлдаш — поездка идёт", "Юлдаш — сәфәр бара"))
            // Уведомление в шторке — тоже голос Юлдаша: на «ты», как везде.
            .setContentText(if (isDriver) {
                if (TripLocationBus.rideShareActive) appTextFor(currentLang, "Показываем машину пассажирам рейса", "Машинаны рейс пассажирҙарына күрһәтәбеҙ")
                else appTextFor(currentLang, "Передача приостановлена — проверяем поездку", "Урынды күрһәтеү туҡтатылды — сәфәрҙе тикшерәбеҙ")
            } else appTextFor(currentLang, "Показываем твою позицию попутчику", "Урыныңды юлдашыңа күрһәтәбеҙ"))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun stopTracking() {
        resolveVersion++
        watchdog.removeCallbacks(driverGuard); watchdog.removeCallbacks(driverRefresh)
        TripLocationBus.driverBookingClosed = null; TripLocationBus.rideShareActive = false
        socketGeneration++
        watchdog.removeCallbacks(autoStop)
        listener?.let { runCatching { lm?.removeUpdates(it) } }
        listener = null
        pacer.reset()
        lm = null
        socket?.close()
        socket = null
        currentBookingId = -1
        TripLocationBus.peer = null
        TripLocationBus.bookingId = null
    }

    override fun onDestroy() {
        stopTracking()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BOOKING = "booking_id"
        const val EXTRA_LANG = "lang"
        const val EXTRA_DRIVER = "is_driver"   // я водитель в этой брони (нет — значит пассажир)
        private const val CHANNEL = "trip_location"
        private const val NOTIF_ID = 4711
        internal const val FIX_INTERVAL_MS = 3_000L   // как часто просим точку у GPS
        internal const val MIN_INTERVAL_MS = 7_000L   // в движении — не чаще раза в 7 с (батарея/трафик)
        internal const val MIN_DISTANCE_M = 10.0      // меньше — дрожание GPS на месте, а не езда
        // Пульс стоянки. Сервер держит точку машины 120 с (backend/app/livepos.py, LIVEPOS_TTL_SEC).
        // 45 с: даже если один пульс пропал в сети, следующий придёт через ≤ 2·45 + 3 = 93 с — до срока.
        // 60 с уже не держит потерю одного кадра (123 с), 30 с — вдвое больше кадров без нужды.
        internal const val HEARTBEAT_MS = 45_000L
        internal const val NO_HEARTBEAT_MS = Long.MAX_VALUE   // пассажир: на стоянке не шлём (см. onStartCommand)
        private const val DRIVER_GUARD_MS = 1_000L
        private const val DRIVER_REFRESH_MS = 12_000L
        private const val MAX_LIFETIME_MS = 6 * 3600_000L   // 6ч — заведомо дольше любой реальной поездки; страховка от зависшего сервиса

        internal fun start(ctx: Context, bookingId: Int, lang: AppLanguage, isDriver: Boolean) {
            val i = Intent(ctx, TripLocationService::class.java)
                .putExtra(EXTRA_BOOKING, bookingId)
                .putExtra(EXTRA_LANG, lang.name)
                .putExtra(EXTRA_DRIVER, isDriver)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TripLocationService::class.java))
        }
    }
}

/**
 * Когда отправлять свою точку. Чистая логика: часы и координаты приходят снаружи — её гоняют
 * обычные JVM-тесты, без эмулятора (`TripLocationPacerTest`).
 *
 *  • Едем — как и раньше: не чаще раза в [minIntervalMs] и только если сдвинулись на [minDistanceM]
 *    от ПОСЛЕДНЕЙ ОТПРАВЛЕННОЙ точки (дрожание GPS на стоянке за езду не считаем).
 *  • Стоим — всё равно раз в [heartbeatMs] (только водитель; пассажиру — [TripLocationService.NO_HEARTBEAT_MS]).
 *    Сервер держит машину для ссылки близким и для SOS 120 с; без пульса стоящая машина пропадала
 *    через 2 минуты — ровно когда «встала в поле» и близким она нужнее всего (проверка безопасности PR170).
 *  • Отдельного таймера нет: пульс уходит вместе с точкой, которую отдал Android. Сами мы старую
 *    точку не повторяем. Но, по нашему пониманию (на телефоне не проверено), Android 12+ в глубоком
 *    сне (Doze) может сам отдавать последнюю известную точку с новым временем — при пороге 0 м
 *    именно она и держит пульс, и сервер примет её за свежую. Таймер `Handler` в глубоком сне
 *    процессора опаздывает, а доставка точки будит процесс сама.
 *  • Нет точки совсем (крытая парковка, навес АЗС, GPS не видит неба; гео выключили) — пульса нет,
 *    и через 120 с машина у близких пропадает. Это осознанно: выдумывать место мы не будем.
 *  • Ушедшей считается только точка, которую принял сокет ([sent]): не принял — следующая точка
 *    (через ~3 с) повторит попытку, а не ждёт ещё 45 с. Канал переподключился — сервис зовёт
 *    [reset], и следующая точка уходит сразу.
 */
internal class TripLocationPacer(
    private val minIntervalMs: Long = TripLocationService.MIN_INTERVAL_MS,
    private val minDistanceM: Double = TripLocationService.MIN_DISTANCE_M,
    private val heartbeatMs: Long = TripLocationService.HEARTBEAT_MS,
) {
    private var hasSent = false
    private var sentAtMs = 0L
    private var sentLat = 0.0
    private var sentLng = 0.0

    /** Пора ли отправить эту точку. Ничего не запоминает — запоминает [sent]. */
    fun due(nowMs: Long, lat: Double, lng: Double): Boolean {
        if (!hasSent) return true
        val sinceMs = nowMs - sentAtMs
        if (sinceMs < minIntervalMs) return false
        return sinceMs >= heartbeatMs || metersBetween(sentLat, sentLng, lat, lng) >= minDistanceM
    }

    /** Точка ушла в канал — от неё считаем и паузу, и расстояние. */
    fun sent(nowMs: Long, lat: Double, lng: Double) {
        hasSent = true
        sentAtMs = nowMs
        sentLat = lat
        sentLng = lng
    }

    /** Стоп или (пере)подключение канала: следующая точка уходит сразу. */
    fun reset() {
        hasSent = false
    }

    private fun metersBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * 6_371_000.0 * Math.asin(Math.min(1.0, Math.sqrt(h)))
    }
}
