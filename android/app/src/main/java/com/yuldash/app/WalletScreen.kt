package com.yuldash.app

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ApiException
import com.yuldash.app.data.PayoutStatusDto
import com.yuldash.app.data.WalletBalanceDto
import com.yuldash.app.data.WalletLedgerEntryDto
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * «Кошелёк» — баланс водителя + история операций (ledger).
 * Крупная карточка баланса (₽) сверху; ниже — список операций: дата, назначение (note),
 * сумма со знаком и цветом (приход зелёным, списание — приглушённым).
 * Приватность: сервер всегда отдаёт только свои записи (по токену).
 * Состояния: загрузка (скелетоны) / ошибка (повтор) / пусто (дружелюбная заглушка).
 */
@Composable
internal fun WalletScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    // Обновление не удалось, но цифры на экране уже есть — это НЕ то же самое, что ошибка
    // на пустом экране, и молчанием быть не должно (см. AppStaleStrip).
    var stale by remember { mutableStateOf(false) }
    var balance by remember { mutableStateOf<WalletBalanceDto?>(null) }
    var ledger by remember { mutableStateOf<List<WalletLedgerEntryDto>>(emptyList()) }
    // Вывод на карту (Модель Б, за флагом): статус грузим отдельно — его сбой не роняет весь кошелёк.
    var payout by remember { mutableStateOf<PayoutStatusDto?>(null) }
    var payoutLoading by remember { mutableStateOf(true) }
    var payoutError by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true; error = false
        payoutLoading = true; payoutError = false
        val balRes = ApiClient.getWalletBalance()
        val ledRes = ApiClient.getWalletLedger(LEDGER_LIMIT)
        // Баланс — обязателен для «шапки»; если и он, и история упали → это ошибка. Иначе показываем что есть.
        balRes.onSuccess { balance = it }
        ledRes.onSuccess { ledger = it }
        error = balRes.isFailure && ledRes.isFailure
        // Жест «потянуть вниз» отрабатывал вхолостую: индикатор крутился, пропадал, баланс не
        // менялся — и водитель читал старую цифру как свежую («начисление не пришло»).
        // Ошибку показывали только при пустой истории, то есть только новичку.
        stale = (balRes.isFailure || ledRes.isFailure) && (balance != null || ledger.isNotEmpty())
        loading = false
        ApiClient.getPayoutStatus()
            .onSuccess { payout = it; payoutError = false }
            .onFailure { if (payout == null) payoutError = true }
        payoutLoading = false
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Кошелёк", "Янсыҡ"), onBack) },
    ) { padding ->
        // Потянуть вниз = обновить. Баланс и история грузились ровно один раз за вход на экран:
        // вернулся из фона через час — цифры старые, и понять это было нельзя.
        AppPullRefresh(
            refreshing = loading && balance != null,
            onRefresh = { scope.launch { load() } },
            modifier = Modifier.padding(padding),
        ) {
        LazyColumn(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            if (stale) item(key = "stale") { MoneyStaleStrip(onRetry = { scope.launch { load() } }) }

            // Подпись под балансом зависит от того, включены ли выплаты. В Модели А деньги идут
            // мимо платформы, и обещание «доступно к выводу» под нулевым балансом читалось как
            // «мои деньги куда-то делись» (аудит 2026-07-26).
            item { WalletBalanceCard(balance, loading, payoutEnabled = payout?.enabled) }

            // Вывод на карту (Модель Б). enabled решает СЕРВЕР: false → честная заглушка «скоро»,
            // true → карта + сумма + «Вывести». Клиент оживёт сам, когда включат флаг — без обновления.
            item {
                when {
                    payoutLoading && payout == null -> SkeletonCard(lines = 2)
                    payoutError && payout == null -> AppErrorState(
                        onRetry = { scope.launch { load() } },
                        title = appText("Не узнали про выплаты", "Түләүҙәр тураһында белә алманыҡ"),
                        text = appText("Проверь интернет и повтори.", "Интернетты тикшереп ҡабатла."),
                    )
                    payout?.enabled == false -> PayoutSoonCard()
                    payout != null -> PayoutCard(
                        status = payout!!,
                        onStatusChange = { payout = it },
                        onPaidOut = { scope.launch { load() } },
                    )
                }
            }

            item {
                SectionHeader(
                    appText("История операций", "Операциялар тарихы"),
                    appText("Начисления за поездки и комиссии сервиса.", "Сәфәрҙәр өсөн килем һәм сервис комиссияһы."),
                )
            }

            when {
                loading && ledger.isEmpty() -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(4) { SkeletonCard(lines = 2) } }
                }
                // `&& !stale` — про сбой уже сказала полоска сверху; повторять то же самое
                // второй раз на одном экране незачем.
                error && ledger.isEmpty() && !stale -> item { AppErrorState(onRetry = { scope.launch { load() } }) }
                ledger.isEmpty() -> item {
                    AppEmptyState(
                        title = appText("Пока операций нет", "Операциялар әлегә юҡ"),
                        text = appText(
                            "Как появятся начисления за поездки — покажем их здесь.",
                            "Сәфәрҙәр өсөн килем булһа — бында күрһәтәбеҙ.",
                        ),
                        icon = Icons.Default.ReceiptLong,
                    )
                }
                else -> items(ledger, key = { it.id }) { entry -> WalletLedgerRow(entry) }
            }

            // Список упёрся в лимит запроса. Молчать тут нельзя: водитель, у которого операций
            // больше, видел ровный обрыв на полуслове и считал, что старые начисления пропали.
            if (ledger.size >= LEDGER_LIMIT) {
                item {
                    Text(
                        appText(
                            "Показаны последние $LEDGER_LIMIT операций.",
                            "Һуңғы $LEDGER_LIMIT операция күрһәтелде.",
                        ),
                        color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        }
    }
}

/** Сколько записей истории тянем за раз. Ровно столько же показываем в подписи под списком. */
private const val LEDGER_LIMIT = 50

/** Крупная карточка баланса. Фиксированный ink-зелёный градиент (белый текст читаем в обеих темах). */
@Composable
private fun WalletBalanceCard(balance: WalletBalanceDto?, loading: Boolean, payoutEnabled: Boolean? = null) {
    Card(
        modifier = Modifier.fillMaxWidth().appearIn(0),
        shape = CanonCardShape,
        colors = CardDefaults.cardColors(containerColor = CanonGreenInk),
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.raised),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(CanonGreenInk, CanonGreenInkDark)))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonOnAccent.copy(alpha = 0.18f), shape = CircleShape) {
                    Icon(
                        Icons.Default.AccountBalanceWallet, contentDescription = null,
                        tint = CanonOnAccent, modifier = Modifier.padding(12.dp).size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    appText("Баланс кошелька", "Янсыҡ балансы"),
                    color = CanonOnAccent.copy(alpha = 0.9f), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
            }
            Text(
                // Копейки показываем: баланс приходит в копейках, а поле balanceRub — это kop//100
                // с сервера. По нему 1 250,50 ₽ выглядели как «1 250 ₽», и сумма операций ниже
                // не сходилась с балансом наверху. Деньги округлять вниз нельзя (MoneyFormatTest).
                if (loading && balance == null) "…" else kopToRub(balance?.balanceKop ?: 0),
                color = CanonOnAccent, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                when (payoutEnabled) {
                    true -> appText("Доступно к выводу через СБП", "СБП аша сығарырға мөмкин")
                    // Выплаты выключены (Модель А): за поездки платят напрямую тебе, здесь —
                    // только бонусы и возвраты. Так честнее, чем обещать вывод, которого нет.
                    false -> appText(
                        "Здесь бонусы и возвраты. За поездки платят тебе напрямую.",
                        "Бында бонустар һәм ҡайтарыуҙар. Сәфәрҙәр өсөн һиңә туранан-тура түләйҙәр.",
                    )
                    else -> appText("Бонусы и возвраты", "Бонустар һәм ҡайтарыуҙар")
                },
                color = CanonOnAccent.copy(alpha = 0.82f), fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
    }
}

/** Строка истории: иконка направления, назначение + дата, сумма со знаком и цветом. */
@Composable
private fun WalletLedgerRow(e: WalletLedgerEntryDto) {
    // Направление: приход (amount ≥ 0) — зелёный «получено»; списание/комиссия (< 0) — приглушённый.
    val income = e.amountKop >= 0
    val amountColor = if (income) CanonGreen2 else CanonMutedStrong
    // kopToRub сам ставит «−» у отрицательных и сохраняет копейки: комиссия 8% от 137 ₽ —
    // это 10,96 ₽, а не «10 ₽». По округлённым строкам история не сходилась с балансом.
    val amountText = if (income) "+" + kopToRub(e.amountKop) else kopToRub(e.amountKop)
    val fallbackNote = ledgerKindLabel(e.kind)   // @Composable — считаем до ifBlank (в лямбду звать нельзя)
    val noteText = e.note.ifBlank { fallbackNote }
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(color = if (income) CanonMint else CanonBg, shape = CircleShape) {
                Icon(
                    if (income) Icons.Default.CallReceived else Icons.Default.CallMade,
                    contentDescription = if (income) appText("Приход", "Килем") else appText("Списание", "Сығым"),
                    tint = if (income) CanonGreen2 else CanonMutedStrong,
                    modifier = Modifier.padding(8.dp).size(20.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(noteText, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 23.sp)
                Text(formatDepart(e.createdAt), color = CanonMuted, fontSize = 12.sp)
            }
            Text(
                amountText,
                color = amountColor, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            )
        }
    }
}

// ─────────────────────────── Вывод на карту (Модель Б, за флагом) ───────────────────────────

/** Честная заглушка, пока выплаты выключены на сервере (enabled=false). Без кнопок-обманок. */
@Composable
private fun PayoutSoonCard() {
    Surface(color = CanonSurface, shape = CanonCardShape, border = BorderStroke(1.dp, CanonBorder)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(color = CanonWarnBg, shape = CircleShape) {
                Icon(
                    Icons.Default.Schedule, contentDescription = null,
                    tint = CanonWarn, modifier = Modifier.padding(8.dp).size(20.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    appText("Выплаты на карту — скоро", "Картаға түләүҙәр — оҙаҡламай"),
                    color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                )
                Text(
                    appText(
                        "Готовим вывод на карту. Пока комиссия и расчёты работают как раньше.",
                        "Картаға сығарыуҙы әҙерләйбеҙ. Әлегә комиссия һәм иҫәпләшеүҙәр элеккесә эшләй.",
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }
        }
    }
}

/**
 * Живая секция вывода: карта ····last4 (или «Добавить карту»), сумма с границами сервера,
 * «Вывести» с двойным подтверждением. Идемпотентность: UUID-ключ генерится ОДИН раз на попытку
 * и переиспользуется при ретрае той же суммы — двойной тап/обрыв сети не спишет баланс дважды.
 */
@Composable
private fun PayoutCard(
    status: PayoutStatusDto,
    onStatusChange: (PayoutStatusDto) -> Unit,
    onPaidOut: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var amountText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }
    var showCardDialog by remember { mutableStateOf(false) }
    // Ключ идемпотентности текущей попытки. Сбрасывается при смене суммы и после успеха.
    var idemKey by remember { mutableStateOf<String?>(null) }

    val minRub = status.minKop / 100
    val maxRub = status.maxKop / 100
    // Границы вывода сервер держит круглыми, а вот баланс — нет: его показываем с копейками,
    // иначе «на балансе 10 ₽» под реальными 10,96 ₽ читается как «часть денег пропала».
    val balanceLabel = kopToRub(status.balanceKop)
    val amountRub = amountText.toIntOrNull() ?: 0
    val amountKop = amountRub * 100

    val amountError = when {
        amountText.isBlank() -> null
        amountKop < status.minKop -> appText("Минимум ${fmtRub(minRub)} ₽", "Кәм тигәндә ${fmtRub(minRub)} ₽")
        amountKop > status.maxKop -> appText("Максимум ${fmtRub(maxRub)} ₽ за раз", "Бер юлы иң күбе ${fmtRub(maxRub)} ₽")
        amountKop > status.balanceKop -> appText("На балансе только $balanceLabel", "Баланста $balanceLabel ғына")
        else -> null
    }
    val canPayout = status.hasRequisite && amountText.isNotBlank() && amountError == null && !busy

    val okMsg = appText("Выплата отправлена 💚", "Түләү ебәрелде 💚")
    val netErrMsg = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")

    Surface(color = CanonSurface, shape = CanonCardShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = CanonMint, shape = CircleShape) {
                    Icon(
                        Icons.Default.CreditCard, contentDescription = null,
                        tint = CanonGreen2, modifier = Modifier.padding(8.dp).size(20.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        appText("Вывод на карту", "Картаға сығарыу"),
                        color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    )
                    Text(
                        if (status.hasRequisite) appText("Карта ····${status.cardLast4}", "Карта ····${status.cardLast4}")
                        else appText("Карта пока не добавлена", "Карта әлегә өҫтәлмәгән"),
                        color = CanonMuted, fontSize = 14.sp,
                    )
                }
                if (status.hasRequisite) {
                    TextButton(onClick = { if (!busy) showCardDialog = true }) {
                        Text(appText("Изменить", "Үҙгәртеү"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
            if (!status.hasRequisite) {
                AppButton(
                    text = appText("Добавить карту", "Карта өҫтәү"),
                    onClick = { showCardDialog = true },
                    style = AppButtonStyle.Secondary,
                    icon = Icons.Default.CreditCard,
                )
            } else {
                OutlinedTextField(
                    value = amountText,
                    // Смена суммы = новая попытка → новый ключ идемпотентности (сгенерится при «Вывести»).
                    onValueChange = { v -> amountText = v.filter { it.isDigit() }.take(6); idemKey = null },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(appText("Сумма, ₽", "Сумма, ₽")) },
                    supportingText = {
                        Text(
                            amountError ?: appText(
                                "От ${fmtRub(minRub)} до ${fmtRub(maxRub)} ₽ · на балансе $balanceLabel",
                                "${fmtRub(minRub)} — ${fmtRub(maxRub)} ₽ · баланста $balanceLabel",
                            ),
                            color = if (amountError != null) CanonRed else CanonMuted, fontSize = 12.sp,
                        )
                    },
                    isError = amountError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    shape = CanonItemShape,
                    trailingIcon = {
                        // «Всё» — подставить весь доступный баланс (обрезаем по максимуму за раз).
                        if (status.balanceKop >= status.minKop) {
                            TextButton(onClick = {
                                amountText = (minOf(status.balanceKop, status.maxKop) / 100).toString()
                                idemKey = null
                            }) {
                                Text(appText("Всё", "Барыһы"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    },
                )
                AppButton(
                    text = if (amountRub > 0) appText("Вывести ${fmtRub(amountRub)} ₽", "${fmtRub(amountRub)} ₽ сығарыу")
                    else appText("Вывести", "Сығарыу"),
                    onClick = {
                        if (busy) return@AppButton
                        // ОДИН ключ на попытку: если прошлая попытка этой же суммы сорвалась — переиспользуем.
                        if (idemKey == null) idemKey = java.util.UUID.randomUUID().toString()
                        showConfirm = true
                    },
                    enabled = canPayout,
                    loading = busy,
                )
            }
        }
    }

    // Двойное подтверждение суммы перед списанием.
    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { if (!busy) showConfirm = false },
            containerColor = CanonSurface,
            title = {
                Text(
                    appText("Вывести ${fmtRub(amountRub)} ₽?", "${fmtRub(amountRub)} ₽ сығарырғамы?"),
                    color = CanonText, fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    appText(
                        "Деньги уйдут на карту ····${status.cardLast4}. Обычно приходят быстро.",
                        "Аҡса ····${status.cardLast4} картаһына китә. Ғәҙәттә тиҙ килә.",
                    ),
                    color = CanonMuted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (busy) return@TextButton
                    val key = idemKey ?: java.util.UUID.randomUUID().toString().also { idemKey = it }
                    busy = true
                    scope.launch {
                        ApiClient.requestPayout(amountKop, key)
                            .onSuccess {
                                busy = false; showConfirm = false
                                idemKey = null; amountText = ""
                                Toast.makeText(context, okMsg, Toast.LENGTH_SHORT).show()
                                onPaidOut()
                            }
                            .onFailure { e ->
                                busy = false; showConfirm = false
                                // Сервер ОТВЕТИЛ — значит попытка закончилась, и ключ надо менять.
                                // Раньше мы держали его всегда, и после отказа банка человек бился
                                // в один и тот же ключ: сервер помнил его как проведённый и отвечал
                                // «уже выплачено», приложение показывало «Готово», а денег не было
                                // ни разу (волна 219).
                                //
                                // Два исключения, и оба про «деньги могли уйти»:
                                //  • provider_unclear — банк не ответил внятно, выплата, возможно,
                                //    в пути. Новый ключ отправил бы её ВТОРОЙ раз;
                                //  • сеть промолчала (не ApiException) — то же самое, ответа нет.
                                val код = (e as? ApiException)?.detailCode.orEmpty()
                                if (e is ApiException && код != "provider_unclear") idemKey = null
                                val msg = if (e is ApiException) (e.message ?: netErrMsg) else netErrMsg
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }
                    }
                }) {
                    Text(
                        if (busy) appText("Отправляем…", "Ебәрәбеҙ…") else appText("Да, вывести", "Эйе, сығарырға"),
                        color = CanonGreen2, fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!busy) showConfirm = false }) {
                    Text(appText("Отмена", "Кире алыу"), color = CanonMuted)
                }
            },
        )
    }

    if (showCardDialog) {
        PayoutCardDialog(
            onDismiss = { showCardDialog = false },
            onSaved = { last4 ->
                showCardDialog = false
                onStatusChange(status.copy(hasRequisite = true, cardLast4 = last4))
            },
        )
    }
}

/**
 * Диалог «Карта для выплат». ПРИВАТНОСТЬ: полный номер живёт только в state этого диалога —
 * НЕ логируется и НЕ уходит на сервер: отправляем только последние 4 цифры (считаем локально).
 */
@Composable
private fun PayoutCardDialog(onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var cardText by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val digits = cardText.filter { it.isDigit() }
    val valid = digits.length in 12..19

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = CanonSurface,
        title = {
            Text(appText("Карта для выплат", "Түләүҙәр өсөн карта"), color = CanonText, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = cardText,
                    onValueChange = { v -> cardText = v.filter { it.isDigit() || it == ' ' }.take(23); failed = false },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(appText("Номер карты", "Карта номеры")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = failed,
                    shape = CanonItemShape,
                )
                Text(
                    appText(
                        "Мы сохраним только последние 4 цифры — полный номер не покидает телефон.",
                        "Беҙ һуңғы 4 һанды ғына һаҡлайбыҙ — тулы номер телефондан китмәй.",
                    ),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                )
                if (failed) {
                    Text(
                        appText("Не сохранилось. Проверь номер и повтори.", "Һаҡланманы. Номерҙы тикшереп ҡабатла."),
                        color = CanonRed, fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (busy || !valid) return@TextButton
                    busy = true
                    val last4 = digits.takeLast(4)   // на сервер уходит ТОЛЬКО это
                    scope.launch {
                        ApiClient.savePayoutRequisite(last4)
                            .onSuccess { saved -> busy = false; onSaved(saved.ifBlank { last4 }) }
                            .onFailure { busy = false; failed = true }
                    }
                },
                enabled = valid && !busy,
            ) {
                Text(
                    if (busy) appText("Сохраняем…", "Һаҡлайбыҙ…") else appText("Сохранить", "Һаҡлау"),
                    color = CanonGreen2, fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!busy) onDismiss() }) {
                Text(appText("Отмена", "Кире алыу"), color = CanonMuted)
            }
        },
    )
}

/** Запасная подпись по kind, если сервер не прислал человекочитаемый note. */
@Composable
private fun ledgerKindLabel(kind: String): String = when (kind) {
    "earn" -> appText("Начисление за поездку", "Сәфәр өсөн килем")
    "fee" -> appText("Комиссия сервиса", "Сервис комиссияһы")
    "payout" -> appText("Выплата", "Түләү")
    else -> appText("Операция", "Операция")
}

/**
 * Разряды пробелом: 12 500 ₽. Единственный денежный форматтер приложения — им напечатаны
 * баланс, вывод средств, чек за такси и итоги водителя.
 *
 * Локаль задана ЯВНО и не зависит от телефона. Было `"%,d".format(n)`, а это
 * `Locale.getDefault()`: на локали с точкой-разделителем «12 500 ₽» превращалось в
 * «12.500 ₽» — сумма читается как двенадцать с половиной рублей. Замена `,` → ` ` это
 * не спасала (там уже точка), а на русской локали не срабатывала вовсе: системный
 * разделитель там — неразрывный пробел, и совпадение шло мимо. С [Locale.US] разделитель
 * всегда запятая, значит замена детерминирована, и сумма выглядит одинаково везде.
 */
internal fun fmtRub(n: Int): String = String.format(Locale.US, "%,d", n).replace(',', ' ')
