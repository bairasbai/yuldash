package com.yuldash.app

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.RequestFeedDto
import kotlinx.coroutines.launch

@Composable
internal fun RequestOfferSheet(
    request: com.yuldash.app.data.RequestFeedDto,
    price: String,
    departureNote: String,
    comment: String,
    responding: Boolean,
    onPriceChange: (String) -> Unit,
    onDepartureNoteChange: (String) -> Unit,
    onCommentChange: (String) -> Unit,
    onSubmit: () -> Unit,
    bindToRide: Boolean? = null, onBindToRideChange: (Boolean) -> Unit = {},
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = CanonSpace.lg).navigationBarsPadding().imePadding(),
        verticalArrangement = Arrangement.spacedBy(CanonSpace.md),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
            Text(appText("Предложить поездку", "Сәфәр тәҡдим итеү"), color = CanonText, style = CanonTitle)
            Text("${request.from} → ${request.to}", color = CanonGreen2, style = CanonBodyStrong)
            val facts = buildList {
                if (request.desiredAt.isNotBlank()) add(formatDepart(request.desiredAt))
                request.maxPrice?.takeIf { it > 0 }?.let { add(appText("бюджет $it ₽", "бюджет $it һум")) }
            }
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = CanonMutedStrong, style = CanonBody)
        }
        Surface(color = CanonMint, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
            Row(Modifier.fillMaxWidth().padding(CanonSpace.md), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Info, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(CanonSpace.sm))
                Text(
                    appText("Цена фиксируется только после принятия пассажиром", "Хаҡ пассажир ҡабул иткәндән һуң ғына теркәлә"),
                    color = CanonMutedStrong, style = CanonCaption,
                )
            }
        }
        RequestRouteOfferChoice(request, bindToRide, onBindToRideChange, enabled = !responding)
        OutlinedTextField(
            value = price,
            onValueChange = onPriceChange,
            label = { Text(appText("Твоя цена, ₽", "Һинең хаҡың, ₽")) },
            supportingText = { Text(appText("Можно указать 0 ₽, если везёшь бесплатно", "Бушлай алып барһаң, 0 ₽ күрһәтергә була")) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = CanonItemShape,
        )
        OutlinedTextField(
            value = departureNote,
            onValueChange = onDepartureNoteChange,
            label = { Text(appText("Когда сможешь выехать", "Ҡасан сыға алаһың")) },
            placeholder = { Text(appText("Например, сегодня в 19:00", "Мәҫәлән, бөгөн 19:00-дә")) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = CanonItemShape,
        )
        OutlinedTextField(
            value = comment,
            onValueChange = onCommentChange,
            label = { Text(appText("Комментарий пассажиру", "Пассажирға аңлатма")) },
            placeholder = { Text(appText("Где удобно встретиться", "Ҡайҙа осрашыу уңайлы")) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 3,
            shape = CanonItemShape,
        )
        Button(
            onClick = onSubmit,
            enabled = !responding && requestOfferReady(request, bindToRide),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = CanonItemShape,
            colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
        ) {
            if (responding) {
                CircularProgressIndicator(color = CanonOnFilled, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(CanonSpace.sm))
            }
            Text(
                if (responding) appText("Отправляем…", "Ебәрәбеҙ…") else appText("Отправить предложение", "Тәҡдимде ебәреү"),
                style = CanonBodyStrong,
            )
        }
        Spacer(Modifier.height(CanonSpace.xs))
    }
}
