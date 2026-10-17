package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

// «Только проверенные» в ленте «Ближайшие поездки» (решение Александра, 17.10.2026). У пассажира
// фильтр включён по умолчанию — `AppPrefs.verifiedOnly` без выбора человека отдаёт true, а кто сам
// выключил его в «Безопасности», тот так и остаётся. Публиковать поездку по-прежнему может каждый,
// поэтому на тихом маршруте лента проверенных бывает пустой, хотя машина есть. Тогда лента не врёт
// «поездок нет», а говорит «Есть N поездок непроверенных водителей — показать?» (N считает сервер,
// `hidden_unverified`) и по «Показать» открывает их ТОЛЬКО для этого поиска: настройка остаётся.
// Пара на сайте — nearbyFeed.ts (`nearbyVerifiedOnly`, `unverifiedRidesLabel`) и NearbyUnverifiedCard.

/**
 * Режет ли лента непроверенных прямо сейчас. Настройка «Безопасности» ([saved]) действует, пока человек
 * не нажал «Показать» — и снова действует, как только поиск ([search]) стал другим, чем тот, где нажал
 * ([revealedFor]): сменил день, условие, город или маршрут — опять только проверенные.
 */
internal fun nearbyVerifiedOnly(saved: Boolean, revealedFor: Any?, search: Any): Boolean = saved && revealedFor != search

/**
 * Показать ли «Есть N поездок непроверенных водителей»: сервер ответил (не сбой), проверенных нет,
 * фильтр сейчас режет, и сервер насчитал спрятанные. Старый сервер числа не шлёт (0) — обычная пустота.
 */
internal fun nearbyUnverifiedHint(fetched: Int, error: Boolean, verifiedOnly: Boolean, hidden: Int): Boolean =
    fetched == 0 && !error && verifiedOnly && hidden > 0

/** «Только проверенные» ленты: сохранённая настройка, «Показать» на один поиск и сколько спрятано. */
internal class NearbyVerifiedState(val saved: Boolean) {
    /** Поиск ([nearbySearch] с [saved]), где человек нажал «Показать»; null — не нажимал. */
    var revealedFor by mutableStateOf<Any?>(null)
        private set

    /** Сколько подходящих поездок спрятал фильтр в последнем ответе (`hidden_unverified`). */
    var hidden by mutableIntStateOf(0)

    fun verifiedOnly(search: Any): Boolean = nearbyVerifiedOnly(saved, revealedFor, search)

    fun reveal(search: Any) {
        revealedFor = search
    }
}

/** Состояние живёт, пока открыт экран; настройку читаем один раз — переключают её в «Безопасности». */
@Composable
internal fun rememberNearbyVerified(): NearbyVerifiedState {
    val ctx = LocalContext.current
    return remember { NearbyVerifiedState(AppPrefs.verifiedOnly(ctx)) }
}

/** «Есть 3 поездки непроверенных водителей — показать?»: русский склоняет оба слова, башкирский — нет. */
@Composable
internal fun unverifiedRidesText(n: Int): String = appText(
    "Есть $n ${pluralRu(n, "поездка", "поездки", "поездок")} " +
        "${pluralRu(n, "непроверенного водителя", "непроверенных водителей", "непроверенных водителей")} — показать?",
    "Тикшерелмәгән йөрөтөүселәрҙең $n сәфәре бар — күрһәтәйекме?",
)

/**
 * Лента проверенных пуста, а у непроверенных водителей на этот же поиск поездки есть ([hidden] > 0).
 * Говорим, почему пусто, и даём показать их — только для этого поиска: «Безопасность» не меняется.
 * «Показать» — обводкой, а не заливкой: безопасный выбор уже сделан, второй — осознанный.
 * Включены другие фильтры — ниже «Сбросить фильтры» ([onReset]), путь назад, как в «Нет поездок с такими условиями».
 */
@Composable
internal fun NearbyUnverifiedCard(hidden: Int, onShow: () -> Unit, onReset: (() -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonItemShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(CanonSpace.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CanonSpace.xs),
        ) {
            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(34.dp))
            Text(
                appText("Проверенных водителей пока нет", "Тикшерелгән йөрөтөүселәр әлегә юҡ"),
                color = CanonText, style = CanonBodyStrong, textAlign = TextAlign.Center,
            )
            Text(unverifiedRidesText(hidden), color = CanonMuted, style = CanonCaption, textAlign = TextAlign.Center)
            AppButton(
                appText("Показать", "Күрһәтергә"), onShow,
                modifier = Modifier.padding(top = CanonSpace.sm), style = AppButtonStyle.Secondary, fillWidth = false,
            )
            Text(
                appText("Только для этого поиска — настройка «Безопасности» не изменится.", "Тик ошо эҙләү өсөн — «Хәүефһеҙлек» көйләүе үҙгәрмәй."),
                color = CanonMuted, style = CanonMicro, textAlign = TextAlign.Center,
            )
            if (onReset != null) {
                TextButton(onClick = onReset, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(appText("Сбросить фильтры", "Фильтрҙы бушат"), color = CanonGreen2, style = CanonBodyStrong)
                }
            }
        }
    }
}
