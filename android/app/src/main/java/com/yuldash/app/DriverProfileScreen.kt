package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.DriverPublicDto

/**
 * Публичный профиль водителя (Screen.DriverProfile) — открывается тапом с карточки поездки.
 * Витрина доверия «между своими»: фото, бейдж «Проверен», стаж в Юлдаше, число поездок,
 * средний рейтинг, последние текстовые отзывы (только прошедшие модерацию). БЕЗ ПДн (телефона).
 * Данные — GET /drivers/{id}/public. Все состояния: загрузка / ошибка+повтор / успех / пусто.
 */
@Composable
internal fun DriverProfileScreen(driverId: Int, onBack: () -> Unit) {
    val lang = LocalAppLanguage.current
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var data by remember { mutableStateOf<DriverPublicDto?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    val errMsg = appText(
        "Не получилось загрузить профиль. Проверь сеть.",
        "Профилде йөкләп булманы. Сетте тикшер.",
    )

    androidx.compose.runtime.LaunchedEffect(driverId, reloadKey) {
        loading = true
        error = null
        ApiClient.getDriverPublic(driverId)
            .onSuccess { data = it; loading = false }
            .onFailure { error = errMsg; loading = false }
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Профиль водителя", "Водитель профиле"), onBack) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                loading -> AppLoading(appText("Загружаем профиль…", "Профиль йөкләнә…"))
                error != null -> AppErrorState(
                    onRetry = { reloadKey++ },
                    text = error!!,
                )
                data != null -> DriverProfileContent(data!!, lang)
            }
        }
    }
}

@Composable
private fun DriverProfileContent(d: DriverPublicDto, lang: AppLanguage) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Box(Modifier.appearIn(0)) { DriverHeaderCard(d) } }
        item { Box(Modifier.appearIn(1)) { DriverStatsRow(d, lang) } }
        item {
            SectionHeader(
                appText("Отзывы попутчиков", "Юлдаштар фекере"),
                subtitle = if (d.reviews.isEmpty()) null
                else appText("Проверенные отзывы", "Тикшерелгән фекерҙәр"),
            )
        }
        if (d.reviews.isEmpty()) {
            item {
                Box(Modifier.appearIn(2)) {
                    AppEmptyState(
                        title = appText("Пока нет отзывов", "Әлегә фекер юҡ"),
                        text = appText(
                            "После поездки попутчики смогут оставить отзыв здесь.",
                            "Сәфәрҙән һуң юлдаштар бында фекер ҡалдыра ала.",
                        ),
                    )
                }
            }
        } else {
            items(d.reviews, key = { it.author + it.createdAt + it.text.take(12) }) { r ->
                Box(Modifier.appearIn(2)) { DriverReviewCard(r.author, r.stars, r.text, r.createdAt) }
            }
        }
    }
}

@Composable
private fun DriverHeaderCard(d: DriverPublicDto) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallAvatar(d.avatarUrl, d.name, 64)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        d.name.ifBlank { appText("Водитель", "Йөрөтөүсе") },
                        fontWeight = FontWeight.Bold, fontSize = TaxiType.Hero, lineHeight = TaxiType.HeroLine,
                        color = CanonText, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (d.car.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                d.car, color = CanonMuted,
                                fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (d.verified) VerifiedPill()
        }
    }
}

@Composable
private fun VerifiedPill() {
    Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Verified, contentDescription = appText("Проверен", "Тикшерелгән"), tint = CanonGreen2, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                appText("Проверен", "Тикшерелгән"), color = CanonGreen2, fontWeight = FontWeight.Bold,
                fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine,
            )
        }
    }
}

@Composable
private fun DriverStatsRow(d: DriverPublicDto, lang: AppLanguage) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCell(
            modifier = Modifier.weight(1f),
            value = if (d.rating != null) String.format(java.util.Locale.US, "%.1f", d.rating) else "—",
            label = appText("рейтинг", "рейтинг"),
            hint = if (d.ratingCount > 0) appText("${d.ratingCount} оценок", "${d.ratingCount} баһа") else null,
            star = true,
        )
        StatCell(
            modifier = Modifier.weight(1f),
            value = d.tripsCount.toString(),
            label = appText("поездок", "сәфәр"),
        )
        StatCell(
            modifier = Modifier.weight(1f),
            value = tenureValue(d.daysInService, lang),
            label = appText("в Юлдаше", "Юлдашта"),
        )
    }
}

@Composable
private fun StatCell(modifier: Modifier, value: String, label: String, hint: String? = null, star: Boolean = false) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (star) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    value, fontWeight = FontWeight.Bold,
                    fontSize = TaxiType.Title, lineHeight = TaxiType.TitleLine, color = CanonText,
                )
            }
            Text(label, color = CanonMuted, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine)
            if (hint != null) Text(hint, color = CanonMuted, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine)
        }
    }
}

@Composable
private fun DriverReviewCard(author: String, stars: Int, text: String, createdAt: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallAvatar("", author, 30)
                Spacer(Modifier.width(8.dp))
                Text(
                    author, fontWeight = FontWeight.Bold,
                    fontSize = TaxiType.Body, lineHeight = TaxiType.BodyLine, color = CanonText,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    (1..5).forEach { n ->
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = if (n <= stars) CanonStar else CanonMuted,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            Text(text, color = CanonText, fontSize = TaxiType.Body, lineHeight = TaxiType.BodyLine)
            shortDate(createdAt)?.let {
                Text(it, color = CanonMuted, fontSize = TaxiType.Caption, lineHeight = TaxiType.CaptionLine)
            }
        }
    }
}

/** Стаж кратко для плитки: «2 г», «5 мес», «12 дн». Полную подпись даёт `label` ячейки. */
private fun tenureValue(days: Int, lang: AppLanguage): String {
    val years = days / 365
    val months = (days % 365) / 30
    return when {
        years >= 1 -> if (lang == AppLanguage.Ba) "$years йыл" else "$years г"
        months >= 1 -> if (lang == AppLanguage.Ba) "$months ай" else "$months мес"
        else -> {
            val dd = days.coerceAtLeast(0)
            if (lang == AppLanguage.Ba) "$dd көн" else "$dd дн"
        }
    }
}

/** ISO-дата «2026-07-05T…» → «05.07.2026». Пусто/битое → null (подпись не показываем). */
private fun shortDate(iso: String): String? {
    val d = iso.trim().take(10)
    if (d.length != 10 || d[4] != '-' || d[7] != '-') return null
    return "${d.substring(8, 10)}.${d.substring(5, 7)}.${d.substring(0, 4)}"
}
