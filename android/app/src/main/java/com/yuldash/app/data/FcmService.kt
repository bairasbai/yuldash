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
 * onMessageReceived → показываем уведомление на нужном канале (чат отдельно от поездок),
 * монохромной иконкой статус-бара, с deep-link extras (type/id) для перехода на нужный экран.
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
        showNotification(title, body, msg.data["type"], msg.data["id"])
    }

    private fun showNotification(title: String, body: String, type: String?, refId: String?) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val silent = !AppPrefs.sounds(this)   // тумблер «Звуки» выключен → беззвучно
        // Раздельные каналы: чат отдельно от поездок/прочего → пользователь глушит/настраивает раздельно.
        // Имена двуязычные (BA · RU) — видны в системных настройках уведомлений, сервис не Composable.
        val channelId = if (type == "chat") CHANNEL_CHAT else CHANNEL_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_DEFAULT, "Юлдаш · Сәфәрҙәр · Поездки", NotificationManager.IMPORTANCE_HIGH)
            )
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_CHAT, "Хәбәрҙәр · Сообщения", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        // Ход такси-заказа (B9b-2): тап открывает экран заказа пассажира (extra ловит MainActivity).
        val openInstantOrder = type == "instant_status"
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (openInstantOrder) putExtra(TaxiOfferNotifier.EXTRA_OPEN_ORDER, true)
            // Общий deep-link для остальных пушей: тип+id → MainActivity сможет открыть нужный экран
            // (чат/бронь). Потребитель роутинга в MainActivity — следующий шаг; extras уже несём.
            if (!type.isNullOrBlank()) putExtra(EXTRA_PUSH_TYPE, type)
            if (!refId.isNullOrBlank()) putExtra(EXTRA_PUSH_ID, refId)
        }
        // requestCode уникален по назначению → FLAG_UPDATE_CURRENT не «заражает» разные уведомления
        // общими extra (иначе тап по одному открыл бы чужой экран). Такси = 1 (как было).
        val reqCode = if (openInstantOrder) 1 else (refId?.hashCode() ?: 0)
        val pi = PendingIntent.getActivity(
            this, reqCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_notification)
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
        const val CHANNEL_DEFAULT = "yuldash_default"   // поездки/брони/прочее (= прежний yuldash_default → канал не осиротеет)
        const val CHANNEL_CHAT = "yuldash_chat"         // сообщения чата — отдельный канал, мьютится независимо
        const val EXTRA_PUSH_TYPE = "push_type"
        const val EXTRA_PUSH_ID = "push_id"
    }
}
