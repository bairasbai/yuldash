package com.yuldash.app

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.DriverBankRecipient
import com.yuldash.app.data.DriverBankRecipientSetup
import com.yuldash.app.data.DriverBankRecipientSetupStore
import com.yuldash.app.data.DriverBankRecipientState
import com.yuldash.app.data.confirmDriverBankRecipient
import com.yuldash.app.data.currentDriverBankRecipientSetupOwner
import com.yuldash.app.data.getDriverBankRecipient
import com.yuldash.app.data.getDriverBankRecipientReceipt
import com.yuldash.app.data.revokeDriverBankRecipient
import com.yuldash.app.data.startDriverBankRecipient
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class RecipientNotice { Ready, LoadError, ActionUnknown, Confirmed, Revoked }

@Composable
internal fun DriverBankRecipientCard(generation: Long) {
    key(generation) { DriverBankRecipientForSession(generation) }
}

@Composable
private fun DriverBankRecipientForSession(generation: Long) {
    val context = LocalContext.current.applicationContext
    val owner = remember(generation) { currentDriverBankRecipientSetupOwner(generation) } ?: return
    val store = remember(owner, context) {
        DriverBankRecipientSetupStore(context.getSharedPreferences("driver_bank_recipient_setup", Context.MODE_PRIVATE), owner)
    }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var recipient by remember { mutableStateOf<DriverBankRecipient?>(null) }
    var setup by remember { mutableStateOf<DriverBankRecipientSetup?>(null) }
    var notice by remember { mutableStateOf(RecipientNotice.Ready) }
    var revokeVersion by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun run(error: RecipientNotice = RecipientNotice.ActionUnknown, work: suspend () -> Unit) {
        if (busy || !owner.isCurrent()) return
        busy = true
        scope.launch {
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (owner.isCurrent()) notice = error
            } finally {
                if (owner.isCurrent()) busy = false
            }
        }
    }

    suspend fun sync(
        fallback: RecipientNotice = RecipientNotice.Ready,
        rejectedSetup: Boolean = false,
        confirmedVersion: String? = null,
        revokedVersion: String? = null,
    ) {
        if (!owner.isCurrent()) return
        val stored = withContext(Dispatchers.IO) { store.read() }
        var missing = rejectedSetup
        val receipt = stored?.let {
            ApiClient.getDriverBankRecipientReceipt(it, generation).getOrElse { error ->
                if ((error as? ApiException)?.status != 404) throw error
                missing = true
                null
            }
        }
        val current = ApiClient.getDriverBankRecipient(generation).getOrThrow()
        if (!owner.isCurrent()) return
        val pending = stored?.takeIf {
            !missing && receipt == null && it.version == current.version && it.expiresAt.isAfter(Instant.now())
        }
        if (stored != null && pending == null) withContext(Dispatchers.IO) { store.clear(stored) }
        if (!owner.isCurrent()) return
        recipient = current
        setup = pending
        val confirmed = receipt?.version ?: confirmedVersion
        notice = when {
            confirmed != null && confirmed == current.version -> RecipientNotice.Confirmed
            revokedVersion != null && revokedVersion == current.version && !current.canRevoke -> RecipientNotice.Revoked
            else -> fallback
        }
    }

    fun refresh() = run(RecipientNotice.LoadError) { sync() }

    DisposableEffect(lifecycle, owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun begin() {
        val current = recipient?.takeUnless { it.state == DriverBankRecipientState.Disabled } ?: return
        run {
            notice = RecipientNotice.Ready
            val next = ApiClient.startDriverBankRecipient(current.version, generation).getOrThrow()
            check(next.version == current.version)
            check(withContext(Dispatchers.IO) { store.keep(next) })
            if (owner.isCurrent()) setup = next
        }
    }

    fun confirm() {
        val candidate = setup ?: return
        if (recipient?.state == DriverBankRecipientState.Disabled) return
        run {
            val saved = withContext(Dispatchers.IO) { store.read() }
            if (saved?.id != candidate.id || !saved.expiresAt.isAfter(Instant.now())) {
                sync()
                return@run
            }
            check(withContext(Dispatchers.IO) { store.keep(saved) })
            val confirmed = ApiClient.confirmDriverBankRecipient(saved, generation)
            val error = confirmed.exceptionOrNull()
            if (error != null) {
                if (error is CancellationException) throw error
                val code = (error as? ApiException)?.status
                sync(RecipientNotice.ActionUnknown, rejectedSetup = code == 404 || code == 409)
            } else {
                sync(confirmedVersion = confirmed.getOrThrow().version)
            }
        }
    }

    fun revoke() {
        val version = revokeVersion?.takeIf { it == recipient?.version } ?: return
        revokeVersion = null
        run {
            val result = ApiClient.revokeDriverBankRecipient(version, generation)
            val error = result.exceptionOrNull()
            if (error is CancellationException) throw error
            sync(
                fallback = if (error == null) RecipientNotice.Ready else RecipientNotice.ActionUnknown,
                revokedVersion = result.getOrNull()?.version,
            )
        }
    }

    val current = recipient
    val failed = notice == RecipientNotice.LoadError || notice == RecipientNotice.ActionUnknown
    val enabled = current != null && current.state != DriverBankRecipientState.Disabled
    val hidden = current?.state == DriverBankRecipientState.Disabled && !current.canRevoke &&
        setup == null && notice == RecipientNotice.Ready
    AnimatedVisibility(!hidden) {
        AppCard {
            Column(Modifier.padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                Text(appText("Реквизит для выплат", "Түләүҙәр өсөн реквизит"), style = CanonHeading, color = CanonText)
                if (busy && current == null) SkeletonCard(lines = 2)
                if (failed) {
                    Text(if (notice == RecipientNotice.LoadError)
                        appText("Не удалось загрузить реквизит.", "Реквизитты йөкләп булманы.")
                    else appText("Не получили подтверждение. Проверь статус.", "Раҫлау килмәне. Торошон тикшер."),
                        style = CanonCaption, color = CanonMuted)
                    AppButton(appText("Проверить статус", "Торошон тикшерергә"), onClick = ::refresh,
                        enabled = !busy, loading = busy, style = AppButtonStyle.Secondary)
                }
                when (current?.state) {
                    DriverBankRecipientState.Configured -> Text(appText("Реквизит подключён.", "Реквизит тоташҡан."),
                        style = CanonCaption, color = CanonMuted)
                    DriverBankRecipientState.RequiresReconnect -> Text(
                        appText("Подключи реквизит заново.", "Реквизитты яңынан тоташтыр."), style = CanonCaption, color = CanonMuted,
                    )
                    DriverBankRecipientState.Disabled -> if (current.canRevoke || setup != null) Text(
                        appText("Подключение на паузе.", "Тоташтырыу туҡтатылған."), style = CanonCaption, color = CanonMuted,
                    )
                    else -> Unit
                }
                if (notice == RecipientNotice.Confirmed) Text(appText("Подключение подтверждено.", "Тоташтырыу раҫланды."),
                    style = CanonCaption, color = CanonMuted)
                if (notice == RecipientNotice.Revoked) Text(appText("Реквизит отключён.", "Реквизит һүндерелде."),
                    style = CanonCaption, color = CanonMuted)
                if (current != null && enabled && revokeVersion == null) {
                    if (setup == null) {
                        AppButton(if (current.canRevoke) appText("Заменить тестовый реквизит", "Һынау реквизитын алмаштырырға")
                        else appText("Подключить тестовый реквизит", "Һынау реквизитын тоташтырырға"),
                            onClick = ::begin, enabled = !busy && !failed, loading = busy)
                    } else {
                        Text(appText("Подтверди использование этого тестового реквизита для выплат попутки.",
                            "Попутка түләүҙәре өсөн ошо һынау реквизитын ҡулланыуҙы раҫла."), style = CanonCaption, color = CanonMuted)
                        AppButton(appText("Подтвердить реквизит", "Реквизитты раҫларға"), onClick = ::confirm,
                            enabled = !busy && !failed, loading = busy)
                    }
                }
                if (current?.canRevoke == true && current.version != null && revokeVersion == null) {
                    AppButton(appText("Отключить реквизит", "Реквизитты һүндерергә"),
                        onClick = { revokeVersion = current.version }, enabled = !busy && !failed, style = AppButtonStyle.Secondary)
                }
                if (revokeVersion != null) {
                    Text(appText("Отключить текущий реквизит для будущих выплат?",
                        "Киләһе түләүҙәр өсөн әлеге реквизитты һүндерергәме?"), style = CanonCaption, color = CanonMuted)
                    AppButton(appText("Подтвердить отключение", "Һүндереүҙе раҫларға"), onClick = ::revoke,
                        enabled = !busy && !failed && revokeVersion == current?.version)
                    AppButton(appText("Назад", "Артҡа"), onClick = { revokeVersion = null }, style = AppButtonStyle.Secondary)
                }
            }
        }
    }
}
