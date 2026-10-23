package com.yuldash.app

import androidx.compose.runtime.Composable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.SettlementDto

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
                    "Тәҡдимдәрҙән ауылдар һәм ҡалалар ғына — адресһыҙ һәм һандарһыҙ. Дүрткә тиклем туҡталыш."),
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
