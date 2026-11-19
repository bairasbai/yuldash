package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay

@Composable
internal fun NearbySearchHistory(
    history: NearbySearchHistoryState,
    onRepeat: (SearchRoute) -> Unit,
) {
    var leaving by remember(history) { mutableStateOf<Pair<Int, SearchHistoryEntry>?>(null) }
    LaunchedEffect(leaving) {
        if (leaving != null) {
            delay(CanonMotion.QUICK.toLong())
            leaving = null
        }
    }
    val rows = history.entries.toMutableList()
    leaving?.let { (index, entry) -> if (rows.none { it.route.key == entry.route.key }) rows.add(index.coerceAtMost(rows.size), entry) }
    AnimatedVisibility(
        visible = rows.isNotEmpty(),
        enter = fadeIn(tween(CanonMotion.QUICK)),
        exit = fadeOut(tween(CanonMotion.QUICK)),
    ) {
        var confirm by remember(history) { mutableStateOf(false) }
        Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
            Text(appText("Недавно искали", "Күптән түгел эҙләнеләр"), color = CanonText, style = CanonBodyStrong)
            rows.forEachIndexed { index, entry ->
                key(entry.route.key) {
                    val visibility = remember {
                        androidx.compose.animation.core.MutableTransitionState(false)
                    }
                    visibility.targetState = leaving?.second?.route?.key != entry.route.key
                    AnimatedVisibility(
                        visibleState = visibility,
                        enter = fadeIn(tween(CanonMotion.QUICK)),
                        exit = fadeOut(tween(CanonMotion.QUICK)),
                    ) {
                        val route = entry.route
                        val from = route.from.map { appText(it.ru, it.ba) }.joinToString(", ")
                            .ifBlank { appText("Все города", "Бөтә ҡалалар") }
                        val to = route.to.map { appText(it.ru, it.ba) }.joinToString(", ")
                            .ifBlank { appText("Любое направление", "Теләһә ҡайҙа") }
                        val name = "$from → $to"
                        val repeat = appText("Повторить поиск: $name", "Эҙләүҙе ҡабатларға: $name")
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                onClick = { history.repeat(route.key, onRepeat) },
                                enabled = leaving == null,
                                modifier = Modifier.weight(1f).heightIn(min = CanonSpace.huge)
                                    .semantics { contentDescription = repeat },
                                shape = CanonItemShape,
                                color = CanonSurface,
                            ) {
                                Text(name, color = CanonText, style = CanonBody, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(CanonSpace.md))
                            }
                            IconButton(onClick = { history.remove(route.key); leaving = index to entry }, enabled = leaving == null, modifier = Modifier.sizeIn(
                                minWidth = CanonSpace.huge, minHeight = CanonSpace.huge,
                            )) {
                                Icon(Icons.Default.DeleteOutline, appText("Удалить из истории: $name", "Тарихтан юйырға: $name"),
                                    tint = CanonText)
                            }
                        }
                    }
                }
            }
            TextButton(onClick = { confirm = true }, enabled = leaving == null, modifier = Modifier.heightIn(min = CanonSpace.huge)) {
                Text(appText("Очистить историю", "Тарихты таҙартыу"))
            }
        }
        if (confirm) AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(appText("Очистить историю поиска?", "Эҙләү тарихын таҙартырғамы?"), style = CanonTitle) },
            text = { Text(appText("Сохранённые адреса и подписки останутся.", "Һаҡланған адрестар һәм яҙылыуҙар ҡаласаҡ."), style = CanonBody) },
            confirmButton = {
                TextButton(onClick = { history.clear(); confirm = false }) { Text(appText("Очистить", "Таҙартыу")) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(appText("Оставить", "Ҡалдырыу")) }
            },
        )
    }
}
