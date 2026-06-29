package com.yuldash.app

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInCubic
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
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val Gold = Color(0xFFD89B12)
private val GoldLight = Color(0xFFFFF1CC)
private val GreenTop = Color(0xFF0B6B3A)
private val GreenBottom = Color(0xFF05301D)

/** Montserrat (сабсет с кириллицей+башкирским) — премиум-гарнитура интро. */
private val Montserrat = FontFamily(
    Font(R.font.montserrat_medium, FontWeight.Medium),
    Font(R.font.montserrat_black, FontWeight.Black),
)

/** Слово ПО БУКВАМ (stagger) — для «смысла» (Попутчик): по-человечески, скромно.
 *  Появление: fade + подъём (EaseOutExpo). Уход: каскад вверх (морф в бренд). */
@Composable
private fun StaggerWord(text: String, visible: Boolean, fontSize: TextUnit) {
    Row {
        text.forEachIndexed { i, ch ->
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(600, i * 42, EaseOutExpo)) +
                    slideInVertically(tween(680, i * 42, EaseOutExpo)) { it / 2 },
                exit = fadeOut(tween(300, i * 22)) +
                    slideOutVertically(tween(360, i * 22)) { -it / 3 },
            ) {
                Text(ch.toString(), color = Color.White, fontSize = fontSize, fontWeight = FontWeight.Medium, fontFamily = Montserrat, letterSpacing = 1.sp)
            }
        }
    }
}

/**
 * Кинематографичное интро ПЕРВОГО запуска (→ онбординг), уровень дорогого бренда.
 * Лого: bloom-сияние + золотое кольцо-ободок + сдержанная пружина (damping .72) + focus-blur (API 31+).
 * «Попутчик» — по буквам (смысл, скромно) → каскадом улетает.
 * «Юлдаш» — приходит уверенным ЦЕЛЫМ: fade+scale, tracking-in (буквы раздвигаются), и по нему
 *   проходит ЗОЛОТОЙ БЛИК (sheen sweep) — фирменная luxury-деталь. + золотая черта из центра.
 * Слоган RU→BA. Финал: ease-in fade-zoom «улёт» сцены в онбординг. Тап=скип. Reduced-motion → финал.
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

    var showMeaning by remember { mutableStateOf(false) }
    var showBrand by remember { mutableStateOf(reduceMotion) }
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
    val sheen = remember { Animatable(-260f) }    // позиция золотого блика по «Юлдаш»

    LaunchedEffect(Unit) {
        if (reduceMotion) { delay(1000); finish(); return@LaunchedEffect }
        launch { drift.animateTo(1.05f, tween(4700, easing = EaseInOutSine)) }
        launch { logoAlpha.animateTo(1f, tween(540, easing = EaseOutExpo)) }
        launch { glow.animateTo(1f, tween(950, easing = EaseOutExpo)) }
        logoScale.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow))
        delay(110)
        showMeaning = true
        delay(1000)
        showMeaning = false; showBrand = true
        launch { delay(300); sheen.animateTo(680f, tween(900, easing = EaseInOutSine)) }   // блик по бренду
        delay(320); showUnderline = true
        delay(720); showSlogan = true
        delay(880); sloganBa = true
        delay(1000); exiting = true
        delay(440); finish()
    }

    val ring by animateFloatAsState(if (showMeaning || showBrand) 1f else 0f, tween(720, easing = EaseOutExpo), label = "ring")
    val brandIn by animateFloatAsState(if (showBrand) 1f else 0f, tween(680, easing = EaseOutExpo), label = "bIn")
    val brandScale by animateFloatAsState(if (showBrand) 1f else 0.94f, spring(0.78f, Spring.StiffnessMediumLow), label = "bSc")
    val underline by animateFloatAsState(if (showUnderline) 1f else 0f, tween(800, easing = EaseOutExpo), label = "ul")
    val exitAlpha by animateFloatAsState(if (exiting) 0f else 1f, tween(440, easing = EaseInOutSine), label = "exA")
    val exitScale by animateFloatAsState(if (exiting) 1.08f else 1f, tween(460, easing = EaseInCubic), label = "exS")

    val brandBrush = Brush.linearGradient(
        listOf(Color.White, Color.White, GoldLight, Color.White, Color.White),
        start = Offset(sheen.value, 0f), end = Offset(sheen.value + 200f, 0f),
    )

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
                    Modifier.size(230.dp).graphicsLayer { alpha = glow.value * 0.45f }
                        .background(Brush.radialGradient(listOf(Color.White.copy(0.32f), Color.Transparent))),
                )
                Box(Modifier.size(150.dp).graphicsLayer { alpha = ring * 0.45f }.border(1.5.dp, Gold, CircleShape))
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
            Spacer(Modifier.height(36.dp))
            Box(modifier = Modifier.height(88.dp), contentAlignment = Alignment.TopCenter) {
                StaggerWord("Попутчик", showMeaning, 38.sp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Юлдаш",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = Montserrat,
                        letterSpacing = (brandIn * 2f).sp,
                        modifier = Modifier.graphicsLayer { alpha = brandIn; scaleX = brandScale; scaleY = brandScale },
                        style = TextStyle(brush = brandBrush),
                    )
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier.width(72.dp).height(3.dp)
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
                        color = Color.White.copy(0.92f), fontSize = 15.sp, fontFamily = Montserrat,
                        fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp,
                    )
                }
            }
        }
    }
}
