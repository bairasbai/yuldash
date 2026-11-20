package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.DriverPaySettingsDto
import com.yuldash.app.data.directDriverPayMethods
import com.yuldash.app.data.getDriverPaySettings
import com.yuldash.app.data.setDriverPaySettings
import kotlinx.coroutines.launch

private enum class DriverPayStatus { Loading, Ready, LoadError, SaveUnknown, Review, Saved }

@Composable
internal fun DriverPaySettingsCard(generation: Long) {
    key(generation) { DriverPaySettingsForSession(generation) }
}

@Composable
private fun DriverPaySettingsForSession(generation: Long) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<DriverPaySettingsDto?>(null) }
    var draft by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf(DriverPayStatus.Loading) }
    var checking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    fun show(value: DriverPaySettingsDto, next: DriverPayStatus) {
        snapshot = value
        draft = value.methods
        status = next
    }

    LaunchedEffect(reload) {
        checking = true
        val value = ApiClient.getDriverPaySettings(generation).getOrNull()
        if (ApiClient.isCurrentSession(generation)) {
            if (value != null) show(value, DriverPayStatus.Ready)
            else if (status != DriverPayStatus.SaveUnknown) status = DriverPayStatus.LoadError
        }
        checking = false
    }

    fun save() {
        val before = snapshot ?: return
        if (saving || !ApiClient.isCurrentSession(generation)) return
        val chosen = directDriverPayMethods.filter { it in draft }
        saving = true
        scope.launch {
            try {
                var current = ApiClient.setDriverPaySettings(chosen, before.updatedAt, generation).getOrNull()
                if (current == null && ApiClient.isCurrentSession(generation)) {
                    current = ApiClient.getDriverPaySettings(generation).getOrNull()
                }
                if (!ApiClient.isCurrentSession(generation)) return@launch
                if (current != null) {
                    show(current, if (current.methods == chosen) DriverPayStatus.Saved else DriverPayStatus.Review)
                } else {
                    snapshot = null
                    status = DriverPayStatus.SaveUnknown
                }
            } finally {
                saving = false
            }
        }
    }

    AppCard {
        Column(Modifier.padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Text(appText("Как ты принимаешь оплату", "Түләүҙе нисек ҡабул итәһең"), style = CanonHeading, color = CanonText)
            when (status) {
                DriverPayStatus.Loading -> SkeletonCard(lines = 2)
                DriverPayStatus.LoadError, DriverPayStatus.SaveUnknown -> {
                    Text(
                        if (status == DriverPayStatus.SaveUnknown) appText(
                            "Сохранение не подтвердилось. Проверь текущие настройки.",
                            "Һаҡлау раҫланманы. Әлеге көйләүҙәрҙе тикшер.",
                        ) else appText("Не удалось загрузить настройки.", "Көйләүҙәрҙе йөкләп булманы."),
                        style = CanonCaption, color = CanonMuted,
                    )
                    AppButton(appText("Проверить настройки", "Көйләүҙәрҙе тикшерергә"),
                        onClick = { reload++ }, loading = checking, enabled = !checking, style = AppButtonStyle.Secondary)
                }
                DriverPayStatus.Ready, DriverPayStatus.Review, DriverPayStatus.Saved -> {
                    Text(appText(
                        "Пассажир платит тебе напрямую. Если оставить оба пункта пустыми, можно договориться.",
                        "Юлсы туранан-тура һиңә түләй. Ике пунктты ла буш ҡалдырһаң, килешергә була.",
                    ), style = CanonCaption, color = CanonMuted)
                    directDriverPayMethods.forEach { method ->
                        val checked = method in draft
                        Row(
                            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
                                .toggleable(checked, enabled = !saving, role = Role.Checkbox) { enabled ->
                                    draft = if (enabled) draft + method else draft.filterNot { it == method }
                                    if (status == DriverPayStatus.Saved) status = DriverPayStatus.Ready
                                },
                            horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked, onCheckedChange = null, enabled = !saving)
                            Text(payMethodLabel(method), style = CanonBody, color = CanonText)
                        }
                    }
                    val cardBlockers = snapshot?.cardBlockers.orEmpty()
                    AnimatedVisibility(cardBlockers.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                            Text(appText("Оплата картой пока недоступна.", "Карта менән түләү әлегә мөмкин түгел."),
                                style = CanonCaption, color = CanonMuted)
                            cardBlockers.forEach { Text(appText(it.ru, it.ba), style = CanonCaption, color = CanonMuted) }
                        }
                    }
                    AnimatedVisibility(status == DriverPayStatus.Review || status == DriverPayStatus.Saved) {
                        Text(if (status == DriverPayStatus.Review)
                            appText("Проверь текущий выбор и сохрани снова.", "Әлеге һайлауҙы тикшер һәм ҡабат һаҡла.")
                        else appText("Способы оплаты сохранены.", "Түләү ысулдары һаҡланды."),
                            style = CanonCaption, color = CanonMuted)
                    }
                    AppButton(
                        appText("Сохранить", "Һаҡларға"), onClick = ::save, loading = saving,
                        enabled = !saving && snapshot?.methods?.toSet() != draft.toSet(),
                    )
                }
            }
        }
    }
}
