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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground-сервис «Я на линии» (такси, B7a-1). ОБРАЗЕЦ — TripLocationService (та же схема
 * старт/стоп, канал, GPS-слушатель). Пока водитель на линии, ИЗ ФОНА (экран погашен,
 * приложение свёрнуто):
 *  • presence-heartbeat (~15с) → водитель виден matcher'у, смена (§8) честно тикает;
 *  • опрос входящего оффера (~5с) → полноэкранное уведомление «Новый заказ 🚕»
 *    (TaxiOfferNotifier) — страховка к пушу.
 * Без сервиса Android замораживает корутины свёрнутого приложения через ~1 мин, presence
 * протухает (TTL) — водитель «исчезал» с линии, хотя тумблер включён.
 *
 * Останов: тумблер выключен (ProfileScreen зовёт stop) ИЛИ сервер отверг heartbeat
 * (401 выход / 403 долг·8ч·пауза качества / 409 «включи Я на линии») — глушим сами.
 * Приватность: координаты не логируем, сервер держит их только в Redis с TTL.
 */
class TaxiLineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lm: LocationManager? = null
    private var listener: LocationListener? = null
    @Volatile private var lastLat: Double? = null
    @Volatile private var lastLng: Double? = null
    private var currentLang = AppLanguage.Ru
    private var loopsStarted = false
    @Volatile private var lastNotifiedOrderId = -1   // не дребезжим: одно уведомление на оффер

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_LANG)?.let {
            currentLang = runCatching { AppLanguage.valueOf(it) }.getOrDefault(AppLanguage.Ru)
        }
        // Не вошёл или нет ТОЧНОЙ геолокации → на линии стоять нечем (presence без координат мёртв).
        // Почему именно FINE, а не «примерное» (аудит такси, P0-1): по presence matcher выбирает
        // ближайшего водителя, считает подачу и рисует машину пассажиру на карте. Примерное
        // местоположение — это километры погрешности: заказы уходили бы мимо, ETA врало бы,
        // а машина на карте стояла бы не там. Честнее один раз попросить точную, чем тихо
        // работать «как получится». Поэтому ProfileScreen НЕ включает тумблер без FINE — здесь
        // это только страховка (разрешение могли отозвать в настройках, пока сервис жил).
        if (!ApiClient.isLoggedIn() ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            stopSelf(); return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        // Стартуем не с «пустых» координат: последняя известная позиция приложения,
        // чтобы первый heartbeat ушёл сразу, не дожидаясь свежего GPS-фикса.
        if (lastLat == null) { lastLat = LocationPrefs.lastLat; lastLng = LocationPrefs.lastLng }
        if (listener == null) startLocationUpdates()
        if (!loopsStarted) { loopsStarted = true; startLoops() }
        return START_STICKY
    }

    /** GPS-слушатель (паттерн TripLocationService): GPS основной, NETWORK — фолбэк. */
    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = manager
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                lastLat = loc.latitude; lastLng = loc.longitude
            }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }
        listener = l
        runCatching {
            if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10_000L, 25f, l)
            } else if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10_000L, 25f, l)
            }
        }
    }

    private fun startLoops() {
        // ① Presence ~15с. Сервер отверг (401/403/409) → линия закончилась (выход, долг,
        // 8-часовой лимит, пауза качества, «Я на линии» снят) → сервис больше не нужен.
        scope.launch {
            while (isActive) {
                val la = lastLat; val lo = lastLng
                if (la != null && lo != null) {
                    val res = ApiClient.instantPresence(la, lo)
                    val status = (res.exceptionOrNull() as? ApiException)?.status
                    if (status == 401 || status == 403 || status == 409) { stopSelf(); return@launch }
                    // Сетевая ошибка — не страшно: следующий тик через 15с, TTL presence ~45с.
                }
                delay(PRESENCE_INTERVAL_MS)
            }
        }
        // ② Опрос оффера ~5с — страховка к push (B7a-2): нашли новый → полноэкранное уведомление.
        scope.launch {
            while (isActive) {
                ApiClient.getDriverOffer().onSuccess { o ->
                    if (o != null && o.status == "offered" && o.id != lastNotifiedOrderId) {
                        lastNotifiedOrderId = o.id
                        TaxiOfferNotifier.show(
                            this@TaxiLineService, o.id, o.fromText, o.toText, o.priceEstimate, currentLang,
                        )
                    }
                }
                delay(OFFER_POLL_MS)
            }
        }
        // ③ Сторож (паттерн TripLocationService): если ProfileScreen крашнулся и не позвал stop(),
        // не висим вечно. Смена ограничена 8ч на сервере (presence → 403), это второй пояс.
        scope.launch { delay(MAX_LIFETIME_MS); stopSelf() }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL,
                        appTextFor(currentLang, "На линии (такси)", "Линияла (такси)"),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(appTextFor(currentLang, "Юлдаш · Ты на линии 🚕", "Юлдаш · Һин линияла 🚕"))
            .setContentText(appTextFor(
                currentLang,
                "Ждём заказы рядом. Сойдёшь с линии — уведомление исчезнет.",
                "Яҡында заказдар көтәбеҙ. Линиянан төшһәң — белдереү юғала.",
            ))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        listener?.let { runCatching { lm?.removeUpdates(it) } }
        listener = null
        scope.cancel()   // глушим оба цикла (presence + опрос оффера)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_LANG = "lang"
        private const val CHANNEL = "taxi_line"
        private const val NOTIF_ID = 4712
        private const val PRESENCE_INTERVAL_MS = 15_000L
        private const val OFFER_POLL_MS = 5_000L
        private const val MAX_LIFETIME_MS = 12 * 3600_000L   // страховка; реальный предел — 8ч смены на сервере

        internal fun start(ctx: Context, lang: AppLanguage) {
            val i = Intent(ctx, TaxiLineService::class.java).putExtra(EXTRA_LANG, lang.name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TaxiLineService::class.java))
        }
    }
}
