package com.yuldash.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.isActive

/**
 * Чистый шелл главного экрана: держит выбранную вкладку (переживает поворот), рисует Scaffold с нижним
 * меню (YuldashBottomBar) и анимированно переключает тело вкладки. Тело каждой вкладки приходит слотом
 * `tabContent(tab, selectTab)` — так HomeShell не знает про данные/сеть и тестируется на JVM (Robolectric).
 * Аппаратная «Назад» с любой вкладки, кроме Карты, возвращает на Карту. Смену вкладки прокидываем наверх
 * (onTabChange) — чтобы «Назад» с под-экранов возвращался на активную вкладку, а не на Карту.
 */
@Composable
internal fun HomeShell(
    initialTab: HomeTab,
    onTabChange: (HomeTab) -> Unit = {},
    onSelectTab: (HomeTab, () -> Unit) -> Unit = { _, commit -> commit() },
    onOpenInstantOrder: () -> Unit = {},
    tabContent: @Composable (tab: HomeTab, selectTab: (HomeTab) -> Unit) -> Unit,
) {
    val generation by ApiClient.sessionChanges.collectAsState()
    var selectedTab by remember(initialTab) { mutableStateOf(initialTab) }   // поворот хранит vm.startHomeTab; старый снимок Home не подменяет явный переход
    val shellScope = rememberCoroutineScope()
    var selectionEpoch by remember { mutableIntStateOf(0) }
    val callbackEpoch = selectionEpoch
    fun selectTab(tab: HomeTab, afterSelection: () -> Unit = {}) {
        if (shellScope.isActive && callbackEpoch == selectionEpoch) {
            onSelectTab(tab) {
                selectedTab = tab
                selectionEpoch++
                afterSelection()
            }
        }
    }
    LaunchedEffect(selectedTab) { onTabChange(selectedTab) }

    BackHandler(enabled = selectedTab != HomeTab.Map) {
        selectTab(HomeTab.Map)
    }

    // Пока идёт поиск или сама поездка, нижнее меню прячется. Заказ уже живой: случайный
    // переход в другой раздел только теряет контекст, а освободившееся место нужно карте.
    val taxiOrderOnScreen = TaxiNavigationState.orderOnScreen(generation)
    Scaffold(
        containerColor = CanonBg,
        bottomBar = {
            AnimatedVisibility(
                visible = !taxiOrderOnScreen,
                enter = fadeIn(tween(CanonMotion.NORMAL)),
                exit = fadeOut(tween(CanonMotion.QUICK)),
            ) {
                YuldashBottomBar(
                    selectedTab = selectedTab,
                    onSelect = { selectTab(it) }
                )
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            // Полоска стоит НАД содержимым, а не поверх него: наложенная, она закрывала
            // переключатель сервисов, и тот торчал из-под неё краями.
            val renderedTrip = TaxiNavigationState.currentTrip(generation)
            ActiveTripBar(onOpen = {
                if (renderedTrip != null) TaxiNavigationState.runIfCurrentTrip(renderedTrip) {
                    if (!TaxiNavigationState.tripOnScreen(generation)) {
                        selectTab(HomeTab.Map, onOpenInstantOrder)
                    }
                }
            })
            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(CanonMotion.NORMAL)) +
                        slideInVertically(animationSpec = tween(CanonMotion.NORMAL)) { it / 18 })
                        .togetherWith(fadeOut(animationSpec = tween(CanonMotion.QUICK)))
                },
                label = "homeTab"
            ) { tab ->
                val renderedEpoch = selectionEpoch
                val gate = HomeTabActionGate(
                    accepts = { shellScope.isActive && tab == selectedTab && renderedEpoch == selectionEpoch },
                    choose = { target, after -> selectTab(target, after) },
                )
                CompositionLocalProvider(LocalHomeTabActionGate provides gate) {
                    tabContent(tab) { target -> gate.guard { selectTab(target) }() }
                }
            }
        }
    }
}

@Composable
internal fun YuldashBottomBar(
    selectedTab: HomeTab,
    onSelect: (HomeTab) -> Unit
) {
    Surface(
        color = CanonSurface.copy(alpha = 0.98f),
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        shadowElevation = CanonDepth.sheet
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()   // на жест-навигации иконки меню не уезжают под системную полосу
                .heightIn(min = 78.dp)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.Top
        ) {
            YuldashBottomItem(
                selected = selectedTab == HomeTab.Map,
                label = appText("Карта", "Карта"),
                iconRes = R.drawable.yu_map_tab,
                onClick = { onSelect(HomeTab.Map) }
            )
            YuldashBottomItem(
                selected = selectedTab == HomeTab.Rides,
                label = appText("Поездки", "Сәфәрҙәр"),
                iconRes = R.drawable.yu_trip_list,
                onClick = { onSelect(HomeTab.Rides) }
            )
            YuldashBottomItem(
                selected = selectedTab == HomeTab.Request,
                label = appText("Заявка", "Ғариза"),
                iconRes = R.drawable.yu_request_add,
                onClick = { onSelect(HomeTab.Request) }
            )
            YuldashBottomItem(
                selected = selectedTab == HomeTab.Chat,
                label = appText("Чат", "Чат"),
                iconRes = R.drawable.yu_chat,
                onClick = { onSelect(HomeTab.Chat) }
            )
            YuldashBottomItem(
                selected = selectedTab == HomeTab.Profile,
                label = appText("Профиль", "Профиль"),
                iconRes = R.drawable.yu_profile,
                onClick = { onSelect(HomeTab.Profile) },
                badge = NavSignals.payNowDebtKop.value > 0
            )
        }
    }
}

@Composable
private fun RowScope.YuldashBottomItem(
    selected: Boolean,
    label: String,
    iconRes: Int,
    onClick: () -> Unit,
    badge: Boolean = false
) {
    val pillColor by animateColorAsState(if (selected) CanonGold else Color.Transparent, tween(CanonMotion.NORMAL), label = "navPill")
    val iconTint by animateColorAsState(if (selected) CanonGoldInk else CanonMuted, tween(CanonMotion.NORMAL), label = "navTint")
    val labelColor by animateColorAsState(if (selected) CanonGreen else CanonMutedStrong, tween(CanonMotion.NORMAL), label = "navLabel")
    val iconScale by animateFloatAsState(if (selected) 1.12f else 1f, tween(CanonMotion.NORMAL), label = "navScale")
    val badgeScale by animateFloatAsState(if (badge) 1f else 0f, tween(CanonMotion.QUICK), label = "navBadge")
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected; role = Role.Tab }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box {
            Surface(
                color = pillColor,
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    painterResource(iconRes),
                    contentDescription = label,
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .size(21.dp)
                        .graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                    tint = iconTint
                )
            }
            // Точка «загляни сюда»: срочный долг за дальнюю поездку. Живёт на вкладке, а не
            // только в кабинете, потому что пуш может не дойти вовсе — уведомления выключены,
            // нет устройства, антишторм. Без неё водитель зашёл бы в приложение, не увидел
            // требования и всё равно считался бы предупреждённым.
            if (badgeScale > 0.01f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 2.dp, end = 8.dp)
                        .size(9.dp)
                        .graphicsLayer { scaleX = badgeScale; scaleY = badgeScale }
                        .background(CanonRed, CircleShape)
                        .border(2.dp, CanonSurface, CircleShape)
                )
            }
        }
        Text(
            text = label,
            color = labelColor,
            style = CanonMicro,
            modifier = Modifier.fillMaxWidth(),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
