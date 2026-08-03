package com.yuldash.app

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * ════════════════════════════════════════════════════════════════════════════
 *  UiKit — продакшен-набор переиспользуемых UI-компонентов Юлдаш
 * ════════════════════════════════════════════════════════════════════════════
 *
 *  Зачем: один источник правды для кнопок, состояний (загрузка/пусто/ошибка),
 *  скелетонов и карточек. Раньше каждый экран лепил это вручную
 *  (Button+CircularProgressIndicator копировался 4 раза, у каждого списка свой
 *  лоадер). Теперь — один компонент, единый вид, единое поведение.
 *
 *  Принципы (как у топ-стартапа):
 *   • Переиспользование — собираешь экран из готовых блоков, не из примитивов.
 *   • Все состояния из коробки — loading / empty / error / content (не только «успех»).
 *   • Доступность — тач-цель ≥ 54dp, текст в sp (уважает системный размер),
 *     цвета только из Canon* (контраст + тёмная тема бесплатно).
 *   • Адаптивность — fillWidth, доли ширины у скелетонов, длинный BA-текст не ломает.
 *   • Двуязычие — компонент НЕ хардкодит текст: RU/BA передаёт вызывающий через appText().
 *
 *  API (дизайн пропсов):
 *   AppButton(text, onClick, style, icon?, loading, enabled, fillWidth)
 *   AppStateContainer(loading, error, items, onRetry) { items -> ... }
 *   AppLoading(label?) · AppErrorState(onRetry, title?, text?) · AppEmptyState(title, text, icon)
 *   SkeletonBox(widthFraction, height) · SkeletonCard(lines) · AppCard(onClick?) { ... }
 *   SectionHeader(title, subtitle?)
 *
 *  Пример (типовой список с сервера):
 *   AppStateContainer(
 *       loading = state.loading, error = state.error,
 *       items = state.rides, onRetry = { reload++ },
 *       emptyTitle = appText("Поездок пока нет", "Сәфәрҙәр әлегә юҡ"),
 *       emptyText  = appText("Появятся — покажем здесь", "Барлыҡҡа килһә — күрһәтәбеҙ"),
 *       loadingContent = { Column { repeat(3) { SkeletonCard() } } },
 *   ) { rides -> rides.forEach { RideCard(it) } }
 *
 *  Лучшие практики:
 *   • Не показывай спиннер поверх уже загруженных данных — `AppStateContainer`
 *     уходит в loading только когда `items` пуст (иначе показывает старые + тихо обновляет).
 *   • Кнопку с сетевым запросом всегда делай `loading=`, не блокируй экран целиком.
 *   • Для списков предпочитай SkeletonCard спиннеру — пользователь видит «форму» контента.
 */

// ─────────────────────────── Кнопки ───────────────────────────

/** Визуальные стили кнопки. Primary — основное действие, Accent — золотой акцент (бренд),
 *  Secondary — контурная (второстепенное), Danger — опасное (отмена/SOS-подтверждение). */
internal enum class AppButtonStyle { Primary, Accent, Secondary, Danger }

/**
 * Единая кнопка Юлдаш. Заменяет россыпь `Button { if (loading) CircularProgressIndicator else Text }`.
 *
 * @param text   надпись (двуязычие — передаёт вызывающий через appText)
 * @param loading показывает спиннер и блокирует нажатие (для сетевых действий)
 * @param icon   опциональная ведущая иконка
 * @param fillWidth растягивать по ширине (по умолчанию да — мобильный паттерн)
 */
@Composable
internal fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AppButtonStyle = AppButtonStyle.Primary,
    icon: ImageVector? = null,
    loading: Boolean = false,
    enabled: Boolean = true,
    fillWidth: Boolean = true,
    height: Dp = 54.dp,
) {
    val shape = RoundedCornerShape(16.dp)
    val base = modifier
        .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
        .heightIn(min = height)   // тач-цель ≥ 48dp (доступность)
    val container = when (style) {
        AppButtonStyle.Primary -> CanonGreen2
        AppButtonStyle.Accent -> CanonGold
        AppButtonStyle.Secondary -> Color.Transparent
        AppButtonStyle.Danger -> CanonRed
    }
    val contentColor = when (style) {
        AppButtonStyle.Accent -> CanonGoldInk
        AppButtonStyle.Secondary -> CanonGreen2
        else -> Color.White
    }
    val realEnabled = enabled && !loading
    if (style == AppButtonStyle.Secondary) {
        OutlinedButton(
            onClick = onClick, enabled = realEnabled, modifier = base, shape = shape,
            border = BorderStroke(1.5.dp, if (realEnabled) CanonGreen2 else CanonBorder),
        ) { AppButtonContent(text, icon, loading, contentColor) }
    } else {
        Button(
            onClick = onClick, enabled = realEnabled, modifier = base, shape = shape,
            colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = contentColor),
        ) { AppButtonContent(text, icon, loading, contentColor) }
    }
}

@Composable
private fun AppButtonContent(text: String, icon: ImageVector?, loading: Boolean, tint: Color) {
    if (loading) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), color = tint, strokeWidth = 2.dp)
    } else {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = tint)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = tint)
    }
}

// ─────────────────────────── Карточка-обёртка ───────────────────────────

/** Стандартная карточка-поверхность Юлдаш: CanonSurface + бордер + форма + тень.
 *  Убирает копипасту `Card(colors=…, shape=…, elevation=…, border=…)` по экранам.
 *  onClick != null → лёгкое сжатие при нажатии (bounceClick). Паддинг задаёт контент. */
@Composable
internal fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: Shape = CanonCardShape,
    elevation: Dp = 1.dp,
    content: @Composable () -> Unit,
) {
    val m = (if (onClick != null) modifier.bounceClick(onClick) else modifier).fillMaxWidth()
    Card(
        modifier = m,
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = shape,
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = BorderStroke(1.dp, CanonBorder),
    ) { Column { content() } }
}

/** Заголовок секции: жирный титул + опц. подпись. Единая типографика разделов. */
@Composable
internal fun SectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // По шкале: заголовок раздела крупный, но НЕ жирный до крика (19 SemiBold вместо 20 Black).
        // Иерархию здесь держит размер, а не вес — так работает спокойная премиальность.
        Text(title, color = CanonText, style = CanonHeading)
        if (subtitle != null) Text(subtitle, color = CanonMuted, style = CanonCaption)
    }
}

// ─────────────────────────── Скелетоны (загрузка) ───────────────────────────

/** Пульсирующий плейсхолдер-блок для скелетонов. widthFraction — доля ширины (адаптив). */
@Composable
internal fun SkeletonBox(
    modifier: Modifier = Modifier,
    widthFraction: Float = 1f,
    height: Dp = 14.dp,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.45f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(820), RepeatMode.Reverse), label = "skeletonAlpha",
    )
    Box(
        modifier
            .then(if (widthFraction >= 1f) Modifier.fillMaxWidth() else Modifier.fillMaxWidth(widthFraction))
            .height(height)
            .clip(shape)
            .background(CanonMint.copy(alpha = alpha))
    )
}

/** Скелетон-карточка под загрузку списка: показывает «форму» контента вместо голого спиннера. */
@Composable
internal fun SkeletonCard(lines: Int = 3, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SkeletonBox(widthFraction = 0.6f, height = 16.dp)
            repeat(lines) { i ->
                SkeletonBox(widthFraction = if (i == lines - 1) 0.4f else 0.85f, height = 12.dp)
            }
        }
    }
}

// ─────────────────────────── Состояния: loading / error / empty ───────────────────────────

/** Центрированный индикатор загрузки с опциональной подписью. */
@Composable
internal fun AppLoading(label: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(color = CanonGreen2, strokeWidth = 2.5.dp, modifier = Modifier.size(30.dp))
        if (label != null) Text(label, color = CanonMuted, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

/** Состояние ошибки (нет сети / сбой) с кнопкой «Повторить». Тексты по умолчанию двуязычны. */
@Composable
internal fun AppErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = appText("Что-то пошло не так", "Нимәлер дөрөҫ булманы"),
    text: String = appText("Проверь интернет и повтори", "Интернетты тикшереп ҡабатла"),
    retryLabel: String = appText("Повторить", "Ҡабатлау"),
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, CanonBorder),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(color = CanonDangerBg, shape = CircleShape) {
                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = CanonRed, modifier = Modifier.padding(15.dp).size(30.dp))
            }
            Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
            Text(text, color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
            AppButton(retryLabel, onRetry, style = AppButtonStyle.Primary, icon = Icons.Default.Refresh)
        }
    }
}

/**
 * Спокойная плашка-предупреждение поверх уже показанных данных: «связь пропала, на экране
 * может быть старое».
 *
 * Отличается от [AppErrorState] тем, что не заменяет собой контент — данные-то есть, просто
 * они могли устареть. Раньше такие ситуации молчали: обратный отсчёт предзаказа продолжал
 * тикать по замороженным цифрам, и человек шёл к дороге к машине, которой уже нет.
 */
@Composable
internal fun AppNoticeCard(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Default.CloudOff) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = CanonWarnBg,
        shape = CanonItemShape,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(20.dp))
            Text(text, color = CanonWarn, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ─────────────────────────── Обновление жестом ───────────────────────────

/**
 * «Потянуть вниз, чтобы обновить» — один компонент на всё приложение.
 *
 * Зачем: жест сверху вниз — базовый рефлекс в Яндекс Такси/Доставке и Самокате. До этого
 * в Юлдаше он не работал НИГДЕ: единственным способом обновить список было выйти с экрана
 * и зайти заново. Человек тянул экран, ничего не происходило, и он считал данные свежими.
 *
 * @param refreshing идёт ли обновление прямо сейчас (крутить индикатор)
 * @param onRefresh что вызвать по жесту — обычно тот же reload(), что и при входе на экран
 *
 * Оборачивай СКРОЛЛЯЩИЙСЯ контейнер (LazyColumn/Column со scroll) — иначе жесту не за что зацепиться.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppPullRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) { content() }
}

/** Дружелюбная заглушка «пусто» + опциональное действие. Делегирует к EmptyStateCard (единый вид). */
@Composable
internal fun AppEmptyState(
    title: String,
    text: String,
    icon: ImageVector = Icons.Default.Inbox,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) = EmptyStateCard(title = title, text = text, icon = icon, action = actionLabel, onAction = onAction)

/**
 * Универсальный контейнер состояний списка. Сам выбирает что показать:
 *  • пусто + грузим      → loadingContent (по умолчанию AppLoading)
 *  • пусто + ошибка      → AppErrorState(onRetry)
 *  • пусто               → AppEmptyState(empty*)
 *  • есть данные         → content(items)  (даже если идёт фоновое обновление — не мигаем спиннером)
 */
@Composable
internal fun <T> AppStateContainer(
    loading: Boolean,
    error: Boolean,
    items: List<T>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    emptyTitle: String = appText("Пока пусто", "Әлегә буш"),
    emptyText: String = appText("Здесь скоро появятся данные", "Бында тиҙҙән мәғлүмәт күренер"),
    emptyIcon: ImageVector = Icons.Default.Inbox,
    emptyActionLabel: String? = null,
    onEmptyAction: (() -> Unit)? = null,
    loadingContent: @Composable () -> Unit = { AppLoading() },
    content: @Composable (List<T>) -> Unit,
) {
    Box(modifier) {
        when {
            loading && items.isEmpty() -> loadingContent()
            error && items.isEmpty() -> AppErrorState(onRetry = onRetry)
            items.isEmpty() -> AppEmptyState(emptyTitle, emptyText, emptyIcon, emptyActionLabel, onEmptyAction)
            else -> content(items)
        }
    }
}
