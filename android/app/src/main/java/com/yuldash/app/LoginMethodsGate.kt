package com.yuldash.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.EaseOutExpo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yuldash.app.data.ApiClient

/**
 * Состояние публичного списка способов входа: кеш → сеть, обновление при возврате в приложение.
 * Вынесено из LoginScreen.kt — храповик размеров не даёт экрану расти (FileSizeRatchetTest).
 */
internal class LoginMethodsState(
    val methods: List<String>,
    val loading: Boolean,
    val error: Boolean,
    val retry: () -> Unit,
)

@Composable
internal fun rememberLoginMethodsState(
    onTelegramRemoved: () -> Unit,
    onSmsRemoved: () -> Unit,
): LoginMethodsState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var methods by remember { mutableStateOf(LoginMethods.read(context)) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        loading = true
        error = false
        ApiClient.loginMethods().onSuccess {
            methods = LoginMethods.normalize(it)
            LoginMethods.save(context, methods)
            if ("telegram" !in methods) onTelegramRemoved()
            if ("sms" !in methods) onSmsRemoved()
        }.onFailure { error = true }
        loading = false
    }
    DisposableEffect(lifecycleOwner) {
        var wasPaused = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) wasPaused = true
            if (event == Lifecycle.Event.ON_RESUME && wasPaused) {
                wasPaused = false
                reload++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return LoginMethodsState(methods, loading, error, retry = { reload++ })
}

/**
 * Ошибка входа: что случилось + что делать + кнопка «Повторить». Один вид для обоих потоков.
 *
 * Почему так: вход — единственная дверь в приложение, и «упало молча» здесь дороже всего. Отдельной
 * строкой даём объяснение для двух самых частых случаев у нас — нет интернета и код от бота ещё не дошёл.
 *
 * Анимация живая: баннера при входе в композицию нет (`text == null`), он появляется в момент ошибки.
 * Текст держим в [shownText] — иначе на выходе баннер схлопывался бы пустым.
 * `internal` — чтобы покрыть на JVM (Robolectric) без сети и ApiClient.
 */
@Composable
internal fun LoginErrorBanner(
    currentLanguage: AppLanguage,
    text: String?,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var shownText by remember { mutableStateOf(text.orEmpty()) }
    LaunchedEffect(text) { if (text != null) shownText = text }
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(tween(CanonMotion.NORMAL)) + expandVertically(tween(CanonMotion.NORMAL, easing = EaseOutExpo)),
        exit = fadeOut(tween(CanonMotion.QUICK)) + shrinkVertically(tween(CanonMotion.QUICK)),
        modifier = modifier,
    ) {
        Surface(color = CanonDangerBg, shape = CanonItemShape, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = appTextFor(currentLanguage, "Ошибка", "Хата"),
                        tint = CanonRed,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(shownText, color = CanonRed, fontSize = LoginBody, lineHeight = 23.sp)
                }
                Text(
                    text = appTextFor(
                        currentLanguage,
                        "Нет интернета или код ещё не пришёл? Проверь связь и нажми «Повторить».",
                        "Интернет юҡмы, әллә код килеп еткәне юҡмы? Бәйләнеште тикшер ҙә «Ҡабатла» баҫ.",
                    ),
                    color = CanonMuted, fontSize = LoginCaption, lineHeight = 20.sp,
                )
                if (onRetry != null) {
                    AppButton(
                        text = appTextFor(currentLanguage, "Повторить", "Ҡабатлау"),
                        onClick = onRetry,
                        style = AppButtonStyle.Secondary,
                        icon = Icons.Default.Refresh,
                        height = 48.dp,
                    )
                }
            }
        }
    }
}
