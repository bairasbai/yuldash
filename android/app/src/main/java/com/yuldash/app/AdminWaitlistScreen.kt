package com.yuldash.app

// ============================ Админ: лист ожидания (ранний доступ, §11) ============================
// По паттерну AdminTaxiScreen: умная обёртка держит стейт и сеть, LazyColumn рисует все состояния
// (загрузка / ошибка+Повторить / пусто / список). Сверху счётчики: всего, позваны, по городам, по ролям.
// Фильтры: роль (все/пассажиры/водители) и статус (все/ждут/позваны). Записи выбираются чекбоксами →
// «Пометить волну (N)» проставляет invited_at (саму рассылку Александр делает сам, СМС тут нет).

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.AdminWaitlistDto
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.launch

@Composable
internal fun AdminWaitlistScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    var data by remember { mutableStateOf<AdminWaitlistDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var roleFilter by remember { mutableStateOf("") }          // "" | passenger | driver
    var invitedFilter by remember { mutableStateOf<Boolean?>(null) }   // null | false(ждут) | true(позваны)
    var selected by remember { mutableStateOf(setOf<Int>()) }
    var inviting by remember { mutableStateOf(false) }

    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")
    val actionErrMsg = appText("Не получилось. Проверь сеть и повтори.", "Булманы. Селтәрҙе тикшереп ҡабатла.")
    val invitedMsg = appText("Волна помечена", "Тулҡын билдәләнде")

    fun reload() {
        loading = true; error = null
        scope.launch {
            ApiClient.getAdminWaitlist(role = roleFilter, invited = invitedFilter)
                .onSuccess { data = it; selected = selected.intersect(it.items.map { e -> e.id }.toSet()) }
                .onFailure { error = loadErr }
            loading = false
        }
    }
    LaunchedEffect(roleFilter, invitedFilter) { reload() }

    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Лист ожидания", "Көтөү исемлеге"), onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            item {
                Text(
                    appText(
                        "Ранний доступ: кто ждёт запуска такси. Выбери записи и пометь волну — рассылку делаешь сам, СМС отсюда не уходят.",
                        "Иртә инеү: такси асылыуын кем көтә. Яҙмаларҙы һайла ла тулҡынды билдәлә — хәбәрҙе үҙең ебәрәһең, СМС бынан китмәй.",
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp,
                )
            }
            // ---------- Счётчики ----------
            data?.let { d ->
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WaitlistStatCard(appText("Всего", "Барлығы"), d.total.toString(), Modifier.weight(1f))
                        WaitlistStatCard(appText("Ждут", "Көтәләр"), (d.total - d.invited).toString(), Modifier.weight(1f))
                        WaitlistStatCard(appText("Позваны", "Саҡырылған"), d.invited.toString(), Modifier.weight(1f))
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        WaitlistStatCard(appText("Пассажиры", "Пассажирҙар"), d.passengers.toString(), Modifier.weight(1f))
                        WaitlistStatCard(appText("Водители", "Водителдәр"), d.drivers.toString(), Modifier.weight(1f))
                    }
                }
                if (d.byCity.isNotEmpty()) {
                    item {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            d.byCity.forEach { (cityName, count) ->
                                Surface(color = CanonSurface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, CanonBorder)) {
                                    Text(
                                        "$cityName · $count",
                                        color = CanonText, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // ---------- Фильтры ----------
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WaitlistFilterChip(appText("Все", "Барыһы"), roleFilter == "") { roleFilter = "" }
                    WaitlistFilterChip(appText("Пассажиры", "Пассажирҙар"), roleFilter == "passenger") { roleFilter = "passenger" }
                    WaitlistFilterChip(appText("Водители", "Водителдәр"), roleFilter == "driver") { roleFilter = "driver" }
                    WaitlistFilterChip(appText("Ждут", "Көтәләр"), invitedFilter == false) {
                        invitedFilter = if (invitedFilter == false) null else false
                    }
                    WaitlistFilterChip(appText("Позваны", "Саҡырылған"), invitedFilter == true) {
                        invitedFilter = if (invitedFilter == true) null else true
                    }
                }
            }
            // ---------- Состояния: загрузка / ошибка / пусто / список ----------
            if (loading) {
                item { SkeletonCard(lines = 3) }
                item { SkeletonCard(lines = 3) }
            } else if (error != null) {
                item { ListedError(error ?: "") { reload() } }
            } else if (data?.items.isNullOrEmpty()) {
                item {
                    ListedEmpty(
                        appText("Пока никого", "Әлегә бер кем дә юҡ"),
                        appText("Здесь появятся номера с лендинга и из приложения.", "Бында лендингтан һәм ҡушымтанан номерҙар күренер."),
                    )
                }
            } else {
                val rows = data?.items.orEmpty()
                items(rows.size, key = { "wl-" + rows[it].id }) { i ->
                    val e = rows[i]
                    val isInvited = e.invitedAt != null
                    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = e.id in selected,
                                onCheckedChange = { v -> selected = if (v) selected + e.id else selected - e.id },
                                enabled = !isInvited,   // уже позванных заново не помечаем
                                colors = CheckboxDefaults.colors(checkedColor = CanonGreen2),
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(e.phone, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                val meta = buildList {
                                    if (e.city.isNotBlank()) add(e.city)
                                    add(if (e.role == "driver") appText("водитель", "водитель") else appText("пассажир", "пассажир"))
                                    add(e.createdAt.take(10))
                                }.joinToString("  ·  ")
                                Text(meta, color = CanonMuted, fontSize = 12.sp)
                            }
                            if (isInvited) {
                                Surface(color = CanonMint, shape = RoundedCornerShape(10.dp)) {
                                    Text(
                                        appText("Позван", "Саҡырылған"), color = CanonGreen2, fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                    }
                }
                // ---------- Пометить волну ----------
                item {
                    AnimatedVisibility(visible = selected.isNotEmpty()) {
                        Button(
                            onClick = {
                                if (inviting) return@Button
                                inviting = true
                                val ids = selected.toList()
                                scope.launch {
                                    ApiClient.adminWaitlistInvite(ids)
                                        .onSuccess {
                                            Toast.makeText(ctx, invitedMsg, Toast.LENGTH_SHORT).show()
                                            selected = emptySet(); reload()
                                        }
                                        .onFailure { Toast.makeText(ctx, actionErrMsg, Toast.LENGTH_SHORT).show() }
                                    inviting = false
                                }
                            },
                            enabled = !inviting,
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                        ) {
                            Text(
                                appText("Пометить волну (${selected.size})", "Тулҡынды билдәләү (${selected.size})"),
                                fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Карточка-счётчик (всего/ждут/позваны, пассажиры/водители). */
@Composable
private fun WaitlistStatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder), modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(label, color = CanonMuted, fontSize = 12.sp)
        }
    }
}

/** Чип фильтра (роль/статус), тач-цель ≥48dp. */
@Composable
private fun WaitlistFilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (active) CanonMint else CanonSurface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, if (active) CanonGreen2 else CanonBorder),
        modifier = Modifier.height(48.dp),
    ) {
        Box(Modifier.padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (active) CanonGreen2 else CanonMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}
