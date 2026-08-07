package com.yuldash.app

// Мягкое обновление (B9b-1b): плашка «Вышла новая версия» над любым экраном.
//
// Чем отличается от ForceUpdateScreen. Там версия уже НЕ поддерживается: приложение закрыто
// целиком, выхода нет, только кнопка в стор. Это последнее средство, и на каждый релиз его
// не применишь — иначе человек посреди дороги останется без Юлдаша из-за мелкого обновления.
// Здесь наоборот: всё работает, мы только зовём. Поэтому плашку можно закрыть.
//
// Закрыли — молчим до СЛЕДУЮЩЕЙ версии: повторно предлагать то, от чего человек уже отказался,
// это не забота, а навязчивость. Номер закрытой версии помнит YuldashApp (SharedPreferences),
// так что переустановка списка не переживает, а перезапуск — переживает.

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray

/** Ключ в настройках устройства: последняя версия, от обновления до которой человек отказался. */
internal const val PREF_UPDATE_DISMISSED = "update_dismissed_code"

/** Массив из ответа сервера → строки через `\n`. Такой вид переживает поворот экрана
 *  в rememberSaveable; обратно разбирается в [linesToList]. */
internal fun jsonArrayToLines(arr: JSONArray?): String {
    if (arr == null) return ""
    return (0 until arr.length())
        .mapNotNull { arr.optString(it).trim().ifBlank { null } }
        .joinToString("\n")
}

/** Обратная сторона [jsonArrayToLines]. */
internal fun linesToList(lines: String): List<String> =
    lines.split("\n").filter { it.isNotBlank() }

/**
 * Плашка «Вышла новая версия».
 *
 * @param visible показывать ли — решает YuldashApp (версия свежее нашей, эту ещё не закрывали,
 *   экран подходящий: на сплэше и онбординге плашка была бы дичью).
 * @param versionName человеческий номер версии («1.1.0»); пустой — просто не покажем.
 * @param whatsNewRu / [whatsNewBa] что нового, до трёх пунктов с сервера. Список берётся
 *   по текущему языку интерфейса: башкир не должен читать русские пункты.
 * @param storeUrl куда ведёт кнопка. Пустым сюда не приходит — YuldashApp без ссылки плашку
 *   не показывает: звать обновиться и никуда не вести хуже, чем промолчать.
 * @param ownsStatusBar отступ под статус-бар даёт ПЕРВЫЙ видимый элемент сверху. Когда над нами
 *   висит плашка «нет связи», отступ уже её забота, и второй превратился бы в полосу пустоты
 *   над содержимым (урок 2026-08-04, docs/lessons.md).
 */
@Composable
internal fun UpdateBanner(
    visible: Boolean,
    versionName: String,
    whatsNewRu: List<String>,
    whatsNewBa: List<String>,
    storeUrl: String,
    ownsStatusBar: Boolean,
    onLater: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val whatsNew = if (LocalAppLanguage.current == AppLanguage.Ba) whatsNewBa else whatsNewRu
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(tween(CanonMotion.NORMAL)) { -it } + fadeIn(tween(CanonMotion.NORMAL)),
        exit = slideOutVertically(tween(CanonMotion.QUICK)) { -it } + fadeOut(tween(CanonMotion.QUICK)),
    ) {
        Surface(
            modifier = Modifier
                .then(if (ownsStatusBar) Modifier.statusBarsPadding() else Modifier)
                .fillMaxWidth()
                .padding(horizontal = CanonSpace.lg, vertical = CanonSpace.sm),
            shape = CanonCardShape,
            color = CanonSurface,
            shadowElevation = CanonDepth.card,
            border = BorderStroke(1.dp, CanonHairlineGreen),
        ) {
            Column(Modifier.padding(CanonSpace.lg)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = CanonMint) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = null,   // смысл несёт заголовок рядом
                                tint = CanonGreen2,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(CanonSpace.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            appText("Вышла новая версия", "Яңы версия сыҡты"),
                            style = CanonBodyStrong,
                            color = CanonText,
                        )
                        if (versionName.isNotBlank()) {
                            Text(
                                appText("Версия $versionName", "$versionName версияһы"),
                                style = CanonMicro,
                                color = CanonMuted,
                            )
                        }
                    }
                    // Крестик = «позже»: закрыть предложение должно быть так же легко, как принять.
                    IconButton(onClick = onLater) {   // размер по умолчанию 48dp — палец попадает
                        Icon(
                            Icons.Default.Close,
                            contentDescription = appText("Напомнить позже", "Аҙаҡ иҫкә төшөрөргә"),
                            tint = CanonMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.height(CanonSpace.md))
                Button(
                    onClick = {
                        runCatching {   // нет браузера/стора → не падаем, плашка просто остаётся
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(storeUrl))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    shape = CanonItemShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CanonGreen2,
                        contentColor = CanonOnAccent,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) {
                    Text(appText("Обновить", "Яңыртыу"), style = CanonButton)
                }
                // Что нового — ПОД кнопкой сознательно: сначала действие, потом объяснение.
                // Человеку, который и так готов обновиться, не надо читать список, чтобы нажать.
                if (whatsNew.isNotEmpty()) {
                    Spacer(Modifier.height(CanonSpace.md))
                    Text(
                        appText("Что нового", "Ниҙәр яңы"),
                        style = CanonMicro,
                        color = CanonMutedStrong,
                    )
                    whatsNew.forEach { line ->
                        Spacer(Modifier.height(CanonSpace.xs))
                        Row {
                            // Точка списка — кружок, а не символ «•»: он крупнее и в разных
                            // шрифтах прыгает по вертикали. Отступ сверху ставит её на строку.
                            Box(
                                Modifier
                                    .padding(top = CanonSpace.sm)
                                    .size(CanonSpace.xs)
                                    .background(CanonGreen2, CircleShape)
                            )
                            Spacer(Modifier.width(CanonSpace.sm))
                            Text(line, style = CanonCaption, color = CanonMuted)
                        }
                    }
                }
            }
        }
    }
}
