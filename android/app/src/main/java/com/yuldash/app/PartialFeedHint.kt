package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
internal fun PartialFeedHint(visible: Boolean) {
    AnimatedVisibility(visible, enter = fadeIn(tween(CanonMotion.NORMAL)), exit = fadeOut(tween(CanonMotion.QUICK))) {
        Text(appText("Показаны не все — уточни село или дату", "Бөтәһе лә күрһәтелмәгән — ауылды йәки көндө аныҡла"),
            style = CanonCaption, color = CanonMuted)
    }
}
