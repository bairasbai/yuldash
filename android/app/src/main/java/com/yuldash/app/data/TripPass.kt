package com.yuldash.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.runBlocking
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
        return removeCurrentPassport(context, bookingId, owner, tripEnded = true)
    }

    internal fun hideOfflineCopy(context: Context, bookingId: Int, owner: SnapshotOwner): RemovalResult =
        removeCurrentPassport(context, bookingId, owner, tripEnded = false)

    @Synchronized internal fun isHidden(bookingId: Int): Boolean =
        plainPrefs?.let { TripPassDeletion.blocks(it, bookingId) } == true

    private fun removeCurrentPassport(context: Context, bookingId: Int, owner: SnapshotOwner, tripEnded: Boolean): RemovalResult {
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
            removed = requestRemovalAfterCold(owner, bookingId, tripEnded)
        }
        return if (cold == SnapshotPersistence.DURABLY_BLOCKED && removed == RemovalResult.CLEARED) {
            RemovalResult.DEFERRED
        } else removed
    }

    @Synchronized private fun requestRemovalAfterCold(owner: SnapshotOwner, bookingId: Int, tripEnded: Boolean): RemovalResult = runCatching {
        if (!OfflineTripSnapshots.cacheEnabled(owner)) return@runCatching RemovalResult.NOT_SAVED
        val ticket = OfflineTripSnapshots.ticket()?.takeIf { it.owner == owner }
            ?: return@runCatching RemovalResult.NOT_SAVED
        if (tripEnded && !OfflineTripSnapshots.gate.close(ticket, bookingId)) return@runCatching RemovalResult.NOT_SAVED
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
