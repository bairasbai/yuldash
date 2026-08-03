package com.yuldash.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.yuldash.app.data.MedicalPartnerDto
import com.yuldash.app.data.RideDto
import kotlinx.coroutines.launch

/**
 * F22 — «Поездки к клинике» (B2B «медцентр-партнёр»).
 *
 * ДЕЛИКАТНО: это ЛОГИСТИКА (доехать до клиники), НЕ медуслуга. Никаких обещаний лечения,
 * записи к врачу или мед.данных пациента. Клиника — просто точка назначения на карте, к которой
 * пассажиры из районов могут подсесть в общую попутку. Тон — спокойный, добрососедский.
 */

/** Карточка-вход на вкладке Карта: «Поездки к клинике». */
@Composable
internal fun ClinicRidesEntryCard(onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(
                    Icons.Default.LocalHospital,
                    contentDescription = appText("К клинике", "Клиникаға"),
                    tint = CanonGreen2,
                    modifier = Modifier.padding(12.dp).size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    appText("Поездки к клинике", "Клиникаға сәфәрҙәр"),
                    color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                )
                Text(
                    appText("Кто-то уже едет к больнице — подсядь по пути",
                        "Кемдер клиникаға бара — юлда ҡушыл"),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
        }
    }
}

/** Экран «Поездки к клинике»: выбор клиники-партнёра + попутки к ней. Все состояния. */
@Composable
internal fun ClinicRidesScreen(onBack: () -> Unit, onBookRide: (Ride) -> Unit) {
    val scope = rememberCoroutineScope()

    var partners by remember { mutableStateOf<List<MedicalPartnerDto>>(emptyList()) }
    var partnersLoading by remember { mutableStateOf(true) }
    var partnersError by remember { mutableStateOf(false) }
    var partnersReload by remember { mutableStateOf(0) }

    var selected by remember { mutableStateOf<MedicalPartnerDto?>(null) }
    var rides by remember { mutableStateOf<List<RideDto>>(emptyList()) }
    var ridesLoading by remember { mutableStateOf(false) }
    var ridesError by remember { mutableStateOf(false) }
    var ridesReload by remember { mutableStateOf(0) }

    // Загрузка справочника клиник.
    LaunchedEffect(partnersReload) {
        partnersLoading = true; partnersError = false
        ApiClient.getMedicalPartners()
            .onSuccess { list ->
                partners = list
                partnersLoading = false
                if (selected == null) selected = list.firstOrNull()   // сразу показываем первую клинику
            }
            .onFailure { partnersLoading = false; partnersError = true }
    }

    // Загрузка поездок к выбранной клинике.
    LaunchedEffect(selected?.id, ridesReload) {
        val p = selected ?: return@LaunchedEffect
        ridesLoading = true; ridesError = false; rides = emptyList()
        ApiClient.getRidesToPartner(p.id)
            .onSuccess { rides = it; ridesLoading = false }
            .onFailure { ridesLoading = false; ridesError = true }
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Поездки к клинике", "Клиникаға сәфәрҙәр"), onBack) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            // Деликатная плашка: честно объясняем, что это логистика, не медуслуга.
            item {
                Surface(color = CanonMint, shape = CanonItemShape) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.LocalHospital, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            appText(
                                "Помогаем доехать до нужной клиники вместе с попутчиками. Это обычная поездка до больницы — без записи к врачу и без медицинских данных.",
                                "Кәрәкле клиникаға юлдаштар менән барырға ярҙам итәбеҙ. Был ябай сәфәр — врачҡа яҙылыуһыҙ һәм медицина мәғлүмәтенһеҙ.",
                            ),
                            color = CanonText, fontSize = 14.sp, lineHeight = 20.sp,
                        )
                    }
                }
            }

            when {
                // Клиники грузятся.
                partnersLoading && partners.isEmpty() -> item { AppLoading(appText("Загружаем клиники", "Клиникалар йөкләнә")) }
                // Ошибка загрузки клиник.
                partnersError && partners.isEmpty() -> item { AppErrorState(onRetry = { partnersReload++ }) }
                // Клиник нет.
                partners.isEmpty() -> item {
                    AppEmptyState(
                        title = appText("Пока нет клиник", "Әлегә клиникалар юҡ"),
                        text = appText("Клиники-партнёры скоро появятся здесь.", "Партнёр-клиникалар тиҙҙән бында күренер."),
                        icon = Icons.Default.LocalHospital,
                    )
                }
                else -> {
                    // Выбор клиники — горизонтальные чипы.
                    item {
                        Text(appText("Выбери клинику", "Клиниканы һайла"), color = CanonMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    item {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            partners.forEach { p ->
                                ClinicChip(partner = p, selected = selected?.id == p.id) { selected = p }
                            }
                        }
                    }
                    // Заголовок + адрес выбранной клиники.
                    selected?.let { p ->
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(appText("Кто едет в «${p.name}»", "«${p.name}»-ға кем бара"), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                if (p.address.isNotBlank()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("${p.city}, ${p.address}", color = CanonMuted, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                    // Поездки к выбранной клинике — свои состояния.
                    when {
                        ridesLoading && rides.isEmpty() -> item { AppLoading() }
                        ridesError && rides.isEmpty() -> item { AppErrorState(onRetry = { ridesReload++ }) }
                        rides.isEmpty() -> item {
                            AppEmptyState(
                                title = appText("Сюда пока никто не едет", "Бында әлегә бер кем дә бармай"),
                                text = appText("Загляни позже или опубликуй свою поездку к этой клинике.", "Һуңыраҡ ҡара йәки был клиникаға үҙ сәфәреңде баҫтыр."),
                                icon = Icons.Default.Schedule,
                            )
                        }
                        else -> items(rides.size, key = { rides[it].id }) { i ->
                            val dto = rides[i]
                            ClinicRideCard(dto = dto, onBook = { onBookRide(dto.toUiRide()) })
                        }
                    }
                }
            }
        }
    }
}

/** Селектор-чип клиники. */
@Composable
private fun ClinicChip(partner: MedicalPartnerDto, selected: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) CanonGreen2 else CanonSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (selected) CanonGreen2 else CanonBorder),
        modifier = Modifier.bounceClick(onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.LocalHospital, contentDescription = null,
                tint = if (selected) androidx.compose.ui.graphics.Color.White else CanonGreen2,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    partner.name, color = if (selected) androidx.compose.ui.graphics.Color.White else CanonText,
                    fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    partner.city,
                    color = if (selected) androidx.compose.ui.graphics.Color.White.copy(alpha = 0.85f) else CanonMuted,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/** Карточка поездки к клинике — маршрут, время, водитель, места, цена, «Поехать». */
@Composable
private fun ClinicRideCard(dto: RideDto, onBook: () -> Unit) {
    val ride = remember(dto.id) { dto.toUiRide() }
    val time = if (LocalAppLanguage.current == AppLanguage.Ba) (ride.timeBa ?: ride.time) else ride.time
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${ride.from} → ${ride.to}", color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (ride.verified) {
                    Icon(Icons.Default.Verified, contentDescription = appText("Проверен", "Тикшерелгән"), tint = CanonGreen2, modifier = Modifier.size(18.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetaChip(Icons.Default.Schedule, time)
                MetaChip(Icons.Default.EventSeat, appText("${ride.seats} мест", "${ride.seats} урын"))
                MetaChip(Icons.Default.Star, String.format(java.util.Locale.US, "%.1f", ride.rating))
            }
            // Двуязычный дефолт имени водителя (toUiRide больше не кладёт русский литерал). BA-draft: «Йөрөтөүсе».
            val driverFallback = appText("Водитель", "Йөрөтөүсе")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ride.driver.ifBlank { driverFallback }, color = CanonMuted, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (ride.price > 0) "${ride.price} ₽" else appText("Бесплатно", "Бушлай"), color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            AppButton(appText("Поехать", "Барырға"), onBook, height = 46.dp)
        }
    }
}

@Composable
private fun MetaChip(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = CanonMuted, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, color = CanonMuted, fontSize = 14.sp)
    }
}
