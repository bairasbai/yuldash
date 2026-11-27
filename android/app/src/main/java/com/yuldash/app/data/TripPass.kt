package com.yuldash.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * F11 — Офлайн-паспорт поездки + очередь исходящих действий.
 *
 * Зачем: трасса Башкортостана (перевал Белорецк↔Уфа, 100+ км) — местами вообще без связи.
 * Когда `/bookings/{id}/details` не грузится, экран активной поездки поднимает СОХРАНЁННЫЙ
 * локально паспорт (маршрут, время, водитель+телефон, код посадки, точка сбора, оплата) с
 * плашкой «Офлайн — данные сохранены», а исходящие (сообщение, статус «сел») кладутся в
 * очередь и авто-ретраятся при появлении сети — ничего не теряется.
 *
 * Приватность: телефон водителя — персональные данные, храним в EncryptedSharedPreferences
 * (Android Keystore, как токен сессии). Телефон/координаты НЕ логируем.
 */

internal data class TripPassCommitReceipt(
    val bookingId: Int,
    val sourcePrivateFingerprint: String,
    val payloadSha256: String,
)

internal data class TripPassSecureCandidate(
    val bookingId: Int,
    val raw: String,
    val pass: TripPass,
    val payloadSha256: String,
)

/**
 * Локальное хранилище паспортов поездок (по booking id). Secure — внутри телефон водителя.
 * Сохранённые байты не дают доступа: общий process gate разрешает только текущую проекцию.
 */
object TripPassStore {
    // v1 has no trustworthy owner. Keep it isolated; never migrate it into v2.
    private const val PREF_SECURE = "yuldash_trippass_secure_v2"
    private const val PREF_PLAIN = "yuldash_trippass_v2"
    private const val KEY_PREFIX = "pass_"
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var plainPrefs: SharedPreferences? = null
    @Volatile private var securePrefs: SharedPreferences? = null
    private var migration: OfflineMigration.Selection? = null

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
        initStores(app.getSharedPreferences(PREF_PLAIN, Context.MODE_PRIVATE), secure)
    }

    @Synchronized internal fun initStores(plain: SharedPreferences, secure: SharedPreferences?) {
        plainPrefs = plain
        val available = OfflineStoreReset.availableSecure(plain, secure)
        securePrefs = available
        // Разовый перенос из открытого хранилища в шифрованное — как в очереди исходящих.
        //
        // Зачем (аудит 2026-08-08, волна 74). На «кривой» прошивке Keystore может не подняться,
        // и тогда паспорт — имя, ГОСНОМЕР и ТЕЛЕФОН водителя, код посадки — ложится открытым.
        // Дальше телефон перезагружают, шифрование заводится, и приложение начинает писать уже
        // в защищённое место. Старые записи при этом оставались лежать в открытом навсегда:
        // ни перенести, ни стереть их никто не пытался. Выход из аккаунта чистил только текущее
        // хранилище — то есть обещание «вышел, и чужих данных на телефоне нет» выполнялось
        // не полностью. У соседней очереди исходящих такой перенос был с самого начала.
        migration = OfflineMigration.open(plain, available) { it.startsWith(KEY_PREFIX) }
        prefs = migration!!.storage
        TripPassDeletion.reconcile(plain, available, migration!!.writable)
        if (!OfflineMigration.resetUnconfirmed(plain)) {
            OfflineTripSnapshots.retryRemovedPassports(TripPassDeletion.confirmedIds(plain))
        }
    }

    /**
     * Перенос «открытое → шифрованное» отдельной функцией, потому что иначе его нельзя проверить:
     * в тестовой среде Keystore не поднимается, ветка `secure != null` не выполняется никогда,
     * и тест на переезд был бы зелёным на пустоте (урок волны 61). Здесь оба хранилища —
     * обычные параметры, и тест подставляет свои.
     */
    internal fun migratePlain(plain: SharedPreferences, secure: SharedPreferences): Boolean =
        OfflineMigration.open(plain, secure) { it.startsWith(KEY_PREFIX) }.writable

    private fun sp(context: Context): SharedPreferences {
        prefs?.let { return it }
        init(context)
        return prefs!!
    }

    /** A new login must not hide an uncommitted logout reset behind its new auth token. */
    @Synchronized internal fun ensureResetCommitted(): Boolean {
        val plain = plainPrefs ?: return true
        if (OfflineMigration.resetUnconfirmed(plain)) clearAll()
        return !OfflineMigration.resetUnconfirmed(plain)
    }

    /** Сохранить/обновить паспорт брони. */
    @Synchronized fun save(
        context: Context,
        pass: TripPass,
        retryMigration: Boolean = false,
        expectedGeneration: Long? = null,
    ): Boolean = runCatching {
        if (expectedGeneration != null && expectedGeneration != ApiClient.queueSessionGeneration()) return@runCatching false
        val owner = OfflineTripSnapshots.currentOwner() ?: return@runCatching false
        if (!OfflineTripSnapshots.cacheEnabled(owner) || !OfflineTripSnapshots.matchesPass(pass)) return@runCatching false
        sp(context)
        if (TripPassDeletion.blocks(plainPrefs ?: return@runCatching false, pass.bookingId)) return@runCatching false
        if (retryMigration && migration?.writable == false) {
            val plain = plainPrefs ?: return@runCatching false
            // A save retry must never undo an unconfirmed logout reset.
            if (OfflineMigration.resetUnconfirmed(plain)) return@runCatching false
            val secure = securePrefs
            if (secure != null) initStores(plain, secure) else init(context)
        }
        val store = sp(context)
        if (migration?.writable == false) return@runCatching false
        commitOfflineString(store, KEY_PREFIX + pass.bookingId, pass.toJson().toString(), plainPrefs ?: return@runCatching false)
    }.getOrDefault(false)

    @Synchronized internal fun commitSecure(
        context: Context,
        pass: TripPass,
        sourcePrivateFingerprint: String,
        retryMigration: Boolean,
        expectedGeneration: Long?,
    ): TripPassCommitReceipt? = runCatching {
        if (pass.boardingCode.isBlank() ||
            (expectedGeneration != null && expectedGeneration != ApiClient.queueSessionGeneration())) return@runCatching null
        val owner = OfflineTripSnapshots.currentOwner() ?: return@runCatching null
        if (!OfflineTripSnapshots.cacheEnabled(owner) || !OfflineTripSnapshots.matchesPass(pass) ||
            OfflineTripSnapshots.projection(pass.bookingId)?.fingerprint != sourcePrivateFingerprint) return@runCatching null
        sp(context)
        val plain = plainPrefs ?: return@runCatching null
        if (OfflineMigration.resetUnconfirmed(plain) || TripPassDeletion.blocks(plain, pass.bookingId)) return@runCatching null
        if (retryMigration && migration?.writable == false) {
            val secure = securePrefs
            if (secure != null) initStores(plain, secure) else init(context)
        }
        val secure = securePrefs ?: return@runCatching null
        if (migration?.writable != true) return@runCatching null
        val key = KEY_PREFIX + pass.bookingId
        val raw = pass.toJson().toString()
        if (!commitOfflineString(secure, key, raw, plain)) return@runCatching null
        if (readOfflineString(secure, plain, key) != raw) return@runCatching null
        TripPassCommitReceipt(pass.bookingId, sourcePrivateFingerprint, snapshotHash(raw))
    }.getOrNull()

    @Synchronized internal fun readSecureCandidate(
        context: Context,
        owner: SnapshotOwner,
        bookingId: Int,
    ): TripPassSecureCandidate? = runCatching {
        if (!OfflineTripSnapshots.cacheEnabled(owner) || bookingId <= 0) return@runCatching null
        sp(context)
        val plain = plainPrefs ?: return@runCatching null
        val secure = securePrefs ?: return@runCatching null
        if (OfflineMigration.resetUnconfirmed(plain) || migration?.readable != true ||
            TripPassDeletion.blocks(plain, bookingId)) return@runCatching null
        val raw = readOfflineString(secure, plain, KEY_PREFIX + bookingId) ?: return@runCatching null
        val pass = TripPass.fromJson(JSONObject(raw))
        require(raw == pass.toJson().toString() && pass.bookingId == bookingId && pass.boardingCode.isNotBlank())
        TripPassSecureCandidate(bookingId, raw, pass, snapshotHash(raw))
    }.getOrNull()

    /** Only a current fresh-details projection can be returned. Cold ciphertext is never authority. */
    @Synchronized fun load(context: Context, bookingId: Int): TripPass? = runCatching {
        val owner = OfflineTripSnapshots.currentOwner() ?: return@runCatching null
        if (!OfflineTripSnapshots.cacheEnabled(owner)) return@runCatching null
        OfflineTripSnapshots.privatePass(bookingId)
    }.getOrNull()

    /** Дополнить сохранённый паспорт кодом посадки (приходит на экране активной поездки). */
    @Synchronized fun updateBoardingCode(
        context: Context, bookingId: Int, code: String,
        expectedGeneration: Long? = null, retryMigration: Boolean = false,
    ): Boolean {
        if (code.isBlank() || (expectedGeneration != null && expectedGeneration != ApiClient.queueSessionGeneration())) return false
        val cur = load(context, bookingId) ?: return false
        // Even an equal in-memory value does not prove the last disk write succeeded.
        return save(context, cur.copy(boardingCode = code), retryMigration, expectedGeneration)
    }

    enum class RemovalResult { CLEARED, DEFERRED, NOT_SAVED }

    /** CLEARED means both sources were erased; DEFERRED means a durable deletion barrier exists. */
    fun requestRemoval(context: Context, bookingId: Int, expectedGeneration: Long? = null): RemovalResult {
        val owner = OfflineTripSnapshots.currentOwner() ?: return RemovalResult.NOT_SAVED
        if (expectedGeneration != null && owner.generation != expectedGeneration) return RemovalResult.NOT_SAVED
        // Never wait for snapshot diskAccess while holding the store or session monitor.
        val cold = runCatching { runBlocking { OfflineTripSnapshots.revokeColdDurably(owner, bookingId) } }
            .getOrDefault(SnapshotPersistence.NOT_DURABLE)
        if (cold == SnapshotPersistence.STALE || cold == SnapshotPersistence.NOT_DURABLE) return RemovalResult.NOT_SAVED
        val initialized = synchronized(this) {
            OfflineTripSnapshots.cacheEnabled(owner) && runCatching { sp(context) }.isSuccess
        }
        if (!initialized) return RemovalResult.NOT_SAVED
        var removed = RemovalResult.NOT_SAVED
        ApiClient.runIfCurrentSession(owner.generation) {
            removed = requestRemovalAfterCold(owner, bookingId)
        }
        return if (cold == SnapshotPersistence.DURABLY_BLOCKED && removed == RemovalResult.CLEARED) {
            RemovalResult.DEFERRED
        } else removed
    }

    @Synchronized private fun requestRemovalAfterCold(owner: SnapshotOwner, bookingId: Int): RemovalResult = runCatching {
        if (!OfflineTripSnapshots.cacheEnabled(owner)) return@runCatching RemovalResult.NOT_SAVED
        val ticket = OfflineTripSnapshots.ticket()?.takeIf { it.owner == owner }
            ?: return@runCatching RemovalResult.NOT_SAVED
        if (!OfflineTripSnapshots.gate.close(ticket, bookingId)) return@runCatching RemovalResult.NOT_SAVED
        OfflineTripSnapshots.forgetLive(owner, bookingId)
        val plain = plainPrefs ?: return@runCatching RemovalResult.NOT_SAVED
        if (OfflineMigration.resetUnconfirmed(plain)) return@runCatching RemovalResult.NOT_SAVED
        if (!TripPassDeletion.request(plain, bookingId)) return@runCatching RemovalResult.NOT_SAVED
        if (TripPassDeletion.reconcile(plain, securePrefs, migration?.writable == true)) RemovalResult.CLEARED
        else RemovalResult.DEFERRED
    }.getOrDefault(RemovalResult.NOT_SAVED)

    /** Compatibility result: true only when physical cleanup of both stores was confirmed. */
    fun remove(context: Context, bookingId: Int): Boolean =
        requestRemoval(context, bookingId) == RemovalResult.CLEARED

    /**
     * Стереть ВСЕ паспорта — выход из аккаунта / удаление аккаунта.
     * В паспорте лежат имя и телефон пассажира: на общем телефоне это чужие ПДн (152-ФЗ),
     * следующий вошедший не должен их видеть. Контекст не нужен: зовётся после init().
     */
    @Synchronized fun clearAll() {
        plainPrefs?.let { plain ->
            if (!OfflineStoreReset.clear(plain, securePrefs)) {
                securePrefs = null
                prefs = plain
            }
            migration = OfflineMigration.open(plain, securePrefs) { it.startsWith(KEY_PREFIX) }
            prefs = migration!!.storage
        }
    }
}

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
    private var claimedRating: Long? = null
    private const val RATING_ATTEMPTED = "attempted"

    init {
        ApiClient.onSessionReset { synchronized(this) { refusedActions.clear() } }
    }

    /** Меняется при любом изменении очереди → Compose перечитывает [count]. */
    val version = mutableStateOf(0)

    /**
     * Очередь шифруем — тем же способом, что паспорт поездки этажом выше.
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
        readAll(context)?.count { it.bookingId == bookingId && it.kind != "rate" } ?: 0

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

    @Synchronized internal fun saveRating(context: Context, bookingId: Int, rating: QueuedRating, generation: Long): OutboxAction? {
        if (bookingId <= 0 || !rating.fresh() || generation != ApiClient.queueSessionGeneration() ||
            OfflineTripSnapshots.currentOwner()?.namespace != rating.owner) return null
        val list = readAll(context) ?: return null
        if (securePrefs == null || prefs !== securePrefs) return null
        list.firstOrNull { it.kind == "rate" && it.bookingId == bookingId &&
            QueuedRating.read(it)?.let { old -> old.owner == rating.owner && old.fresh() } == true }?.let { return it }
        val action = OutboxAction(nextId(), bookingId, "rate", "${rating.owner}:${rating.until}", System.currentTimeMillis())
        return action.takeIf { writeAll(context, list + action) }
    }

    @Synchronized internal fun cancelRating(context: Context, bookingId: Int, owner: String, id: Long, generation: Long): Boolean {
        if (generation != ApiClient.queueSessionGeneration() || claimedRating == id) return false
        val rows = readAll(context) ?: return false
        val action = rows.firstOrNull { it.id == id && it.bookingId == bookingId &&
            it.kind == "rate" && QueuedRating.read(it)?.owner == owner } ?: return false
        return writeAll(context, rows.filterNot { it == action })
    }

    @Synchronized internal fun canCancelRating(context: Context, id: Long): Boolean =
        claimedRating != id && readAll(context)?.any { it.id == id && it.kind == "rate" } == true

    internal fun ratingAttempted(action: OutboxAction): Boolean = action.requestKey == RATING_ATTEMPTED

    @Synchronized internal fun queuedRating(context: Context, id: Long, bookingId: Int): OutboxAction? =
        readAll(context)?.firstOrNull { it.id == id && it.bookingId == bookingId && it.kind == "rate" }

    private suspend fun sendRating(context: Context, action: OutboxAction, generation: Long,
                                   refreshIfUnauthorized: Boolean): Result<QueuedActionResult> {
        val rating = QueuedRating.read(action) ?: return Result.failure(IllegalArgumentException("Invalid rating"))
        fun admitted() = rating.fresh() && !RateNotificationAction.locked(context) &&
            OfflineTripSnapshots.currentOwner()?.let { it.namespace == rating.owner && it.generation == generation } == true
        synchronized(this) {
            if (!admitted() || securePrefs == null || prefs !== securePrefs ||
                readAll(context)?.contains(action) != true) return Result.failure(IllegalStateException("Rating unavailable"))
            claimedRating = action.id
        }
        fun admitAttempt(): Boolean = synchronized(this) {
            if (!admitted() || claimedRating != action.id) return@synchronized false
            val rows = readAll(context) ?: return@synchronized false
            val saved = rows.firstOrNull { it.id == action.id } ?: return@synchronized false
            saved.requestKey == RATING_ATTEMPTED ||
                writeAll(context, rows.map { if (it == saved) it.copy(requestKey = RATING_ATTEMPTED) else it })
        }
        return try {
            ApiClient.sendQueuedAction(action, generation, RatingSend(refreshIfUnauthorized, ::admitted, ::admitAttempt))
        } finally {
            synchronized(this) { claimedRating = null }
        }
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
    ): Boolean = flushMutex.withLock { flushLocked(context, expectedGeneration, shouldContinue, onRefused) }

    internal suspend fun flushConfirmedRating(context: Context, generation: Long, id: Long) {
        if (!flushMutex.tryLock()) return
        try {
            flushLocked(context, generation, { true }, {}, ratingId = id)
        } finally {
            flushMutex.unlock()
        }
    }

    private suspend fun flushLocked(
        context: Context, expectedGeneration: Long, shouldContinue: () -> Boolean,
        onRefused: (OutboxAction) -> Unit, ratingId: Long? = null,
    ): Boolean {
        // Capture the caller's login before waiting for another flush; never adopt its successor.
        if (expectedGeneration != ApiClient.queueSessionGeneration() || !shouldContinue()) return false
        var changed = false
        val generation = synchronized(this) {
            sp(context)
            val plain = plainPrefs ?: return false
            if (!recoverOfflineWrites(plain, securePrefs)) return false
            if (migration?.writable == false) return false
            queueGeneration
        }
        val session = expectedGeneration
        val attemptedRatings = mutableSetOf<Long>()
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
            if (!shouldContinue()) return changed
            val action = synchronized(this) {
                if (generation != queueGeneration || session != ApiClient.queueSessionGeneration()) return changed
                val current = readAll(context) ?: return changed
                val now = System.currentTimeMillis()
                val fresh = current.filter {
                    if (it.kind == "rate") QueuedRating.read(it)?.let { rating ->
                        rating.fresh() && rating.owner == OfflineTripSnapshots.currentOwner()?.namespace
                    } == true else it.kind == "message" || now - it.createdAt <= STATUS_MAX_AGE_MS
                }
                if (fresh.size != current.size) {
                    if (!writeAll(context, fresh)) return changed
                    changed = true
                }
                fresh.firstOrNull { (ratingId == null || (it.id == ratingId && it.kind == "rate")) &&
                    (it.kind != "rate" || (it.id !in attemptedRatings && !RateNotificationAction.locked(context))) }
            } ?: break
            if (!shouldContinue()) return changed
            val result = if (action.kind == "rate") sendRating(context, action, session, refreshIfUnauthorized = ratingId == null)
                else ApiClient.sendQueuedAction(action, session)
            val failure = result.exceptionOrNull()
            if (action.kind == "rate" && failure != null && (failure !is ApiException ||
                    failure.status !in setOf(403, 404, 409, 410, 422))) {
                attemptedRatings += action.id
                if (ratingId == null) RateNotificationAction.stillPending(context, action, session)
                continue
            }
            if (failure != null && (failure !is ApiException ||
                    failure.status == 408 || failure.status == 429 || failure.status in 500..599)) break
            synchronized(this) {
                if (generation != queueGeneration || session != ApiClient.queueSessionGeneration()) return changed
                // Merge with the live queue: messages added during HTTP must not be overwritten.
                val current = readAll(context) ?: return changed
                val remaining = current.filterNot { it.id == action.id }
                // Acknowledge this completed request even if its UI closed during HTTP.
                if (!writeAll(context, remaining)) return changed
                if (failure != null) refusedActions.add(action.id) else refusedActions.remove(action.id)
                changed = true
            }
            if (failure != null) onRefused(action)
            if (action.kind == "rate") {
                val rating = QueuedRating.read(action)
                if (rating != null) RateNotificationAction.result(context, action.bookingId, rating.owner, session,
                    result.getOrNull() as? QueuedActionResult.RatingApplied)
            }
        }
        return changed
    }
}
