package com.yuldash.app

// Выключатели направлений — запуск волнами (решение Александра, 13.10.2026).
//
// Волна 1 — только попутки. Такси, курьеры, посылки, реклама и «Скидки по пути» написаны,
// но включаются по одному, когда готовы документы. Включает СЕРВЕР (переменная окружения),
// приложение обновлять не нужно: оно спрашивает GET /config/features и решает, показать
// вход в направление или аккуратное «Скоро в Юлдаше» (ComingSoon.kt).
//
// Правило безопасности одно: НЕИЗВЕСТНО → ВЫКЛЮЧЕНО. Первый запуск без сети, битый ответ,
// незнакомое поле — человек увидит «скоро», а не экран, который упрётся в отказ сервера.
// Попутки флага не имеют и не выключаются никогда.
//
// Кеш — в своём ящике настроек: последнее известное состояние видно сразу на холодном
// старте, а свежее подтягивается с сервера при запуске и при входе в закрытое направление,
// если запомненному больше пяти минут. Это не личные данные: флаги одинаковы для всех.

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Направление, которое сервер включает отдельно. `key` — имя поля в ответе /config/features. */
internal enum class Feature(val key: String) {
    Taxi("taxi"),
    Courier("courier"),
    Parcels("parcels"),
    Ads("ads"),
    Business("business"),
}

/**
 * Снимок флагов. `known = false` — сервер не ответил ни разу (ни сейчас, ни в кеше):
 * тогда всё, кроме попуток, считается выключенным.
 */
internal data class FeatureSet(
    val on: Set<Feature>,
    val known: Boolean,
    val fetchedAtMs: Long,
) {
    fun isOn(feature: Feature): Boolean = feature in on

    /** Доставка открыта, если работает хоть одна её половина: «по пути» или курьер. */
    val deliveryOn: Boolean get() = isOn(Feature.Parcels) || isOn(Feature.Courier)

    companion object {
        val UNKNOWN = FeatureSet(emptySet(), known = false, fetchedAtMs = 0L)
    }
}

internal object FeatureFlags {
    /** Свой ящик настроек: флаги общие для всех, к аккаунту отношения не имеют. */
    const val PREFS = "yuldash_features"
    private const val KEY_FEATURES_JSON = "features_json"
    private const val KEY_FEATURES_AT = "features_at"

    /** Сколько запомненное считается свежим. Сервер кеширует ответ на минуту — чаще незачем. */
    const val MAX_AGE_MS: Long = 5 * 60 * 1000L

    /** Сколько ждём ответа о флагах. Дольше — честное «скоро», а не скелет на полминуты
     *  (обычный запрос на плохой связи живёт до трёх попыток по 15 секунд). */
    const val ANSWER_WAIT_MS: Long = 8_000L

    private val mutableState = MutableStateFlow(FeatureSet.UNKNOWN)
    val state: StateFlow<FeatureSet> = mutableState.asStateFlow()

    private val mutableSettled = MutableStateFlow(false)
    /** Первая попытка спросить сервер закончилась (ответом или сбоем). До неё неизвестное
     *  показывается загрузкой, а не «скоро»: иначе на первом запуске мигнула бы неправда. */
    val settled: StateFlow<Boolean> = mutableSettled.asStateFlow()

    @Volatile private var loaded = false
    /** Ходить ли за флагами самому. В проде всегда да; тесты выключают, чтобы лишний запрос
     *  не съел ответ, приготовленный тестом для другого адреса. */
    @Volatile private var autoRefresh = true
    private val refreshLock = Mutex()

    val current: FeatureSet get() = mutableState.value

    /**
     * Разбор ответа сервера. Чистая функция — её и проверяют тесты.
     * Включено только то, где сервер прямо сказал `true`; всё прочее (нет поля, строка,
     * число, null) — выключено.
     */
    fun parse(json: JSONObject?, fetchedAtMs: Long): FeatureSet {
        if (json == null) return FeatureSet.UNKNOWN
        val on = Feature.entries.filter { f ->
            json.has(f.key) && !json.isNull(f.key) && json.opt(f.key) == true
        }.toSet()
        return FeatureSet(on, known = true, fetchedAtMs = fetchedAtMs)
    }

    /** Пора ли спросить сервер заново. Неизвестное — всегда пора. */
    fun isStale(set: FeatureSet, nowMs: Long): Boolean =
        !set.known || nowMs - set.fetchedAtMs !in 0..MAX_AGE_MS

    /** Поднять запомненное с диска. Один раз за процесс; дальше живём в памяти. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = true
            val prefs = runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()
                ?: return
            val raw = prefs.getString(KEY_FEATURES_JSON, null) ?: return
            val at = prefs.getLong(KEY_FEATURES_AT, 0L)
            val cached = runCatching { parse(JSONObject(raw), at) }.getOrNull() ?: return
            // Сеть могла ответить раньше, чем мы дочитали диск: свежее не затираем старым.
            if (!mutableState.value.known) mutableState.value = cached
        }
    }

    /** Спросить сервер. Сбой сети оставляет прежнее состояние (кеш или «неизвестно»). */
    suspend fun refresh(context: Context, nowMs: () -> Long = System::currentTimeMillis) {
        ensureLoaded(context)
        refreshLock.withLock {
            try {
                withTimeoutOrNull(ANSWER_WAIT_MS) { ApiClient.features() }?.onSuccess { json ->
                    val fresh = parse(json, nowMs())
                    mutableState.value = fresh
                    runCatching {
                        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString(KEY_FEATURES_JSON, json.toString())
                            .putLong(KEY_FEATURES_AT, fresh.fetchedAtMs)
                            .apply()
                    }
                }
            } finally {
                mutableSettled.value = true
            }
        }
    }

    /** Спросить сервер, только если запомненное устарело. */
    suspend fun refreshIfStale(context: Context, nowMs: () -> Long = System::currentTimeMillis) {
        ensureLoaded(context)
        if (autoRefresh && isStale(mutableState.value, nowMs())) refresh(context, nowMs)
    }

    /** Тест-хук: подставить состояние, не трогая сеть и диск. */
    internal fun setForTest(set: FeatureSet) {
        loaded = true
        autoRefresh = false
        mutableSettled.value = true
        mutableState.value = set
    }

    /** Тест-хук: «холодный старт процесса» — память пуста, следующий `ensureLoaded` читает диск. */
    internal fun coldStartForTest() {
        loaded = false
        autoRefresh = false
        mutableSettled.value = false
        mutableState.value = FeatureSet.UNKNOWN
    }

    /** Тест-хук (зовёт `ApiClient.resetForTest`): все направления открыты, без сети. */
    internal fun resetForTest() = setForTest(FeatureSet(Feature.entries.toSet(), known = true, fetchedAtMs = 0L))
}

/**
 * Текущие флаги для экрана. Читает запомненное с диска и, если оно устарело, тихо
 * спрашивает сервер — экран перерисуется сам, когда ответ придёт.
 */
@Composable
internal fun rememberFeatures(): FeatureSet {
    val context = LocalContext.current
    FeatureFlags.ensureLoaded(context)
    val set by FeatureFlags.state.collectAsState()
    LaunchedEffect(Unit) { FeatureFlags.refreshIfStale(context) }
    return set
}
