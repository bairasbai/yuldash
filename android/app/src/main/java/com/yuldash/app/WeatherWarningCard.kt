package com.yuldash.app

// ❄️ Погода на маршруте: гололёд, метель, туман, мороз — перед выездом.
//
// Что видит человек. Если на пути ничего опасного — НИЧЕГО не видит: карточки просто нет.
// Появляется она только когда есть что сказать, и тогда первой строкой идёт самое опасное.
// Ни одну кнопку она не блокирует: поездку решает человек, приложение лишь сообщает факт
// вовремя — до выезда, а не когда машина уже на трассе.
//
// Тексты приходят с сервера готовыми на двух языках (см. backend/app/weather_warn.py):
// пороги «что считать метелью» живут в одном месте и правятся без выпуска приложения.

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.RouteWeatherDto
import com.yuldash.app.data.WeatherWarningDto

/** Иконка под вид опасности. Гололёд и мороз — снежинка, метель — ветер, гроза — молния. */
private fun weatherIcon(kind: String): ImageVector = when (kind) {
    "ice", "frost", "snow" -> Icons.Default.AcUnit
    "blizzard", "wind" -> Icons.Default.Air
    "fog" -> Icons.Default.Cloud
    "thunder" -> Icons.Default.Bolt
    else -> Icons.Default.Warning
}

/**
 * Готовая карточка по данным сервера. Показывает только предупреждения; когда их нет —
 * не рисует ничего (не «всё хорошо», а именно ничего: пустая плашка каждый день перестаёт
 * читаться, и в день гололёда её пролистают вместе с остальным).
 */
@Composable
internal fun WeatherWarningCard(weather: RouteWeatherDto?, modifier: Modifier = Modifier) {
    val warnings = weather?.warnings.orEmpty()
    AnimatedVisibility(
        visible = weather?.available == true && warnings.isNotEmpty(),
        enter = expandVertically(tween(CanonMotion.NORMAL)) + fadeIn(tween(CanonMotion.NORMAL)),
        exit = shrinkVertically(tween(CanonMotion.QUICK)) + fadeOut(tween(CanonMotion.QUICK)),
        modifier = modifier,
    ) {
        val severe = warnings.any { it.severe }
        Surface(
            color = if (severe) CanonDangerBg else CanonWarnBg,
            shape = CanonCardShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = CanonSurface, shape = CircleShape) {
                        Icon(
                            weatherIcon(warnings.first().kind),
                            contentDescription = null,
                            tint = if (severe) CanonRed else CanonWarn,
                            modifier = Modifier.padding(8.dp).size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            appText("Погода на маршруте", "Юлдағы һауа торошо"),
                            color = if (severe) CanonRed else CanonWarn,
                            fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        )
                        // Температуру показываем только если она есть: подпись «—°» ничего не
                        // добавляет, а место занимает.
                        weather?.temperatureC?.let { t ->
                            Text(
                                appText("Сейчас ${Math.round(t)}°", "Хәҙер ${Math.round(t)}°"),
                                color = CanonMuted, fontSize = 12.sp,
                            )
                        }
                    }
                }
                warnings.forEach { w -> WeatherWarningRow(w) }
                Text(
                    appText(
                        "Это предупреждение, а не запрет — решай сам.",
                        "Был иҫкәртеү, тыйыу түгел — үҙең хәл ит.",
                    ),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
        }
    }
}

@Composable
private fun WeatherWarningRow(w: WeatherWarningDto) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            weatherIcon(w.kind),
            contentDescription = null,
            tint = if (w.severe) CanonRed else CanonWarn,
            modifier = Modifier.size(18.dp).padding(top = 2.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            appText(w.ru, w.ba),
            color = CanonText,
            fontSize = 14.sp, lineHeight = 20.sp,
            fontWeight = if (w.severe) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Загрузка погоды для маршрута. Держит своё состояние и молчит при любой неудаче:
 * нет сети, сервис недоступен, координат нет — карточка просто не появится.
 *
 * `atIso` — время выезда: у попутки оно в будущем, и погода нужна НА НЕГО, а не на сейчас.
 */
@Composable
internal fun rememberRouteWeather(
    fromLat: Double? = null, fromLng: Double? = null,
    toLat: Double? = null, toLng: Double? = null,
    fromCity: String? = null, toCity: String? = null,
    atIso: String? = null,
): RouteWeatherDto? {
    var weather by remember(fromLat, fromLng, toLat, toLng, fromCity, toCity, atIso) {
        mutableStateOf<RouteWeatherDto?>(null)
    }
    LaunchedEffect(fromLat, fromLng, toLat, toLng, fromCity, toCity, atIso) {
        val haveCoords = fromLat != null && fromLng != null
        val haveCity = !fromCity.isNullOrBlank()
        if (!haveCoords && !haveCity) return@LaunchedEffect
        // Человек ещё печатает название — не дёргаем сервер на каждую букву.
        if (!haveCoords) kotlinx.coroutines.delay(600)
        ApiClient.getRouteWeather(fromLat, fromLng, toLat, toLng, fromCity, toCity, atIso)
            .onSuccess { weather = it }
        // onFailure намеренно пуст: погода не вправе показывать человеку ошибку. Не пришла —
        // значит её нет, экран работает как раньше.
    }
    return weather
}
