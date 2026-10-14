package com.yuldash.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yandex.mapkit.MapKitFactory

/**
 * Единый шов «есть ли у сборки живая карта».
 *
 * Ключ Яндекс MapKit лежит в `local.properties`, в git его нет — поэтому сборка в чистом worktree
 * и в CI идёт БЕЗ ключа. Без ключа `MapKitFactory.initialize` бросает «You need to set the API key
 * before using MapKit!» и приложение закрывается. Так падали экран брони, «Отметить на карте» и
 * карта такси (поймано на эмуляторе 14.10.2026). С ключом (релиз, телефон Александра) ничего не
 * меняется.
 *
 * Правило: любой composable, который создаёт `MapView`, первой строкой зовёт
 * `if (MapKitUnavailable(modifier)) return`. Держит это `MapKitGateGuardTest` — новая карта без
 * шва не пройдёт сборку проверок.
 */
internal fun mapKitKeyAvailable(): Boolean = BuildConfig.YANDEX_MAPKIT_KEY.isNotBlank()

// MapKit init: setLocale ДО initialize и ТОЛЬКО один раз на процесс.
// Повторный setLocale после initialize → AssertionError (краш при возврате на карту с подэкрана).
// Зовётся только после проверки ключа выше: без ключа initialize бросает AssertionError.
private var mapKitReady = false
internal fun ensureMapKit(context: Context) {
    if (mapKitReady) return
    mapKitReady = true
    runCatching { MapKitFactory.setLocale("ru_RU") }   // если уже инициализирован — пропускаем, не крашим
    MapKitFactory.initialize(context)
}

/**
 * `true` — ключа нет: на месте карты уже нарисована спокойная заглушка, вызывающий делает `return`.
 * `false` — ключ есть, рисуй живую карту как обычно.
 */
@Composable
internal fun MapKitUnavailable(modifier: Modifier = Modifier): Boolean {
    if (mapKitKeyAvailable()) return false
    MapKitUnavailablePlaceholder(modifier)
    return true
}

/** Заглушка на месте карты: цвет и размер как у карты, по центру — маршрут и короткая подпись. */
@Composable
internal fun MapKitUnavailablePlaceholder(modifier: Modifier = Modifier) {
    Box(modifier.background(CanonMint), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(CanonSpace.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
        ) {
            Icon(
                painterResource(R.drawable.yu_route),
                contentDescription = null,
                tint = CanonGreen2,
                modifier = Modifier.size(32.dp),
            )
            Text(
                appText("Карта сейчас недоступна", "Карта хәҙер юҡ"),
                color = CanonText,
                style = CanonBodyStrong,
                textAlign = TextAlign.Center,
            )
            Text(
                appText("Адреса и маршрут на месте — карту подключим позже", "Адрестар һәм маршрут урынында — картаны һуңыраҡ тоташтырырбыҙ"),
                color = CanonMuted,
                style = CanonCaption,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * «Отметить на карте» без карты: на весь экран та же заглушка и кнопка «Назад». Точку тут не
 * выбрать, поэтому прямо говорим, что делать: адрес в поле остаётся рабочим способом.
 */
@Composable
internal fun PickupPickerUnavailable(onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(CanonBg).safeDrawingPadding()) {
        MapKitUnavailablePlaceholder(Modifier.fillMaxSize())
        Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(CanonSpace.lg).minimumInteractiveComponentSize(),
            onClick = onDismiss,
            shape = CircleShape,
            color = CanonSurface,
            shadowElevation = CanonDepth.raised,
        ) {
            Icon(
                Icons.Default.ArrowBackIosNew,
                contentDescription = appText("Назад", "Артҡа"),
                tint = CanonText,
                modifier = Modifier.padding(CanonSpace.md).size(18.dp),
            )
        }
    }
}
