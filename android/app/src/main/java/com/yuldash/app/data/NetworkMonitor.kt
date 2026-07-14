package com.yuldash.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Процессный монитор доступности сети. Один на всё приложение (object).
 *
 * Зачем: WebSocket-сокеты (чат/гео/лента карты) при обрыве сети раньше подхватывались только
 * через backoff-таймер (до ~30с). Здесь мы слушаем системное событие «сеть вернулась»
 * (ConnectivityManager.registerDefaultNetworkCallback → onAvailable) и мгновенно будим всех
 * подписчиков — реконнект происходит в момент возврата связи, а не по тайм-ауту.
 *
 * Приватность: событий про КАКАЯ сеть/где мы не собираем и не логируем — только факт «сеть есть».
 * Батарея: один системный колбэк на процесс (не на сокет), слушатели держим через WeakReference —
 * забытая отписка не течёт (мёртвые ссылки вычищаются при следующем событии).
 *
 * init() идемпотентна — повторный вызов ничего не регистрирует второй раз.
 */
object NetworkMonitor {

    /** Слушатель возврата сети. Реализуют сокеты — на onNetworkAvailable() делают мгновенный реконнект. */
    fun interface Listener {
        fun onNetworkAvailable()
    }

    // CopyOnWriteArrayList: безопасная итерация из потока системного колбэка без блокировок при уведомлении.
    private val listeners = CopyOnWriteArrayList<WeakReference<Listener>>()

    @Volatile private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            notifyAvailable()
        }
    }

    /** Регистрирует системный колбэк один раз за процесс. Безопасно звать многократно. */
    @Synchronized
    fun init(context: Context) {
        if (registered) return
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(callback)   // требует ACCESS_NETWORK_STATE (есть в манифесте)
            registered = true
        }
    }

    /** Подписаться на возврат сети. Идемпотентно (повторный тот же listener не дублируется). */
    fun subscribe(listener: Listener) {
        // чистим мёртвые ссылки и проверяем, нет ли уже этого слушателя
        var already = false
        val dead = ArrayList<WeakReference<Listener>>()
        for (ref in listeners) {
            val l = ref.get()
            if (l == null) dead.add(ref)
            else if (l === listener) already = true
        }
        if (dead.isNotEmpty()) listeners.removeAll(dead)
        if (!already) listeners.add(WeakReference(listener))
    }

    /** Отписаться (обязательно в close() сокета, чтобы не будить мёртвый канал). */
    fun unsubscribe(listener: Listener) {
        val dead = ArrayList<WeakReference<Listener>>()
        for (ref in listeners) {
            val l = ref.get()
            if (l == null || l === listener) dead.add(ref)
        }
        if (dead.isNotEmpty()) listeners.removeAll(dead)
    }

    private fun notifyAvailable() {
        val dead = ArrayList<WeakReference<Listener>>()
        for (ref in listeners) {
            val l = ref.get()
            if (l == null) dead.add(ref)
            else runCatching { l.onNetworkAvailable() }
        }
        if (dead.isNotEmpty()) listeners.removeAll(dead)
    }
}
