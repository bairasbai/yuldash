package com.yuldash.app.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.yuldash.app.MainActivity
import com.yuldash.app.R

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
        val n = msg.notification
        val title = n?.title ?: msg.data["title"] ?: "Юлдаш"
        val body = n?.body ?: msg.data["body"] ?: ""
        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Уведомления Юлдаш", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build()
        mgr.notify(System.currentTimeMillis().toInt(), notif)
    }

    companion object {
        const val CHANNEL_ID = "yuldash_default"
    }
}
