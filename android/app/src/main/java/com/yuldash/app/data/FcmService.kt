package com.yuldash.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.yuldash.app.AppPrefs
import com.yuldash.app.MainActivity
import com.yuldash.app.R
import com.yuldash.app.TaxiOfferNotifier

/**
 * Приём push-уведомлений (FCM). Работает после добавления app/google-services.json.
 * onNewToken → регистрируем токен устройства на сервере (если вошли).
 * onMessageReceived → показываем уведомление (новое сообщение / бронь / SOS).
 */
class FcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        ApiClient.fireRegisterPushToken(token)
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        // Тумблер «Уведомления» (Настройки) выключен → не показываем пуш (клиентское заглушение).
        if (!AppPrefs.notifications(this)) return
        // Оффер такси (B7a-2): data-only пуш от matcher'а → полноэкранная карточка «Новый заказ 🚕»
        // (канал «Заказы такси», важность MAX, звук+вибро, full-screen intent → кабинет водителя).
        if (msg.data["type"] == "instant_offer") {
            TaxiOfferNotifier.show(
                this,
                orderId = msg.data["order_id"]?.toIntOrNull() ?: 0,
                fromText = msg.data["from"] ?: "",
                toText = msg.data["to"] ?: "",
                priceRub = msg.data["price"]?.toIntOrNull() ?: 0,
                lang = AppPrefs.language(this),
                ttlSec = msg.data["ttl_sec"]?.toIntOrNull() ?: 20,
            )
            return
        }
        val n = msg.notification
        val title = n?.title ?: msg.data["title"] ?: "Юлдаш"
        val body = n?.body ?: msg.data["body"] ?: ""
        // Ход такси-заказа (B9b-2): тап по уведомлению открывает экран заказа пассажира
        // (extra ловит MainActivity.handleNavIntent → NavSignals.openInstantOrder).
        showNotification(title, body, openInstantOrder = msg.data["type"] == "instant_status")
    }

    private fun showNotification(title: String, body: String, openInstantOrder: Boolean = false) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val silent = !AppPrefs.sounds(this)   // тумблер «Звуки» выключен → беззвучно
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                // Имя канала видно в системных настройках — двуязычно (BA · RU), сервис не Composable.
                NotificationChannel(CHANNEL_ID, "Юлдаш · Хәбәрҙәр · Уведомления", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (openInstantOrder) putExtra(TaxiOfferNotifier.EXTRA_OPEN_ORDER, true)
        }
        // requestCode различает интенты с нав-экстрой и без — иначе FLAG_UPDATE_CURRENT
        // дописал бы extra в общий PendingIntent и «заразил» обычные уведомления.
        val pi = PendingIntent.getActivity(
            this, if (openInstantOrder) 1 else 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setSilent(silent)
            .setContentIntent(pi)
            .build()
        mgr.notify(System.currentTimeMillis().toInt(), notif)
    }

    companion object {
        const val CHANNEL_ID = "yuldash_default"
    }
}
