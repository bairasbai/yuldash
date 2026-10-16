package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Выбранные города на языке человека, в порядке списка (популярные — первыми). */
@Composable
private fun chosenNames(chosen: Set<String>, options: List<FilterCity>): List<String> =
    options.filter { it.ru in chosen }.map { appText(it.ru, it.ba) } + chosen.filter { c -> options.none { it.ru == c } }

/**
 * Чипы «Откуда» и «Куда» в общем ряду фильтров ленты + тонкая черта после них, как между «когда» и
 * «на чём». Нет городов с поездками (старый сервер, пустая база) — не рисуем ничего: пустая шторка хуже.
 */
@Composable
internal fun RowScope.NearbyCityChips(state: NearbyCityFilterState, chipModifier: Modifier) {
    if (!state.offered) return
    var sheetForFrom by remember { mutableStateOf<Boolean?>(null) }   // true — «Откуда», false — «Куда»
    NearbyFilterChip(
        Icons.Default.Place,
        cityChipLabel(appText("Откуда", "Ҡайҙан"), chosenNames(state.from, state.fromOptions)),
        state.from.isNotEmpty(),
        modifier = chipModifier,
    ) { sheetForFrom = true }
    NearbyFilterChip(
        Icons.Default.Flag,
        cityChipLabel(appText("Куда", "Ҡайҙа"), chosenNames(state.to, state.toOptions)),
        state.to.isNotEmpty(),
        modifier = chipModifier,
    ) { sheetForFrom = false }
    Box(Modifier.align(Alignment.CenterVertically).padding(end = CanonSpace.sm).width(1.dp).height(22.dp).background(CanonBorder))
    sheetForFrom?.let { isFrom ->
        NearbyCitySheet(
            title = if (isFrom) appText("Откуда", "Ҡайҙан") else appText("Куда", "Ҡайҙа"),
            anyLabel = if (isFrom) appText("Все города", "Бөтә ҡалалар") else appText("Любое направление", "Теләһә ҡайҙа"),
            options = if (isFrom) state.fromOptions else state.toOptions,
            chosen = if (isFrom) state.from else state.to,
            onChange = { if (isFrom) state.from = it else state.to = it },
            onDismiss = { sheetForFrom = null },
        )
    }
}

/** Шторка выбора городов: «Все города» снимает выбор, остальные строки — галочки, можно несколько. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NearbyCitySheet(
    title: String,
    anyLabel: String,
    options: List<FilterCity>,
    chosen: Set<String>,
    onChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    AppModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = CanonBg) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = CanonSpace.lg).navigationBarsPadding().padding(bottom = CanonSpace.lg)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.xs),
        ) {
            Text(title, color = CanonText, style = CanonTitle, modifier = Modifier.padding(bottom = CanonSpace.sm))
            CityRow(anyLabel, chosen.isEmpty()) { onChange(emptySet()) }
            options.forEach { city ->
                val on = city.ru in chosen
                CityRow(appText(city.ru, city.ba), on) { onChange(if (on) chosen - city.ru else chosen + city.ru) }
            }
            AppButton(appText("Готово", "Әҙер"), onDismiss, modifier = Modifier.padding(top = CanonSpace.md))
        }
    }
}

/** Строка-галочка: вся строка — цель нажатия ≥48dp, читалка экрана слышит «флажок, отмечен». */
@Composable
private fun CityRow(label: String, selected: Boolean, onToggle: () -> Unit) {
    val bg by animateColorAsState(if (selected) CanonMint else CanonSurface, tween(CanonMotion.QUICK), label = "city-row")
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(bg, CanonItemShape)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = CanonSpace.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
