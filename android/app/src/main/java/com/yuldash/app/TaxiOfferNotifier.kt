package com.yuldash.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat

/**
 * Полноэкранное уведомление «Новый заказ 🚕» (такси, B7a). Общая точка для ДВУХ источников:
 *  • push от matcher'а (FcmService, data type=instant_offer) — мгновенно;
 *  • фоновый опрос оффера из TaxiLineService (~5с) — страховка, если пуш не дошёл.
 * Канал «Заказы такси» — важность MAX: звук + вибрация + full-screen intent, чтобы карточка
 * оффера всплыла даже на погасшем экране (как входящий звонок — эталон Яндекс Про).
 * Тап/фуллскрин → MainActivity с extra → YuldashApp открывает кабинет водителя,
 * где InstantDriverOnlineController уже рисует существующий InstantOfferOverlay.
 */
internal object TaxiOfferNotifier {
    const val CHANNEL_ID = "taxi_offers"
    const val EXTRA_OPEN_OFFER = "yuldash_open_instant_offer"
    private const val NOTIF_ID = 4713   // фиксированный: новый оффер заменяет прошлое уведомление

    /** Канал (once). Важность MAX + звук/вибрация — оффер живёт ~20с, его нельзя проспать. */
    private fun ensureChannel(ctx: Context, lang: AppLanguage) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            appTextFor(lang, "Заказы такси", "Такси заказдары"),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = appTextFor(
                lang,
                "Входящие заказы, пока ты на линии",
                "Линияла саҡта килгән заказдар",
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400)
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            setBypassDnd(false)
        }
        nm.createNotificationChannel(ch)
    }

    /**
     * Показать оффер. ttlSec — сколько живёт оффер на сервере: по истечении уведомление
     * само исчезает (setTimeoutAfter), чтобы водитель не тапал протухший заказ.
     */
    fun show(
        ctx: Context,
        orderId: Int,
        fromText: String,
        toText: String,
        priceRub: Int,
        lang: AppLanguage,
        ttlSec: Int = 20,
    ) {
        if (!AppPrefs.notifications(ctx)) return   // тумблер «Уведомления» в Настройках
        ensureChannel(ctx, lang)
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_OFFER, true)
        }
        val pi = PendingIntent.getActivity(
            ctx, orderId, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val from = fromText.ifBlank { appTextFor(lang, "Точка А", "А нөктәһе") }
        val to = toText.ifBlank { appTextFor(lang, "Точка Б", "Б нөктәһе") }
        val silent = !AppPrefs.sounds(ctx)   // тумблер «Звуки» — беззвучно, но всплывает
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(appTextFor(lang, "Новый заказ 🚕", "Яңы заказ 🚕"))
            .setContentText("$from → $to · $priceRub ₽")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)   // как входящий звонок: всплыть поверх всего
            .setAutoCancel(true)
            .setSilent(silent)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)                   // погасший экран → карточка сразу на весь экран
            .setTimeoutAfter(ttlSec.coerceAtLeast(5) * 1000L)
            .build()
        mgr.notify(NOTIF_ID, notif)
    }

    /** Убрать уведомление (оффер принят/протух/сошёл с линии). */
    fun cancel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID)
    }
}

/**
 * Навигационные сигналы из мира Android (уведомления/интенты) в мир Compose.
 * MainActivity ставит флаг из onCreate/onNewIntent → YuldashApp читает и открывает
 * кабинет водителя (там живёт карточка оффера), затем сбрасывает флаг.
 */
internal object NavSignals {
    val openDriverCabinet = mutableStateOf(false)
}
