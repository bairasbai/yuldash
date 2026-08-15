package com.yuldash.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.Co2
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MyStatsDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

// Фикс-палитра шеринг-открытки (StatsShareCard) — независима от темы: карточка уходит картинкой в
// мессенджеры «как есть», у получателя может быть любая тема. Поэтому цвета жёстко зашиты, а НЕ берутся
// из Canon* (которые меняются со светлой/тёмной темой). Здесь собраны в одном месте, чтобы не размазывать.
private val StatsCardTop = Color(0xFF0B6B3A)      // верх зелёного градиента карточки
private val StatsCardBottom = Color(0xFF063A20)   // низ зелёного градиента карточки
private val StatsMint = Color(0xFFCDEBD9)         // мятные подписи на зелёном
private val StatsMintBright = Color(0xFFDDF3E5)   // светлее — имя и подпись бренда (запас по контрасту)
private val StatsGold = Color(0xFFF5D07A)         // золото кубка (звание)
private val StatsGlassStrong = Color(0x33FFFFFF)  // «стеклянный» кружок под кубком
private val StatsGlassSoft = Color(0x1FFFFFFF)    // «стеклянный» фон мини-плиток

/**
 * F18 «Мой Юлдаш» — личная статистика попутчика.
 * Км вместе · число поездок · сэкономлено ₽ (vs такси) · CO₂ · звание.
 * Шеринг: карточка статистики рисуется в Bitmap (Android Canvas) и уходит картинкой в share-sheet;
 * при любой ошибке рендера/записи — фолбэк на текстовый шеринг.
 * Состояния: загрузка / ошибка (повтор) / данные (нули для новичка — валидны, с тёплым пояснением).
 */
@Composable
internal fun MyStatsScreen(onBack: () -> Unit) {
    val language = LocalAppLanguage.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<MyStatsDto?>(null) }

    suspend fun load() {
        loading = true; error = false
        ApiClient.getMyStats()
            .onSuccess { stats = it; loading = false }
            .onFailure { error = true; loading = false }
    }
    LaunchedEffect(Unit) { load() }

    val name = remember { ApiClient.cachedName()?.takeIf { it.isNotBlank() } ?: "" }

    Scaffold(containerColor = CanonBg) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Default.ArrowBackIosNew,
                            contentDescription = appText("Назад", "Артҡа"),
                            tint = CanonGreen,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        appText("Мой Юлдаш", "Минең Юлдаш"),
                        color = CanonGreen, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }

            when {
                loading -> item { AppLoading(appText("Считаем твои километры…", "Километрҙарыңды һанайбыҙ…")) }
                error -> item { AppErrorState(onRetry = { scope.launch { load() } }) }
                stats != null -> {
                    val s = stats!!
                    item { StatsShareCard(s, name, language) }
                    // Бейджи (G8): бэкенд считал их с самого начала, а приложение не звало —
                    // награда существовала только в базе, человек её никогда не видел.
                    item { AchievementsSection() }

                    // Детальные плитки 2×2.
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatTile(
                                Modifier.weight(1f), Icons.Default.Route, CanonGreen2,
                                fmtKmStat(s.km), appText("км вместе", "км бергә"),
                            )
                            StatTile(
                                Modifier.weight(1f), Icons.Default.DirectionsCar, CanonGreen2,
                                fmtInt(s.trips), appText("поездок", "сәфәр"),
                            )
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatTile(
                                Modifier.weight(1f), Icons.Default.Savings, CanonStar,
                                "${fmtInt(s.savedRub)} ₽", appText("сэкономлено", "янға ҡалды"),
                            )
                            StatTile(
                                Modifier.weight(1f), Icons.Default.Co2, CanonGreen2,
                                fmtKg(s.co2SavedKg), appText("CO₂ меньше", "CO₂ кәмерәк"),
                            )
                        }
                    }

                    // Прогресс до следующего звания.
                    if (s.nextAt != null) {
                        item { RankProgressCard(s, language) }
                    }

                    // Пояснение про прикидку (честно: коэффициенты ориентировочные).
                    item {
                        Text(
                            appText(
                                "Экономия и CO₂ — примерная оценка в сравнении с поездкой на такси.",
                                "Янға ҡалыу һәм CO₂ — таксиға ҡарата яҡынса баһа.",
                            ),
                            color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        )
                    }

                    // Кнопка «Поделиться».
                    item {
                        AppButton(
                            text = appText("Поделиться", "Уртаҡлашыу"),
                            onClick = {
                                scope.launch {
                                    val caption = shareCaption(s, language)
                                    val uri = runCatching {
                                        withContext(Dispatchers.IO) {
                                            val bmp = drawStatsBitmap(s, name, language)
                                            saveSharePng(context, bmp)
                                        }
                                    }.getOrNull()
                                    if (uri != null) shareImage(context, uri, caption)
                                    else shareText(context, caption)   // фолбэк: текстом
                                }
                            },
                            style = AppButtonStyle.Primary,
                            icon = Icons.Default.IosShare,
                        )
                    }
                }
            }
        }
    }
}

/** Карточка-открытка со статистикой (на экране). Тот же макет уходит картинкой (drawStatsBitmap). */
@Composable
private fun StatsShareCard(s: MyStatsDto, name: String, language: AppLanguage) {
    val rankTitle = if (language == AppLanguage.Ba) s.rankTitleBa else s.rankTitleRu
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CanonCardShape,
        colors = CardDefaults.cardColors(containerColor = StatsCardTop),
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(StatsCardTop, StatsCardBottom)))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        appTextFor(language, "МОЙ ЮЛДАШ", "МИНЕҢ ЮЛДАШ"),
                        color = StatsMint, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        rankTitle,
                        color = Color.White, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
                    )
                    if (name.isNotBlank()) {
                        Text(name, color = StatsMintBright, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Surface(color = StatsGlassStrong, shape = CircleShape) {
                    Icon(
                        Icons.Default.EmojiEvents, contentDescription = null,
                        tint = StatsGold, modifier = Modifier.padding(12.dp).size(30.dp),
                    )
                }
            }

            // Крупный акцент — километры вместе.
            Column {
                Text(
                    fmtKmStat(s.km),
                    color = Color.White, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    appTextFor(language, "километров вместе", "километр бергә"),
                    color = StatsMint, fontSize = 14.sp,
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniStat(Modifier.weight(1f), fmtInt(s.trips), appTextFor(language, "поездок", "сәфәр"))
                // «сэкономлено», не «сэкономил»: карточкой делятся в мессенджерах, и мужской род
                // делал женщину в собственной открытке мужчиной. Башкирское «янға ҡалды» безличное.
                MiniStat(Modifier.weight(1f), "${fmtInt(s.savedRub)} ₽", appTextFor(language, "сэкономлено", "янға ҡалды"))
                MiniStat(Modifier.weight(1f), fmtKg(s.co2SavedKg), appTextFor(language, "CO₂ меньше", "CO₂ кәм"))
            }

            Text(
                // Была 0xFF9FD6B4 11sp — на светлом верху градиента контраст падал ниже 4.5:1;
                // берём светлее (StatsMintBright) и 12sp для читаемости мелкой подписи.
                appTextFor(language, "Юлдаш · поездки между своими · yulbash.ru", "Юлдаш · үҙебеҙҙекеләр менән · yulbash.ru"),
                color = StatsMintBright, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun MiniStat(modifier: Modifier, value: String, label: String) {
    Column(
        modifier
            .clip(CanonItemShape)
            .background(StatsGlassSoft)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(label, color = StatsMint, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StatTile(
    modifier: Modifier,
    icon: ImageVector,
    accent: Color,
    value: String,
    label: String,
) {
    Card(
        modifier = modifier,
        shape = CanonCardShape,
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
        border = BorderStroke(1.dp, CanonBorder),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(color = CanonMint, shape = CircleShape) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.padding(8.dp).size(20.dp))
            }
            Text(value, color = CanonText, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
            Text(label, color = CanonMuted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun RankProgressCard(s: MyStatsDto, language: AppLanguage) {
    val nextTitle = (if (language == AppLanguage.Ba) s.nextTitleBa else s.nextTitleRu).orEmpty()
    val nextAt = s.nextAt ?: return
    val progress = if (nextAt > 0) (s.trips.toFloat() / nextAt.toFloat()).coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(progress, animationSpec = tween(700), label = "rankProgress")
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = CanonCardShape,
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
        border = BorderStroke(1.dp, CanonBorder),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.EmojiEvents, contentDescription = null, tint = CanonStar, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    appText("До звания «$nextTitle»", "«$nextTitle» исеменә тиклем"),
                    color = CanonText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
            }
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(8.dp)),
                color = CanonGreen2,
                trackColor = CanonMint,
            )
            Text(
                appText("Осталось ${fmtInt(s.toNext)} ${tripsWord(s.toNext)}", "Тағы ${fmtInt(s.toNext)} сәфәр"),
                color = CanonMuted, fontSize = 14.sp,
            )
        }
    }
}

// ---- Форматирование ----

private fun fmtInt(n: Int): String = "%,d".format(n).replace(',', ' ')

private fun fmtKmStat(km: Double): String {
    if (km >= 1000) return fmtInt(km.toInt())
    val whole = km % 1.0 == 0.0
    return if (whole) String.format(java.util.Locale.US, "%.0f", km) else String.format(java.util.Locale.US, "%.1f", km)
}

private fun fmtKg(kg: Double): String {
    val whole = kg % 1.0 == 0.0
    val body = if (whole) String.format(java.util.Locale.US, "%.0f", kg) else String.format(java.util.Locale.US, "%.1f", kg)
    return "$body кг"
}

/** Русский плюрал для «поездок» (1 поездка / 2 поездки / 5 поездок). */
private fun tripsWord(n: Int): String {
    val m10 = n % 10
    val m100 = n % 100
    return when {
        m10 == 1 && m100 != 11 -> "поездка"
        m10 in 2..4 && m100 !in 12..14 -> "поездки"
        else -> "поездок"
    }
}

// ---- Шеринг ----

private fun shareCaption(s: MyStatsDto, language: AppLanguage): String {
    val rank = if (language == AppLanguage.Ba) s.rankTitleBa else s.rankTitleRu
    return if (language == AppLanguage.Ba) {
        "Минең Юлдаш: ${fmtInt(s.trips)} сәфәр, ${fmtKmStat(s.km)} км бергә, ${fmtInt(s.savedRub)} ₽ янға ҡалды. " +
            "Исемем — «$rank». Юлдашҡа ҡушыл: yulbash.ru"
    } else {
        // Подпись уходит в чужие чаты от имени человека — род тут угадывать нельзя.
        "Мой Юлдаш: ${fmtInt(s.trips)} ${tripsWord(s.trips)}, ${fmtKmStat(s.km)} км вместе, сэкономлено ~${fmtInt(s.savedRub)} ₽. " +
            "Звание — «$rank». Присоединяйся: yulbash.ru"
    }
}

/** Рисуем открытку статистики на Android Canvas (без зависимости от Compose-capture API). 1080×1350. */
private fun drawStatsBitmap(s: MyStatsDto, name: String, language: AppLanguage): Bitmap {
    val w = 1080
    val h = 1350
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)

    // Фон-градиент (тот же зелёный, что у экранной карточки).
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF0B6B3A.toInt(), 0xFF063A20.toInt(), Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

    val bold = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    val regular = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    val pad = 80f
    val white = 0xFFFFFFFF.toInt()
    val mint = 0xFFCDEBD9.toInt()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun text(str: String, x: Float, y: Float, size: Float, color: Int, tf: Typeface, spacing: Float = 0f) {
        paint.apply { this.color = color; textSize = size; typeface = tf; letterSpacing = spacing }
        canvas.drawText(str, x, y, paint)
    }

    // Кикер.
    text(appTextFor(language, "МОЙ ЮЛДАШ", "МИНЕҢ ЮЛДАШ"), pad, 150f, 34f, 0xFFBFE8CF.toInt(), bold, 0.18f)
    paint.letterSpacing = 0f

    // Звание (крупно).
    val rank = if (language == AppLanguage.Ba) s.rankTitleBa else s.rankTitleRu
    text(rank, pad, 240f, 84f, white, bold)
    if (name.isNotBlank()) text(name, pad, 300f, 40f, mint, regular)

    // Кубок в правом верхнем углу.
    val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    canvas.drawCircle(w - pad - 40f, 150f, 70f, badge)
    text("🏆", w - pad - 78f, 172f, 66f, white, regular)

    // Крупные километры.
    text(fmtKmStat(s.km), pad, 560f, 210f, white, bold)
    text(appTextFor(language, "километров вместе", "километр бергә"), pad, 620f, 42f, mint, regular)

    // Три мини-блока.
    val gap = 24f
    val boxW = (w - pad * 2 - gap * 2) / 3f
    val boxTop = 720f
    val boxH = 220f
    val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
    data class Mini(val value: String, val label: String)
    val minis = listOf(
        Mini(fmtInt(s.trips), appTextFor(language, "поездок", "сәфәр")),
        Mini("${fmtInt(s.savedRub)} ₽", appTextFor(language, "сэкономлено", "янға ҡалды")),
        Mini(fmtKg(s.co2SavedKg), appTextFor(language, "CO₂ меньше", "CO₂ кәм")),
    )
    val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    minis.forEachIndexed { i, m ->
        val left = pad + i * (boxW + gap)
        canvas.drawRoundRect(RectF(left, boxTop, left + boxW, boxTop + boxH), 32f, 32f, boxPaint)
        val cx = left + boxW / 2f
        centerPaint.apply { color = white; textSize = 46f; typeface = bold }
        canvas.drawText(m.value, cx, boxTop + 110f, centerPaint)
        centerPaint.apply { color = mint; textSize = 30f; typeface = regular }
        canvas.drawText(m.label, cx, boxTop + 165f, centerPaint)
    }

    // Подпись бренда внизу.
    text(
        appTextFor(language, "Юлдаш · поездки между своими", "Юлдаш · үҙебеҙҙекеләр менән"),
        pad, h - 150f, 36f, 0xFF9FD6B4.toInt(), bold,
    )
    text("yulbash.ru", pad, h - 95f, 34f, mint, regular)
    return bmp
}

private fun saveSharePng(context: Context, bitmap: Bitmap): android.net.Uri {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(dir, "my_yuldash.png")
    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun shareImage(context: Context, uri: android.net.Uri, caption: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TEXT, caption)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, caption).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun shareText(context: Context, caption: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, caption)
    }
    context.startActivity(Intent.createChooser(intent, caption).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}


/**
 * Бейджи профиля — тёплое «спасибо, что ты с нами», а не рейтинг: на распределение заказов
 * они не влияют (так и в бэкенде). Незаработанные показываем с прогрессом, а не прячем:
 * видеть «ещё 3 поездки» приятнее, чем не знать о награде вовсе.
 *
 * Секция сама грузит данные и молча исчезает при ошибке — это украшение, из-за которого
 * экран статистики ломаться не должен.
 */
@Composable
private fun AchievementsSection() {
    var data by remember { mutableStateOf<com.yuldash.app.data.AchievementsDto?>(null) }
    LaunchedEffect(Unit) { ApiClient.getMyAchievements().onSuccess { data = it } }
    val d = data ?: return
    if (d.items.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(appText("Твои значки", "Һинең билдәләрең"), color = CanonText,
                fontWeight = FontWeight.Bold, fontSize = 19.sp)
            Spacer(Modifier.width(8.dp))
            Surface(color = CanonMint, shape = RoundedCornerShape(999.dp)) {
                Text("${d.earnedCount}/${d.items.size}", color = CanonGreen2, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        d.items.forEach { b -> AchievementRow(b) }
        Text(
            appText(
                "Значки — это про тепло, а не про рейтинг: на заказы они никак не влияют.",
                "Билдәләр — йылылыҡ хаҡында, рейтинг хаҡында түгел: заказдарға улар тәьҫир итмәй.",
            ),
            color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
        )
    }
}

@Composable
private fun AchievementRow(b: com.yuldash.app.data.AchievementDto) {
    Surface(
        color = if (b.earned) CanonMint else CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, if (b.earned) CanonGreen2 else CanonBorder),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (b.earned) Icons.Default.EmojiEvents else Icons.Default.Lock,
                contentDescription = null,
                tint = if (b.earned) CanonGold else CanonMuted,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(appText(b.ru, b.ba), color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (!b.earned) {
                    Text(
                        appText("Ещё ${(b.goal - b.value).coerceAtLeast(0)} до значка", "Билдәгә тағы ${(b.goal - b.value).coerceAtLeast(0)}"),
                        color = CanonMuted, fontSize = 12.sp,
                    )
                }
            }
            if (b.earned) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
            }
        }
    }
}
