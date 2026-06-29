package com.yuldash.app

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Gold = Color(0xFFD89B12)
private val GreenTop = Color(0xFF0B6B3A)
private val GreenBottom = Color(0xFF05301D)

/** Слово, проявляющееся ПО БУКВАМ (stagger) с премиум-easing — fade + подъём снизу.
 *  При visible=false буквы каскадом улетают вверх (для морфа смысла в бренд). */
@Composable
private fun StaggerWord(text: String, visible: Boolean, fontSize: TextUnit, color: Color = Color.White) {
    Row {
        text.forEachIndexed { i, ch ->
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(620, i * 45, EaseOutExpo)) +
                    slideInVertically(tween(700, i * 45, EaseOutExpo)) { it / 2 },
                exit = fadeOut(tween(300, i * 22)) +
                    slideOutVertically(tween(360, i * 22)) { -it / 3 },
            ) {
                Text(ch.toString(), color = color, fontSize = fontSize, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
        }
    }
}

/**
 * Брендовое интро ПЕРВОГО запуска (→ онбординг) — кинематографично, уровень дорогого бренда.
 * Акт 0: лого всплывает (bloom-сияние + пружинная посадка + focus-blur на API 31+).
 * Акт 1: смысл «Попутчик» проявляется по буквам (EaseOutExpo).
 * Акт 2: «Попутчик» каскадом улетает, «Юлдаш» появляется по буквам + золотая черта (цвет бренда) чертится из центра.
 * Акт 3: слоган RU→BA (fade + подъём). Финал: плавный fade-zoom выход в онбординг.
 * Тап = скип. Уважает reduced-motion (анимации выкл → сразу финальный кадр).
 */
@Composable
internal fun IntroScreen(onComplete: () -> Unit) {
    val ctx = LocalContext.current
    val reduceMotion = remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) == 0f
        }.getOrDefault(false)
    }

    var showMeaning by remember { mutableStateOf(false) }              // «Попутчик» (в reduceMotion не показываем)
    var showBrand by remember { mutableStateOf(reduceMotion) }        // «Юлдаш»
    var showUnderline by remember { mutableStateOf(reduceMotion) }
    var showSlogan by remember { mutableStateOf(reduceMotion) }
    var sloganBa by remember { mutableStateOf(reduceMotion) }
    var exiting by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    fun finish() { if (!done) { done = true; onComplete() } }

    val logoScale = remember { Animatable(if (reduceMotion) 1f else 0.84f) }
    val logoAlpha = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val glow = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val drift = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        if (reduceMotion) { delay(1000); finish(); return@LaunchedEffect }
        launch { drift.animateTo(1.05f, tween(4600, easing = EaseInOutSine)) }   // медленный «дыхательный» зум
        launch { logoAlpha.animateTo(1f, tween(540, easing = EaseOutExpo)) }
        launch { glow.animateTo(1f, tween(950, easing = EaseOutExpo)) }
        logoScale.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow))   // мягкая пружина
        delay(110)
        showMeaning = true
        delay(1000)
        showMeaning = false; showBrand = true        // морф: смысл → бренд
        delay(320); showUnderline = true             // золотая черта
        delay(700); showSlogan = true                // слоган RU
        delay(880); sloganBa = true                  // слоган RU → BA
        delay(1000); exiting = true                  // кинематографичный выход
        delay(430); finish()
    }

    val underline by animateFloatAsState(if (showUnderline) 1f else 0f, tween(780, easing = EaseOutExpo), label = "ul")
    val exitAlpha by animateFloatAsState(if (exiting) 0f else 1f, tween(430, easing = EaseInOutSine), label = "exA")
    val exitScale by animateFloatAsState(if (exiting) 1.05f else 1f, tween(430, easing = EaseInOutSine), label = "exS")

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(GreenTop, GreenBottom)))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { finish() },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Color(0x40000000)), radius = 1500f)))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = exitAlpha
                scaleX = exitScale * drift.value
                scaleY = exitScale * drift.value
            },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(230.dp)
                        .graphicsLayer { alpha = glow.value * 0.45f }
                        .background(Brush.radialGradient(listOf(Color.White.copy(0.32f), Color.Transparent))),
                )
                Surface(
                    modifier = Modifier.size(132.dp)
                        .graphicsLayer { scaleX = logoScale.value; scaleY = logoScale.value; alpha = logoAlpha.value }
                        .then(
                            if (Build.VERSION.SDK_INT >= 31 && logoAlpha.value < 0.98f)
                                Modifier.blur(((1f - logoAlpha.value) * 16).dp) else Modifier,
                        ),
                    shape = CircleShape, color = Color.White, shadowElevation = 22.dp,
                ) {
                    Image(painterResource(R.drawable.yuldash_logo), "Юлдаш", Modifier.padding(22.dp))
                }
            }
            Spacer(Modifier.height(34.dp))
            Box(modifier = Modifier.height(86.dp), contentAlignment = Alignment.TopCenter) {
                StaggerWord("Попутчик", showMeaning, 38.sp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    StaggerWord("Юлдаш", showBrand, 44.sp)
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier.width(70.dp).height(3.dp)
                            .graphicsLayer { scaleX = underline }
                            .background(Gold, RoundedCornerShape(2.dp)),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            AnimatedVisibility(showSlogan, enter = fadeIn(tween(560, easing = EaseOutExpo))) {
                AnimatedContent(
                    targetState = sloganBa,
                    transitionSpec = {
                        (fadeIn(tween(640, easing = EaseOutExpo)) + slideInVertically(tween(640, easing = EaseOutExpo)) { it / 3 })
                            .togetherWith(fadeOut(tween(360)) + slideOutVertically(tween(360)) { -it / 3 })
                    },
                    label = "slo",
                ) { ba ->
                    Text(
                        if (ba) "Үҙебеҙҙекеләр араһында юллашыу" else "Поездки между своими",
                        color = Color.White.copy(0.92f), fontSize = 15.sp, letterSpacing = 0.5.sp,
                    )
                }
            }
        }
    }
}
