package com.yuldash.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Брендовое интро ПЕРВОГО запуска (→ онбординг). Три акта, ~3.25с, тап = скип:
 *  1) под лого проявляется смысл «Попутчик» (RU);
 *  2) «Попутчик» морфит в бренд «Юлдаш» (crossfade + scale);
 *  3) слоган кроссфейдит RU→BA.
 * Лого появляется мягко (без scale-pop), продолжая системный сплэш — это убирает «дубль» лого.
 * Уважает reduced-motion (если системные анимации выключены — сразу финальный кадр, короткая пауза).
 * Тексты намеренно показывают оба языка по очереди (это сам смысл интро), не зависят от текущего языка.
 */
@Composable
internal fun IntroScreen(onComplete: () -> Unit) {
    val ctx = LocalContext.current
    val reduceMotion = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                ctx.contentResolver,
                android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
    var phase by remember { mutableIntStateOf(if (reduceMotion) 2 else 0) }
    var sloganBa by remember { mutableStateOf(reduceMotion) }
    var done by remember { mutableStateOf(false) }
    fun finish() {
        if (!done) {
            done = true
            onComplete()
        }
    }

    LaunchedEffect(Unit) {
        if (reduceMotion) {
            delay(900); finish(); return@LaunchedEffect
        }
        delay(1100); phase = 1       // «Попутчик» → «Юлдаш»
        delay(850); phase = 2        // показать слоган (RU)
        delay(550); sloganBa = true  // слоган RU → BA
        delay(750); finish()         // ~3.25с → онбординг
    }

    val logoAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(if (reduceMotion) 0 else 380),
        label = "introLogoAlpha",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0B6B3A), Color(0xFF073F25))))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { finish() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                modifier = Modifier.size(132.dp).alpha(logoAlpha),
                shape = CircleShape,
                color = Color.White,
                shadowElevation = 18.dp,
            ) {
                Image(
                    painter = painterResource(R.drawable.yuldash_logo),
                    contentDescription = "Юлдаш",
                    modifier = Modifier.padding(22.dp),
                )
            }
            Spacer(Modifier.height(26.dp))
            // Акт 1→2: смысл «Попутчик» превращается в бренд «Юлдаш».
            AnimatedContent(
                targetState = phase >= 1,
                transitionSpec = {
                    (fadeIn(tween(460)) + scaleIn(initialScale = 0.9f, animationSpec = tween(460)))
                        .togetherWith(fadeOut(tween(340)) + scaleOut(targetScale = 1.06f, animationSpec = tween(340)))
                },
                label = "wordMorph",
            ) { isBrand ->
                Text(
                    if (isBrand) "Юлдаш" else "Попутчик",
                    color = Color.White,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Spacer(Modifier.height(8.dp))
            // Акт 3: слоган RU → BA.
            AnimatedVisibility(visible = phase >= 2, enter = fadeIn(tween(400))) {
                AnimatedContent(
                    targetState = sloganBa,
                    transitionSpec = { fadeIn(tween(460)).togetherWith(fadeOut(tween(340))) },
                    label = "sloganMorph",
                ) { ba ->
                    Text(
                        if (ba) "Үҙебеҙҙекеләр араһында юллашыу" else "Поездки между своими",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}
