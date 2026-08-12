package com.yuldash.app

// ═══════════════════ Админ: одна очередь модерации витрины «Скидки по пути» ═══════════════════
// По паттерну AdminPartnersScreen: умная обёртка держит стейт+сеть, LazyColumn рисует все состояния.
//
// Зачем экран. Раньше «непросмотренное» лежало в двух местах, а купоны — вообще нигде: чистый
// купон не видел НИКТО и НИКОГДА (автопроверка ищет телефоны/ссылки/ругань по шаблонам и
// пропускает «скидка 90% при предоплате на карту»). Здесь всё в одном списке, сверху — то, что
// кого-то блокирует.
//
// Порядок карточек задаёт СЕРВЕР (GET /admin/moderation): задержанные автопроверкой → с жалобами
// → просто непросмотренные. Экран не пересортировывает — иначе правило срочности разъедется
// между клиентом и сервером.

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.AdminCouponDto
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ModerationQueueDto
import kotlinx.coroutines.launch

@Composable
internal fun AdminModerationScreen(onBack: () -> Unit, onOpenPartners: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var queue by remember { mutableStateOf(ModerationQueueDto(emptyList(), emptyList(), 0)) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf(0) }
    var blockTarget by remember { mutableStateOf<AdminCouponDto?>(null) }

    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    val actionErr = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    val okMsg = appText("Проверено", "Тикшерелде")
    val blockedMsg = appText("Купон снят с витрины", "Купон витринанан алынды")

    fun reload() {
        loading = true; error = null
        scope.launch {
            ApiClient.getModerationQueue()
                .onSuccess { queue = it }
                .onFailure { error = (it as? com.yuldash.app.data.ApiException)?.message ?: loadErr }
            loading = false
        }
    }
    LaunchedEffect(Unit) { reload() }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Модерация", "Тикшереү"), onBack) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = CanonSpace.lg),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
            contentPadding = PaddingValues(vertical = CanonSpace.lg),
        ) {
            item {
                Text(
                    appText(
                        "Всё, что ещё не смотрел. Сверху — то, что ждёт тебя: задержанное проверкой " +
                            "и то, на что пожаловались. Ниже — уже видное людям, но не проверенное.",
                        "Һин ҡарамағандың бөтәһе. Өҫтә — һине көткәне: тикшереү тотҡаны һәм зарланғаны. " +
                            "Аҫта — кешеләргә күренә, әммә тикшерелмәгәне.",
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }

            if (queue.partners.isNotEmpty()) {
                item {
                    QueueSection(
                        appText("Бизнесы ждут первого одобрения: ${queue.partners.size}",
                                "Бизнестар беренсе раҫлауҙы көтә: ${queue.partners.size}"),
                        appText("Открыть список бизнесов", "Бизнестар исемлеген асыу"),
                        onOpenPartners,
                    )
                }
            }

            when {
                loading && queue.total == 0 -> {
                    item { SkeletonCard(lines = 3) }
                    item { SkeletonCard(lines = 3) }
                }
                error != null && queue.total == 0 -> item { ListedError(error ?: "") { reload() } }
                queue.coupons.isEmpty() && queue.partners.isEmpty() -> item {
                    ListedEmpty(
                        appText("Всё проверено", "Бөтәһе тикшерелгән"),
                        appText("Новые купоны и бизнесы появятся здесь сами.",
                                "Яңы купондар һәм бизнестар бында үҙҙәре күренер."),
                    )
                }
                else -> items(queue.coupons.size, key = { "mc-" + queue.coupons[it].id }) { i ->
                    val c = queue.coupons[i]
                    Box(Modifier.appearIn(i.coerceAtMost(6))) {
                        ModerationCouponCard(
                            c = c,
                            busy = busyId == c.id,
                            onApprove = {
                                if (busyId != 0) return@ModerationCouponCard
                                busyId = c.id
                                scope.launch {
                                    ApiClient.approveCoupon(c.id)
                                        .onSuccess { Toast.makeText(ctx, okMsg, Toast.LENGTH_SHORT).show(); reload() }
                                        .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr, Toast.LENGTH_SHORT).show() }
                                    busyId = 0
                                }
                            },
                            onBlock = { blockTarget = c },
                        )
                    }
                }
            }
        }
    }

    blockTarget?.let { target ->
        BlockCouponDialog(
            title = target.title,
            onDismiss = { blockTarget = null },
            onConfirm = { reason ->
                blockTarget = null
                busyId = target.id
                scope.launch {
                    ApiClient.blockCoupon(target.id, reason)
                        .onSuccess { Toast.makeText(ctx, blockedMsg, Toast.LENGTH_SHORT).show(); reload() }
                        .onFailure { Toast.makeText(ctx, (it as? com.yuldash.app.data.ApiException)?.message ?: actionErr, Toast.LENGTH_SHORT).show() }
                    busyId = 0
                }
            },
        )
    }
}

/** Заголовок-плашка секции с кнопкой перехода. */
@Composable
private fun QueueSection(label: String, action: String, onClick: () -> Unit) {
    Surface(color = CanonWarnBg, shape = CanonItemShape) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = CanonSpace.md, vertical = CanonSpace.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = CanonWarn, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                 modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) {
                Text(action, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
        }
    }
}

/** Почему купон в очереди — человеческим языком, а не кодом состояния. */
@Composable
private fun ReviewReason(c: AdminCouponDto) {
    val (bg, fg, text) = when {
        c.review == "held" -> Triple(
            CanonDangerBg, CanonRed,
            appText("Задержан проверкой (${flagLabel(c.reviewFlag)}) — людям не виден",
                    "Тикшереү тотто (${flagLabel(c.reviewFlag)}) — кешеләргә күренмәй"),
        )
        c.reportsCount > 0 -> Triple(
            CanonWarnBg, CanonWarn,
            appText("Жалоб: ${c.reportsCount} — люди говорят, что тут что-то не так",
                    "Зар: ${c.reportsCount} — кешеләр ниҙер дөрөҫ түгел ти"),
        )
        else -> Triple(
            CanonMint, CanonGreen2,
            appText("Виден людям, но ты его ещё не смотрел",
                    "Кешеләргә күренә, әммә һин ҡарамағанһың"),
        )
    }
    Surface(color = bg, shape = CanonTinyShape) {
        Text(text, color = fg, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 17.sp,
             modifier = Modifier.padding(horizontal = CanonSpace.sm, vertical = CanonSpace.xs))
    }
}

/** Метка автопроверки → слово, понятное человеку. */
@Composable
private fun flagLabel(flag: String): String = when (flag) {
    "contact" -> appText("телефон или ссылка", "телефон йәки һылтанма")
    "abuse" -> appText("резкие слова", "ҡаты һүҙҙәр")
    "warn" -> appText("похоже на развод", "алдау һымаҡ")
    else -> appText("метка проверки", "тикшереү билдәһе")
}

@Composable
private fun ModerationCouponCard(
    c: AdminCouponDto,
    busy: Boolean,
    onApprove: () -> Unit,
    onBlock: () -> Unit,
) {
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(
            Modifier.fillMaxWidth().padding(CanonSpace.lg),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
        ) {
            ReviewReason(c)
            Text(c.title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 23.sp)
            if (c.discountText.isNotBlank()) {
                Text(c.discountText, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            if (c.description.isNotBlank()) {
                Text(c.description, color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp)
            }
            // Название бизнеса и город — данные, а не надпись: переводить нечего.
            Text("${c.partnerName} · ${c.city}", color = CanonMuted, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                Button(
                    onClick = onApprove, enabled = !busy,
                    modifier = Modifier.weight(1f).height(44.dp), shape = CanonTinyShape,
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                ) {
                    Text(appText("Всё в порядке", "Бөтәһе яҡшы"), fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                OutlinedButton(
                    onClick = onBlock, enabled = !busy,
                    modifier = Modifier.weight(1f).height(44.dp), shape = CanonTinyShape,
                ) {
                    Text(appText("Снять", "Алыу"), color = CanonRed, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}

/** Снятие купона: причину обязательно — её увидит владелец бизнеса и сможет поправить. */
@Composable
private fun BlockCouponDialog(title: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(appText("Снять купон", "Купонды алыу"), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    appText("Напиши причину — её увидит владелец бизнеса и сможет поправить текст.",
                            "Сәбәбен яҙ — уны бизнес хужаһы күрер һәм текстты төҙәтә алыр."),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                )
                OutlinedTextField(
                    value = reason, onValueChange = { reason = it.take(500) },
                    placeholder = { Text(appText("Например: скидки на деле нет", "Мәҫәлән: ташлама ысынында юҡ")) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason.trim()) }, enabled = reason.isNotBlank()) {
                Text(appText("Снять", "Алыу"), color = CanonRed, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(appText("Отмена", "Кире ҡағыу"), color = CanonMuted)
            }
        },
    )
}