package com.yuldash.app

import android.app.Application
import com.yandex.mapkit.MapKitFactory
import com.yuldash.app.data.Analytics
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.FcmService
import com.yuldash.app.data.NetworkMonitor
import io.sentry.android.core.SentryAndroid

/**
 * Application-класс Юлдаша. Задачи при старте процесса: отдать ключ Яндекс MapKit
 * (setApiKey должен идти ДО MapKitFactory.initialize, который вызывается лениво при
 * открытии вкладки «Карта») и, при наличии DSN, поднять Sentry (сбор ошибок/крашей).
 *
 * Ключи берутся из BuildConfig (они из local.properties, в git не попадают).
 * Если ключа MapKit нет — карту не инициализируем (фолбэк-превью). Если нет
 * SENTRY_DSN — Sentry не инициализируется (no-op), приложение работает как раньше.
 */
class YuldashApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        initSentry()                      // сбор ошибок — до остального, чтобы поймать ранние сбои
        ApiClient.init(this)              // загрузить сохранённый токен сессии
        Analytics.init(this)              // Firebase Analytics: DAU/удержание/воронка событий
        NetworkMonitor.init(this)         // мгновенный реконнект WS-сокетов при возврате сети (дополняет backoff)
        // Каналы уведомлений — при старте, а не при первом пуше. Иначе человек открывает
        // «Настройки → Уведомления Юлдаша» до первого заказа и видит пустой экран: настраивать нечего.
        FcmService.ensureChannels(this)
        TaxiOfferNotifier.ensureChannel(this, AppPrefs.language(this))
        ApiClient.registerCurrentPushToken()   // если уже вошли — зарегистрировать устройство для push
        if (BuildConfig.YANDEX_MAPKIT_KEY.isNotBlank()) {
            MapKitFactory.setApiKey(BuildConfig.YANDEX_MAPKIT_KEY)
        }
    }

    /** Поднять Sentry только при заданном DSN. Пусто → no-op (ничего не шлётся). */
    private fun initSentry() {
        val dsn = BuildConfig.SENTRY_DSN
        if (dsn.isBlank()) return
        SentryAndroid.init(this) { options ->
            options.dsn = dsn
            options.environment = if (BuildConfig.DEBUG) "debug" else "release"
            options.release = "yuldash@" + BuildConfig.VERSION_NAME
            // Приватность (152-ФЗ): не отправлять PII — телефоны/координаты/токены.
            options.isSendDefaultPii = false
            // Второй рубеж. isSendDefaultPii=false запрещает Sentry ПРИКЛАДЫВАТЬ тела и заголовки,
            // но не спасает, если номер попал ВНУТРЬ текста ошибки или в адрес запроса — а адреса
            // приезжают сюда сами, хлебными крошками сетевого слоя. Чистим перед отправкой.
            options.setBeforeSend { event, _ ->
                runCatching { scrubSentryEvent(event) }.getOrNull()   // не смогли вычистить → не шлём
            }
            options.setBeforeBreadcrumb { crumb, _ ->
                runCatching {
                    crumb.message = crumb.message?.let { scrubPersonal(it) }
                    crumb
                }.getOrNull()
            }
        }
    }
}

// ------------------------------ вычистка личных данных ------------------------------
// Чистим осознанно грубо: лучше затереть лишнее в тексте ошибки, чем отправить чужой телефон.
// Для отладки нужен вид сбоя и стек, а не персональные данные из него. Зеркало серверного
// `observability.py::scrub_text` — правила держим одинаковыми с обеих сторон.
private val SCRUB: List<Pair<Regex, String>> = listOf(
    // телефон в любом написании: разделителем считаем только пробел/дефис/точку/скобки
    Regex("""(?<!\d)(?:\+?7|8)[ \-.()]{0,3}(?:\d[ \-.()]{0,3}){9}\d(?!\d)""") to "<телефон>",
    // координаты в адресе — это местоположение человека
    Regex("""\b(lat|lng|lon|latitude|longitude)=-?\d+\.\d+""", RegexOption.IGNORE_CASE) to "$1=<коорд>",
    // JWT: три base64-куска через точку. Токен = доступ к аккаунту
    Regex("""\b[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b""") to "<токен>",
    // Live-ссылка близкому (/t/{token}) — ключ к живым координатам поездки. Приложение её
    // строит и показывает при «поделиться», поэтому она может попасть в текст сбоя или в
    // хлебную крошку. Зеркало серверного правила (аудит 2026-08-08).
    Regex("""/t/[A-Za-z0-9_-]{16,}""") to "/t/***",
    // Почта — такие же личные данные, как телефон: человек пишет её в поддержку и в свободные
    // поля, а оттуда она попадает в текст сбоя. На сервере это правило появилось в волне 45,
    // а сюда его не перенесли — хотя рядом написано «правила держим одинаковыми с обеих
    // сторон» (аудит 2026-08-08, волна 87). Имя до собаки бывает кириллицей — и тут дословный
    // перенос с сервера не сработал бы: в Python `\w` с флагом UNICODE ловит кириллицу, а в
    // Kotlin по умолчанию нет, только латиницу. Включаем `(?U)` явно, иначе «марат@…» уехал бы
    // наружу целиком, а правило выглядело бы рабочим.
    Regex("""(?U)[\w.%+-]+@[\w.-]+\.[A-Za-z]{2,}""") to "<почта>",
    Regex("""\b(token|access_token|refresh_token|code|otp|password|secret|api_key|key)=[^&\s"']+""",
        RegexOption.IGNORE_CASE) to "$1=<скрыто>",
)

/** Вычистить личные данные из строки. Публичная — используется и тестами. */
internal fun scrubPersonal(s: String): String =
    SCRUB.fold(s) { acc, (rx, repl) -> rx.replace(acc, repl) }

/** Пройтись по тем полям события, куда реально попадает текст: сообщение и значения исключений. */
private fun scrubSentryEvent(event: io.sentry.SentryEvent): io.sentry.SentryEvent {
    event.message?.let { m ->
        m.message = m.message?.let(::scrubPersonal)
        m.formatted = m.formatted?.let(::scrubPersonal)
    }
    event.exceptions?.forEach { ex -> ex.value = ex.value?.let(::scrubPersonal) }
    event.breadcrumbs?.forEach { c -> c.message = c.message?.let(::scrubPersonal) }
    return event
}
