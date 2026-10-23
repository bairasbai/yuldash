package com.yuldash.app

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * «Проверен» в шапке профиля (приёмка 18.10, п.13): водитель прошёл проверку документов, а в профиле
 * у него было написано «Пассажир» и не было ни следа проверки — хотя пассажиры видят этот бейдж
 * на его поездках. Шапка зелёная, поэтому знак белый, как подпись роли рядом.
 */
@Composable
internal fun ProfileVerifiedMark() {
    Spacer(Modifier.width(CanonSpace.sm))
    Icon(Icons.Default.Verified, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
    Spacer(Modifier.width(CanonSpace.xs))
    Text(appText("Проверен", "Тикшерелгән"), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
}
