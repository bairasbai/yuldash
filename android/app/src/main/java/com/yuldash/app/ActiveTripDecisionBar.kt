package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

// Только повторная заметка о посадке; сама карточка и её состояние остаются на месте.
internal val LocalCompactActiveTripFooter = compositionLocalOf { false }

/** Закреплённые действия поездки. Посадка остаётся одним постоянным дочерним блоком. */
@Composable
internal fun ActiveTripDecisionBar(
    primaryText: String?,
    onMessage: () -> Unit,
    onPrimary: () -> Unit,
    finishText: String? = null,
    onFinish: () -> Unit = {},
    unread: Int = 0,
    boarding: (@Composable () -> Unit)? = null,
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val largeText = density.fontScale >= 1.2f
    val message = appText("Написать", "Яҙырға")
    val measurer = rememberTextMeasurer()
    // Button/OutlinedButton задают labelLarge; AppButtonContent меняет только16sp и Bold.
    // Остальные параметры, включая letterSpacing/fontFamily/lineHeight, наследуются.
    val buttonStyle = LocalTextStyle.current.merge(MaterialTheme.typography.labelLarge)
        .merge(TextStyle(fontSize = CanonButton.fontSize, fontWeight = FontWeight.Bold))
    val buttonPadding = ButtonDefaults.ContentPadding
    val paddingPx = with(density) {
        buttonPadding.calculateLeftPadding(direction).roundToPx() + buttonPadding.calculateRightPadding(direction).roundToPx()
    }
    val iconAndGapPx = with(density) { (CanonSpace.md + CanonSpace.sm).roundToPx() + CanonSpace.sm.roundToPx() }
    val messageWeight = 0.9f
    val primaryWeight = 1.35f
    Surface(color = CanonSurface, shadowElevation = CanonDepth.sheet) {
        val barModifier = Modifier.fillMaxWidth().navigationBarsPadding()
            .padding(horizontal = CanonSpace.lg, vertical = CanonSpace.md)
        BoxWithConstraints(barModifier) {
            val availablePx = constraints.maxWidth
            fun fits(text: String, buttonWidthPx: Int, icon: Boolean): Boolean {
                val textWidthPx = buttonWidthPx - paddingPx - if (icon) iconAndGapPx else 0
                if (textWidthPx <= 0) return false
                val layout = measurer.measure(text, buttonStyle, softWrap = false, maxLines = 1,
                    constraints = Constraints(maxWidth = textWidthPx))
                return layout.lineCount == 1 && !layout.hasVisualOverflow
            }
            val fullRowPx = availablePx - with(density) { CanonSpace.md.roundToPx() }
            // Проверяем каждую прежнюю weighted-ячейку отдельно, а не половину экрана.
            val fullMessagePx = (fullRowPx * messageWeight / (messageWeight + primaryWeight)).toInt()
            val fullPrimaryPx = (fullRowPx * primaryWeight / (messageWeight + primaryWeight)).toInt()
            val compactPrimaryPx = availablePx - with(density) { CanonSpace.huge.roundToPx() + CanonSpace.sm.roundToPx() }
            val compact = largeText && boarding != null && primaryText != null &&
                !(fits(message, fullMessagePx, false) && fits(primaryText, fullPrimaryPx, true)) &&
                fits(primaryText, compactPrimaryPx, true)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Один provider/call site во всех режимах: remember/scope/dialog не размонтируются.
                CompositionLocalProvider(LocalCompactActiveTripFooter provides compact) { boarding?.invoke() }
                if (compact) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(CanonSpace.huge)) {
                            OutlinedIconButton(onClick = onMessage, modifier = Modifier.fillMaxSize(), shape = CanonItemShape) {
                                Icon(Icons.Default.ChatBubbleOutline, contentDescription = message, tint = CanonGreen2)
                            }
                            if (unread > 0) UnreadBadge(unread, Modifier.align(Alignment.TopEnd)
                                .offset(x = CanonSpace.xs, y = -CanonSpace.xs))
                        }
                        AppButton(text = requireNotNull(primaryText), onClick = onPrimary,
                            modifier = Modifier.weight(1f), icon = Icons.Default.KeyboardArrowRight, fillWidth = false)
                    }
                } else if (largeText) {
                    Box(Modifier.fillMaxWidth()) {
                        AppButton(text = message, onClick = onMessage, style = AppButtonStyle.Secondary,
                            icon = Icons.Default.ChatBubbleOutline)
                        if (unread > 0) UnreadBadge(unread, Modifier.align(Alignment.TopEnd)
                            .offset(x = CanonSpace.xs, y = -CanonSpace.xs))
                    }
                    if (primaryText != null) AppButton(text = primaryText, onClick = onPrimary, icon = Icons.Default.KeyboardArrowRight)
                } else {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(messageWeight).fillMaxHeight()) {
                            AppButton(text = message, onClick = onMessage, modifier = Modifier.fillMaxSize(),
                                style = AppButtonStyle.Secondary, fillWidth = false)
                            if (unread > 0) UnreadBadge(unread, Modifier.align(Alignment.TopEnd)
                                .offset(x = CanonSpace.xs, y = -CanonSpace.xs))
                        }
                        if (primaryText != null) AppButton(text = primaryText, onClick = onPrimary,
                            modifier = Modifier.weight(primaryWeight).fillMaxHeight(),
                            icon = Icons.Default.KeyboardArrowRight, fillWidth = false)
                    }
                }
                if (finishText != null) AppButton(text = finishText, onClick = onFinish,
                    style = AppButtonStyle.Secondary, height = 48.dp)
            }
        }
    }
}
