package com.yuldash.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.SettlementDto
import kotlinx.coroutines.launch

internal const val MAX_RIDE_STOPS = 4
internal const val MAX_RIDE_STOP_LENGTH = 60

internal enum class RideStopProblem { Invalid, TooMany, Endpoint, Duplicate }
internal class RideStopException(val problem: RideStopProblem) : IllegalArgumentException(problem.name)

/** Только название населённого пункта; телефон и адрес с номером не уходят даже в поиск. */
internal fun rideStopTextAllowed(value: String): Boolean {
    val text = value.trim()
    return text.length in 1..MAX_RIDE_STOP_LENGTH && text.any(Char::isLetter) &&
        text.all { it.isLetter() || it in " -.()" }
}

private fun foldRideStop(value: String): String = value.trim().lowercase(java.util.Locale.ROOT).map {
    when (it) {
        'ё' -> 'е'; 'ә' -> 'а'; 'ө' -> 'о'; 'ү' -> 'у'; 'һ' -> 'х'; 'ҙ' -> 'з'
        'ҫ' -> 'с'; 'ң' -> 'н'; 'ғ' -> 'г'; 'ҡ' -> 'к'; else -> it
    }
}.joinToString("")

internal fun rideStopName(value: String): String = value.substringBefore('(').trim()
    .replace(Regex("^(?:(?:д|дер|с|п|пос|пгт|г)\\.\\s*|(?:деревня|село|посёлок|поселок|пгт|аул)\\s+)", RegexOption.IGNORE_CASE), "")
    .trim()

internal fun rideStopKey(value: String): String = foldRideStop(rideStopName(value))

private fun rideStopDistrict(value: String): String = value.trim().takeIf { it.endsWith(')') }
    ?.substringAfterLast('(', "")?.dropLast(1)?.trim().orEmpty()

/** Район сохраняется вместе с именем: две Берёзовки не превращаются в одну точку. */
internal fun rideStopPickText(language: AppLanguage, settlement: SettlementDto): String {
    val name = settlementTitleFor(language, settlement)
    val district = settlement.district?.trim().orEmpty()
    return if (district.isEmpty()) name else "$name ($district)"
}

internal fun rideStopIsEndpoint(settlement: SettlementDto, endpoints: List<String>): Boolean {
    val names = listOfNotNull(settlement.nameRu, settlement.nameBa).map(::rideStopKey)
    return endpoints.any { endpoint ->
        if (rideStopKey(endpoint) !in names) false else {
            val district = rideStopDistrict(endpoint)
            // Без района одинаковое имя не разрешает угадать другой населённый пункт.
            district.isBlank() || foldRideStop(district) == foldRideStop(settlement.district.orEmpty())
        }
    }
}

/** Существующий черновик тоже проверяется: ввод не теряем, неоднозначное имя просим выбрать. */
internal suspend fun resolveRideStops(
    values: List<String>,
    from: String,
    to: String,
    language: AppLanguage,
    search: suspend (String) -> List<SettlementDto> = { ApiClient.searchSettlements(it, 30).getOrThrow() },
): List<String> {
    val nonempty = values.map(String::trim).filter(String::isNotEmpty)
    if (nonempty.size > MAX_RIDE_STOPS) throw RideStopException(RideStopProblem.TooMany)
    if (nonempty.any { !rideStopTextAllowed(it) }) throw RideStopException(RideStopProblem.Invalid)
    val seen = mutableSetOf<Int>()
    return nonempty.map { raw ->
        val name = rideStopKey(raw)
        val district = rideStopDistrict(raw)
        val matches = search(rideStopName(raw)).filter { s ->
            listOfNotNull(s.nameRu, s.nameBa).any { rideStopKey(it) == name } &&
                (district.isBlank() || foldRideStop(s.district.orEmpty()) == foldRideStop(district))
        }.distinctBy { it.id }
        val selected = matches.singleOrNull() ?: throw RideStopException(RideStopProblem.Invalid)
        if (rideStopIsEndpoint(selected, listOf(from, to))) throw RideStopException(RideStopProblem.Endpoint)
        if (!seen.add(selected.id)) throw RideStopException(RideStopProblem.Duplicate)
        rideStopPickText(language, selected).also {
            if (!rideStopTextAllowed(it)) throw RideStopException(RideStopProblem.Invalid)
        }
    }
}

@Composable
internal fun rideStopErrorMessages(): Map<RideStopProblem, String> = mapOf(
    RideStopProblem.Invalid to appText("Выбери село или город из подсказок. Точное место договоритесь в чате.", "Ауыл йәки ҡаланы тәҡдимдәрҙән һайла. Теүәл урынды чатта килешегеҙ."),
    RideStopProblem.TooMany to appText("Можно добавить до четырёх остановок.", "Дүрткә тиклем туҡталыш өҫтәргә була."),
    RideStopProblem.Endpoint to appText("Начало и конец маршрута уже указаны — выбери остановку по пути.", "Юлдың башы һәм аҙағы күрһәтелгән инде — юл ыңғайындағы туҡталышты һайла."),
    RideStopProblem.Duplicate to appText("Эта остановка уже добавлена.", "Был туҡталыш өҫтәлгән инде."),
)

/** Узкий блок прежней формы; остальные поля публикации остаются на своих местах. */
@Composable
internal fun CreateRideStopsEditor(values: List<String>, onChange: (List<String>) -> Unit, field: (@Composable (Int) -> Unit)?) {
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(CanonSpace.md), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.yu_multi_stop), contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(CanonSpace.sm))
                Text(appText("Остановки по пути", "Юл буйындағы туҡталыштар"), style = CanonBodyStrong, color = CanonText)
            }
            Text(appText("Куда заезжаешь по дороге — так тебя найдут попутчики с этих мест.", "Юлда ҡайҙа туҡтайһың — шул урындарҙан юлдаштар һине табыр."), style = CanonCaption, color = CanonMuted)
            // Правило — до ввода, а не только ошибкой после «Опубликовать» (те же границы, что resolveRideStops и сервер).
            if (values.isNotEmpty()) Text(
                appText("Только сёла и города из подсказок — без адреса и цифр. До четырёх остановок.",
                    "Тәҡдимдәрҙән ауылдар һәм ҡалалар ғына — адресһеҙ һәм һандарһыҙ. Иң күбе — дүрт туҡталыш."),
                style = CanonCaption, color = CanonMuted,
            )
            values.forEachIndexed { index, value ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Box(Modifier.weight(1f)) {
                        if (field != null) field(index) else OutlinedTextField(
                            value = value,
                            onValueChange = { text -> onChange(values.toMutableList().also { it[index] = text }) },
                            placeholder = { Text(appText("Например, Темясово", "Мәҫәлән, Темәс")) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = CanonFieldShape,
                        )
                    }
                    IconButton(onClick = { onChange(values.toMutableList().also { it.removeAt(index) }) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Default.Close, contentDescription = appText("Убрать остановку", "Туҡталышты алып ташлау"), tint = CanonMuted)
                    }
                }
            }
            if (values.size < MAX_RIDE_STOPS) {
                TextButton(onClick = { onChange(values + "") }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(CanonSpace.xs))
                    Text(appText("Добавить остановку", "Туҡталыш өҫтәү"), style = CanonBodyStrong, color = CanonGreen2)
                }
            }
        }
    }
}

// ---------- RS-02 Б: цена до каждой остановки ----------

/** Хранилище цен остановок формы публикации: состояние Compose на полях, экрану нужны
 *  только «что отправить» и признак ошибки ввода. */
internal class StopPricesStore(val errorText: String) {
    var enabled by mutableStateOf(false)
    var values by mutableStateOf(listOf<String>())
    var splitBusy by mutableStateOf(false)
    var splitFailed by mutableStateOf(false)
    var invalid by mutableStateOf(false)   // правка: ввод неверен (текст показывает редактор)
}

@Composable
internal fun rememberStopPricesStore(): StopPricesStore {
    val err = appText("Проверь цены остановок: каждая больше предыдущей и меньше цены маршрута.",
                      "Туҡталыш хаҡтарын тикшер: һәр береһе алдағыһынан ҙур һәм юл хаҡынан бәләкәй.")
    return remember { StopPricesStore(err) }
}

/** Хранилище цен остановок для диалога правки своего рейса — предзаполнено текущими ценами. */
@Composable
internal fun rememberStopPricesEdit(ride: Ride): StopPricesStore {
    val err = appText("Цены остановок: каждая больше предыдущей и меньше цены маршрута.",
                      "Туҡталыш хаҡтары: һәр береһе алдағыһынан ҙур һәм юл хаҡынан бәләкәй.")
    return remember(ride.id) {
        StopPricesStore(err).apply {
            enabled = ride.stopPrices.isNotEmpty()
            values = ride.stopPrices.map { it.price.toString() }
        }
    }
}

/** Правка рейса: что отправить в stop_prices — null = не менялось; [] = свернули в одну цену.
 *  Неверен ввод → invalid=true (текст показывает редактор) и null. */
internal fun StopPricesStore.editToSend(ride: Ride, basePrice: Int): List<Int>? {
    if (ride.waypoints.isEmpty()) return null
    if (!enabled) return if (ride.stopPrices.isNotEmpty()) emptyList() else null
    val parsed = stopPricesInputValid(basePrice, ride.waypoints.size, values.filter { it.isNotBlank() })
    if (parsed == null) { invalid = true; return null }
    return if (parsed == ride.stopPrices.map { it.price }) null else parsed
}

/** Цены остановок в диалоге правки своего рейса (без «Поделить по расстоянию»; текст ошибки — внутри). */
@Composable
internal fun RideEditStopPrices(ride: Ride, price: Int, store: StopPricesStore) {
    if (ride.waypoints.isEmpty()) return
    StopPricesEditor(from = ride.from, to = ride.to, stops = ride.waypoints, price = price,
        enabled = store.enabled, onEnabledChange = { store.enabled = it; store.invalid = false },
        values = store.values, onValuesChange = { store.values = it; store.invalid = false },
        splitBusy = false, splitFailed = false, onSplit = null)
    if (store.invalid) {
        Text(store.errorText, style = CanonCaption, color = CanonRed)
    }
}

/** «Поделить по расстоянию»: км звеньев — свой справочник сервера (price_hint?stops=),
 *  последнее звено — остаток прямой начало→конец. */
internal fun StopPricesStore.split(scope: kotlinx.coroutines.CoroutineScope, from: String, to: String, stops: List<String>, total: Int) {
    if (total <= 0 || stops.isEmpty() || splitBusy) return
    splitBusy = true
    splitFailed = false
    scope.launch {
        val hint = ApiClient.getPriceHint(from.trim(), to.trim(), stops).getOrNull()
        splitBusy = false
        val legs = stops.map { s -> hint?.stopHints?.firstOrNull { it.stop == s }?.distanceKm }
        val known = legs.filterNotNull().sum()
        val lastLeg = hint?.distanceKm?.toDouble()?.let { (it - known).takeIf { d -> d > 0 } }
        val split = splitPriceByLegs(total, legs + lastLeg)
        if (split == null) { values = emptyList(); splitFailed = true } else values = split.map { it.toString() }
    }
}

/** Что отправить серверу: выключено/нет остановок — пустой список; ввод неверен — null. */
internal fun StopPricesStore.toSend(price: Int, stopsCount: Int, stopsPresent: Boolean): List<Int>? =
    if (!enabled || !stopsPresent) emptyList()
    else stopPricesInputValid(price, stopsCount, values.filter { it.isNotBlank() })

/** Самодостаточный редактор цен остановок для формы публикации (слот в CreateRideScreen). */
@Composable
internal fun CreateRideStopPrices(store: StopPricesStore, from: String, to: String, stops: List<String>, price: Int) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    StopPricesEditor(
        from = from, to = to, stops = stops, price = price,
        enabled = store.enabled, onEnabledChange = { store.enabled = it; store.splitFailed = false },
        values = store.values, onValuesChange = { store.values = it },
        splitBusy = store.splitBusy, splitFailed = store.splitFailed,
        onSplit = { store.split(scope, from, to, stops, price) },
    )
}

/** «Поделить по расстоянию»: цена всего маршрута → кумулятивные цены до каждой остановки.
 *  Шаг округления 10 ₽; ломается строгий рост — «предыдущая + 1 ₽»; не помещается
 *  (маршрут дешевле, чем точек роста) → null (поля пустые, подсказка водителю).
 *  legKm — км звеньев [начало→стоп1, …, стопN→конец] (длина = остановок+1); неизвестное
 *  или неположительное звено считаем весом 1, чтобы подсказка не ломала деление. */
internal fun splitPriceByLegs(total: Int, legKm: List<Double?>): List<Int>? {
    val stops = legKm.size - 1
    if (stops < 1 || total <= stops) return null
    val weights = legKm.map { (it ?: 0.0).takeIf { d -> d > 0.0 } ?: 1.0 }
    val sumKm = weights.sum()
    val cum = IntArray(stops)
    var acc = 0.0
    for (i in 0 until stops) {
        acc += weights[i]
        cum[i] = ((total * acc / sumKm).toInt() / 10) * 10
    }
    var prev = 0
    for (i in 0 until stops) {
        var v = cum[i]
        if (v <= prev) v = prev + 1
        if (v >= total) return null
        cum[i] = v
        prev = v
    }
    return cum.toList()
}

/** Суммы участков из кумулятивных цен: [до стоп1, стоп1→стоп2, …, стопN→конец]. */
internal fun legPriceAmounts(stopPrices: List<Int>, total: Int): List<Int> {
    val out = ArrayList<Int>(stopPrices.size + 1)
    var prev = 0
    for (p in stopPrices) { out.add(p - prev); prev = p }
    out.add(total - prev)
    return out
}

/** Ввод цен остановок из формы → список рублей для сервера; null — правило нарушено
 *  (не числа, не то число полей, рост не строгий, цена ≥ маршрута). Пустой ввод — одна цена. */
internal fun stopPricesInputValid(price: Int, stops: Int, values: List<String>): List<Int>? {
    if (values.isEmpty()) return emptyList()
    if (values.size != stops) return null
    val nums = values.map { it.toIntOrNull() ?: return null }
    var prev = 0
    for (v in nums) {
        if (v <= prev || v >= price) return null
        prev = v
    }
    return nums
}

/** Переключатель «Цена до каждой остановки» + поля, подсказка участков и «Поделить по расстоянию». */
@Composable
internal fun StopPricesEditor(
    from: String,
    to: String,
    stops: List<String>,
    price: Int,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    values: List<String>,
    onValuesChange: (List<String>) -> Unit,
    splitBusy: Boolean,
    splitFailed: Boolean,
    onSplit: (() -> Unit)? = null,   // null — кнопка «Поделить по расстоянию» скрыта (диалог правки)
) {
    if (stops.isEmpty()) return
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(CanonSpace.md), verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(appText("Цена до каждой остановки", "Һәр туҡталышҡа тиклем хаҡ"), style = CanonBodyStrong, color = CanonText)
                    Text(appText("Попутчик платит только за свой путь.", "Юлдаш тик үҙ юлы өсөн түләй."), style = CanonCaption, color = CanonMuted)
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            AnimatedVisibility(enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                    stops.forEachIndexed { index, stop ->
                        OutlinedTextField(
                            value = values.getOrElse(index) { "" },
                            onValueChange = { text ->
                                onValuesChange(
                                    values.toMutableList().also {
                                        while (it.size <= index) it.add("")
                                        it[index] = text.filter(Char::isDigit).take(5)
                                    },
                                )
                            },
                            label = { Text(appText("до $stop, ₽", "$stop тиклем, ₽")) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(), shape = CanonFieldShape,
                        )
                    }
                    val cum = values.map { it.toIntOrNull() ?: 0 }
                    if (cum.size == stops.size && cum.all { it > 0 } && price > 0) {
                        val legs = legPriceAmounts(cum, price)
                        val points = listOf(from.ifBlank { appText("Начало", "Башы") }) + stops + to.ifBlank { appText("Конец", "Аҙағы") }
                        Text(
                            legs.indices.joinToString(" · ") { i -> "${points[i]} → ${points[i + 1]}: ${legs[i]} ₽" },
                            style = CanonCaption, color = CanonMuted,
                        )
                    }
                    if (onSplit != null) {
                        OutlinedButton(onClick = onSplit, enabled = !splitBusy && price > 0,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(appText("Поделить по расстоянию", "Алыҫлыҡҡа ҡарап бүлеү"), style = CanonBodyStrong, color = CanonGreen2)
                        }
                    }
                    if (splitFailed) {
                        Text(appText("Не получилось поделить — впиши цены сам", "Бүлеп булманы — хаҡты үҙең язы"),
                            style = CanonCaption, color = CanonMuted)
                    }
                }
            }
        }
    }
}
