package com.yuldash.shared

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ⭐ ЕДИНАЯ точка входа общего UI. И Android-хост (MainActivity), и iOS-хост (MainViewController)
// вызывают ОДНУ и ту же App(). Ниже — обычный код Юлдаша: Canon-цвета, appText(ru, ba), анимация.
// Ничего платформо-специфичного. Это и есть суть Compose Multiplatform: один экран — два телефона.

@Composable
fun App() {
    var language by remember { mutableStateOf(AppLanguage.Ru) }
    CompositionLocalProvider(LocalAppLanguage provides language) {
        MaterialTheme {
            TrialScreen(
                onToggleLanguage = {
                    language = if (language == AppLanguage.Ba) AppLanguage.Ru else AppLanguage.Ba
                }
            )
        }
    }
}

@Composable
private fun TrialScreen(onToggleLanguage: () -> Unit) {
    Surface(color = CanonBg, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Android · iOS",
                color = CanonGreen2,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(14.dp))

            // Карточка-приветствие в фирменном Canon-стиле (мягкая форма, поверхность, тонкая рамка).
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(CanonCardShape)
                    .background(CanonSurface)
                    .border(1.dp, CanonBorder, CanonCardShape)
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Юлдаш",
                    color = CanonGreen,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))

                // Приветствие плавно сменяется при переключении языка (анимация в коде — уровень iPhone).
                AnimatedContent(
                    targetState = LocalAppLanguage.current,
                    transitionSpec = { fadeIn(tween(280)) togetherWith fadeOut(tween(160)) },
                    label = "greeting",
                ) { lang ->
                    Text(
                        text = appTextFor(lang, ru = "Привет, сосед!", ba = "Һаумы, күрше!"),
                        color = CanonText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = appText(
                        ru = "Один экран на Kotlin — и на Android, и на iPhone.",
                        ba = "Бер Kotlin-экран — Android-та ла, iPhone-да ла.",
                    ),
                    color = CanonMuted,
                    fontSize = 16.sp,
                )
            }

            Spacer(Modifier.height(20.dp))

            LanguagePill(
                label = appText(ru = "Сменить язык", ba = "Телде алмаштырыу"),
                onClick = onToggleLanguage,
            )
        }
    }
}

/** Кнопка-пилюля: золотой акцент бренда + плавная смена цвета при нажатии. */
@Composable
private fun LanguagePill(label: String, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (pressed) CanonGreen2 else CanonGold, tween(160), label = "pill-bg")
    Text(
        text = label,
        color = if (pressed) Color.White else CanonGoldInk,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(CanonItemShape)
            .background(bg)
            .clickable {
                pressed = !pressed
                onClick()
            }
            .padding(horizontal = 22.dp, vertical = 14.dp),
    )
}
