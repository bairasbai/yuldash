package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.OwnerSwitchDto
import com.yuldash.app.data.OwnerSwitchKey
import com.yuldash.app.data.PAID_INTEGRATIONS_PAUSED
import com.yuldash.app.data.ownerSwitchAffected
import com.yuldash.app.data.ownerSwitches
import com.yuldash.app.data.setOwnerSwitch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.launch

private sealed interface OwnerSwitchLoad {
    data object Loading : OwnerSwitchLoad
    data class Error(val message: String?) : OwnerSwitchLoad
    data class Ready(val rows: Map<OwnerSwitchKey, OwnerSwitchDto>) : OwnerSwitchLoad
}

private data class OwnerSwitchConfirmation(val key: OwnerSwitchKey, val affected: Long, val revision: Long?)

@Composable
internal fun AdminSwitchesScreen(onBack: () -> Unit) {
    val generation by ApiClient.sessionChanges.collectAsState()
    key(generation) {
        OwnerSwitchesForSession(generation, onBack)
    }
}

@Composable
private fun OwnerSwitchesForSession(generation: Long, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val saveFallback = appText("Не удалось сохранить. Проверяем текущее состояние…",
        "Һаҡлап булманы. Әлеге торошто тикшерәбеҙ…")
    var state by remember { mutableStateOf<OwnerSwitchLoad>(OwnerSwitchLoad.Loading) }
    var reload by remember { mutableStateOf(0) }
    var saving by remember { mutableStateOf<OwnerSwitchKey?>(null) }
    var confirmation by remember { mutableStateOf<OwnerSwitchConfirmation?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reload) {
        state = OwnerSwitchLoad.Loading
        ApiClient.ownerSwitches(generation)
            .onSuccess { state = OwnerSwitchLoad.Ready(it) }
            .onFailure { state = OwnerSwitchLoad.Error((it as? ApiException)?.message) }
    }

    fun save(method: OwnerSwitchKey, value: Boolean, confirmed: Boolean = false,
             revision: Long? = (state as? OwnerSwitchLoad.Ready)?.rows?.get(method)?.revision) {
        if (saving != null || (method == OwnerSwitchKey.RideshareCard && value)) return
        saving = method
        confirmation = null
        saveError = null
        scope.launch {
            try {
                ApiClient.setOwnerSwitch(method, value, confirmed, generation, revision)
                    .onSuccess { next ->
                        if (method == OwnerSwitchKey.PaidIntegrations) {
                            state = OwnerSwitchLoad.Loading
                            reload += 1
                        } else {
                            val current = state as? OwnerSwitchLoad.Ready
                            if (current != null) state = current.copy(rows = current.rows + (method to next))
                        }
                    }
                    .onFailure { error ->
                        val count = ownerSwitchAffected(error)
                        val canAffectLogin = method == OwnerSwitchKey.Sms || method == OwnerSwitchKey.PaidIntegrations
                        if (canAffectLogin && !value && !confirmed && count != null) {
                            confirmation = OwnerSwitchConfirmation(method, count, revision)
                        } else {
                            saveError = (error as? ApiException)?.message ?: saveFallback
                            reload += 1
                        }
                    }
            } finally {
                saving = null
            }
        }
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Выключатели", "Күсергестәр"), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(CanonSpace.lg),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
        ) {
            saveError?.let { message ->
                item { Text(message, style = CanonCaption, color = CanonRed) }
            }
            when (val current = state) {
                OwnerSwitchLoad.Loading -> item { SkeletonCard(lines = 2) }
                is OwnerSwitchLoad.Error -> item {
                    ListedError(
                        current.message ?: appText("Не удалось загрузить настройки. Проверь интернет.",
                            "Көйләүҙәрҙе йөкләп булманы. Интернетты тикшер."),
                        onRetry = { reload += 1 },
                    )
                }
                is OwnerSwitchLoad.Ready -> {
                    if (current.rows.isEmpty()) item {
                        ListedEmpty(appText("Настроек пока нет", "Көйләүҙәр әлегә юҡ"),
                            appText("Попробуй обновить список.", "Исемлекте яңыртып ҡара."))
                        TextButton(onClick = { reload += 1 }) { Text(appText("Обновить", "Яңыртыу")) }
                    }
                    items(current.rows.entries.toList(), key = { it.key.key }) { (method, row) ->
                        OwnerSwitchCard(
                            method, row, enabled = saving == null && confirmation == null,
                            saving = saving == method, onChange = { save(method, it) },
                        )
                    }
                }
            }
        }
    }

    confirmation?.let { pending ->
        val pause = pending.key == OwnerSwitchKey.PaidIntegrations
        val count = pending.affected
        AlertDialog(
            onDismissRequest = { confirmation = null },
            containerColor = CanonSurface,
            shape = CanonCardShape,
            title = {
                Text(if (pause) appText("Приостановить платные подключения?", "Түләүле хеҙмәттәрҙе туҡтатып торорғамы?")
                    else appText("Отключить SMS?", "SMS-ты һүндерергәме?"), style = CanonTitle, color = CanonText)
            },
            text = {
                Text(appText("Без способа входа останутся аккаунты пользователей — $count.",
                    "Инеү ысулы ҡалмаясаҡ ҡулланыусы аккаунттары — $count."),
                    style = CanonCaption, color = CanonMuted)
            },
            dismissButton = {
                TextButton(onClick = { confirmation = null }) { Text(appText("Отмена", "Кире алыу"), color = CanonGreen2) }
            },
            confirmButton = {
                TextButton(onClick = { save(pending.key, false, confirmed = true, revision = pending.revision) }) {
                    Text(if (pause) appText("Приостановить", "Туҡтатып тороу")
                        else appText("Отключить SMS", "SMS-ты һүндереү"), color = CanonRed)
                }
            },
        )
    }
}

@Composable
private fun OwnerSwitchCard(
    method: OwnerSwitchKey,
    row: OwnerSwitchDto,
    enabled: Boolean,
    saving: Boolean,
    onChange: (Boolean) -> Unit,
) {
    var historyVisible by remember { mutableStateOf(false) }
    val name = when (method) {
        OwnerSwitchKey.PaidIntegrations -> appText("Платные подключения", "Түләүле хеҙмәттәр")
        OwnerSwitchKey.SmsNotices -> appText("SMS-уведомления", "SMS-хәбәрҙәр")
        OwnerSwitchKey.RideshareCard -> appText("Оплата картой — подготовка", "Карта менән түләү — әҙерлек")
        OwnerSwitchKey.Sms -> "SMS"
        OwnerSwitchKey.Telegram -> "Telegram"
        OwnerSwitchKey.Vk -> "VK ID"
        OwnerSwitchKey.WhatsApp -> "WhatsApp"
    }
    val card = method == OwnerSwitchKey.RideshareCard
    val effective = !card && row.effective
    val wanted = row.stored ?: effective
    val locked = method == OwnerSwitchKey.Telegram
    val needsRevision = card || method.key.startsWith("external.")
    val revisionAvailable = !needsRevision || row.revision != null
    val canEnable = !card && row.blockers.all { it == PAID_INTEGRATIONS_PAUSED }
    val status = if (!card && row.blockers.isEmpty()) ownerSwitchLabel(effective) else appText("Сейчас недоступно", "Әле эшләмәй")
    val stored = when {
        row.stored == null -> appText("По умолчанию", "Ғәҙәттә")
        row.stored != effective -> appText("Сохранено", "Һаҡланған") + " — " + ownerSwitchLabel(wanted)
        else -> null
    }
    val locale = if (LocalAppLanguage.current == AppLanguage.Ba) Locale.forLanguageTag("ba-RU") else Locale.forLanguageTag("ru-RU")
    val dateFormat = remember(locale) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale) }
    fun dateText(value: String) = runCatching {
        dateFormat.format(OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()))
    }.getOrDefault(value)

    AppCard(shape = CanonItemShape) {
        Column(Modifier.padding(CanonSpace.md), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
                Column(Modifier.weight(1f)) {
                    Text(name, style = CanonBodyStrong, color = CanonText)
                    Text(listOfNotNull(status, stored).joinToString(" · "), style = CanonCaption, color = CanonMuted)
                }
                Switch(
                    checked = wanted,
                    onCheckedChange = onChange,
                    enabled = enabled && !locked && row.editable && revisionAvailable && (wanted || canEnable),
                    modifier = Modifier.semantics { contentDescription = name },
                    colors = SwitchDefaults.colors(checkedTrackColor = CanonGreen2, checkedThumbColor = CanonOnFilled),
                )
            }
            if (saving) Text(appText("Сохраняем…", "Һаҡлайбыҙ…"), style = CanonCaption, color = CanonMuted)
            if (locked) Text(appText("Нужен для входа администратора.", "Администратор инеү өсөн кәрәк."),
                style = CanonCaption, color = CanonMuted)
            if (method == OwnerSwitchKey.PaidIntegrations) Text(
                appText("Ставит на паузу платные SMS. Настройки сохраняются.",
                    "Түләүле SMS-тарҙы туҡтатып тора. Көйләүҙәр һаҡлана."),
                style = CanonCaption, color = CanonMuted,
            )
            if (!row.editable) Text(appText("Эту настройку меняет владелец.", "Был көйләүҙе хужа үҙгәртә."),
                style = CanonCaption, color = CanonMuted)
            row.blockers.forEach { code ->
                val label = row.localizedBlockers[code]
                Text(if (label == null) ownerSwitchBlocker(code) else appText(label.ru, label.ba), style = CanonCaption, color = CanonMuted)
            }
            row.inFlight?.takeIf { it > 0 }?.let { count ->
                Text(appText("SMS в процессе отправки — $count", "Ебәрелеп ятҡан SMS — $count"),
                    style = CanonCaption, color = CanonMuted)
            }
            row.updatedAt?.let {
                Text(listOfNotNull(dateText(it), row.updatedBy).joinToString(" · "), style = CanonCaption, color = CanonMuted)
            }
            if (row.history.isNotEmpty()) {
                TextButton(onClick = { historyVisible = !historyVisible }) {
                    Text(appText("История изменений", "Үҙгәрештәр тарихы"), color = CanonGreen2)
                }
                AnimatedVisibility(visible = historyVisible) {
                    Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                        row.history.forEach { entry ->
                            Text(listOfNotNull(ownerSwitchLabel(entry.value), dateText(entry.createdAt),
                                entry.author, entry.note).joinToString(" · "), style = CanonCaption, color = CanonMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ownerSwitchLabel(value: Boolean) =
    if (value) appText("Включено", "Ҡабыҙылған") else appText("Выключено", "Һүндерелгән")

@Composable
private fun ownerSwitchBlocker(code: String) = when (code) {
    "SMS_PROVIDER" -> appText("SMS-провайдер не настроен", "SMS-провайдер көйләнмәгән")
    "SMS_RU_API_ID" -> appText("Не задан ключ SMS.ru", "SMS.ru асҡысы ҡуйылмаған")
    "SMS_FROM" -> appText("Не задано имя отправителя SMS", "SMS ебәреүсенең исеме ҡуйылмаған")
    "SMS_BUDGET_HMAC_KEY" -> appText("Не настроен ключ учёта лимитов SMS", "SMS лимиттарын иҫәпләү асҡысы көйләнмәгән")
    "SMS_NOTIFY_ENABLED" -> appText("SMS-уведомления ещё не согласованы", "SMS-хәбәрҙәр әле килешелмәгән")
    "SMSDAR_SENDER" -> appText("Не задано имя отправителя SMSDAR", "SMSDAR ебәреүсеһенең исеме ҡуйылмаған")
    PAID_INTEGRATIONS_PAUSED -> appText("Платные подключения на паузе", "Түләүле хеҙмәттәр туҡтатылып тора")
    "smsru_confirmation_pending" -> appText("Подключение SMS.ru ещё не подтверждено", "SMS.ru-ға тоташыу әле раҫланмаған")
    "SMSDAR_ID" -> appText("Не задан идентификатор SMSDAR", "SMSDAR идентификаторы ҡуйылмаған")
    "SMSDAR_PASSWORD" -> appText("Не задан пароль SMSDAR", "SMSDAR серһүҙе ҡуйылмаған")
    "TELEGRAM_BOT_TOKEN" -> appText("Не задан ключ бота Telegram", "Telegram ботының асҡысы ҡуйылмаған")
    "TELEGRAM_WEBHOOK_SECRET" -> appText("Не настроена проверка сообщений от бота", "Бот хәбәрҙәрен тикшереү көйләнмәгән")
    "sms_atomic_budget_pending" -> appText("Лимит платных SMS ещё не готов", "Түләүле SMS лимиты әле әҙер түгел")
    "vk_id_integration_pending" -> appText("Вход через VK ID ещё не подключён", "VK ID аша инеү әле тоташтырылмаған")
    "whatsapp_integration_pending" -> appText("Вход через WhatsApp ещё не подключён", "WhatsApp аша инеү әле тоташтырылмаған")
    else -> appText("Подключение ещё не готово.", "Тоташтырыу әле әҙер түгел.")
}
