package com.yuldash.app

// Force-update (B9b-1): блокирующий экран «Обнови Юлдаш 🙌».
// Показывается ВМЕСТО всего приложения, когда versionCode < min_version_code с сервера
// (/version/min). Выхода «Назад» нет намеренно — только кнопка в стор. Офлайн/ошибка ручки
// сюда НЕ приводят (YuldashApp блокирует только при явном ответе сервера).

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Блокирующий экран обновления. Тон — тёплый, по-добрососедски: не «ошибка», а «мы стали лучше».
 * [storeUrl] — ссылка на стор с сервера; пустая → кнопки нет, только просьба обновиться.
 */
@Composable
internal fun ForceUpdateScreen(storeUrl: String) {
    val context = LocalContext.current
    // Мягкое появление (уровень iOS): контент чуть всплывает и проявляется.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(450), label = "fuAlpha")
    val lift by animateFloatAsState(if (shown) 0f else 24f, tween(500), label = "fuLift")

    Box(
        Modifier
            .fillMaxSize()
            .background(CanonBg)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .graphicsLayer { this.alpha = alpha; translationY = lift },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(shape = CircleShape, color = CanonMint) {
                Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                    Text("🙌", fontSize = 46.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                appText("Обнови Юлдаш", "Юлдашты яңырт"),
                color = CanonText,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                appText(
                    "Вышла новая версия — эта уже не поддерживается. Обнови приложение, и поехали дальше 🚗",
                    "Яңы версия сыҡты — быныһы инде эшләмәй. Ҡушымтаны яңырт та, артабан юлға сығабыҙ 🚗",
                ),
                color = CanonMuted,
                fontSize = 16.sp,
                lineHeight = 23.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))
            if (storeUrl.isNotBlank()) {
                Button(
                    onClick = {
                        runCatching {   // нет браузера/стора → не падаем, просто остаёмся на экране
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(storeUrl))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    shape = CanonItemShape,
                    colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2, contentColor = Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp),
                ) {
                    Text(appText("Обновить приложение", "Ҡушымтаны яңыртыу"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            } else {
                Text(
                    appText(
                        "Скачай свежую версию там же, где ставил эту",
                        "Яңы версияны быныһын ҡуйған урындан уҡ алып ҡуй",
                    ),
                    color = CanonMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
