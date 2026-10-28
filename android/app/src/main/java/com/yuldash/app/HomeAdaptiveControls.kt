package com.yuldash.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** Measure the actual font/density, including selected weight, before choosing a layout. */
internal fun homeLabelFits(measurer: TextMeasurer, text: String, style: TextStyle, widthPx: Int, lines: Int = 1): Boolean {
    if (widthPx <= 0) return false
    val layout = measurer.measure(AnnotatedString(text), style = style, maxLines = lines, constraints = Constraints(maxWidth = widthPx))
    if (layout.multiParagraph.didExceedMaxLines) return false
    return (0 until layout.lineCount).all { line ->
        val end = layout.getLineEnd(line)
        !layout.isLineEllipsized(line) && layout.getLineLeft(line) >= 0f && layout.getLineRight(line) <= widthPx &&
            (end == text.length || end <= 0 || !text[end - 1].isLetterOrDigit() || !text[end].isLetterOrDigit())
    }
}

@Composable
internal fun MapHeroActions(onFind: () -> Unit, onDriver: () -> Unit) {
    val find = appText("Найти попутку", "Юлдаш табыу")
    val driver = appText("Я водитель", "Мин йөрөтөүсе")
    val style = LocalTextStyle.current.merge(CanonCaption).copy(fontWeight = FontWeight.SemiBold)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val usable = maxWidth - CanonSpace.md
        val reserved = CanonSpace.md * 2 + 20.dp + CanonSpace.sm
        val horizontal = homeLabelFits(measurer, find, style, with(density) { (usable * (1.25f / 2.2f) - reserved).toPx().toInt() }, 2) &&
            homeLabelFits(measurer, driver, style, with(density) { (usable * (0.95f / 2.2f) - reserved).toPx().toInt() }, 2)
        val findButton: @Composable (Modifier) -> Unit = { modifier ->
            Button(onClick = onFind, modifier = modifier.heightIn(min = 56.dp), shape = CanonItemShape,
                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
                contentPadding = PaddingValues(horizontal = CanonSpace.md, vertical = CanonSpace.sm)) {
                Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(CanonSpace.sm))
                Text(find, style = style, maxLines = 2)
            }
        }
        val driverButton: @Composable (Modifier) -> Unit = { modifier ->
            Button(onClick = onDriver, modifier = modifier.heightIn(min = 56.dp), shape = CanonItemShape,
                colors = ButtonDefaults.buttonColors(containerColor = CanonGold, contentColor = CanonGoldInk),
                contentPadding = PaddingValues(horizontal = CanonSpace.md, vertical = CanonSpace.sm)) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = CanonGoldInk, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(CanonSpace.sm))
                Text(driver, style = style, maxLines = 2)
            }
        }
        if (horizontal) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
            findButton(Modifier.weight(1.25f)); driverButton(Modifier.weight(0.95f))
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            findButton(Modifier.fillMaxWidth()); driverButton(Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun ChatHomeTabs(tabs: List<Pair<String, LocalizedText>>, selected: String, unread: Int, onSelect: (String) -> Unit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = LocalTextStyle.current.merge(CanonMicro).copy(fontWeight = FontWeight.Bold)
    val count = if (unread > 99) "99+" else unread.toString()
    val badgeWidth = with(density) { measurer.measure(AnnotatedString(count), style).size.width.toDp() } + CanonSpace.sm
    val icons = listOf(Icons.Default.ChatBubble, Icons.Default.ListAlt, Icons.Default.Settings)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cell = (maxWidth - CanonSpace.sm * (tabs.size - 1)) / tabs.size
        val horizontal = tabs.all { (key, label) ->
            val reserved = CanonSpace.sm * 2 + 18.dp + CanonSpace.xs +
                if (key == "system" && unread > 0) maxOf(18.dp, badgeWidth) + CanonSpace.xs else 0.dp
            homeLabelFits(measurer, label.text(), style, with(density) { (cell - reserved).toPx().toInt() })
        }
        val button: @Composable (Int, Modifier) -> Unit = { index, modifier ->
            val (key, label) = tabs[index]
            FilledTonalButton(onClick = { onSelect(key) }, modifier = modifier.heightIn(min = 50.dp).testTag("home-chat-$key"),
                shape = CanonFieldShape, contentPadding = PaddingValues(horizontal = CanonSpace.sm),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (selected == key) CanonMint else CanonSurface,
                    contentColor = CanonText)) {
                Icon(icons[index], contentDescription = null, modifier = Modifier.size(18.dp), tint = if (selected == key) CanonGreen2 else CanonMuted)
                Spacer(Modifier.width(CanonSpace.xs))
                Text(label.text(), style = style, modifier = Modifier.weight(1f), maxLines = 1)
                // Бейдж непрочитанных уведомлений на кнопке «Система».
                if (key == "system" && unread > 0) {
                    Spacer(Modifier.width(CanonSpace.xs))
                    Box(Modifier.defaultMinSize(minWidth = 18.dp, minHeight = 18.dp).background(CanonRed, CircleShape)
                        .padding(horizontal = CanonSpace.xs).testTag("home-chat-unread"), contentAlignment = Alignment.Center) {
                        Text(count, color = CanonOnAccent, style = style, maxLines = 1)
                    }
                }
            }
        }
        if (horizontal) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            tabs.indices.forEach { button(it, Modifier.weight(1f)) }
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            tabs.indices.forEach { button(it, Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
internal fun YuldashBottomBar(selectedTab: HomeTab, onSelect: (HomeTab) -> Unit) {
    val items = listOf(
        Triple(HomeTab.Map, appText("Карта", "Карта"), R.drawable.yu_map_tab),
        Triple(HomeTab.Rides, appText("Поездки", "Сәфәрҙәр"), R.drawable.yu_trip_list),
        Triple(HomeTab.Request, appText("Заявка", "Ғариза"), R.drawable.yu_request_add),
        Triple(HomeTab.Chat, appText("Чат", "Чат"), R.drawable.yu_chat),
        Triple(HomeTab.Profile, appText("Профиль", "Профиль"), R.drawable.yu_profile),
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val style = LocalTextStyle.current.merge(CanonMicro)
    Surface(color = CanonSurface.copy(alpha = 0.98f),
        shape = RoundedCornerShape(topStart = CanonCardShape.topStart, topEnd = CanonCardShape.topEnd,
            bottomEnd = CornerSize(0.dp), bottomStart = CornerSize(0.dp)), shadowElevation = CanonDepth.sheet) {
        BoxWithConstraints(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min = 78.dp)
            .padding(horizontal = CanonSpace.xs, vertical = CanonSpace.xs)) {
            val cellPx = with(density) { (maxWidth / items.size).toPx().toInt() }
            val showLabels = items.all { (_, label, _) ->
                listOf(FontWeight.Medium, FontWeight.Bold).all { weight ->
                    homeLabelFits(measurer, label, style.copy(fontWeight = weight), cellPx)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                items.forEach { (tab, label, icon) ->
                    YuldashBottomItem(selectedTab == tab, label, icon, { onSelect(tab) }, showLabels,
                        badge = tab == HomeTab.Profile && NavSignals.payNowDebtKop.value > 0)
                }
            }
        }
    }
}

@Composable
private fun RowScope.YuldashBottomItem(selected: Boolean, label: String, iconRes: Int, onClick: () -> Unit, showLabel: Boolean, badge: Boolean) {
    val pillColor by animateColorAsState(if (selected) CanonGold else Color.Transparent, tween(CanonMotion.NORMAL), label = "navPill")
    val iconTint by animateColorAsState(if (selected) CanonGoldInk else CanonMuted, tween(CanonMotion.NORMAL), label = "navTint")
    val labelColor by animateColorAsState(if (selected) CanonGreen else CanonMutedStrong, tween(CanonMotion.NORMAL), label = "navLabel")
    val iconScale by animateFloatAsState(if (selected) 1.12f else 1f, tween(CanonMotion.NORMAL), label = "navScale")
    val badgeScale by animateFloatAsState(if (badge) 1f else 0f, tween(CanonMotion.QUICK), label = "navBadge")
    val interaction = remember { MutableInteractionSource() }
    Column(Modifier.weight(1f).heightIn(min = CanonSpace.huge)
        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
        .semantics(mergeDescendants = true) { this.selected = selected; role = Role.Tab; contentDescription = label }
        .padding(vertical = CanonSpace.xs), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = if (showLabel) Arrangement.spacedBy(CanonSpace.xs) else Arrangement.Center) {
        Box {
            Surface(color = pillColor, shape = CanonFieldShape) {
                Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier
                    .padding(horizontal = CanonSpace.md, vertical = CanonSpace.xs).size(21.dp)
                    .graphicsLayer { scaleX = iconScale; scaleY = iconScale }, tint = iconTint)
            }
            // Точка «загляни сюда»: срочный долг за дальнюю поездку. Живёт на вкладке, а не
            // только в кабинете, потому что пуш может не дойти вовсе — уведомления выключены,
            // нет устройства, антишторм. Без неё водитель зашёл бы в приложение, не увидел
            // требования и всё равно считался бы предупреждённым.
            if (badgeScale > 0.01f) Box(Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = CanonSpace.sm)
                .size(9.dp).graphicsLayer { scaleX = badgeScale; scaleY = badgeScale }
                .background(CanonRed, CircleShape).border(2.dp, CanonSurface, CircleShape).testTag("home-profile-debt"))
        }
        if (showLabel) Text(label, color = labelColor, style = CanonMicro, modifier = Modifier.fillMaxWidth(),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 1)
    }
}
