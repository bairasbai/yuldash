package com.yuldash.app

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * «Застрял на трассе» — ОДИН блок на все три сценария: попутка, такси, доставка.
 *
 * Уровень мягче красного SOS: не «спасите», а «машина встала, минус двадцать, приезжайте».
 * Доверенным контактам уходит SMS с координатами, поддержка Юлдаша видит сигнал в ленте.
 *
 * Почему общий файл. Кнопка жила двумя независимыми копиями (попутка и такси) с разными
 * текстами и разным поведением после отправки, а у курьера её не было вовсе — при том что
 * сервер её принимал с 2026-08-06, то есть нажать было негде. Аудит 2026-08-06: третья копия
 * не появится, потому что копий больше нет.
 *
 * @param key       объект, к которому привязан сигнал (id брони/заказа/посылки) — сброс состояния
 *                  при смене объекта, иначе «помощь позвана» переезжает на следующую поездку.
 * @param send      как отправить сигнал: даём координаты, получаем результат.
 */
@Composable
fun RoadsideHelpAction(
    key: Any?,
    modifier: Modifier = Modifier,
    ownerGeneration: Long? = null,
    isCurrentParent: () -> Boolean = { true },
    send: suspend (Double?, Double?, Long) -> Result<ApiClient.RoadsideResult>,
) {
    val generation = remember { ownerGeneration ?: ApiClient.queueSessionGeneration() }
    val session by ApiClient.sessionChanges.collectAsState()
    if (session != generation || !ApiClient.isCurrentSession(generation) ||
        (ownerGeneration != null && ownerGeneration != generation)) return
    key(key, generation) { RoadsideHelpContent(modifier, generation, isCurrentParent, send) }
}

@Composable
private fun RoadsideHelpContent(
    modifier: Modifier, generation: Long, isCurrentParent: () -> Boolean,
    send: suspend (Double?, Double?, Long) -> Result<ApiClient.RoadsideResult>,
) {
    val scope = rememberCoroutineScope()
    val currentParent by rememberUpdatedState(isCurrentParent)
    fun isCurrent() = scope.isActive && ApiClient.isCurrentSession(generation) && currentParent()
    fun commit(action: () -> Unit) { ApiClient.runIfCurrentSession(generation) { if (isCurrent()) action() } }
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    var openingVersion by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    // Скольким близким реально уйдёт SMS. -1 = ещё не отправляли.
    var notified by remember { mutableIntStateOf(-1) }
    // Сколько доверенных заведено всего. Нужен, чтобы отличить «звать некого» от
    // «есть кого, но сообщение не уйдёт»: человеку на трассе это разные новости (волна 184).
    var contactsTotal by remember { mutableIntStateOf(0) }
    val failMsg = appText(
        "Сигнал не отправлен. Проверь связь и повтори.",
        "Сигнал ебәрелмәне. Бәйләнеште тикшереп ҡабатла.",
    )

    // Отправлено → спокойное подтверждение, которое НЕ исчезает (в отличие от всплывашки):
    // человек в стрессе на трассе должен видеть, что помощь действительно вызвана.
    if (sent) {
        Surface(color = CanonWarnBg, shape = CanonItemShape, modifier = modifier) {
            Text(
                // Текст по факту, а не по замыслу (волна 122). Раньше здесь всегда стояло
                // «близкие получили твои координаты» — даже когда доверенных контактов человек
                // не заводил и SMS не ушло НИКОМУ. Курьер на трассе в минус двадцать читал это
                // и переставал звонить сам.
                when {
                    notified > 0 -> appText(
                        "Помощь вызвана: близкие и поддержка получили твои координаты.",
                        "Ярҙам саҡырылды: яҡындар һәм ярҙам хеҙмәте координаталарыңды алды.",
                    )
                    // Доверенных нет вовсе — звать было некого, и это поправимо на будущее.
                    contactsTotal == 0 -> appText(
                        "Поддержка получила твои координаты. Близких в списке нет — добавь их " +
                            "в «Доверенных», чтобы в следующий раз им тоже ушло SMS.",
                        "Ярҙам хеҙмәте координаталарыңды алды. Яҡындар исемлектә юҡ — уларҙы " +
                            "«Ышаныслы кешеләр»гә өҫтә, киләһе юлы SMS уларға ла китһен.",
                    )
                    // Доверенные есть, а сообщение им не уйдёт (волна 184). Раньше человек
                    // видел здесь «близкие получили твои координаты» и переставал звонить сам.
                    else -> appText(
                        "Поддержка получила твои координаты. Сообщение близким сейчас не " +
                            "уходит — позвони им сам, если можешь.",
                        "Ярҙам хеҙмәте координаталарыңды алды. Яҡындарға хәбәр хәҙер китмәй — " +
                            "мөмкин булһа, үҙең шылтырат.",
                    )
                },
                color = CanonWarn,
                style = CanonCaption,
                modifier = Modifier.fillMaxWidth().padding(CanonSpace.md),
            )
        }
        return
    }

    RoadsideHelpButton(
        sending = busy,
        sent = false,
        onClick = { commit { if (!busy && !sent && !confirm) { openingVersion++; confirm = true } } },
        modifier = modifier,
    )

    if (confirm) {
        val opening = openingVersion
        AlertDialog(
            onDismissRequest = { commit { if (!busy && openingVersion == opening) confirm = false } },
            containerColor = CanonSurface,
            title = {
                Text(
                    appText("Позвать помощь?", "Ярҙам саҡырырғамы?"),
                    color = CanonText, fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    appText(
                        "Твоим доверенным контактам уйдёт SMS с координатами, а поддержка Юлдаша увидит сигнал. Если угрожает опасность — звони 112.",
                        "Ышаныслы контакттарыңа координаталар менән SMS китә, Юлдаш ярҙамы сигналды күрә. Хәүеф янаһа — 112-гә шылтырат.",
                    ),
                    color = CanonMuted, style = CanonCaption,
                )
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    var accepted = false
                    var lat: Double? = null
                    var lng: Double? = null
                    commit {
                        if (confirm && openingVersion == opening && !busy && !sent) {
                            busy = true; accepted = true
                            lat = LocationPrefs.lastLat; lng = LocationPrefs.lastLng
                        }
                    }
                    if (accepted) scope.launch {
                        if (!isCurrent()) return@launch
                        try {
                            send(lat, lng, generation)
                            .onSuccess { r ->
                                commit {
                                    notified = r.notified
                                    contactsTotal = r.contactsTotal
                                    sent = true
                                    confirm = false
                                }
                            }
                            .onFailure {
                                commit {
                                    android.widget.Toast
                                    .makeText(ctx, serverSaid(it, failMsg), android.widget.Toast.LENGTH_LONG)
                                    .show()
                                }
                            }
                        } finally { commit { busy = false } }
                    }
                }) {
                    Text(
                        appText("Позвать помощь", "Ярҙам саҡырыу"),
                        color = CanonWarn, fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { commit { if (!busy && openingVersion == opening) confirm = false } }) {
                    Text(appText("Отмена", "Кире алыу"), color = CanonMuted)
                }
            },
        )
    }
}

/**
 * Красный SOS в рабочем экране курьера.
 *
 * Почему отдельно от мягкой кнопки. Довод, которым добавили «застрял на трассе», для настоящей
 * опасности звучит сильнее: курьер едет ОДИН, рядом нет пассажира, который заметит беду.
 * Мягкую кнопку ему дали, а красная осталась только на вкладке «Карта» — в беде человек
 * не ходит по вкладкам (аудит 2026-08-06).
 *
 * Ведёт на общий экран SOS (звонок 112/102/103 + сигнал близким и дежурному), а не шлёт сигнал
 * молча: в опасности выбор «кому звонить» должен остаться у человека.
 *
 * @param route подпись-контекст дежурному («Доставка #12 Баймак → Сибай»). У сигнала нет поля
 *              под доставку, а знать, что человек был в рейсе, дежурному нужно — поэтому она
 *              уходит в заметку сигнала, тем же путём, каким туда попадают координаты.
 */
@Composable
internal fun CourierSosButton(route: String, modifier: Modifier = Modifier) {
    AppButton(
        text = appText("SOS — нужна помощь", "SOS — ярҙам кәрәк"),
        onClick = { NavSignals.openSosWithNote.value = route },
        style = AppButtonStyle.Danger,
        icon = Icons.Default.Shield,
        modifier = modifier.fillMaxWidth(),
    )
}
