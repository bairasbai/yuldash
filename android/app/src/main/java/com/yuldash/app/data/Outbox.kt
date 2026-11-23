package com.yuldash.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Одно отложенное исходящее действие. */
data class OutboxAction(
    val id: Long,
    val bookingId: Int,
    val kind: String,     // "message" | "trip_status" | "driver_status"
    val payload: String,  // текст сообщения / код статуса
    val createdAt: Long,
    val requestKey: String = "",
)

/**
 * Очередь исходящих действий на время без сети. Сообщения и статусы («сел», «доехал»)
 * не теряются: кладём в очередь, при появлении сети [flush] авто-отправляет по порядку.
 * Хранение — на диске (переживает перезапуск). Compose-наблюдаемый счётчик [version] для UI.
 */
object Outbox {
    private var queueGeneration = 0L // Protected by this object's monitor, never held across HTTP.
    private const val PREF_SECURE = "yuldash_outbox_secure_v2"
    private const val PREF = "yuldash_outbox_v2"      // старое открытое хранилище (разовая миграция)
    private const val KEY = "queue"
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var plainPrefs: SharedPreferences? = null
    @Volatile private var securePrefs: SharedPreferences? = null
    private var migration: OfflineMigration.Selection? = null
    private val flushMutex = Mutex()
    private var seq = System.currentTimeMillis()
    // Только ID, без текста: общий отправитель мог снять отказанное сообщение раньше экрана.
    private val refusedActions = mutableSetOf<Long>()

    init {
        ApiClient.onSessionReset { synchronized(this) { refusedActions.clear() } }
    }

    /** Меняется при любом изменении очереди → Compose перечитывает [count]. */
    val version = mutableStateOf(0)

    /**
     * Очередь шифруем — тем же способом, что паспорт поездки.
     *
     * В очереди лежит ТЕКСТ сообщений: пассажирка написала «стою у второго подъезда, дочка со мной»,
     * связь пропала — и фраза осталась на диске телефона открытым текстом. Паспорт поездки рядом
     * шифруется именно потому, что там телефон водителя; переписка ничем не менее личная, а
     * хранилась иначе (аудит 2026-08-08). Хранилище приложения и так закрыто системой, а бэкап
     * выключен (`allowBackup=false`) — это вторая стена на случай потерянного или рутованного
     * телефона.
     *
     * Keystore недоступен (бывает на «кривых» прошивках) → работаем как раньше: потерять
     * неотправленное сообщение хуже, чем сохранить его в открытом виде.
     */
    @Synchronized fun init(context: Context) {
        val app = context.applicationContext
        val secure = runCatching {
            val masterKey = MasterKey.Builder(app)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                app,
                PREF_SECURE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
        initStores(app.getSharedPreferences(PREF, Context.MODE_PRIVATE), secure)
    }

    @Synchronized internal fun initStores(plain: SharedPreferences, secure: SharedPreferences?) {
        queueGeneration++
        refusedActions.clear()
        plainPrefs = plain
        val available = OfflineStoreReset.availableSecure(plain, secure)
        securePrefs = available
        migration = OfflineMigration.open(plain, available) { it == KEY }
        prefs = migration!!.storage
    }

    private fun sp(context: Context): SharedPreferences {
        prefs?.let { return it }
        init(context)
        return prefs!!
    }

    @Synchronized internal fun ensureResetCommitted(): Boolean {
        val plain = plainPrefs ?: return true
        if (OfflineMigration.resetUnconfirmed(plain)) clearAll()
        return !OfflineMigration.resetUnconfirmed(plain)
    }

    /** null means unreadable data; only an absent key represents an empty queue. */
    private fun readAll(context: Context): MutableList<OutboxAction>? {
        val read = runCatching {
            sp(context)
            if (migration?.readable != true) return null
            migration?.read(KEY)
        }
        if (read.isFailure) return null
        val raw = read.getOrNull() ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                OutboxAction(o.optLong("id"), o.optInt("booking_id"), o.optString("kind"), o.optString("payload"), o.optLong("created_at"), o.optString("request_key"))
            }.toMutableList()
        }.getOrNull()
    }

    private fun writeAll(context: Context, list: List<OutboxAction>): Boolean {
        sp(context)
        if (migration?.writable == false) return false
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().put("id", a.id).put("booking_id", a.bookingId).put("kind", a.kind).put("payload", a.payload).put("created_at", a.createdAt).put("request_key", a.requestKey))
        }
        val saved = runCatching { commitOfflineString(sp(context), KEY, arr.toString(), plainPrefs ?: return false) }.getOrDefault(false)
        if (saved) version.value = version.value + 1
        return saved
    }

    /** Сколько действий в очереди по конкретной брони (для плашки «N в очереди»). */
    @Synchronized fun count(context: Context, bookingId: Int): Int =
        readAll(context)?.count { it.bookingId == bookingId } ?: 0

    /** Есть ли вообще что отправлять (любая бронь) — чтобы не дёргать flush на пустой очереди при старте (M4). */
    @Synchronized fun hasPending(context: Context): Boolean = readAll(context)?.isNotEmpty() == true

    /** Действие ещё ждёт отправки. Очередь не прочиталась — считаем, что ждёт: не прячем неотправленное. */
    @Synchronized fun contains(context: Context, action: OutboxAction): Boolean =
        readAll(context)?.any { it.id == action.id } ?: true

    /** Неотправленные сообщения брони — чат показывает их «В очереди» и после ухода с экрана или перезапуска. */
    @Synchronized fun pendingMessages(context: Context, bookingId: Int, expectedGeneration: Long): List<OutboxAction> =
        if (expectedGeneration != ApiClient.queueSessionGeneration()) emptyList()
        else readAll(context)?.filter { it.bookingId == bookingId && it.kind == "message" }.orEmpty()

    /** Исход общего отправителя нужен экрану этого же входа; на диск ID не пишется. */
    @Synchronized fun wasRefused(action: OutboxAction, expectedGeneration: Long): Boolean =
        expectedGeneration == ApiClient.queueSessionGeneration() && action.id in refusedActions

    @Synchronized fun enqueue(context: Context, action: OutboxAction, expectedGeneration: Long? = null): Boolean {
        if (expectedGeneration != null && ApiClient.queueSessionGeneration() != expectedGeneration) return false
        val list = readAll(context) ?: return false
        val existing = list.firstOrNull { it.id == action.id }
        if (existing != null) return existing == action
        list.add(action)
        return writeAll(context, list).also { if (it) refusedActions.remove(action.id) }
    }

    /** Exact owner/action acknowledgement; a failed disk cleanup never undoes server delivery. */
    @Synchronized fun acknowledge(context: Context, action: OutboxAction, expectedGeneration: Long): Boolean {
        if (ApiClient.queueSessionGeneration() != expectedGeneration) return false
        val current = readAll(context) ?: return false
        val remaining = current.filterNot { it.id == action.id && it == action }
        return remaining.size == current.size || writeAll(context, remaining)
    }

    /**
     * Выбросить всю очередь — выход из аккаунта. Иначе неотправленные сообщения и статусы
     * прошлого пользователя ушли бы ОТ НОВОГО аккаунта при первом же появлении сети.
     * Контекст не нужен: зовётся после init().
     */
    @Synchronized fun clearAll() {
        queueGeneration++
        refusedActions.clear()
        plainPrefs?.let { plain ->
            if (!OfflineStoreReset.clear(plain, securePrefs)) {
                securePrefs = null
                prefs = plain
            }
            migration = OfflineMigration.open(plain, securePrefs) { it == KEY }
            prefs = migration!!.storage
        }
        version.value = version.value + 1
    }

    fun newMessage(bookingId: Int, text: String) =
        OutboxAction(nextId(), bookingId, "message", text, System.currentTimeMillis(), java.util.UUID.randomUUID().toString())

    internal fun messageRequestKey(action: OutboxAction): String = action.requestKey.ifBlank {
        // Old queues have no key: derive a stable identity from the stored action, never text alone.
        val identity = JSONArray().put("android-outbox-v1").put(action.id).put(action.bookingId)
            .put(action.createdAt).put(action.payload).toString()
        java.util.UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString()
    }

    fun newTripStatus(bookingId: Int, status: String) =
        OutboxAction(nextId(), bookingId, "trip_status", status, System.currentTimeMillis())

    fun newDriverStatus(bookingId: Int, status: String) =
        OutboxAction(nextId(), bookingId, "driver_status", status, System.currentTimeMillis())

    @Synchronized private fun nextId(): Long { seq += 1; return seq }

    /** Через сколько отложенный СТАТУС перестаёт быть правдой (сообщений не касается). */
    internal const val STATUS_MAX_AGE_MS = 6 * 3600_000L

    /**
     * Отправить всё, что накопилось, по порядку. Успех → удаляем из очереди.
     * Сетевая ошибка → останавливаемся, оставляем на потом (ретрай при следующей сети).
     * Временный HTTP-отказ (408/429/5xx) → оставляем до следующего запуска flush.
     * Окончательный отказ (например, бронь закрыта) → снимаем действие, чтобы очередь
     * не «отравилась» вечным повтором. Ничего не роняем.
     */
    suspend fun flush(
        context: Context,
        expectedGeneration: Long = ApiClient.queueSessionGeneration(),
        shouldContinue: () -> Boolean = { true },
        /** Сервер окончательно отказал, действие снято с очереди (чат пометит «Не доставлено»). */
        onRefused: (OutboxAction) -> Unit = {},
    ): Boolean = flushMutex.withLock {
        // Capture the caller's login before waiting for another flush; never adopt its successor.
        if (expectedGeneration != ApiClient.queueSessionGeneration() || !shouldContinue()) return@withLock false
        var changed = false
        val generation = synchronized(this) {
            sp(context)
            val plain = plainPrefs ?: return@withLock false
            if (!recoverOfflineWrites(plain, securePrefs)) return@withLock false
            if (migration?.writable == false) return@withLock false
            queueGeneration
        }
        val session = expectedGeneration
        // Протухшие СТАТУСЫ выбрасываем, сообщения — никогда.
        //
        // `createdAt` лежал в очереди с самого начала и не использовался нигде. А между тем
        // отложенный статус живёт ровно до тех пор, пока он правда. Водитель жмёт «Я на месте»
        // в селе без связи, довозит человека, приезжает домой и ловит вайфай вечером — и
        // пассажиру в одиннадцать вечера прилетает «водитель на месте» о поездке, которая
        // закончилась в шесть. Хуже, чем не отправить вовсе: человек выходит к дороге.
        //
        // Сообщения — другое дело. Это СЛОВА человека, он их написал и рассчитывает, что они
        // дойдут. Опоздавшее «подъезжаю» неловко, потерянное «жду у второго подъезда» хуже.
        // Поэтому сообщения летят при любой задержке.
        //
        // Шесть часов, а не двадцать минут: связь в дороге пропадает надолго, и статус,
        // отправленный через час, всё ещё про эту поездку.
        while (true) {
            // Only this caller stops; pending actions and the global startup flush remain intact.
            if (!shouldContinue()) return@withLock changed
            val action = synchronized(this) {
                if (generation != queueGeneration || session != ApiClient.queueSessionGeneration()) return@withLock changed
                val current = readAll(context) ?: return@withLock changed
                val now = System.currentTimeMillis()
                val fresh = current.filter { it.kind == "message" || now - it.createdAt <= STATUS_MAX_AGE_MS }
                if (fresh.size != current.size) {
                    if (!writeAll(context, fresh)) return@withLock changed
                    changed = true
                }
                fresh.firstOrNull()
            } ?: break
            if (!shouldContinue()) return@withLock changed
            val result = ApiClient.sendQueuedAction(action, session)
            val failure = result.exceptionOrNull()
            if (failure != null && (failure !is ApiException ||
                    failure.status == 408 || failure.status == 429 || failure.status in 500..599)) break
            synchronized(this) {
                if (generation != queueGeneration || session != ApiClient.queueSessionGeneration()) return@withLock changed
                // Merge with the live queue: messages added during HTTP must not be overwritten.
                val current = readAll(context) ?: return@withLock changed
                val remaining = current.filterNot { it.id == action.id }
                // Acknowledge this completed request even if its UI closed during HTTP.
                if (!writeAll(context, remaining)) return@withLock changed
                if (failure != null) refusedActions.add(action.id) else refusedActions.remove(action.id)
                changed = true
            }
            if (failure != null) onRefused(action)
        }
        changed
    }
}
