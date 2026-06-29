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
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .imePadding()   // поле отзыва/кнопка не прячутся за клавиатурой
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(6.dp))

            if (sent) {
                // Состояние «спасибо» после отправки
                Card(
                    colors = CardDefaults.cardColors(containerColor = CanonSurface),
                    shape = CanonCardShape,
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen, modifier = Modifier.size(52.dp))
                        Text(
                            appText("Спасибо за отзыв!", "Фекерең өсөн рәхмәт!"),
                            color = CanonText, fontSize = 22.sp, fontWeight = FontWeight.Black,
                        )
                        Text(
                            appText(
                                "Мы прочитаем его лично. Лучшие отзывы попадут на сайт Юлдаша.",
                                "Уны шәхсән уҡыйбыҙ. Иң яҡшы фекерҙәр Юлдаш сайтына эләгер.",
                            ),
                            color = CanonMuted, fontSize = 15.sp, lineHeight = 20.sp,
                        )
                        Button(
                            onClick = onBack,
                            colors = ButtonDefaults.buttonColors(containerColor = CanonGreen, contentColor = CanonBg),
                            shape = CanonCardShape,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                        ) {
                            Text(appText("Готово", "Әҙер"), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
                return@Column
            }

            Text(
                appText(
                    "Как тебе Юлдаш? Оцени и напиши пару слов — это поможет другим решиться.",
                    "Юлдаш нисек? Баһала һәм бер-ике һүҙ яҙ — был башҡаларға ҡарар итергә ярҙам итер.",
                ),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 19.sp,
            )

            // Звёзды
            Card(
                colors = CardDefaults.cardColors(containerColor = CanonSurface),
                shape = CanonCardShape,
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    for (i in 1..5) {
                        IconButton(onClick = { stars = i }) {
                            Icon(
                                if (i <= stars) Icons.Default.Star else Icons.Outlined.StarBorder,
                                contentDescription = appText("$i звёзд", "$i йондоҙ"),
                                tint = if (i <= stars) CanonGold else CanonMuted,
                                modifier = Modifier.size(38.dp),
                            )
                        }
                    }
                }
            }

            // Текст отзыва
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= 600) { text = it; error = null } },
                label = { Text(appText("Твой отзыв", "Һинең фекерең")) },
                placeholder = { Text(appText("Что понравилось? Как прошла поездка?", "Нимә оҡшаны? Сәфәр нисек үтте?")) },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("${text.trim().length}/600", color = CanonMuted, fontSize = 12.sp) },
            )

            // Город (необязательно)
            OutlinedTextField(
                value = city,
                onValueChange = { if (it.length <= 60) city = it },
                label = { Text(appText("Город (необязательно)", "Ҡала (мотлаҡ түгел)")) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )

            error?.let {
                Text(it, color = CanonRed, fontSize = 14.sp)
            }

            Button(
                onClick = {
                    if (!canSubmit) return@Button
                    sending = true
                    error = null
                    scope.launch {
                        ApiClient.submitAppReview(stars, text.trim(), city.trim())
                            .onSuccess { sent = true }
                            .onFailure { error = netErrMsg }
                        sending = false
                    }
                },
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
                color = CanonMuted, fontSize = 12.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}
