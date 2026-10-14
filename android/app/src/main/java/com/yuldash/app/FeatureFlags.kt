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
// Кеш — только последнее подтверждение: новый процесс не открывает направления без сети.
// Ошибка и таймаут закрывают всё, кроме попуток, даже после прежнего enabled-ответа.
// Флаги одинаковы для всех; обновление не содержит токена и личных данных.

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import kotlinx.coroutines.CancellationException
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
 * Снимок флагов. `known = false` — живого подтверждения сейчас нет:
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
    @Volatile private var completedAttempt = 0L
    private var lastCompletedNetwork = -1L
    @Volatile private var networkRevision = 0L
    internal val automaticRefreshEnabled: Boolean get() = autoRefresh
    private var lastAttemptAt = 0L
    private const val RETRY_GAP_MS = 15_000L

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

    /** Кеш с диска никогда не служит разрешением: сначала нужен живой ответ сервера. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
            loaded = true
            if (autoRefresh) FeatureConnectivity.init(context.applicationContext)
        }
    }

    private fun remember(context: Context, json: JSONObject, at: Long) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_FEATURES_JSON, json.toString()).putLong(KEY_FEATURES_AT, at).apply()
        }
    }

    /** Сбой закрывает новые действия сразу и убирает enabled из сохранённого ответа. */
    private fun closeAfterFailure(context: Context) {
        mutableState.value = FeatureSet.UNKNOWN
        remember(context, JSONObject(), 0L)
    }

    /** Системный обрыв сети имеет приоритет над поздним enabled-ответом. */
    internal fun markOffline(context: Context) {
        synchronized(this) {
            networkRevision++
            closeAfterFailure(context)
            mutableSettled.value = true
        }
        FeatureFlagTransport.cancelPending()
    }

    /** Общая попытка: ожидавшие тот же запрос не запускают второй, даже после его отказа. */
    private suspend fun update(context: Context, onlyStale: Boolean, nowMs: () -> Long) {
        val observedAttempt = completedAttempt
        val requestedNetwork = networkRevision
        ensureLoaded(context)
        refreshLock.withLock {
            if (observedAttempt != completedAttempt && lastCompletedNetwork == requestedNetwork) return
            val now = nowMs()
            if (onlyStale && (!isStale(current, now) ||
                    (lastAttemptAt > 0 && now >= lastAttemptAt && now - lastAttemptAt < RETRY_GAP_MS))) return
            lastAttemptAt = now
            val capturedNetwork = networkRevision
            try {
                val reply = ApiClient.features()
                reply.onSuccess { json ->
                    synchronized(this) {
                        if (capturedNetwork == networkRevision) {
                            val fresh = parse(json, nowMs())
                            mutableState.value = fresh
                            remember(context, json, fresh.fetchedAtMs)
                        }
                    }
                }.onFailure {
                    synchronized(this) { if (capturedNetwork == networkRevision) closeAfterFailure(context) }
                }
            } catch (cancelled: CancellationException) {
                synchronized(this) { if (capturedNetwork == networkRevision) closeAfterFailure(context) }
                throw cancelled
            } catch (_: Exception) {
                synchronized(this) { if (capturedNetwork == networkRevision) closeAfterFailure(context) }
            } finally {
                lastCompletedNetwork = capturedNetwork
                completedAttempt++
                mutableSettled.value = true
            }
        }
    }

    suspend fun refresh(context: Context, nowMs: () -> Long = System::currentTimeMillis) =
        update(context, onlyStale = false, nowMs = nowMs)

    suspend fun refreshIfStale(context: Context, nowMs: () -> Long = System::currentTimeMillis) {
        if (autoRefresh) update(context, onlyStale = true, nowMs = nowMs)
    }

    /** Тест-хук: подставить состояние, не трогая сеть и диск. */
    internal fun setForTest(set: FeatureSet, automatic: Boolean = false) {
        loaded = true
        lastAttemptAt = 0L
        completedAttempt = 0L
        lastCompletedNetwork = -1L
        networkRevision = 0L
        autoRefresh = automatic
        mutableSettled.value = true
        mutableState.value = set
    }

    /** Тест-хук: память пуста; старый enabled-кеш не заменяет живое подтверждение. */
    internal fun coldStartForTest(auto: Boolean = false) {
        loaded = false
        lastAttemptAt = 0L
        completedAttempt = 0L
        lastCompletedNetwork = -1L
        networkRevision = 0L
        autoRefresh = auto
        mutableSettled.value = false
        mutableState.value = FeatureSet.UNKNOWN
    }

    /** Тест-хук (зовёт `ApiClient.resetForTest`): все направления открыты, без сети. */
    internal fun resetForTest() = setForTest(FeatureSet(Feature.entries.toSet(), known = true, fetchedAtMs = 0L))
}

/**
 * Текущие флаги: первая проверка и возврат из фона. TTL и mutex удерживают лишние GET.
 */
@Composable
internal fun rememberFeatures(nowMs: () -> Long = System::currentTimeMillis): FeatureSet {
    val context = LocalContext.current
    FeatureFlags.ensureLoaded(context)
    val set by FeatureFlags.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentClock by rememberUpdatedState(nowMs)
    LaunchedEffect(lifecycleOwner) {
        FeatureFlags.refreshIfStale(context, currentClock)
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { FeatureFlags.refreshIfStale(context, currentClock) }
    }
    return set
}
