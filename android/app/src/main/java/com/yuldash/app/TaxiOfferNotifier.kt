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
 * Канал «Заказы такси» — важность MAX: звук + вибрация, чтобы оффер нельзя было проспать.
 * Карточка поверх погасшего экрана (full-screen intent) — только если система это РАЗРЕШИЛА:
 * с Android 14 это отдельное право, и по умолчанию оно есть лишь у звонилок и будильников
 * (аудит такси, P1-5). Нет права — уведомление всё равно всплывает «шторкой» сверху.
 * Тап/фуллскрин → MainActivity с extra → YuldashApp открывает кабинет водителя,
 * где InstantDriverOnlineController уже рисует существующий InstantOfferOverlay.
 *
 * Если уведомления запрещены совсем (отказ в POST_NOTIFICATIONS на Android 13+ либо выключены
 * в настройках) — показать нечего, и молчать об этом нельзя: водителю на экране линии
 * говорит об этом предупреждение (DriverOnlineHint в ProfileScreen).
 */
internal object TaxiOfferNotifier {
    const val CHANNEL_ID = "taxi_offers"
    const val EXTRA_OPEN_OFFER = "yuldash_open_instant_offer"
    // B9b-2: тап по пушу о ходе заказа (instant_status) → открыть экран заказа пассажира.
    const val EXTRA_OPEN_ORDER = "yuldash_open_instant_order"
    private const val NOTIF_ID = 4713   // фиксированный: новый оффер заменяет прошлое уведомление

    /**
     * Канал. Важность MAX + звук/вибрация — оффер живёт ~20с, его нельзя проспать.
     * Зовём и при старте приложения (YuldashApplication), чтобы канал был в системных настройках
     * сразу, и перед каждым показом. Повторный вызов для существующего канала обновляет только
     * имя/описание (Android игнорирует смену важности и звука) — значит смена языка в профиле
     * честно переименовывает канал, а настройки пользователя не сбрасываются.
     */
    fun ensureChannel(ctx: Context, lang: AppLanguage) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
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
        // Тумблер приложения и системное разрешение — обе причины внутри notificationsAllowed
        // (волна 117): раньше тумблер проверялся здесь отдельно, и экран линии про него не знал.
        // Разрешения нет → notify() молча ничего не сделает. Раньше мы этого даже не замечали:
        // на погасшем экране уведомление — единственный способ доставить оффер, и водитель мог
        // час «работать» без единого заказа (аудит такси, P0-2). Теперь выходим честно, а на
        // экране линии человек видит предупреждение и кнопку «Включить».
        if (!notificationsAllowed(ctx)) return
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
        // Android 14+: без права на full-screen intent система молча проглатывает такой вызов.
        // Ставим его только когда право есть — иначе полагаемся на обычный heads-up.
        val fullScreen = fullScreenOfferAllowed(ctx)
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            // Иконка статус-бара обязана быть МОНОХРОМНОЙ (Android красит её в один цвет по альфе).
            // Было R.mipmap.ic_launcher — цветной лаунчер превращался в белый квадрат.
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(appTextFor(lang, "Новый заказ 🚕", "Яңы заказ 🚕"))
            .setContentText("$from → $to · $priceRub ₽")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)   // как входящий звонок: всплыть поверх всего
            .setAutoCancel(true)
            .setSilent(silent)
            .setContentIntent(pi)
            .apply { if (fullScreen) setFullScreenIntent(pi, true) }   // погасший экран → карточка на весь экран
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
    // Срочная комиссия за дальнюю поездку (копейки, 0 = нет). Красная точка на вкладке
    // «Профиль» — второй рубеж к пушу: пуш уходит молча в никуда, если уведомления выключены
    // в системе, у аккаунта нет устройства или сработал антишторм, и водитель узнал бы о
    // трёхчасовом сроке только по факту блокировки.
    val payNowDebtKop = mutableStateOf(0)
    // B7b: сквозная навигация из экранов такси-заказа (в т.ч. встроенных в главную) —
    // без прокидывания колбэков через все слои. 0 = сигнала нет.
    val openInstantChat = mutableStateOf(0)      // orderId → открыть чат заказа
    // B9b-2: пуш о ходе такси-заказа (водитель принял / машина на месте / …) → экран заказа
    // пассажира (он сам подхватывает активный заказ, orderId не нужен).
    val openInstantOrder = mutableStateOf(false)
    val openSosForOrder = mutableStateOf(0)      // orderId → открыть SOS с контекстом заказа
    // Экран заработка курьера — отдельный, вкладку в «Режиме курьера» он сам переключить не может.
    // Через сигнал он просит открыть «Заказы»: пустая заглушка не просто указывает дорогу, а ведёт.
    val openCourierOrders = mutableStateOf(false)
    /**
     * Открыть SOS с произвольной подписью-контекстом (например «Доставка #12 Баймак → Сибай»).
     * Нужен курьеру: он едет ОДИН, рядом нет пассажира, который заметит беду, — но у SOS-события
     * нет поля под доставку, а заводить колонку ради строки в сообщении дежурному не стоит.
     * Контекст уходит в заметку сигнала — тем же путём, каким туда попадают координаты.
     * `null` = сигнала нет.
     */
    val openSosWithNote = mutableStateOf<String?>(null)
    // Пуш про рекламу и партнёрство («Реклама одобрена», «Оплата получена — подписка продлена»).
    // Лента уведомлений внутри приложения умела открывать эти кабинеты давно, а тап по пушу
    // в шторке — нет: одно и то же событие вело в разные места (слияние веток 2026-08-15).
    val openAdsCabinet = mutableStateOf(false)
    val openPartnerCabinet = mutableStateOf(false)
    // Чек за такси-поездку (аудит 2026-07-26): кнопка живёт в финальной карточке заказа —
    // глубоко внутри экрана такси, в т.ч. встроенного в главную. 0 = сигнала нет.
    val openTaxiReceipt = mutableStateOf(0)      // orderId → открыть чек за поездку
}
