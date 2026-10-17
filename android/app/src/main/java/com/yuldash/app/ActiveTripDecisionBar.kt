package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

// Закреплённая нижняя панель экрана активной попутки (вариант B). Вынесена из
// BookingActiveTripScreen.kt (храповик размера файлов, 17.10.2026) вместе с посадкой:
// посадку отмечает водитель, а у пассажира до неё своей главной кнопки нет.

/**
 * «Написать» + главное действие + (водителю после «Выехал» и «Подъезжаю», пока главная — не
 * «Завершить») «Завершить поездку»: `activeTripShowsDriverFinish`, RideshareTripSteps.kt.
 *
 * [primaryText] — null у пассажира до посадки: «Я сел» больше нет, посадку отмечает водитель
 * (решение 17.10.2026). Тогда над «Написать» стоит [boarding] — «Водитель отметит посадку»
 * и «Сообщить о проблеме»; у водителя «в пути» там же «Пассажир в машине» и «Отменить»
 * (RideshareBoarding.kt, `activeTripBoardingSlot`).
 */
@Composable
internal fun ActiveTripDecisionBar(
    primaryText: String?,
    onMessage: () -> Unit,
    onPrimary: () -> Unit,
    finishText: String? = null,
    onFinish: () -> Unit = {},
    unread: Int = 0,   // новые сообщения собеседника — бейдж на «Написать», чтобы не пропустить
    boarding: (@Composable () -> Unit)? = null,
) {
    val largeText = LocalDensity.current.fontScale >= 1.2f
    Surface(color = CanonSurface, shadowElevation = CanonDepth.sheet) {
        val barModifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = CanonSpace.lg, vertical = CanonSpace.md)
        Column(barModifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            boarding?.invoke()
            if (largeText) {
                Box(Modifier.fillMaxWidth()) {
                    AppButton(
                        text = appText("Написать", "Яҙырға"),
                        onClick = onMessage,
                        style = AppButtonStyle.Secondary,
                        icon = Icons.Default.ChatBubbleOutline,
                    )
                    if (unread > 0) UnreadBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = CanonSpace.xs, y = -CanonSpace.xs))
                }
                if (primaryText != null) AppButton(text = primaryText, onClick = onPrimary, icon = Icons.Default.KeyboardArrowRight)
            } else {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(0.9f).fillMaxHeight()) {
                        AppButton(
                            text = appText("Написать", "Яҙырға"),
                            onClick = onMessage,
                            modifier = Modifier.fillMaxSize(),
                            style = AppButtonStyle.Secondary,
                            fillWidth = false,
                        )
                        if (unread > 0) UnreadBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = CanonSpace.xs, y = -CanonSpace.xs))
                    }
                    if (primaryText != null) {
                        AppButton(
                            text = primaryText,
                            onClick = onPrimary,
                            modifier = Modifier.weight(1.35f).fillMaxHeight(),
                            icon = Icons.Default.KeyboardArrowRight,
                            fillWidth = false,
                        )
                    }
                }
            }
            if (finishText != null) {
                AppButton(
                    text = finishText,
                    onClick = onFinish,
                    style = AppButtonStyle.Secondary,
                    height = 48.dp,
                )
            }
        }
    }
}
