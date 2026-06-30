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
import com.yuldash.app.data.LocationSocket
import com.yuldash.app.data.TripLocationBus

/**
 * Foreground-сервис live-позиции. Пока активна поездка: шлёт мой GPS в WS-канал брони
 * (раз в ~7с) и принимает позицию ДРУГОГО участника (кладёт в TripLocationBus → карта рисует
 * маркер). Foreground = работает с погасшим экраном (Android не душит фоновую отправку).
 * Приватность: стрим только во время активной поездки; запускаем/глушим из YuldashApp по
 * activeBookingId. Координаты на сервере НЕ хранятся (только ретрансляция).
 *
 * НЕ проверено вживую (нужны 2 телефона в одной брони) — собрано, помечено в docs/tasks.md.
 */
class TripLocationService : Service() {
    private var socket: LocationSocket? = null
    private var lm: LocationManager? = null
    private var listener: LocationListener? = null
    private var lastSent = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val bookingId = intent?.getIntExtra(EXTRA_BOOKING, -1) ?: -1
        if (bookingId <= 0) { stopSelf(); return START_NOT_STICKY }
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        )
        TripLocationBus.bookingId = bookingId
        if (socket == null) {
            socket = LocationSocket(bookingId, onPeer = { TripLocationBus.peer = it }).also { it.connect() }
            startLocationUpdates()
        }
        return START_STICKY
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        val manager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm = manager
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                val now = System.currentTimeMillis()
                if (now - lastSent < MIN_INTERVAL_MS) return   // не чаще ~раза в 7с (батарея/трафик)
                lastSent = now
                socket?.sendLoc(loc.latitude, loc.longitude, if (loc.hasBearing()) loc.bearing.toDouble() else null)
            }
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        }
        listener = l
        runCatching {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 10f, l)
            manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 10f, l)
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Поездка", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Юлдаш — поездка идёт")
            .setContentText("Показываем вашу позицию попутчику")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        listener?.let { runCatching { lm?.removeUpdates(it) } }
        socket?.close()
        socket = null
        TripLocationBus.peer = null
        TripLocationBus.bookingId = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_BOOKING = "booking_id"
        private const val CHANNEL = "trip_location"
        private const val NOTIF_ID = 4711
        private const val MIN_INTERVAL_MS = 7000L

        fun start(ctx: Context, bookingId: Int) {
            val i = Intent(ctx, TripLocationService::class.java).putExtra(EXTRA_BOOKING, bookingId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TripLocationService::class.java))
        }
    }
}
