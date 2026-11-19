package com.yuldash.app.data

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.yuldash.app.AppPrefs
import com.yuldash.app.MainActivity
import com.yuldash.app.R
import com.yuldash.app.appTextFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal data class QueuedRating(val owner: String, val until: Long) {
    fun fresh() = until > System.currentTimeMillis() / 1000L

    companion object {
        fun read(action: OutboxAction): QueuedRating? {
            val fields = action.payload.split(':', limit = 2)
            val until = fields.getOrNull(1)?.toLongOrNull() ?: return null
            return QueuedRating(fields[0], until)
        }
    }
}

internal sealed interface QueuedActionResult {
    data object Applied : QueuedActionResult
    data class RatingApplied(val kept: Boolean, val stars: Int) : QueuedActionResult
}

/** Both confirmation buttons are explicit and scoped to the original signed-in account. */
class RateNotificationAction : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val booking = intent.getIntExtra("booking", 0)
        val owner = OfflineTripSnapshots.currentOwner() ?: return
        if (booking <= 0 || intent.getStringExtra("owner") != owner.namespace) return
        val app = context.applicationContext
        when (intent.action) {
            ASK -> {
                val until = intent.getLongExtra("until", 0L)
                if (!QueuedRating(owner.namespace, until).fresh()) return
                if (locked(app)) {
                    post(app, owner, booking, text(app, "Разблокируй телефон, чтобы оценить", "Баһалау өсөн телефонды ас"),
                        listOf(askAction(app, owner, booking, until)))
                    return
                }
                val nonce = UUID.randomUUID().toString()
                ApiClient.runIfCurrentSession(owner.generation) {
                    if (!OfflineTripSnapshots.current(owner)) return@runIfCurrentSession
                    val deadline = android.os.SystemClock.elapsedRealtime() + PROMPT_TIMEOUT_MS
                    prompts.entries.removeIf { it.value.deadline <= android.os.SystemClock.elapsedRealtime() || it.value.booking == booking }
                    prompts[nonce] = Prompt(booking, QueuedRating(owner.namespace, until), deadline)
                    post(app, owner, booking, text(app, "Поставить 5 ★?", "5 ★ ҡуйырғамы?"), listOf(
                        button(app, owner, booking, YES, text(app, "Да", "Эйе"), nonce = nonce),
                        button(app, owner, booking, NO, text(app, "Отмена", "Кире алыу"), nonce = nonce),
                    ), prompt = true)
                }
            }
            YES, NO -> {
                val nonce = intent.getStringExtra("nonce") ?: return
                val prompt = prompts[nonce]?.takeIf { it.booking == booking && it.rating.owner == owner.namespace } ?: return
                if (!prompts.remove(nonce, prompt)) return
                if (intent.action == NO) {
                    clear(app, owner, booking)
                    return
                }
                if (!prompt.rating.fresh() || prompt.deadline <= android.os.SystemClock.elapsedRealtime()) return
                if (locked(app)) {
                    post(app, owner, booking, text(app, "Разблокируй телефон, чтобы оценить", "Баһалау өсөн телефонды ас"),
                        listOf(askAction(app, owner, booking, prompt.rating.until)))
                    return
                }
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (!prompt.rating.fresh() || locked(app) || !OfflineTripSnapshots.current(owner)) return@launch
                        val queued = Outbox.saveRating(app, booking, prompt.rating, owner.generation)
                        if (queued == null) {
                            post(app, owner, booking, text(app, "Не получилось сохранить оценку", "Баһаны һаҡлап булманы"),
                                listOf(askAction(app, owner, booking, prompt.rating.until)))
                            return@launch
                        }
                        postPending(app, owner, queued, sending = true)
                        withTimeoutOrNull(8_000) { Outbox.flushConfirmedRating(app, owner.generation, queued.id) }
                        postPending(app, owner, queued, sending = false)
                    } finally { pending?.finish() }
                }
            }
            CANCEL -> {
                val pending = goAsync()
                val id = intent.getLongExtra("id", 0)
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (Outbox.cancelRating(app, booking, owner.namespace, id, owner.generation)) {
                            clear(app, owner, booking)
                        } else {
                            Outbox.queuedRating(app, id, booking)?.let { stillPending(app, it, owner.generation) }
                        }
                    } finally { pending?.finish() }
                }
            }
        }
    }

    private data class Prompt(val booking: Int, val rating: QueuedRating, val deadline: Long)

    companion object {
        private const val ASK = "rating.ask"
        private const val YES = "rating.confirm"
        private const val NO = "rating.dismiss"
        private const val CANCEL = "rating.cancel"
        private const val PROMPT_TIMEOUT_MS = 60_000L
        private val prompts = ConcurrentHashMap<String, Prompt>()

        init { ApiClient.onSessionReset { prompts.clear() } }

        internal fun locked(context: Context): Boolean =
            context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false

        private fun text(context: Context, ru: String, ba: String) = appTextFor(AppPrefs.language(context), ru, ba)

        internal fun askAction(context: Context, owner: SnapshotOwner, booking: Int, until: Long) =
            button(context, owner, booking, ASK, "5 ★", until = until)

        private fun button(context: Context, owner: SnapshotOwner, booking: Int, action: String, label: String,
                           nonce: String = "", until: Long = 0, actionId: Long = 0): NotificationCompat.Action {
            val intent = Intent(context, RateNotificationAction::class.java).apply {
                this.action = action
                data = Uri.Builder().scheme("yuldash-rating").authority(action).appendPath(owner.namespace)
                    .appendPath(booking.toString()).appendPath(nonce).appendPath(until.toString()).appendPath(actionId.toString()).build()
                putExtra("booking", booking)
                putExtra("owner", owner.namespace)
                putExtra("nonce", nonce)
                putExtra("until", until)
                putExtra("id", actionId)
            }
            val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Action.Builder(0, label, pending).apply {
                if (Build.VERSION.SDK_INT >= 31 && action == YES) setAuthenticationRequired(true)
            }.build()
        }

        private fun openTrip(context: Context, owner: SnapshotOwner, booking: Int): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                data = Uri.Builder().scheme("yuldash-rating").authority("trip").appendPath(owner.namespace)
                    .appendPath(booking.toString()).build()
                putExtra(MainActivity.EXTRA_NAVIGATION_DELIVERY_ID, UUID.randomUUID().toString())
                putExtra("recipient_user_id", owner.userId.toString())
                putExtra(FcmService.EXTRA_PUSH_TYPE, "booking_done")
                putExtra(FcmService.EXTRA_PUSH_ID, booking.toString())
            }
            return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun id(booking: Int) = "booking_done|$booking".hashCode()

        internal fun stillPending(context: Context, action: OutboxAction, generation: Long) {
            val owner = OfflineTripSnapshots.currentOwner()?.takeIf {
                it.generation == generation && it.namespace == QueuedRating.read(action)?.owner
            } ?: return
            postPending(context, owner, action, sending = false)
        }

        private fun postPending(context: Context, owner: SnapshotOwner, action: OutboxAction, sending: Boolean) {
            ApiClient.runIfCurrentSession(owner.generation) {
                if (!OfflineTripSnapshots.current(owner) || Outbox.queuedRating(context, action.id, action.bookingId) == null) {
                    return@runIfCurrentSession
                }
                val notification = if (sending) text(context, "Отправляем…", "Ебәрәбеҙ…")
                    else text(context, "Сохранили. Отправим, когда откроешь приложение со связью",
                        "Һаҡланыҡ. Ҡушымтаны бәйләнеш булғанда асһаң, ебәрербеҙ")
                val actions = if (sending || !Outbox.canCancelRating(context, action.id)) emptyList() else listOf(
                    button(context, owner, action.bookingId, CANCEL, text(context, "Отменить", "Кире алыу"), actionId = action.id))
                post(context, owner, action.bookingId, notification, actions)
            }
        }

        private fun clear(context: Context, owner: SnapshotOwner, booking: Int) {
            ApiClient.runIfCurrentSession(owner.generation) {
                if (OfflineTripSnapshots.current(owner)) {
                    context.getSystemService(NotificationManager::class.java)?.cancel(id(booking))
                }
            }
        }

        private fun post(context: Context, owner: SnapshotOwner, booking: Int, message: String,
                         actions: List<NotificationCompat.Action> = emptyList(), edit: Boolean = false, prompt: Boolean = false) {
            if (!AppPrefs.notifications(context)) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val open = openTrip(context, owner, booking)
            val public = NotificationCompat.Builder(context, FcmService.CHANNEL_DEFAULT)
                .setSmallIcon(R.drawable.ic_stat_notification).setContentTitle("Юлдаш").build()
            val notification = NotificationCompat.Builder(context, FcmService.CHANNEL_DEFAULT)
                .setSmallIcon(R.drawable.ic_stat_notification).setContentTitle("Юлдаш").setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message)).setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
                .setContentIntent(open).setAutoCancel(actions.isEmpty())
            actions.forEach { notification.addAction(it) }
            if (prompt) notification.setTimeoutAfter(PROMPT_TIMEOUT_MS)
            if (edit) notification.addAction(0, text(context, "Изменить", "Үҙгәртергә"), open)
            ApiClient.runIfCurrentSession(owner.generation) {
                if (OfflineTripSnapshots.current(owner)) manager.notify(id(booking), notification.build())
            }
        }

        internal fun result(context: Context, booking: Int, owner: String, generation: Long,
                            result: QueuedActionResult.RatingApplied?) {
            val current = OfflineTripSnapshots.currentOwner()?.takeIf { it.namespace == owner && it.generation == generation } ?: return
            val label = when {
                result == null -> text(context, "Эту поездку оценить уже нельзя", "Был сәфәрҙе хәҙер баһалап булмай")
                result.kept -> text(context, "Оценка уже стоит: ${result.stars} ★", "Баһа ҡуйылған: ${result.stars} ★")
                else -> text(context, "Оценка ${result.stars} ★ принята", "${result.stars} ★ баһа ҡабул ителде")
            }
            post(context, current, booking, label, edit = result != null)
        }
    }
}
