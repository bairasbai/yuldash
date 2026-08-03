package com.yuldash.app

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Карточка live-ссылки близкому (B7c): ссылка /t/{token} + «Скопировать» + системный share-sheet.
 * Близкий откроет её в браузере (без приложения) и увидит поездку на живой карте.
 * Используется в листах «Поделиться поездкой» такси-заказа и попутки.
 */
@Composable
internal fun LiveLinkCard(link: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val copiedMsg = appText("Ссылка скопирована", "Һылтанма күсерелде")
    val shareText = appText("Следи за моей поездкой в Юлдаш: ", "Юлдашта минең сәфәремде күҙәт: ") + link
    val shareTitle = appText("Отправить ссылку", "Һылтанманы ебәреү")
    Column(modifier.fillMaxWidth()) {
        Text(
            appText("Близкий откроет ссылку в браузере и увидит поездку на карте",
                "Яҡын кеше һылтанманы браузерҙа асып сәфәрҙе картала күрер"),
            color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(8.dp))
        Surface(color = CanonMint, shape = RoundedCornerShape(14.dp)) {
            Text(
                link,
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                color = CanonGreen2, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(link))
                    Toast.makeText(ctx, copiedMsg, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = appText("Скопировать ссылку", "Һылтанманы күсереү"),
                    tint = CanonGreen2, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(appText("Скопировать", "Күсереү"), color = CanonGreen2, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Button(
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    ctx.startActivity(Intent.createChooser(send, shareTitle))
                },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen2),
            ) {
                Icon(Icons.Default.IosShare, contentDescription = appText("Отправить ссылку", "Һылтанманы ебәреү"),
                    tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(appText("Отправить", "Ебәреү"), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }
}
