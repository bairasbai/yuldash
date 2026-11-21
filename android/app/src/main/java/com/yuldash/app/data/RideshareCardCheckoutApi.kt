package com.yuldash.app.data

import android.content.SharedPreferences
import java.net.URI
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal val cardCheckoutOperationId = Regex("[a-f0-9]{32}")
private val bankAuthority = Regex("(?:yookassa\\.ru|yoomoney\\.ru)(?::443)?", RegexOption.IGNORE_CASE)
private const val SCOPE = "booking.card_confirm"
internal const val CARD_CONFIRM_RETRY_MS = 24 * 60 * 60 * 1000L

internal enum class CardCheckoutAction { Start, Retry, Resume }

internal data class CardCheckoutOffer(
    val action: CardCheckoutAction,
    val amountKop: Int,
    val retryAfterPaymentId: String?,
    val snapshot: CommandFingerprint.Snapshot,
)

internal data class SavedCardConfirmation(
    val bookingId: Int,
    val key: String,
    val hash: String,
    val wireBody: String,
    val createdAt: Long,
) {
    val canRepeat get() = System.currentTimeMillis() - createdAt in 0L until CARD_CONFIRM_RETRY_MS
    val retryAfterPaymentId get() = JSONObject(wireBody).let {
        if (it.isNull("retry_after_payment_id")) null else it.getString("retry_after_payment_id")
    }
}

internal enum class CardConfirmationOutcome { Unseen, Applied, Closed }

internal fun decodeCardCheckoutOffer(bookingId: Int, value: JSONObject): CardCheckoutOffer? {
    if (value.isNull("action")) return null
    val action = when (value.getString("action")) {
        "start" -> CardCheckoutAction.Start
        "resume" -> CardCheckoutAction.Resume
        "retry" -> CardCheckoutAction.Retry
        else -> error("Unknown bank confirmation action")
    }
    val quote = value.getJSONObject("confirmation")
    fun revision(key: String): Long {
        val raw = quote.get(key)
        require(raw is Int || raw is Long)
        return (raw as Number).toLong().also { require(it >= 0) }
    }
    val amount = revision("amount_kop").also { require(it in 1L..Int.MAX_VALUE.toLong()) }.toInt()
    val paid = revision("paid_revision")
    val card = revision("card_revision")
    val recipient = (quote.get("recipient_version") as String).also { require(cardCheckoutOperationId.matches(it)) }
    val consent = (quote.get("driver_consent_at") as String).also { OffsetDateTime.parse(it) }
    val retryAfter = if (quote.isNull("retry_after_payment_id")) null else
        (quote.get("retry_after_payment_id") as String).also { require(cardCheckoutOperationId.matches(it)) }
    require((action == CardCheckoutAction.Retry) == (retryAfter != null))
    val body = JSONObject().put("amount_kop", amount).put("paid_revision", paid).put("card_revision", card)
        .put("recipient_version", recipient).put("driver_consent_at", consent)
        .put("retry_after_payment_id", retryAfter ?: JSONObject.NULL)
    return CardCheckoutOffer(action, amount, retryAfter, CommandFingerprint.create(SCOPE, bookingId.toLong(), body))
}

internal fun trustedCardCheckoutUrl(value: String): Boolean {
    if (value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return false
    val uri = runCatching { URI(value) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) && uri.rawAuthority?.let(bankAuthority::matches) == true
}

internal object RideshareCardCheckoutApi {
    private const val MAX_SAVED = 64
    private var prefs: SharedPreferences? = null
    fun init(storage: SharedPreferences) { prefs = storage }
    fun prefKey(account: String) = "rideshare_card_confirmations:$account"
    fun purge(account: String) {
        runCatching { prefs?.edit()?.remove(prefKey(account))?.commit() }
    }

    suspend fun mayHaveSavedConfirmation(generation: Long): Boolean = withContext(Dispatchers.IO) {
        var exists = false
        ApiClient.runIfCurrentSession(generation) {
            val id = ApiClient.myUserId() ?: return@runIfCurrentSession
            val prefix = prefKey(CommandIntent.accountPrefix(ApiClient.apiBase(), id))
            exists = checkNotNull(prefs).all.keys.any { it.startsWith(prefix) }
        }
        exists
    }

    suspend fun terms(bookingId: Int, generation: Long): Result<CardCheckoutOffer?> =
        ApiClient.bookingCommandRequest("GET", cardPath(bookingId) + "/terms", null, generation, ApiClient.apiBase())
            .mapCatching { decodeCardCheckoutOffer(bookingId, it) }

    suspend fun read(owner: BookingCommandApi.CommandOwner, bookingId: Int): Result<SavedCardConfirmation?> =
        BookingCommandApi.withCommandOwner(owner) { rows(owner.account).firstOrNull { it.bookingId == bookingId } }

    suspend fun reserve(owner: BookingCommandApi.CommandOwner, bookingId: Int, offer: CardCheckoutOffer): Result<SavedCardConfirmation?> =
        BookingCommandApi.withCommandOwner(owner) {
            check(BookingCommandApi.publicationAllowed(owner.generation))
            val saved = rows(owner.account)
            if (saved.size >= MAX_SAVED || saved.any { it.bookingId == bookingId }) return@withCommandOwner null
            val command = SavedCardConfirmation(bookingId, CommandFingerprint.KEY_PREFIX + UUID.randomUUID(),
                offer.snapshot.payloadHash, checkNotNull(offer.snapshot.wireBody), System.currentTimeMillis())
            save(owner.account, saved + command)
            command
        }

    suspend fun release(owner: BookingCommandApi.CommandOwner, command: SavedCardConfirmation): Result<Boolean> =
        BookingCommandApi.withCommandOwner(owner) {
            val saved = rows(owner.account)
            if (saved.none { it.key == command.key }) return@withCommandOwner false
            save(owner.account, saved.filterNot { it.key == command.key })
            true
        }

    suspend fun submit(owner: BookingCommandApi.CommandOwner, command: SavedCardConfirmation): Result<Unit> {
        val bookingId = command.bookingId
        return ApiClient.bookingCommandRequest("POST", "/bookings/$bookingId/commands/v1/card-confirm",
            command.wireBody, owner.generation, owner.base, command.key,
            blockDuringDeletion = true, withoutAuthReplay = true).mapCatching { require(it.opt("accepted") == true) }
    }

    suspend fun outcome(owner: BookingCommandApi.CommandOwner, command: SavedCardConfirmation, close: Boolean = false): Result<CardConfirmationOutcome> {
        val key = command.key
        val path = "/command-intents/$key"
        val body = if (close) JSONObject().put("scope", SCOPE).put("target_id", command.bookingId)
            .put("payload_hash", command.hash).toString() else null
        return ApiClient.bookingCommandRequest(if (close) "POST" else "GET", if (close) "$path/close" else path,
            body, owner.generation, owner.base, blockDuringDeletion = close, withoutAuthReplay = close).mapCatching { result ->
            val state = result.getString("state")
            if (!close && state == "unseen" && result.isNull("scope")) return@mapCatching CardConfirmationOutcome.Unseen
            require(result.getString("scope") == SCOPE)
            when (state) {
                "closed_no_effect" -> {
                    require(result.isNull("ref_id"))
                    CardConfirmationOutcome.Closed
                }
                "applied" -> {
                    require(result.getInt("ref_id") == command.bookingId && result.getJSONObject("current").opt("accepted") == true)
                    CardConfirmationOutcome.Applied
                }
                else -> error("Unexpected bank command receipt")
            }
        }
    }

    suspend fun link(bookingId: Int, paymentId: String, generation: Long): Result<String?> {
        if (!cardCheckoutOperationId.matches(paymentId)) return Result.success(null)
        val path = cardPath(bookingId) + "/checkout-link?expected_payment_id=$paymentId"
        return ApiClient.bookingCommandRequest("GET", path, null, generation, ApiClient.apiBase())
            .map { (it.opt("checkout_url") as? String)?.takeIf(::trustedCardCheckoutUrl) }
    }

    private fun cardPath(bookingId: Int) = "/bookings/$bookingId/card-payment"

    private fun rows(account: String): List<SavedCardConfirmation> {
        val json = checkNotNull(prefs).getString(prefKey(account), null)?.let(::JSONArray) ?: return emptyList()
        return List(json.length()) { index ->
            val row = json.getJSONObject(index)
            SavedCardConfirmation(row.getInt("booking"), row.getString("key"), row.getString("hash"),
                row.getString("body"), row.getLong("created"))
        }
    }

    private fun save(account: String, rows: List<SavedCardConfirmation>) {
        val editor = checkNotNull(prefs).edit()
        if (rows.isEmpty()) editor.remove(prefKey(account))
        else editor.putString(prefKey(account), JSONArray().apply {
            for (row in rows) put(JSONObject().put("booking", row.bookingId).put("key", row.key)
                .put("hash", row.hash).put("body", row.wireBody).put("created", row.createdAt))
        }.toString())
        check(editor.commit())
    }
}
