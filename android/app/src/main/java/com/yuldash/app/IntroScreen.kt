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
import androidx.compose.ui.layout.ContentScale
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
                enter = fadeIn(tween(620, i * 38, EaseOutExpo)) +
                    slideInVertically(tween(700, i * 38, EaseOutExpo)) { it / 2 },
                exit = fadeOut(tween(260, i * 12)) +
                    slideOutVertically(tween(300, i * 12)) { -it / 3 },
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

    val logoScale = remember { Animatable(if (reduceMotion) 1f else 0.96f) }   // лёгкий settle, без «прыжка» (значок уже виден на системном сплэше)
    val logoAlpha = remember { Animatable(1f) }   // лого видно сразу — бесшовный хэндофф с системного сплэша (фейд даёт переход экрана)
    val glow = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val drift = remember { Animatable(1f) }
    val sheen = remember { Animatable(-260f) }    // позиция золотого блика по «Юлдаш»
    val kurai = remember { Animatable(if (reduceMotion) 1f else 0f) }   // распускание курая (7 родов)
    val road = remember { Animatable(if (reduceMotion) 1f else 0f) }    // прорисовка дороги (маршрут)
    val sceneScale = remember { Animatable(if (reduceMotion) 1f else 1.08f) }   // мягкий push-in пейзажа (Ken-Burns)
    val sceneAlpha = remember { Animatable(if (reduceMotion) 1f else 0f) }      // пейзаж ПРОЯВЛЯЕТСЯ из зелёного → бесшовно с системным сплэшем

    LaunchedEffect(Unit) {
        if (reduceMotion) { delay(1000); finish(); return@LaunchedEffect }
        launch { drift.animateTo(1.05f, tween(4700, easing = EaseInOutSine)) }
        launch { sceneScale.animateTo(1f, tween(4700, easing = EaseInOutSine)) }   // пейзаж медленно «наезжает»
        launch { sceneAlpha.animateTo(1f, tween(800, easing = EaseInOutSine)) }    // плавное проявление пейзажа из зелёного (без резкого «хлопка»)
        launch { glow.animateTo(1f, tween(950, easing = EaseOutExpo)) }
        launch { kurai.animateTo(1f, tween(1300, easing = EaseOutExpo)) }       // курай распускается за лого
        launch { road.animateTo(1f, tween(1400, easing = EaseOutExpo)) }        // дорога рисуется + точка едет
        logoScale.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow))
        delay(160)
        showMeaning = true
        delay(1200)                 // «Попутчик» держим дольше — читается спокойно
        showMeaning = false
        delay(420)                  // слово ПОЛНОСТЬЮ уходит до «Юлдаш» — без наложения
        showBrand = true
        launch { delay(360); sheen.animateTo(720f, tween(1100, easing = EaseInOutSine)) }   // медленный люкс-блик
        delay(500); showUnderline = true
        delay(780); showSlogan = true
        delay(1400); sloganBa = true    // русский слоган подышал — потом башкирский
        delay(1200); exiting = true     // башкирский подышал — и плавный уход
        delay(500); finish()
    }

    val ring by animateFloatAsState(if (showMeaning || showBrand) 1f else 0f, tween(720, easing = EaseOutExpo), label = "ring")
    val brandIn by animateFloatAsState(if (showBrand) 1f else 0f, tween(780, easing = EaseOutExpo), label = "bIn")
    val brandScale by animateFloatAsState(if (showBrand) 1f else 0.92f, spring(0.82f, Spring.StiffnessLow), label = "bSc")
    val underline by animateFloatAsState(if (showUnderline) 1f else 0f, tween(820, easing = EaseOutExpo), label = "ul")
    val exitAlpha by animateFloatAsState(if (exiting) 0f else 1f, tween(560, easing = EaseInOutSine), label = "exA")
    val exitScale by animateFloatAsState(if (exiting) 1.06f else 1f, tween(580, easing = EaseInCubic), label = "exS")
    val roadFade by animateFloatAsState(if (showMeaning || showBrand) 0f else 1f, tween(520, easing = EaseInOutSine), label = "rf")
    val sloganAlpha by animateFloatAsState(if (showSlogan) 1f else 0f, tween(560, easing = EaseOutExpo), label = "sloA")

    val brandBrush = Brush.linearGradient(
        listOf(Color.White, Color.White, GoldLight, Color.White, Color.White),
        start = Offset(sheen.value, 0f), end = Offset(sheen.value + 200f, 0f),
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(GreenTop)   // тот же зелёный, что у СИСТЕМНОГО сплэша → бесшовный хэндофф, пока пейзаж проявляется
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { finish() },
        contentAlignment = Alignment.Center,
    ) {
        // Пейзаж Башкортостана (как на референсе) — проявляется из зелёного (alpha) + мягкий push-in (scale)
        Image(
            painter = painterResource(R.drawable.splash_landscape),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = sceneScale.value; scaleY = sceneScale.value; alpha = sceneAlpha.value
            },
        )
        // Тёплая вуаль + виньетка: тёмно-зелёный тон и читаемость белого текста поверх сцены
        Box(Modifier.fillMaxSize().background(Color(0x33000000)))
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.Transparent, Color(0x5A000000)), radius = 1500f)))

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = exitAlpha
                scaleX = exitScale * drift.value
                scaleY = exitScale * drift.value
            },
        ) {
            BrandHero(
                logoAlpha = logoAlpha.value,
                logoScale = logoScale.value,
                glow = glow.value,
                ring = ring,
                kurai = kurai.value,
                road = road.value,
                roadFade = roadFade,
            )
            Spacer(Modifier.height(4.dp))
            // Слот СЛОВА (Попутчик/Юлдаш) — компактный, слово по центру; черта и слоган идут вплотную ниже.
            Box(modifier = Modifier.height(58.dp), contentAlignment = Alignment.Center) {
                StaggerWord("Попутчик", showMeaning, 38.sp)
                Text(
                    "Юлдаш",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = Montserrat,
                    letterSpacing = (brandIn * 2f).sp,
                    modifier = Modifier.graphicsLayer { alpha = brandIn; scaleX = brandScale; scaleY = brandScale },
                    style = TextStyle(brush = brandBrush),
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.width(72.dp).height(3.dp)
                    .graphicsLayer { scaleX = underline }
                    .background(Gold, RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.height(14.dp))
            // Высота слота слогана зарезервирована ВСЕГДА → его появление НЕ меняет высоту колонки
            // и не двигает лого вверх (раньше колонка перецентрировалась → дёрганье). Слоган только фейдится.
            Box(modifier = Modifier.height(22.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    targetState = sloganBa,
                    transitionSpec = {
                        // Сначала русский УХОДИТ (240мс), потом башкирский ПРИХОДИТ (delay 240) — без наложения строк.
                        (fadeIn(tween(560, delayMillis = 240, easing = EaseOutExpo)) + slideInVertically(tween(560, delayMillis = 240, easing = EaseOutExpo)) { it / 4 })
                            .togetherWith(fadeOut(tween(240, easing = EaseInOutSine)) + slideOutVertically(tween(240, easing = EaseInOutSine)) { -it / 4 })
                    },
                    label = "slo",
                ) { ba ->
                    Text(
                        if (ba) "Үҙебеҙҙекеләр араһында юллашыу" else "Поездки между своими",
                        color = Color.White.copy(0.92f), fontSize = 15.sp, fontFamily = Montserrat,
                        fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp,
                        modifier = Modifier.graphicsLayer { alpha = sloganAlpha },
                    )
                }
            }
        }
    }
}
