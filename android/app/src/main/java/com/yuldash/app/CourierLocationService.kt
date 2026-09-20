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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yuldash.app.data.InstantLocationSocket

/**
 * Foreground-сервис живой позиции КУРЬЕРА. Пока у курьера есть посылки в работе, шлёт его GPS
 * в канал каждой активной доставки — отправитель видит на карте, где сейчас его посылка.
 *
 * Зачем отдельный сервис. Раньше отправка позиции жила внутри экрана: курьер сворачивал
 * приложение или гасил экран — Compose засыпал вместе с процессом, сокеты закрывались, и у
 * отправителя точка ЗАМИРАЛА посреди доставки. При этом статус в приложении бодро показывал
 * «в пути». У поездок эту же задачу давно решает [TripLocationService] — здесь тот же приём.
 *
 * Приватность (CLAUDE.md §8): канал открыт ТОЛЬКО пока посылка реально в работе. Список активных
 * доставок задаёт вызывающий; закончились — сервис глушится и координаты больше никуда не идут.
 * Сервер координаты не хранит, только ретранслирует отправителю этой посылки.
 */
class CourierLocationService : Service() {
    private var sockets: List<InstantLocationSocket> = emptyList()
    private var currentIds: List<Int> = emptyList()   // каким доставкам сейчас служим (для смены набора)
    private var socketGeneration = 0L
    private var lm: LocationManager? = null
    private var listener: LocationListener? = null
    private var lastSent = 0L
    private var prevLat = 0.0
    private var prevLng = 0.0
    private var currentLang = AppLanguage.Ru
    // Сторож: если экран курьера умер, не позвав stop(), сервис не должен вечно лить GPS.
    private val watchdog = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ids: List<Int>
        if (intent != null) {
            ids = intent.getIntArrayExtra(EXTRA_PARCELS)?.toList().orEmpty()
            intent.getStringExtra(EXTRA_LANG)?.let {
                currentLang = runCatching { AppLanguage.valueOf(it) }.getOrDefault(AppLanguage.Ru)
            }
            // Запоминаем набор доставок: если Android убьёт процесс, при воскрешении (intent == null)
            // продолжим ИМЕННО эти доставки, а не погасим трекинг посреди дороги.
            if (ids.isNotEmpty()) {
                prefs.edit()
                    .putString(KEY_PARCELS, ids.joinToString(","))
                    .putString(KEY_LANG, currentLang.name)
                    .apply()
            }
        } else {
            ids = prefs.getString(KEY_PARCELS, null)
                ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
            prefs.getString(KEY_LANG, null)?.let {
                currentLang = runCatching { AppLanguage.valueOf(it) }.getOrDefault(AppLanguage.Ru)
            }
        }
        // Нет активных доставок ИЛИ нет точной геолокации → не держим бесполезный сервис и не
        // показываем нотификацию «показываем вашу позицию», которая в этом случае была бы враньём.
        if (ids.isEmpty() ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf(); return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(ids.size),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        // Набор доставок поменялся (одну вручил, взял другую) → пересобираем каналы. Иначе GPS
        // продолжил бы литься в канал уже вручённой посылки — это и приватность, и не тот адресат.
        if (currentIds != ids) {
            sockets.forEach { it.close() }
            currentIds = ids
            val generation = ++socketGeneration
            sockets = ids.map { id ->
                InstantLocationSocket.forParcel(id, onPeer = { }, onTerminated = {
                    watchdog.post {
                        if (socketGeneration == generation) removeFinishedParcel(id)
                    }
                })
            }
            sockets.forEach { it.connect() }
        }
        if (listener == null) startLocationUpdates()
        watchdog.removeCallbacks(autoStop)
        watchdog.postDelayed(autoStop, MAX_LIFETIME_MS)
        return START_STICKY
    }

    private fun removeFinishedParcel(id: Int) {
        val index = currentIds.indexOf(id)
        if (index < 0) return
        sockets[index].close()
        sockets = sockets.filterIndexed { i, _ -> i != index }
        currentIds = currentIds.filterIndexed { i, _ -> i != index }
        if (currentIds.isEmpty()) {
            stopTracking()
            stopSelf()
        } else {
            // Остальные доставки продолжаются; завершённую нельзя восстановить после смерти процесса.
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PARCELS, currentIds.joinToString(",")).apply()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(currentIds.size))
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = manager
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val now = System.currentTimeMillis()
                if (now - lastSent < MIN_INTERVAL_MS) return   // не чаще раза в ~10с (батарея/трафик)
                lastSent = now
                // Направление: у части устройств bearing приходит пустым на малой скорости —
                // тогда считаем по смещению, иначе стрелка курьера смотрела бы всегда на север.
                val bearing = when {
                    loc.hasBearing() -> loc.bearing.toDouble()
                    prevLat != 0.0 || prevLng != 0.0 -> {
                        val dLat = loc.latitude - prevLat
                        val dLng = loc.longitude - prevLng
                        if (kotlin.math.abs(dLat) + kotlin.math.abs(dLng) < 0.00005) null
                        else (Math.toDegrees(
                            kotlin.math.atan2(dLng * kotlin.math.cos(Math.toRadians(loc.latitude)), dLat)
                        ) + 360) % 360
                    }
                    else -> null
                }
                prevLat = loc.latitude
                prevLng = loc.longitude
                sockets.forEach { it.sendLoc(loc.latitude, loc.longitude, bearing) }
            }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }
        listener = l
        // GPS основной; сеть — только если GPS выключен (два провайдера разом зря жгут батарею).
        runCatching {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4000L, 15f, l)
            } else if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 4000L, 15f, l)
            }
        }
    }

    private fun buildNotification(count: Int): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        appTextFor(currentLang, "Доставка", "Илтеү"),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val body = if (count > 1) {
            appTextFor(currentLang, "Отправители видят, где посылки", "Ебәреүселәр бандеролдәрҙең ҡайҙалығын күрә")
        } else {
            appTextFor(currentLang, "Отправитель видит, где посылка", "Ебәреүсе бандеролдең ҡайҙалығын күрә")
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(appTextFor(currentLang, "Юлдаш — доставка идёт", "Юлдаш — илтеү бара"))
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun stopTracking() {
        socketGeneration++
        watchdog.removeCallbacks(autoStop)
        listener?.let { runCatching { lm?.removeUpdates(it) } }
        listener = null
        lm = null
        sockets.forEach { it.close() }
        sockets = emptyList()
        currentIds = emptyList()
        // Доставка закрыта штатно → стираем сохранённый набор, чтобы будущее воскрешение сервиса
        // не подняло уже вручённые посылки. Убили процесс без onDestroy — запись остаётся, и это
        // правильно: доставка тогда ещё шла.
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PARCELS).remove(KEY_LANG).apply()
    }

    override fun onDestroy() {
        stopTracking()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PARCELS = "parcel_ids"
        const val EXTRA_LANG = "lang"
        private const val PREFS = "courier_location_svc"
        private const val KEY_PARCELS = "last_parcels"
        private const val KEY_LANG = "last_lang"
        private const val CHANNEL = "courier_location"
        // У каждого фонового GPS-сервиса СВОЙ номер (волна 129). Было 4712 — как у линии
        // такси: у Марата, который и таксует, и возит посылки по пути, плашка «Ты на линии 🚕»
        // подменялась на «доставка идёт» и назад не возвращалась. Дальше он не видел ни линию,
        // ни предупреждение «сервер тебя не видит — заказы не придут», ради которого плашку
        // и делали: стоял час и не понимал, почему тишина.
        // Занятые номера: 4711 — поездка, 4712 — линия такси, 4713 — доставка.
        private const val NOTIF_ID = 4713
        private const val MIN_INTERVAL_MS = 10_000L
        private const val MAX_LIFETIME_MS = 12 * 3600_000L   // дольше любой реальной смены курьера

        /** Начать (или обновить) трансляцию позиции по активным доставкам. Пустой список = стоп. */
        internal fun start(ctx: Context, parcelIds: List<Int>, lang: AppLanguage) {
            if (parcelIds.isEmpty()) { stop(ctx); return }
            val i = Intent(ctx, CourierLocationService::class.java)
                .putExtra(EXTRA_PARCELS, parcelIds.toIntArray())
                .putExtra(EXTRA_LANG, lang.name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CourierLocationService::class.java))
        }
    }
}
