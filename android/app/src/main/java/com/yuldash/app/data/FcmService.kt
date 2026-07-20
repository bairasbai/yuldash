package com.yuldash.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.yuldash.app.AppPrefs
import com.yuldash.app.MainActivity
import com.yuldash.app.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Приём push-уведомлений (FCM). Работает после добавления app/google-services.json.
 * onNewToken → регистрируем токен устройства на сервере (если вошли).
 * onMessageReceived → показываем уведомление на нужном канале + deep-link по data (type/id).
 */
class FcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        ApiClient.fireRegisterPushToken(token)
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        // Тумблер «Уведомления» (Настройки) выключен → не показываем (клиентское заглушение).
        if (!AppPrefs.notifications(this)) return
        val n = msg.notification
        val title = n?.title ?: msg.data["title"] ?: "Юлдаш"
        val body = n?.body ?: msg.data["body"] ?: ""
        showNotification(title, body, msg.data["type"], msg.data["id"])
    }

    private fun showNotification(title: String, body: String, type: String?, id: String?) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        val silent = !AppPrefs.sounds(this)   // тумблер «Звуки» выключен → беззвучно
        // Отдельные каналы: чат отдельно от поездок/прочего → пользователь настраивает/глушит раздельно.
        // Имена двуязычные (§3): видны в системных настройках уведомлений и башкироязычному тоже.
        val channelId = if (type == "chat") CHANNEL_CHAT else CHANNEL_DEFAULT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_CHAT, "Сообщения · Хәбәрҙәр", NotificationManager.IMPORTANCE_HIGH)
            )
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_DEFAULT, "Поездки · Сәфәрҙәр", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        // Deep-link: кладём type/id в Intent → MainActivity сможет открыть нужный экран (чат/поездку),
        // а не просто главный. (Роутинг-потребитель в MainActivity — следующий шаг, extras уже несём.)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (!type.isNullOrBlank()) putExtra(EXTRA_PUSH_TYPE, type)
            if (!id.isNullOrBlank()) putExtra(EXTRA_PUSH_ID, id)
        }
        val pi = PendingIntent.getActivity(
            this, (id?.hashCode() ?: 0), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, channelId)
            // Монохромный силуэт (не launcher-иконка — иначе в статус-баре белый квадрат/пятно).
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setColor(BRAND_GREEN)                 // брендовый акцент (тонировка иконки в статус-баре)
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
        const val CHANNEL_DEFAULT = "yuldash_default"
        const val CHANNEL_CHAT = "yuldash_chat"
        const val EXTRA_PUSH_TYPE = "push_type"
        const val EXTRA_PUSH_ID = "push_id"
        private const val BRAND_GREEN = 0xFF0B6B3A.toInt()
    }
}
