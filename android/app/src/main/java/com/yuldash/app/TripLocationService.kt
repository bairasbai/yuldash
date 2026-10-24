package com.yuldash.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.DriverBookingDto
import com.yuldash.app.data.LocationSocket
import com.yuldash.app.data.TripLocationBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * Foreground-сервис live-позиции. Пока активна поездка: шлёт мой GPS в WS-канал брони
 * (в движении раз в ~7с; водитель на стоянке — раз в 45 с, см. [TripLocationPacer]) и принимает позицию
 * ДРУГОГО участника (кладёт в TripLocationBus → карта рисует
 * маркер). Foreground = работает с погасшим экраном (Android не душит фоновую отправку).
 * Приватность: стрим только во время активной поездки; запускаем/глушим из YuldashApp по
 * activeBookingId. Координаты на сервере НЕ хранятся (только ретрансляция).
 *
 * Водитель ведёт рейс (Д2, решение Александра 24.10.2026): «Завершить» по одному пассажиру GPS не гасит,
 * пока у рейса есть другие живые брони. Канал по-прежнему один — брони-«несущей»; закрылась она —
 * служба по свежему `/driver/bookings` берёт следующую (`pickRideCarrier`, DriverRideGps.kt) или гаснет.
 * Только в РЕЖИМЕ РЕЙСА ([DriverRideMode]): его включает шаг водителя на этом телефоне. Без режима —
 * как раньше. Кому и когда показать точку, решает сервер по правилам каждой брони.
 *
 * НЕ проверено вживую (нужны 2 телефона в одной брони) — собрано, помечено в docs/tasks.md.
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
    private val autoStop = Runnable { shutdown() }
    // --- Д2: рейс водителя ---
    private var owner = -1L                      // поколение входа, в котором служба работает; сменилось — гаснем
    private var ownerUser = 0                    // кто вошёл (0 — неизвестно); воскрешение под другим — стоп (P1-6)
    private var ride: DriverRideMode? = null     // режим рейса: только после шага водителя на этом телефоне
    private val closed = mutableSetOf<Int>()     // брони рейса, которые уже закрылись: обратно в их канал не идём
    private var carrierUntil = 0L                // elapsedRealtime: несущая в начале маршрута годна до срока «Не пришёл»; 0 — без срока
    private var untilElapsed = 0L                // потолок жизни без режима рейса: явный старт с экрана + 6 ч
    private var untilWall = 0L
    private var resolveSeq = 0                   // последний запрос списка; ответ на старый не применяем
    private var resolveAttempt = 0
    private var retryTask: Runnable? = null
    private var pendingClose = 0                 // «Завершить» по несущей без режима рейса: ждём список, канал не гасим
    private var destroyed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val carrierExpiry = Runnable { onCarrierExpired() }
    private val actions: (Int, Boolean) -> Unit = { id, closedNow -> watchdog.post { onDriverAction(id, closedNow) } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = WeakReference(this)
        TripLocationBus.driverActionListener = actions
    }

    private fun prefs(): SharedPreferences = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun hasLocation() = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return revive()
        val bookingId = intent.getIntExtra(EXTRA_BOOKING, -1)
        intent.getStringExtra(EXTRA_LANG)?.let { currentLang = runCatching { AppLanguage.valueOf(it) }.getOrDefault(AppLanguage.Ru) }
        val driver = intent.getBooleanExtra(EXTRA_DRIVER, false)
        // Нет брони ИЛИ нет гео-разрешения → не держим бесполезный foreground-сервис и ложную нотификацию
        // «показываем вашу позицию» (если юзер отозвал гео — стрим всё равно не пойдёт).
        if (bookingId <= 0 || !hasLocation()) { shutdown(); return START_NOT_STICKY }
        val session = ApiClient.queueSessionGeneration()
        val user = ApiClient.myUserId() ?: 0
        if (session != owner || user != ownerUser) { leaveRide(); closed.clear() }   // другой вход — с чистого листа
        owner = session
        ownerUser = user
        val r = ride
        // Устаревший экран (Д2): эта бронь уже закрылась, а служба везёт рейс по живой — в мёртвый канал не идём.
        if (driver && r != null && bookingId in closed && socket != null) { foreground(); return START_STICKY }
        isDriver = driver
        if (!driver || (r != null && bookingId !in r.bookings)) leaveRide()   // другая бронь не этого рейса — режим рейса снят
        if (ride == null) {
            // Явный старт с экрана: сторож — 6 ч от него, как раньше. Воскрешение срок не продлевает (P1-2).
            untilElapsed = SystemClock.elapsedRealtime() + MAX_LIFETIME_MS
            untilWall = System.currentTimeMillis() + MAX_LIFETIME_MS
        }
        carrierUntil = 0L
        watchdog.removeCallbacks(carrierExpiry)
        resolveSeq++   // явный выбор экрана важнее ответа, который ещё в пути
        retryTask?.let(watchdog::removeCallbacks)
        foreground()
        connect(bookingId)
        persist()
        armWatchdog()
        return START_STICKY
    }

    /**
     * H2: START_STICKY воскресил сервис после смерти процесса с intent==null. Долгая поездка ещё идёт —
     * продолжаем, но только тем же человеком (P1-6) и не дольше прежнего срока (P1-2). В режиме рейса
     * несущую сперва выбираем заново по свежему списку (P1-3): сохранённая могла устареть.
     */
    private fun revive(): Int {
        val p = prefs()
        val bookingId = p.getInt(KEY_BOOKING, -1)
        p.getString(KEY_LANG, null)?.let { currentLang = runCatching { AppLanguage.valueOf(it) }.getOrDefault(AppLanguage.Ru) }
        isDriver = p.getBoolean(KEY_DRIVER, false)
        val user = p.getInt(KEY_USER, 0)
        untilElapsed = p.getLong(KEY_UNTIL_ELAPSED, 0L)
        untilWall = p.getLong(KEY_UNTIL_WALL, 0L)
        ride = readRide(p)
        owner = ApiClient.queueSessionGeneration()
        ownerUser = user
        if (bookingId <= 0 || !hasLocation() || user == 0 || user != ApiClient.myUserId() || pastDeadline()) {
            shutdown(); return START_NOT_STICKY
        }
        foreground()
        armWatchdog()
        if (isDriver && ride != null) {
            currentBookingId = bookingId
            TripLocationBus.bookingId = bookingId
            TripLocationBus.rideShareActive = true
            if (listener == null) startLocationUpdates()
            resolve(anchor = bookingId)
        } else connect(bookingId)
        return START_STICKY
    }

    /** Канал брони [bookingId]; та же бронь и живой сокет — ничего не делаем. */
    private fun connect(bookingId: Int) {
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
        if (currentBookingId != bookingId || socket == null) {
            socket?.close()
            TripLocationBus.peer = null   // точка прошлого попутчика не висит на карте (P2-1)
            currentBookingId = bookingId
            val generation = ++socketGeneration
            socket = LocationSocket(
                bookingId,
                onPeer = { TripLocationBus.peer = it },
                // Канал (пере)подключился — следующая точка GPS уходит сразу (≤ 3 с), а не ждёт остаток паузы:
                // после обрыва связи стоящая машина иначе молчала бы до 45 с.
                onConnected = { connected ->
                    if (connected) watchdog.post { if (socketGeneration == generation) pacer.reset() }
                },
                onTerminated = {
                    watchdog.post { if (socketGeneration == generation && !destroyed) onChannelClosed() }
                },
                ownerSession = owner,
            ).also { it.connect() }
        }
        if (listener == null) startLocationUpdates()   // GPS-слушатель один на сервис (шлёт в текущий socket)
        TripLocationBus.rideShareActive = ride != null
    }

    /** Сервер закрыл канал окончательно (бронь закрыта, блок, вход отозван). */
    private fun onChannelClosed() {
        socket = null
        // Не ждём onDestroy: отказ уже окончательный, GPS и восстановление больше не нужны — если рейс не ведём.
        if (!isDriver || (ride == null && pendingClose != currentBookingId)) { shutdown(); return }
        closed += currentBookingId
        if (ride == null) return   // решит ответ на шаг водителя (onDriverAction)
        resolve(anchor = currentBookingId)
    }

    /**
     * Шаг водителя на этом телефоне (сервер принял): включает режим рейса, если бронь — того же рейса,
     * что везёт служба; закрытая несущая — сразу на следующую, не дожидаясь, пока сервер закроет канал.
     */
    private fun onDriverAction(bookingId: Int, closedNow: Boolean) {
        if (destroyed || !isDriver || currentBookingId <= 0 || !ApiClient.isCurrentSession(owner)) return
        val r = ride
        if (r != null && bookingId in r.bookings) {
            if (!closedNow) return
            closed += bookingId
            if (bookingId == currentBookingId) resolve(anchor = bookingId)
            return
        }
        val seq = ++resolveSeq
        if (closedNow && bookingId == currentBookingId) pendingClose = bookingId
        scope.launch {
            val rows = ApiClient.getDriverBookings(expectedGeneration = owner)
            if (!stillMine(seq)) return@launch
            pendingClose = 0
            // Не узнали рейс — режим не включаем, служба как раньше: канал уже закрыт — гаснем.
            val list = rows.getOrNull() ?: run { if (socket == null) shutdown(); return@launch }
            val receivedAt = SystemClock.elapsedRealtime()
            val actionRide = list.firstOrNull { it.bookingId == bookingId }?.rideId ?: 0
            val carrierRide = list.firstOrNull { it.bookingId == currentBookingId }?.rideId ?: 0
            // Шаг по другому рейсу — GPS этого рейса не касается (канал уже закрыт — гаснем, как раньше).
            if (actionRide <= 0 || actionRide != carrierRide) { if (socket == null) shutdown(); return@launch }
            val bookings = list.filter { it.rideId == actionRide }.map { it.bookingId }.toSet()
            ride = ride?.takeIf { it.rideId == actionRide }?.copy(bookings = bookings)
                ?: DriverRideMode.start(actionRide, bookings, receivedAt, System.currentTimeMillis())
            if (closedNow) closed += bookingId
            foreground()   // шторка: «показываем машину пассажирам рейса»
            armWatchdog()
            persist()
            TripLocationBus.rideShareActive = true
            if (closedNow && bookingId == currentBookingId || socket == null) applyPick(list, receivedAt)
        }
    }

    /** Несущая закрылась или срок её вышел: свежий список → следующая или гаснем. */
    private fun resolve(anchor: Int) {
        if (destroyed) return
        if (!sessionIsOwners()) { shutdown(); return }
        socket?.close()   // старый канал больше ни кадра (бронь закрыта / срок вышел)
        socket = null
        TripLocationBus.peer = null
        retryTask?.let(watchdog::removeCallbacks)
        val seq = ++resolveSeq
        scope.launch {
            val result = ApiClient.getDriverBookings(expectedGeneration = owner)
            if (!stillMine(seq)) return@launch
            result.onSuccess { list ->
                resolveAttempt = 0
                currentBookingId = anchor
                applyPick(list, SystemClock.elapsedRealtime())
            }.onFailure { e ->
                // Отказ сервера (401/403/…) — гаснем; нет сети — повтор, не дольше ~10 минут.
                if (e is ApiException) shutdown() else retryResolve(anchor)
            }
        }
    }

    private fun applyPick(list: List<DriverBookingDto>, receivedAt: Long) {
        val r = ride ?: run { shutdown(); return }
        val pick = pickRideCarrier(list, currentBookingId, closed)
        // Рейс — только из строки сервера и только тот же (P1-4): иначе точку получили бы пассажиры чужого рейса.
        val next = pick.carrier.takeIf { pick.rideId == r.rideId } ?: run { shutdown(); return }
        ride = r.copy(bookings = pick.rideBookings)
        carrierUntil = pick.carrierTtlSec?.let { receivedAt + it * 1000L } ?: 0L
        watchdog.removeCallbacks(carrierExpiry)
        if (carrierUntil > 0) watchdog.postDelayed(carrierExpiry, (carrierUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        connect(next)
        persist()
    }

    private fun retryResolve(anchor: Int) {
        if (++resolveAttempt > MAX_RESOLVE_ATTEMPTS) { shutdown(); return }
        val task = Runnable { resolve(anchor) }
        retryTask = task
        watchdog.postDelayed(task, minOf(30_000L, 1_000L shl minOf(resolveAttempt, 5)))
    }

    /** Несущая в начале маршрута дошла до срока «Не пришёл» — дальше её канал не годится (P1-3). */
    private fun onCarrierExpired() {
        if (destroyed || carrierUntil == 0L || SystemClock.elapsedRealtime() < carrierUntil) return
        carrierUntil = 0L
        resolve(anchor = currentBookingId)
    }

    /** Ответ на запрос ещё наш: служба жива, это последний запрос, вход тот же. Вход сменился — гаснем. */
    private fun stillMine(seq: Int): Boolean {
        if (destroyed || seq != resolveSeq) return false
        if (!sessionIsOwners()) { shutdown(); return false }
        return true
    }

    private fun sessionIsOwners() = ApiClient.isCurrentSession(owner) && (ownerUser == 0 || ApiClient.myUserId() == ownerUser)

    private fun pastDeadline(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        ride?.let { return it.expired(now, wall) }
        return untilElapsed <= 0L || now >= untilElapsed || wall >= untilWall || untilElapsed - now > MAX_LIFETIME_MS
    }

    /** Сторож-таймаут: если вызывающий (YuldashApp) крашнется и не позовёт stop(), сервис бы висел и вечно лил GPS. */
    private fun armWatchdog() {
        watchdog.removeCallbacks(autoStop)
        val until = ride?.untilElapsedMs ?: untilElapsed
        watchdog.postDelayed(autoStop, (until - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    private fun leaveRide() {
        ride = null
        carrierUntil = 0L
        TripLocationBus.rideShareActive = false
    }

    private fun foreground() {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
    }

    private fun persist() {
        val r = ride
        prefs().edit().putInt(KEY_BOOKING, currentBookingId).putString(KEY_LANG, currentLang.name)
            .putBoolean(KEY_DRIVER, isDriver).putInt(KEY_USER, ownerUser)
            .putLong(KEY_UNTIL_ELAPSED, r?.untilElapsedMs ?: untilElapsed).putLong(KEY_UNTIL_WALL, r?.untilWallMs ?: untilWall)
            .putInt(KEY_RIDE, r?.rideId ?: 0).putString(KEY_RIDE_BOOKINGS, r?.bookings?.joinToString(",").orEmpty())
            .apply()
    }

    private fun readRide(p: SharedPreferences): DriverRideMode? {
        val rideId = p.getInt(KEY_RIDE, 0).takeIf { it > 0 } ?: return null
        val bookings = p.getString(KEY_RIDE_BOOKINGS, "").orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        return DriverRideMode(rideId, bookings, p.getLong(KEY_UNTIL_ELAPSED, 0L), p.getLong(KEY_UNTIL_WALL, 0L))
    }

    private fun startLocationUpdates() {
        if (destroyed || !hasLocation()) return
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = manager
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                // Гео отозвали посреди поездки — точку не шлём и сервис не держим (тот же гейт, что на старте).
                if (!hasLocation()) { shutdown(); return }
                // Монотонные часы: перевод времени на телефоне назад не замораживает отправку на час.
                val now = SystemClock.elapsedRealtime()
                if (pastDeadline()) { shutdown(); return }   // потолок жизни — на каждой точке, а не одним таймером (Doze)
                if (carrierUntil > 0 && now >= carrierUntil) { onCarrierExpired(); return }
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
            // Уведомление в шторке — тоже голос Юлдаша: на «ты», как везде. Ведёшь рейс — честно: видят все его пассажиры.
            .setContentText(if (ride != null) appTextFor(currentLang, "Показываем машину пассажирам рейса", "Машинаңды рейс пассажирҙарына күрһәтәбеҙ")
                else appTextFor(currentLang, "Показываем твою позицию попутчику", "Урыныңды юлдашыңа күрһәтәбеҙ"))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun shutdown() {
        stopTracking()
        stopSelf()
    }

    private fun stopTracking() {
        socketGeneration++
        resolveSeq++   // запрос, который ещё в пути, ничего не оживит (P1-5)
        watchdog.removeCallbacks(autoStop)
        watchdog.removeCallbacks(carrierExpiry)
        retryTask?.let(watchdog::removeCallbacks)
        retryTask = null
        pendingClose = 0
        listener?.let { runCatching { lm?.removeUpdates(it) } }
        listener = null
        pacer.reset()
        lm = null
        socket?.close()
        socket = null
        currentBookingId = -1
        closed.clear()
        leaveRide()
        TripLocationBus.peer = null
        TripLocationBus.bookingId = null
        // H2: поездка завершена штатно (stopService/stopSelf зовёт onDestroy) → стираем сохранённую бронь,
        // чтобы будущее воскрешение сервиса НЕ подняло уже законченную поездку (приватность + не тот стрим).
        // Если процесс убили без onDestroy (OOM) — prefs остаётся, и это правильно: поездка ещё шла.
        clearRestoration(this)
    }

    override fun onDestroy() {
        destroyed = true
        scope.cancel()
        stopTracking()
        if (TripLocationBus.driverActionListener === actions) TripLocationBus.driverActionListener = null
        if (instance?.get() === this) instance = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BOOKING = "booking_id"
        const val EXTRA_LANG = "lang"
        const val EXTRA_DRIVER = "is_driver"   // я водитель в этой брони (нет — значит пассажир)
        // H2: последняя активная бронь для воскрешения сервиса после смерти процесса (intent==null).
        private const val PREFS = "trip_location_svc"
        private const val KEY_BOOKING = "last_booking"
        private const val KEY_LANG = "last_lang"
        private const val KEY_DRIVER = "last_driver"
        private const val KEY_USER = "last_user"               // чей это рейс: воскрешение под другим входом — стоп
        private const val KEY_UNTIL_ELAPSED = "until_elapsed"  // потолок жизни (elapsedRealtime) — воскрешение не продлевает
        private const val KEY_UNTIL_WALL = "until_wall"        // он же по стенным часам
        private const val KEY_RIDE = "ride_id"                 // режим рейса (Д2): рейс и его брони
        private const val KEY_RIDE_BOOKINGS = "ride_bookings"
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
        private const val MAX_LIFETIME_MS = DRIVER_RIDE_MAX_MS   // 6ч — заведомо дольше любой реальной поездки; страховка от зависшего сервиса
        private const val MAX_RESOLVE_ATTEMPTS = 20   // нет сети при поиске следующей брони — ≈ 9 минут повторов, потом гаснем

        /** Живая служба этого процесса — для команд, которые не должны её запускать (P2-2). */
        @Volatile private var instance: WeakReference<TripLocationService>? = null

        internal fun start(ctx: Context, bookingId: Int, lang: AppLanguage, isDriver: Boolean) {
            val i = Intent(ctx, TripLocationService::class.java)
                .putExtra(EXTRA_BOOKING, bookingId)
                .putExtra(EXTRA_LANG, lang.name)
                .putExtra(EXTRA_DRIVER, isDriver)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        /** Жёсткий стоп: выход, удаление аккаунта, сессия истекла. Сохранённую бронь стираем сами (P1-6). */
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TripLocationService::class.java))
            clearRestoration(ctx)
        }

        /**
         * Экран больше не держит поездку (ушли с неё, открыли завершённую). Пассажир — стоп, как раньше.
         * Водитель в режиме рейса — служба остаётся: GPS принадлежит рейсу, а не экрану (Д2).
         */
        internal fun release(ctx: Context) {
            if (instance?.get()?.keepsRide() != true) ctx.stopService(Intent(ctx, TripLocationService::class.java))
        }

        internal fun clearRestoration(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }

    private fun keepsRide() = !destroyed && isDriver && ride != null && sessionIsOwners()
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
