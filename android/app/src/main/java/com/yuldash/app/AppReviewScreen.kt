package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.launch

@Composable
internal fun AppReviewScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var stars by remember { mutableIntStateOf(5) }
    var text by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // строка ошибки считается в composable-контексте (appText), используется внутри корутины
    val netErrMsg = appText(
        "Не получилось отправить. Проверь интернет и попробуй ещё раз.",
        "Ебәреп булманы. Интернетты тикшереп ҡабат ҡара.",
    )

    val canSubmit = text.trim().length >= 10 && !sending

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Оставить отзыв", "Фекер ҡалдырыу"), onBack) },
    ) { padding ->
        if (sent) {
            // Состояние «спасибо» после отправки — отдельно от формы (у неё свой скролл/imePadding).
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(4.dp))
                ReviewThanksCard(onDone = onBack)
            }
        } else {
            AppReviewFormContent(
                stars = stars,
                text = text,
                city = city,
                sending = sending,
                error = error,
                canSubmit = canSubmit,
                onSelectStars = { stars = it },
                onTextChange = { if (it.length <= 600) { text = it; error = null } },
                onCityChange = { if (it.length <= 60) city = it },
                onSubmit = onSubmit@{
                    if (!canSubmit) return@onSubmit
                    sending = true
                    error = null
                    scope.launch {
                        ApiClient.submitAppReview(stars, text.trim(), city.trim())
                            .onSuccess { sent = true }
                            .onFailure { error = netErrMsg }
                        sending = false
                    }
                },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

/**
 * Чистый рендер формы отзыва: подсказка, звёзды, поле отзыва (счётчик), город, ошибка, кнопка
 * отправки (спиннер при `sending`, disabled по `canSubmit`), нижняя сноска. Всё двуязычное через
 * `appText`. Состояние и сеть — в обёртке [AppReviewScreen] → форма тестируется на JVM (Robolectric).
 *
 * `onTextChange`/`onCityChange` получают сырой ввод (лимиты длины применяет вызывающий), выбор
 * звёзд — `onSelectStars(1..5)`, отправка — `onSubmit`. Карточку «спасибо» (`sent`) рисует обёртка.
 */
@Composable
internal fun AppReviewFormContent(
    stars: Int,
    text: String,
    city: String,
    sending: Boolean,
    error: String?,
    canSubmit: Boolean,
    onSelectStars: (Int) -> Unit,
    onTextChange: (String) -> Unit,
    onCityChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .fillMaxSize()
            .imePadding()   // поле отзыва/кнопка не прячутся за клавиатурой
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        Text(
            appText(
                "Как тебе Юлдаш? Оцени и напиши пару слов — это поможет другим решиться.",
                "Юлдаш нисек? Баһала һәм бер-ике һүҙ яҙ — был башҡаларға ҡарар итергә ярҙам итер.",
            ),
            color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
        )

        // Звёзды
        ReviewStarsRow(selected = stars, onSelect = onSelectStars)

        // Текст отзыва
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text(appText("Твой отзыв", "Һинең фекерең")) },
            placeholder = { Text(appText("Что понравилось? Как прошла поездка?", "Нимә оҡшаны? Сәфәр нисек үтте?")) },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("${text.trim().length}/600", color = CanonMuted, fontSize = 12.sp) },
        )

        // Город (необязательно)
        OutlinedTextField(
            value = city,
            onValueChange = onCityChange,
            label = { Text(appText("Город (необязательно)", "Ҡала (мотлаҡ түгел)")) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        error?.let {
            Text(it, color = CanonRed, fontSize = 14.sp)
        }

        Button(
            onClick = onSubmit,
            enabled = canSubmit,
            colors = ButtonDefaults.buttonColors(containerColor = CanonGreen, contentColor = CanonBg),
            shape = CanonCardShape,
            modifier = Modifier.fillMaxWidth().height(54.dp),
        ) {
            if (sending) {
                CircularProgressIndicator(color = CanonBg, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Text(appText("Отправить отзыв", "Фекерҙе ебәрергә"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        Text(
            appText(
                "Отзыв появится на сайте после короткой проверки — чтобы не было спама.",
                "Фекер ҡыҫҡа тикшереүҙән һуң сайтта күренер — спам булмаһын өсөн.",
            ),
            color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
        )
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Ряд из 5 звёзд для оценки. Чистый под-компонент: без своего состояния и сети —
 * выбранное число звёзд (`selected`) держит вызывающий, тап отдаётся через `onSelect`.
 * Двуязычный `contentDescription` считается по `LocalAppLanguage`.
 */
@Composable
internal fun ReviewStarsRow(selected: Int, onSelect: (Int) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            for (i in 1..5) {
                IconButton(onClick = { onSelect(i) }) {
                    Icon(
                        if (i <= selected) Icons.Default.Star else Icons.Outlined.StarBorder,
                        contentDescription = starsText(i),
                        tint = if (i <= selected) CanonGold else CanonMuted,
                        modifier = Modifier.size(38.dp),
                    )
                }
            }
        }
    }
}

/**
 * Карточка «Спасибо за отзыв» после успешной отправки. Чистый под-компонент:
 * только текст (двуязычный через `appText`) + кнопка «Готово» с колбэком `onDone`.
 */
@Composable
internal fun ReviewThanksCard(onDone: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen, modifier = Modifier.size(52.dp))
            Text(
                appText("Спасибо за отзыв!", "Фекерең өсөн рәхмәт!"),
                color = CanonText, fontSize = 24.sp, fontWeight = FontWeight.Bold,
            )
            Text(
                appText(
                    "Мы прочитаем его лично. Лучшие отзывы попадут на сайт Юлдаша.",
                    "Уны шәхсән уҡыйбыҙ. Иң яҡшы фекерҙәр Юлдаш сайтына эләгер.",
                ),
                color = CanonMuted, fontSize = 16.sp, lineHeight = 23.sp,
            )
            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen, contentColor = CanonBg),
                shape = CanonCardShape,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(appText("Готово", "Әҙер"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}
