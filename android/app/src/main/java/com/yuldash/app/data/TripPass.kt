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

/** Снимок брони, сохранённый локально для показа без сети. */
data class TripPass(
    val bookingId: Int,
    val fromCity: String,
    val toCity: String,
    val departAt: String,      // ISO с сервера, форматируется на экране
    val driverName: String,
    val driverCar: String,
    val driverPhone: String,   // ПДн — только в secure-хранилище, не логировать
    val boardingCode: String,
    val pickup: String,
    val pickupLat: Double?,
    val pickupLng: Double?,
    val price: Int,
    val seats: Int,
    val paymentNote: String,   // договорённость об оплате (если есть), иначе пусто
    val savedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("booking_id", bookingId)
        put("from_city", fromCity)
        put("to_city", toCity)
        put("depart_at", departAt)
        put("driver_name", driverName)
        put("driver_car", driverCar)
        put("driver_phone", driverPhone)
        put("boarding_code", boardingCode)
        put("pickup", pickup)
        if (pickupLat != null) put("pickup_lat", pickupLat)
        if (pickupLng != null) put("pickup_lng", pickupLng)
        put("price", price)
        put("seats", seats)
        put("payment_note", paymentNote)
        put("saved_at", savedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): TripPass = TripPass(
            bookingId = o.optInt("booking_id"),
            fromCity = o.optString("from_city"),
            toCity = o.optString("to_city"),
            departAt = o.optString("depart_at"),
            driverName = o.optString("driver_name"),
            driverCar = o.optString("driver_car"),
            driverPhone = o.optString("driver_phone"),
            boardingCode = o.optString("boarding_code"),
            pickup = o.optString("pickup"),
            pickupLat = if (o.isNull("pickup_lat")) null else o.optDouble("pickup_lat"),
            pickupLng = if (o.isNull("pickup_lng")) null else o.optDouble("pickup_lng"),
            price = o.optInt("price"),
            seats = o.optInt("seats", 1),
            paymentNote = o.optString("payment_note"),
            savedAt = o.optLong("saved_at"),
        )
    }
}

/**
 * Локальное хранилище паспортов поездок (по booking id). Secure — внутри телефон водителя.
 * Если Keystore недоступен — мягко падаем на обычные prefs (как ApiClient с токеном).
 */
object TripPassStore {
    private const val PREF_SECURE = "yuldash_trippass_secure"
    private const val PREF_PLAIN = "yuldash_trippass"
    private const val KEY_PREFIX = "pass_"
    @Volatile private var prefs: SharedPreferences? = null

    fun init(context: Context) {
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
        prefs = secure ?: app.getSharedPreferences(PREF_PLAIN, Context.MODE_PRIVATE)
    }

    private fun sp(context: Context): SharedPreferences {
        prefs?.let { return it }
        init(context)
        return prefs!!
    }

    /** Сохранить/обновить паспорт брони. */
    fun save(context: Context, pass: TripPass) {
        runCatching {
            sp(context).edit().putString(KEY_PREFIX + pass.bookingId, pass.toJson().toString()).apply()
        }
    }

    /** Прочитать паспорт (синхронно, из локального хранилища) — доступно без сети. */
    fun load(context: Context, bookingId: Int): TripPass? = runCatching {
        sp(context).getString(KEY_PREFIX + bookingId, null)?.let { TripPass.fromJson(JSONObject(it)) }
    }.getOrNull()

    /** Дополнить сохранённый паспорт кодом посадки (приходит на экране активной поездки). */
    fun updateBoardingCode(context: Context, bookingId: Int, code: String) {
        if (code.isBlank()) return
        val cur = load(context, bookingId) ?: return
        if (cur.boardingCode == code) return
        save(context, cur.copy(boardingCode = code))
    }

    /** Убрать паспорт (бронь завершена/отменена) — не держим лишние ПДн на диске. */
    fun remove(context: Context, bookingId: Int) {
        runCatching { sp(context).edit().remove(KEY_PREFIX + bookingId).apply() }
    }

    /**
     * Стереть ВСЕ паспорта — выход из аккаунта / удаление аккаунта.
     * В паспорте лежат имя и телефон пассажира: на общем телефоне это чужие ПДн (152-ФЗ),
     * следующий вошедший не должен их видеть. Контекст не нужен: зовётся после init().
     */
    fun clearAll() {
        runCatching { prefs?.edit()?.clear()?.apply() }
    }
}

/** Одно отложенное исходящее действие. */
data class OutboxAction(
    val id: Long,
    val bookingId: Int,
    val kind: String,     // "message" | "trip_status" | "driver_status"
    val payload: String,  // текст сообщения / код статуса
    val createdAt: Long,
)

/**
 * Очередь исходящих действий на время без сети. Сообщения и статусы («сел», «доехал»)
 * не теряются: кладём в очередь, при появлении сети [flush] авто-отправляет по порядку.
 * Хранение — на диске (переживает перезапуск). Compose-наблюдаемый счётчик [version] для UI.
 */
object Outbox {
    private const val PREF_SECURE = "yuldash_outbox_secure"
    private const val PREF = "yuldash_outbox"      // старое открытое хранилище (разовая миграция)
    private const val KEY = "queue"
    @Volatile private var prefs: SharedPreferences? = null
    private val flushMutex = Mutex()
    private var seq = System.currentTimeMillis()

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
    fun init(context: Context) {
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
        val plain = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        // Разовый перенос: то, что уже лежит открытым текстом, переезжает в шифрованное.
        if (secure != null && plain.contains(KEY)) {
            secure.edit().putString(KEY, plain.getString(KEY, null)).apply()
            plain.edit().remove(KEY).apply()
        }
        prefs = secure ?: plain
    }

    private fun sp(context: Context): SharedPreferences {
        prefs?.let { return it }
        init(context)
        return prefs!!
    }

    private fun readAll(context: Context): MutableList<OutboxAction> {
        val raw = runCatching { sp(context).getString(KEY, null) }.getOrNull() ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                OutboxAction(o.optLong("id"), o.optInt("booking_id"), o.optString("kind"), o.optString("payload"), o.optLong("created_at"))
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun writeAll(context: Context, list: List<OutboxAction>) {
        val arr = JSONArray()
        list.forEach { a ->
            arr.put(JSONObject().put("id", a.id).put("booking_id", a.bookingId).put("kind", a.kind).put("payload", a.payload).put("created_at", a.createdAt))
        }
        runCatching { sp(context).edit().putString(KEY, arr.toString()).apply() }
        version.value = version.value + 1
    }

    /** Сколько действий в очереди по конкретной брони (для плашки «N в очереди»). */
    fun count(context: Context, bookingId: Int): Int =
        readAll(context).count { it.bookingId == bookingId }

    /** Есть ли вообще что отправлять (любая бронь) — чтобы не дёргать flush на пустой очереди при старте (M4). */
    fun hasPending(context: Context): Boolean = readAll(context).isNotEmpty()

    fun enqueue(context: Context, action: OutboxAction) {
        val list = readAll(context)
        list.add(action)
        writeAll(context, list)
    }

    /**
     * Выбросить всю очередь — выход из аккаунта. Иначе неотправленные сообщения и статусы
     * прошлого пользователя ушли бы ОТ НОВОГО аккаунта при первом же появлении сети.
     * Контекст не нужен: зовётся после init().
     */
    fun clearAll() {
        runCatching { prefs?.edit()?.remove(KEY)?.apply() }
        version.value = version.value + 1
    }

    fun newMessage(bookingId: Int, text: String) =
        OutboxAction(nextId(), bookingId, "message", text, System.currentTimeMillis())

    fun newTripStatus(bookingId: Int, status: String) =
        OutboxAction(nextId(), bookingId, "trip_status", status, System.currentTimeMillis())

    fun newDriverStatus(bookingId: Int, status: String) =
        OutboxAction(nextId(), bookingId, "driver_status", status, System.currentTimeMillis())

    @Synchronized private fun nextId(): Long { seq += 1; return seq }

    /**
     * Отправить всё, что накопилось, по порядку. Успех → удаляем из очереди.
     * Сетевая ошибка → останавливаемся, оставляем на потом (ретрай при следующей сети).
     * Ошибка сервера (не сеть, напр. бронь закрыта) → выбрасываем действие, чтобы очередь
     * не «отравилась» вечным повтором. Ничего не роняем.
     */
    suspend fun flush(context: Context): Boolean = flushMutex.withLock {
        var changed = false
        var list = readAll(context)
        while (list.isNotEmpty()) {
            val a = list.first()
            val result = when (a.kind) {
                "message" -> ApiClient.sendMessage(a.bookingId, a.payload)
                "trip_status" -> ApiClient.setTripStatus(a.bookingId, a.payload)
                "driver_status" -> ApiClient.driverStatus(a.bookingId, a.payload).map { }
                else -> Result.success(Unit)
            }
            if (result.isSuccess) {
                list = list.drop(1).toMutableList()
                writeAll(context, list); changed = true
            } else {
                val err = result.exceptionOrNull()
                if (err is ApiException) {
                    // Сервер увидел запрос и отверг (не сеть) — повтор не поможет, снимаем из очереди.
                    list = list.drop(1).toMutableList()
                    writeAll(context, list); changed = true
                } else {
                    // Сеть всё ещё лежит — прекращаем, попробуем позже.
                    break
                }
            }
        }
        changed
    }
}
