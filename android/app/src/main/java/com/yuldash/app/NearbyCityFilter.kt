package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.FilterCity

// Фильтры «Откуда» и «Куда» ленты «Ближайшие поездки» (просьба Александра 16.10.2026: «нужны
// только поездки из Баймака или из Сибая в любую сторону»).
//
// Почему не два ряда чипов, как в ленте курьера. Фильтры ленты сведены в ОДИН ряд намеренно
// (MapScreen.kt, пояснение 2026-08-03): два ряда выталкивали карточки поездок за край экрана.
// Поэтому здесь — два чипа в том же ряду, а города выбираются в шторке. Выбранное видно на чипе:
// «Откуда · Баймак +1». Список городов даёт сервер — только те, где поездки сейчас есть.

/** Подпись чипа: «Откуда», «Откуда · Баймак», «Откуда · Баймак +1». */
internal fun cityChipLabel(base: String, chosen: List<String>): String = when (chosen.size) {
    0 -> base
    1 -> "$base · ${chosen[0]}"
    else -> "$base · ${chosen[0]} +${chosen.size - 1}"
}

/** Что выбрано (русские имена: сервер сам ищет и по башкирскому) и из чего выбирать. */
@Stable
internal class NearbyCityFilterState {
    var from by mutableStateOf<Set<String>>(emptySet())
    var to by mutableStateOf<Set<String>>(emptySet())
    var fromOptions by mutableStateOf<List<FilterCity>>(emptyList())
    var toOptions by mutableStateOf<List<FilterCity>>(emptyList())
    /** Найденные поиском (город или деревня вне топа): их имя на двух языках — для подписи чипа. */
    var found by mutableStateOf<List<FilterCity>>(emptyList())
    val active: Boolean get() = from.isNotEmpty() || to.isNotEmpty()
    /** Есть что выбрать или что снять: тогда чипы на месте, даже если рядом поездок нет. */
    val offered: Boolean get() = active || fromOptions.isNotEmpty() || toOptions.isNotEmpty()

    fun clear() {
        from = emptySet()
        to = emptySet()
    }
}

/**
 * Состояние фильтров городов. Список городов перечитывает вместе с лентой ([reloadKey] — её «Обновить»
 * и авто-обновление): город с новой поездкой появляется сам, сбой сети при открытии карты не прячет
 * чипы до следующего входа. Старый сервер без `/rides/cities` — чипов нет.
 */
@Composable
internal fun rememberNearbyCityFilter(reloadKey: Int): NearbyCityFilterState {
    val state = remember { NearbyCityFilterState() }
    LaunchedEffect(reloadKey) {
        ApiClient.getRideCities().onSuccess {
            state.fromOptions = it.from
            state.toOptions = it.to
        }
    }
    return state
}

/** Выбранные места на языке человека: сначала из списка (популярные — первыми), потом найденные поиском. */
@Composable
private fun chosenNames(chosen: Set<String>, options: List<FilterCity>, found: List<FilterCity>): List<String> {
    val known = (options + found).distinctBy { it.ru }
    return known.filter { it.ru in chosen }.map { appText(it.ru, it.ba) } + chosen.filter { c -> known.none { it.ru == c } }
}

/**
 * Чипы «Откуда» и «Куда» в общем ряду фильтров ленты (дальше — «Когда» и «Фильтры», NearbyFiltersPanel.kt).
 * Нет городов с поездками (старый сервер, пустая база) — не рисуем ничего: пустая шторка хуже.
 */
@Composable
internal fun RowScope.NearbyCityChips(state: NearbyCityFilterState, chipModifier: Modifier) {
    if (!state.offered) return
    var sheetForFrom by remember { mutableStateOf<Boolean?>(null) }   // true — «Откуда», false — «Куда»
    NearbyFilterChip(
        Icons.Default.Place,
        cityChipLabel(appText("Откуда", "Ҡайҙан"), chosenNames(state.from, state.fromOptions, state.found)),
        state.from.isNotEmpty(),
        modifier = chipModifier,
    ) { sheetForFrom = true }
    NearbyFilterChip(
        Icons.Default.Flag,
        cityChipLabel(appText("Куда", "Ҡайҙа"), chosenNames(state.to, state.toOptions, state.found)),
        state.to.isNotEmpty(),
        modifier = chipModifier,
    ) { sheetForFrom = false }
    sheetForFrom?.let { isFrom ->
        NearbyCitySheet(
            title = if (isFrom) appText("Откуда", "Ҡайҙан") else appText("Куда", "Ҡайҙа"),
            anyLabel = if (isFrom) appText("Все города", "Бөтә ҡалалар") else appText("Любое направление", "Теләһә ҡайҙа"),
            options = if (isFrom) state.fromOptions else state.toOptions,
            found = state.found,
            chosen = if (isFrom) state.from else state.to,
            onChange = { if (isFrom) state.from = it else state.to = it },
            onFound = { state.found = (state.found + it).distinctBy { c -> c.ru } },
            onDismiss = { sheetForFrom = null },
        )
    }
}

/**
 * Шторка выбора мест: поиск по справочнику (21 город, райцентры, деревни РБ и приграничья —
 * та же строка, что в публикации поездки), «Все города» снимает выбор, ниже — галочки: сначала
 * найденные поиском, потом города, где поездки есть сейчас. Можно несколько.
 */
@Composable
private fun NearbyCitySheet(
    title: String,
    anyLabel: String,
    options: List<FilterCity>,
    found: List<FilterCity>,
    chosen: Set<String>,
    onChange: (Set<String>) -> Unit,
    onFound: (FilterCity) -> Unit,
    onDismiss: () -> Unit,
) {
    NearbySheet(title, onDismiss = onDismiss) {
        SettlementPickField(appText("Найти город или деревню", "Ҡала йәки ауыл табыу"), picked = null) { s ->
            if (s != null) {
                onFound(FilterCity(s.nameRu, s.nameBa ?: s.nameRu))
                onChange(chosen + s.nameRu)
            }
        }
        NearbyCheckRow(anyLabel, chosen.isEmpty()) { onChange(emptySet()) }
        (found.filter { f -> f.ru in chosen && options.none { it.ru == f.ru } } + options).forEach { city ->
            val on = city.ru in chosen
            NearbyCheckRow(appText(city.ru, city.ba), on) { onChange(if (on) chosen - city.ru else chosen + city.ru) }
        }
        AppButton(appText("Готово", "Әҙер"), onDismiss, modifier = Modifier.padding(top = CanonSpace.md))
    }
}

/**
 * Строка-галочка шторок ленты (города, условия, день): вся строка — цель нажатия ≥48dp, читалка
 * экрана слышит «флажок, отмечен» ([role] = RadioButton — «переключатель», для выбора одного из).
 */
@Composable
internal fun NearbyCheckRow(
    label: String,
    selected: Boolean,
    icon: ImageVector? = null,
    role: Role = Role.Checkbox,
    onToggle: () -> Unit,
) {
    val bg by animateColorAsState(if (selected) CanonMint else CanonSurface, tween(CanonMotion.QUICK), label = "check-row")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(bg, CanonItemShape)
            .toggleable(value = selected, role = role, onValueChange = { onToggle() })
            .padding(horizontal = CanonSpace.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(end = CanonSpace.md).size(20.dp))
        }
        Text(label, color = CanonText, style = CanonBody, modifier = Modifier.weight(1f))
        AnimatedVisibility(
            visible = selected,
            enter = fadeIn(tween(CanonMotion.QUICK)) + scaleIn(tween(CanonMotion.QUICK)),
            exit = fadeOut(tween(CanonMotion.QUICK)) + scaleOut(tween(CanonMotion.QUICK)),
        ) {
            Icon(Icons.Default.Check, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
        }
    }
}
