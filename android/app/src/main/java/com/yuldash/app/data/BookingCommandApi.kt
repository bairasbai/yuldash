package com.yuldash.app.data

import android.content.SharedPreferences
import com.yuldash.app.existingBookingIdOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal object BookingCommandApi {
    private var prefs: SharedPreferences? = null
    private var confirmedOwner: CommandOwner? = null
    private var deletingGeneration: Long? = null
    private val store = CommandIntent(
        read = { account -> checkNotNull(prefs).getString(CommandIntent.prefKey(account), null) },
        write = { account, value -> checkNotNull(prefs).edit().putString(CommandIntent.prefKey(account), value).commit() },
    )

    internal data class CommandOwner(val generation: Long, val base: String, val userId: Int, val account: String)

    fun init(storage: SharedPreferences) {
        prefs = storage
        RidePublicationApi.init(storage)
        DriverBookingDecisionApi.init(storage)
    }

    suspend fun book(rideId: Int, body: JSONObject, expectedGeneration: Long,
                     previousKey: String?, retry: Boolean): Result<BookingCreatedDto> {
        val owner = commandOwner(expectedGeneration).getOrElse { return Result.failure(it) }
        val (snapshot, slot) = withBookingCommands(owner) {
            if (deletingGeneration == owner.generation) throw stale()
            // Reserving or returning a seat advances the public version of the ride.
            // Replaying the original command must keep its precondition and still match the entire body.
            val originalTerms = if (retry) list(owner.account)
                .firstOrNull { it.rideId == rideId && it.key == previousKey }?.bookingTerms else null
            val currentTerms = CommandIntent.BookingTerms(
                if (body.isNull("expected_terms_version")) null else body.getLong("expected_terms_version"),
            )
            val snapshot = bookingCommandSnapshot(rideId, body, originalTerms)
            snapshot to prepare(owner.account, rideId, snapshot.payloadHash, previousKey, retry, currentTerms)
        }.getOrElse { return Result.failure(it) }
        val response = ApiClient.bookingCommandRequest("POST", BookingCommandOutcome.PATH, snapshot.wireBody,
            owner.generation, owner.base, slot.key)
            .mapCatching { BookingCommandOutcome.booking(it, rideId, owner.userId) }
        val booking = response.getOrNull()
        if (booking != null) {
            val accepted = withBookingCommands(owner) { settle(owner.account, slot, CommandIntent.State.Applied) }
            if (accepted.getOrNull() == true) {
                Analytics.log("booking")
                return Result.success(booking)
            }
            return Result.failure(CommandIntent.Failure(CommandIntent.Problem.Unknown, slot))
        }
        val refusal = response.exceptionOrNull() as? ApiException
        if (refusal != null && refusal.status in 400..499 && refusal.status !in setOf(401, 404, 405, 408, 429)) {
            val receipt = reconcileBookingCommand(owner, slot, close = false).getOrNull()
            if (receipt != null) {
                val terminalSlot = slot.copy(state = receipt.state)
                when (receipt.state) {
                    CommandIntent.State.Applied -> return receipt.booking?.let { Result.success(it) }
                        ?: Result.failure(CommandIntent.Failure(CommandIntent.Problem.Gone, terminalSlot))
                    CommandIntent.State.Rejected -> return Result.failure(CommandIntent.Failure(
                        CommandIntent.Problem.Rejected, terminalSlot, receipt.rejectionCode))
                    CommandIntent.State.Closed -> return Result.failure(CommandIntent.Failure(CommandIntent.Problem.Closed, terminalSlot))
                    CommandIntent.State.Unknown -> existingBookingIdOf(refusal)?.let { id ->
                        val existing = ApiClient.bookingCommandRequest("GET", "/bookings/$id/details", null,
                            owner.generation, owner.base).mapCatching {
                            require(it.getString("role") == "passenger" && it.getInt("ride_id") == rideId)
                            val status = it.getString("status")
                            require(status in setOf("pending", "confirmed", "onboard", "cancelled", "done"))
                            BookingCreatedDto(id, status)
                        }.getOrNull()
                        if (existing != null) return Result.failure(CommandIntent.Failure(
                            CommandIntent.Problem.Unknown, slot, existingBooking = existing))
                    }
                }
            }
        }
        return Result.failure(CommandIntent.Failure(CommandIntent.Problem.Unknown, slot))
    }

    internal suspend fun commandOwner(generation: Long): Result<CommandOwner> {
        val (base, userId) = withGeneration(generation) {
            ApiClient.apiBase() to (ApiClient.myUserId()?.takeIf { it > 0 } ?: throw stale())
        }.getOrElse { return Result.failure(it) }
        val cached = withGeneration(generation) {
            confirmedOwner?.takeIf { it.generation == generation && it.base == base && it.userId == userId }
        }.getOrElse { return Result.failure(it) }
        if (cached != null) return Result.success(cached)
        val profile = ApiClient.bookingCommandRequest("GET", "/me", null, generation, base)
            .getOrElse { return Result.failure(it) }
        return withGeneration(generation) {
            if (ApiClient.apiBase() != base || ApiClient.myUserId() != userId) throw stale()
            require(profile.getInt("id") == userId)
            val created = (profile.opt("created_at") as? String)?.takeIf(String::isNotBlank)
                ?: throw CommandIntent.Failure(CommandIntent.Problem.Storage)
            CommandOwner(generation, base, userId, CommandIntent.account(base, userId, created))
                .also { confirmedOwner = it }
        }
    }

    private fun stale() = CommandIntent.Failure(CommandIntent.Problem.Superseded)

    private inline fun <T> withGeneration(generation: Long, crossinline action: () -> T): Result<T> {
        var result: Result<T>? = null
        ApiClient.runIfCurrentSession(generation) { result = runCatching { action() } }
        return result ?: Result.failure(stale())
    }

    private suspend fun <T> withBookingCommands(owner: CommandOwner, action: CommandIntent.() -> T): Result<T> =
        withCommandOwner(owner) { store.action() }

    internal suspend fun <T> withCommandOwner(owner: CommandOwner, action: () -> T): Result<T> =
        withContext(Dispatchers.IO) {
            withGeneration(owner.generation) {
                if (ApiClient.myUserId() != owner.userId || ApiClient.apiBase() != owner.base) throw stale()
                action()
            }
        }

    internal fun publicationAllowed(generation: Long): Boolean =
        withGeneration(generation) { deletingGeneration != generation }.getOrDefault(false)

    suspend fun <T> withDeletionBarrier(generation: Long, delete: suspend (List<String>) -> Result<T>): Result<T> {
        var acquired = false
        try {
            val accounts = withContext(Dispatchers.IO) {
                withGeneration(generation) {
                    if (deletingGeneration == generation) throw stale()
                    deletingGeneration = generation
                    acquired = true
                    val id = ApiClient.myUserId() ?: return@withGeneration emptyList()
                    val base = ApiClient.apiBase()
                    confirmedOwner?.takeIf { it.generation == generation && it.userId == id && it.base == base }
                        ?.let { return@withGeneration listOf(it.account) }
                    // Capture only keys present before DELETE. A later account may reuse the numeric ID.
                    val accountPrefix = CommandIntent.accountPrefix(base, id)
                    val storagePrefixes = listOf(CommandIntent.prefKey(""), RidePublicationIntent.prefKey(""), DriverDecisionStore.prefKey(""))
                    runCatching {
                        prefs?.all?.keys.orEmpty().mapNotNull { key ->
                            storagePrefixes.firstOrNull { key.startsWith(it + accountPrefix) }?.let(key::removePrefix)
                        }.distinct()
                    }.getOrDefault(emptyList())
                }
            }.getOrElse { return Result.failure(it) }
            return delete(accounts)
        } finally {
            if (acquired) ApiClient.runIfUnchangedSession(generation) {
                if (deletingGeneration == generation) deletingGeneration = null
            }
        }
    }

    /** The caller holds the session lock even when the successful response outlives its generation. */
    fun purge(accounts: List<String>) {
        accounts.forEach {
            runCatching { store.purge(it) }
            RidePublicationApi.purge(it)
            DriverBookingDecisionApi.purge(it)
        }
    }

    internal suspend fun savedBookingCommands(generation: Long): Result<List<CommandIntent.Slot>> {
        val owner = commandOwner(generation).getOrElse { return Result.failure(it) }
        return withBookingCommands(owner) { list(owner.account) }
    }

    internal suspend fun reconcileBookingCommand(
        generation: Long, slot: CommandIntent.Slot, close: Boolean,
    ): Result<BookingCommandOutcome> {
        val owner = commandOwner(generation).getOrElse { return Result.failure(it) }
        return reconcileBookingCommand(owner, slot, close)
    }

    private suspend fun reconcileBookingCommand(
        owner: CommandOwner, slot: CommandIntent.Slot, close: Boolean,
    ): Result<BookingCommandOutcome> {
        val current = withBookingCommands(owner) { list(owner.account).firstOrNull { it.rideId == slot.rideId } }
            .getOrElse { return Result.failure(it) }
        if (current?.key != slot.key || (close && current.state != CommandIntent.State.Unknown)) {
            return Result.failure(CommandIntent.Failure(CommandIntent.Problem.Superseded, current))
        }
        val body = if (close) JSONObject().put("scope", BookingCommandOutcome.SCOPE)
            .put("target_id", slot.rideId).put("payload_hash", slot.hash) else null
        val response = ApiClient.bookingCommandRequest(if (close) "POST" else "GET", "/command-intents/${slot.key}" + if (close) "/close" else "",
            body?.toString(), owner.generation, owner.base)
            .mapCatching { BookingCommandOutcome.fromReceipt(it, slot.rideId, owner.userId, close) }
        val outcome = response.getOrElse { return Result.failure(it) }
        return withBookingCommands(owner) {
            val latest = list(owner.account).firstOrNull { it.rideId == slot.rideId }
            val accepted = if (outcome.state == CommandIntent.State.Unknown) {
                latest?.key == slot.key && latest.state == CommandIntent.State.Unknown
            } else settle(owner.account, slot, outcome.state)
            if (!accepted) throw CommandIntent.Failure(CommandIntent.Problem.Superseded, latest)
            outcome
        }
    }
}

internal fun bookingCommandSnapshot(
    rideId: Int, body: JSONObject, originalTerms: CommandIntent.BookingTerms?,
): CommandFingerprint.Snapshot = try {
    val outgoing = if (originalTerms == null) body else JSONObject(body, body.keys().asSequence().toList().toTypedArray()).apply {
        if (originalTerms.version == null) remove("expected_terms_version")
        else put("expected_terms_version", originalTerms.version)
    }
    CommandFingerprint.create(BookingCommandOutcome.SCOPE, rideId.toLong(), outgoing)
} catch (_: Exception) {
    throw CommandIntent.Failure(CommandIntent.Problem.Storage)
}

internal fun bookingPayload(
    rideId: Int, seats: Int, payMethod: String, payAmount: Int?, minorPassenger: Boolean,
    guardianName: String, guardianPhone: String, frontSeat: Boolean, pickupStop: String?, dropoffStop: String?,
    expectedPrice: Int?, expectedTermsVersion: Long?, meeting: MeetingPointInput?, alighting: MeetingPointInput?,
): JSONObject = JSONObject().put("ride_id", rideId).put("seats", seats)
        .put("pay_method", payMethod)
        .put("pay_amount", payAmount ?: JSONObject.NULL)
        .apply {
            if (frontSeat) put("front_seat", true)
            if (pickupStop != null) put("pickup_stop", pickupStop)
            if (dropoffStop != null) put("dropoff_stop", dropoffStop)
            if (expectedPrice != null) put("expected_price", expectedPrice)
            if (expectedTermsVersion != null) put("expected_terms_version", expectedTermsVersion)
            if (meeting != null && !meeting.isEmpty) put("meeting", meeting.toJson())
            if (alighting != null && !alighting.isEmpty) put("alighting", alighting.toJson())
            if (minorPassenger) {
                put("minor_passenger", true)
                put("minor_guardian_name", guardianName)
                put("minor_guardian_phone", guardianPhone)
            }
        }
