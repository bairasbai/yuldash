package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Подпись роли в шапке профиля — по тому, что человек делает (просьба Александра 23.10.2026).
 *
 * Один аккаунт и возит, и ездит, а роль на сервере от публикации поездок не меняется: человек,
 * который каждую неделю возит соседей, был «Пассажир». Теперь: есть живая или состоявшаяся своя поездка, прошёл проверку
 * документов или роль «водитель» — «Водитель»; и ещё живая или состоявшаяся бронь чужой — «Водитель и пассажир»
 * (отменённые и отклонённые сервер не считает, приёмка PR210).
 * Пустая/неизвестная роль без поездок → «Пассажир» (роль по умолчанию на бэкенде).
 */
@Composable
internal fun roleLabel(role: String, verifiedDriver: Boolean = false, drives: Boolean = false, books: Boolean = false): String {
    if (role == "admin") return appText("Администратор", "Администратор")
    val driver = role == "driver" || verifiedDriver || drives
    return when {
        driver && books -> appText("Водитель и пассажир", "Йөрөтөүсе һәм пассажир")
        driver -> appText("Водитель", "Йөрөтөүсе")
        else -> appText("Пассажир", "Пассажир")
    }
}

/**
 * Строка под именем: роль, «Проверен» (приёмка 18.10, п.13) и рейтинг. «Водитель и пассажир»
 * с «Проверен» и рейтингом не влезают в одну строку на экране 360dp — тем более по-башкирски
 * («Йөрөтөүсе һәм пассажир» + «Тикшерелгән»). Раньше это был Row: «Проверен» ломалось по слогам,
 * а рейтинг пропадал. Теперь каждая часть — целый кусок, лишнее переносится строкой ниже
 * (ревью 23.10.2026). Шапка зелёная — знаки белые, как подпись роли.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileRoleLine(label: String, verified: Boolean, rating: Double?) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm),
        verticalArrangement = Arrangement.spacedBy(CanonSpace.xs),
    ) {
        Text(label, color = Color.White.copy(alpha = 0.78f), fontSize = 14.sp, modifier = Modifier.align(Alignment.CenterVertically))
        if (verified) Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Verified, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(CanonSpace.xs))
            Text(appText("Проверен", "Тикшерелгән"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        if (rating != null) Row(Modifier.align(Alignment.CenterVertically), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Star, contentDescription = null, tint = CanonStar, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(CanonSpace.xs))
            Text(String.format(java.util.Locale.US, "%.1f", rating), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}
