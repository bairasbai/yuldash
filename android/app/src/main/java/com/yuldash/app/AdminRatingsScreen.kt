package com.yuldash.app

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
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
            contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp),
        ) {
            item {
                Text(
                    appText(
                        "Звёзды учитываются сразу, а текст появляется в профиле только после твоего одобрения. Так профиль не превращается в стену обид.",
                        "Йондоҙҙар шунда уҡ иҫәпләнә, ә текст профилдә тик һин раҫлағас күренә. Шулай профиль үпкә стенаһына әйләнмәй.",
                    ),
                    color = CanonMuted, fontSize = 13.sp, lineHeight = 18.sp,
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
                else -> items(list, key = { it.id }) { r ->
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

@Composable
private fun PendingRatingCard(r: PendingRatingDto, busy: Boolean, onPublish: () -> Unit, onShield: () -> Unit) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = CircleShape) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar,
                        modifier = Modifier.padding(10.dp).size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "★".repeat(r.stars.coerceIn(1, 5)),
                        color = CanonStar, fontWeight = FontWeight.Black, fontSize = 16.sp,
                    )
                    Text(
                        r.author + " · " + formatDepart(r.createdAt),
                        color = CanonMuted, fontSize = 12.sp,
                    )
                }
            }
            Surface(color = CanonBg, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                Text(r.text, color = CanonText, fontSize = 14.sp, lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth().padding(14.dp))
            }
            AppButton(
                text = appText("Опубликовать", "Баҫтырыу"),
                onClick = onPublish,
                icon = Icons.Default.CheckCircle,
                loading = busy,
            )
            // Кнопки «скрыть» нет намеренно: неодобренный текст и так не виден никому.
            // Отдельное действие «отклонить» создало бы иллюзию наказания там, где его нет.
            Text(
                appText(
                    "Не одобряешь — просто пропусти: текст останется скрытым, а звёзды уже учтены.",
                    "Раҫламайһыңмы — үтеп кит: текст йәшерен ҡала, ә йондоҙҙар инде иҫәпләнгән.",
                ),
                color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
            )
            TextButton(onClick = onShield, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    appText("Оценка мстительная — снять из рейтинга", "Баһа үс алыу өсөн — рейтингтан алырға"),
                    color = CanonMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
