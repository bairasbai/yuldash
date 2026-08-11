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
import com.yuldash.app.appTextFor
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
        // Переписка по посылке — это тоже чат: без этой ветки сообщение курьера звенело бы в канале
        // «Поездки» у того, кто его специально приглушил, а в «Сообщениях» не появлялось вовсе.
        // «order_chat» — переписка по заказу такси. Раньше она приходила под общим «chat»
        // (тем же словом, что и чат попутки, но с id заказа): в канал попадала верно, а вот
        // открыть по ней было нечего — по такому id бронь не ищется (аудит 2026-08-06).
        val channelId = if (type in CHAT_TYPES) CHANNEL_CHAT else CHANNEL_DEFAULT
        ensureChannels(this)
        // Ход такси-заказа (B9b-2): тап открывает экран заказа пассажира (extra ловит MainActivity).
        val openInstantOrder = type == "instant_status"
        // Ход посылки: «курьер найден / забрал / в пути / вручил / возврат». Раньше эти пуши
        // приходили без типа — тап вёл просто в приложение, а отправитель узнавал статус, только
        // если сам догадывался переключить вкладку. Теперь тап открывает «Посылки».
        val openParcels = type != null && type.startsWith("parcel")
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (openInstantOrder) putExtra(TaxiOfferNotifier.EXTRA_OPEN_ORDER, true)
            if (openParcels) putExtra(EXTRA_OPEN_PARCELS, true)
            // Общий deep-link: тип+id → MainActivity открывает нужный экран (чат брони, чат
            // такси-заказа). До 2026-08-06 эти extras никто не читал: человек получал
            // «Марат: подъезжаю», жал — и попадал на карту, а переписку искал руками.
            if (!type.isNullOrBlank()) putExtra(EXTRA_PUSH_TYPE, type)
            if (!refId.isNullOrBlank()) putExtra(EXTRA_PUSH_ID, refId)
        }
        // Ключ назначения = ТИП + id. Раньше брали только id — чат брони №5 и статус поездки №5
        // получали один requestCode, и FLAG_UPDATE_CURRENT подменял extras: тап по чату открывал
        // экран поездки. Такси = 1 (как было, чтобы не плодить лишние PendingIntent).
        val key = "${type.orEmpty()}|${refId.orEmpty()}"
        val reqCode = if (openInstantOrder) 1 else key.hashCode()
        val pi = PendingIntent.getActivity(
            this, reqCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(title)
            .setContentText(body)
            // Длинный текст не обрезается до одной строки (башкирский часто длиннее русского).
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setSilent(silent)
            .setContentIntent(pi)
        // Переписка — личное, и телефон часто лежит на столе или в общей семье. Уведомление
        // чата помечаем приватным и даём системе БЕЗОПАСНУЮ версию: на заблокированном экране
        // видно «Юлдаш · Новое сообщение», а имя собеседника и текст открываются после разблокировки.
        // Остальные пуши (машина подъезжает, бронь подтверждена) остаются как были: они полезны
        // именно с экрана блокировки и ничего личного не раскрывают (аудит 2026-08-08, волна 14).
        if (channelId == CHANNEL_CHAT) {
            val lang = AppPrefs.language(this)
            val safe = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .setContentTitle("Юлдаш")            // имя приложения — одно на оба языка
                .setContentText(appTextFor(lang, "Новое сообщение", "Яңы хәбәр"))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(safe)
        }
        val notif = builder.build()
        // id тоже по ключу: новое сообщение того же чата ЗАМЕНЯЕТ прежнее, а не сыплет столбиком
        // (было System.currentTimeMillis() → 20 сообщений = 20 уведомлений). Без id — по времени.
        val notifId = if (refId.isNullOrBlank()) (System.currentTimeMillis().toInt() and 0x7FFFFFFF) else key.hashCode()
        mgr.notify(notifId, notif)
    }

    companion object {
        const val CHANNEL_DEFAULT = "yuldash_default"   // поездки/брони/прочее (= прежний yuldash_default → канал не осиротеет)
        const val CHANNEL_CHAT = "yuldash_chat"         // сообщения чата — отдельный канал, мьютится независимо
        /** Типы пушей-сообщений: чат попутки, чат такси-заказа, чат по посылке. */
        val CHAT_TYPES = setOf("chat", "order_chat", "parcel_chat")
        const val EXTRA_PUSH_TYPE = "push_type"
        const val EXTRA_PUSH_ID = "push_id"
        /** Тап по пушу о посылке → открыть экран «Посылки» (ловит MainActivity). */
        const val EXTRA_OPEN_PARCELS = "yuldash_open_parcels"

        /**
         * Создать каналы уведомлений. Идемпотентно (повторный вызов только обновляет имя).
         * Зовём из [YuldashApplication.onCreate] — чтобы каналы были видны в системных настройках
         * СРАЗУ после установки, а не только после первого пуша (иначе человек открывает
         * «Уведомления» и видит пустой список — нечего настраивать и нечего разрешать).
         * Имена двуязычные (BA · RU): системный экран — не Composable, appText туда не дотянуть.
         */
        fun ensureChannels(ctx: android.content.Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
            runCatching {
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_DEFAULT, "Юлдаш · Сәфәрҙәр · Поездки", NotificationManager.IMPORTANCE_HIGH)
                )
                mgr.createNotificationChannel(
                    NotificationChannel(CHANNEL_CHAT, "Хәбәрҙәр · Сообщения", NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }
    }
}
