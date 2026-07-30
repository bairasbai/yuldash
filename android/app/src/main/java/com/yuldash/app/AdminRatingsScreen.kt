package com.yuldash.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoodBad
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.PendingRatingDto
import kotlinx.coroutines.launch

/*
 * ════════════════════════════════════════════════════════════════════════════
 *  Админ: модерация текстовых отзывов о поездке
 * ════════════════════════════════════════════════════════════════════════════
 *  Звёзды идут в рейтинг сразу, а ТЕКСТ отзыва появляется в публичном профиле только после
 *  одобрения человеком. Очередь на сервере была с самого начала, а экрана не было — поэтому
 *  тексты не публиковались НИКОГДА: люди писали отзывы в пустоту, а в профилях висели одни
 *  звёздочки (аудит 2026-07-26).
 *
 *  Два действия и оба важны:
 *   • «Опубликовать» — отзыв виден в профиле, это и есть репутация «между своими»;
 *   • «Щит рейтинга» — мстительная или накрученная оценка перестаёт влиять на средний балл.
 *     Одна месть-оценка не должна рушить рейтинг честного человека.
 *
 *  Дизайн: правила модерации объяснены ОДИН раз вверху, а не на каждой карточке — карточка
 *  показывает только сам отзыв. Злой отзыв (1–2★) сразу видно по жёлтому знаку «внимание»,
 *  добрый — по мятному. Публикация — крупная зелёная кнопка, «щит» — тихая строка.
 *  Типографика: 20 / 16 / 13 / 11. Сетка 4dp: поля 16, шаг 12, микро-шаг 4.
 */

@Composable
internal fun AdminRatingsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<PendingRatingDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var busyId by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true; error = false
        ApiClient.adminPendingRatings()
            .onSuccess { list = it }
            .onFailure { error = true }
        loading = false
    }

    fun act(id: Int, block: suspend () -> Result<Unit>) {
        if (busyId != 0) return
        busyId = id
        scope.launch {
            block().onSuccess { reload++ }
            busyId = 0
        }
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Отзывы на модерации", "Модерациялағы фекерҙәр"), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        ) {
            item(key = "ratings-rules") {
                RatingsRulesCard(
                    headline = if (list.isNotEmpty())
                        appText("На модерации: ${list.size}", "Модерацияла: ${list.size}")
                    else appText("Как работает модерация", "Модерация нисек эшләй"),
                )
            }
            when {
                loading && list.isEmpty() ->
                    item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { SkeletonCard(lines = 3) } } }
                error && list.isEmpty() -> item { AppErrorState(onRetry = { reload++ }) }
                list.isEmpty() -> item {
                    AppEmptyState(
                        title = appText("Всё разобрано", "Бөтәһе лә ҡаралған"),
                        text = appText("Новых отзывов на модерации нет.", "Модерацияла яңы фекерҙәр юҡ."),
                        icon = Icons.Default.CheckCircle,
                    )
                }
                else -> itemsIndexed(list, key = { _, r -> r.id }) { i, r ->
                    // Разобранный отзыв не пропадает рывком: карточка тает, соседние подъезжают.
                    Box(Modifier.animateItem().appearIn(i.coerceAtMost(6))) {
                        PendingRatingCard(
                            r = r,
                            busy = busyId == r.id,
                            onPublish = { act(r.id) { ApiClient.adminPublishRating(r.id, true) } },
                            onShield = { act(r.id) { ApiClient.adminExcludeRating(r.id, true) } },
                        )
                    }
                }
            }
        }
    }
}

/** Правила модерации — один раз вверху экрана, а не абзацем на каждой карточке.
 *  Заголовок меняется на счётчик очереди (плавно, без скачка). */
@Composable
private fun RatingsRulesCard(headline: String) {
    Surface(color = CanonMint, shape = CanonItemShape) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AnimatedContent(targetState = headline, label = "ratingsHeadline") { t ->
                    Text(t, color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Black, lineHeight = 20.sp)
                }
                Text(
                    appText(
                        "Звёзды учитываются сразу, текст появляется в профиле только после твоего одобрения. " +
                            "Не одобряешь — просто пропусти: текст останется скрытым.",
                        "Йондоҙҙар шунда уҡ иҫәпләнә, текст профилдә тик һин раҫлағас күренә. " +
                            "Раҫламайһыңмы — үтеп кит: текст йәшерен ҡала.",
                    ),
                    color = CanonMutedStrong, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun PendingRatingCard(r: PendingRatingDto, busy: Boolean, onPublish: () -> Unit, onShield: () -> Unit) {
    val stars = r.stars.coerceIn(0, 5)
    val angry = stars in 1..2                       // жалоба: смотреть внимательнее (ожидание = warn)
    val toneBg = if (angry) CanonWarnBg else CanonMint
    val toneFg = if (angry) CanonWarn else CanonGreen2

    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = toneBg, shape = CircleShape) {
                    Icon(
                        if (angry) Icons.Default.MoodBad else Icons.Default.Star,
                        contentDescription = appText("Оценка $stars из 5", "Баһа: $stars, 5-тән"),
                        tint = toneFg,
                        modifier = Modifier.padding(12.dp).size(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RatingStars(stars)
                    Text(
                        r.author + " · " + formatDepart(r.createdAt),
                        color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Сам отзыв — то единственное, ради чего открыли карточку.
            Surface(color = CanonBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                Text(
                    r.text.ifBlank { appText("Текста нет — только звёзды.", "Текст юҡ — тик йондоҙҙар.") },
                    color = CanonText, fontSize = 16.sp, lineHeight = 22.sp,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }

            AppButton(
                text = appText("Опубликовать в профиле", "Профилдә баҫтырыу"),
                onClick = onPublish,
                icon = Icons.Default.CheckCircle,
                loading = busy,
            )

            // Кнопки «скрыть» нет намеренно: неодобренный текст и так не виден никому.
            // Отдельное действие «отклонить» создало бы иллюзию наказания там, где его нет.
            TextButton(
                onClick = onShield,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = CanonMutedStrong, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    appText("Оценка мстительная — снять из рейтинга", "Баһа үс алыу өсөн — рейтингтан алыу"),
                    color = CanonMutedStrong, fontSize = 13.sp, fontWeight = FontWeight.Bold, lineHeight = 18.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Пять звёзд вместо строки «★★★★» — оценка читается за долю секунды, а не пересчётом символов. */
@Composable
private fun RatingStars(stars: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(5) { i ->
            Icon(
                if (i < stars) Icons.Default.Star else Icons.Default.StarBorder,
                contentDescription = null,
                tint = if (i < stars) CanonStar else CanonMuted,   // пустая звезда — CanonMuted (контраст ≥3:1)
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
