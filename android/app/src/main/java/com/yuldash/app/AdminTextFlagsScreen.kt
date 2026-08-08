package com.yuldash.app

// Экран админа «Помеченные тексты».
//
// Зачем он есть. Модерация текста работала и раньше: телефон, увод в мессенджер, мат и фишинг
// помечались в комментариях, заявках, откликах, отзывах и чате. Но результат ложился только в
// счётчик — в пульсе было видно ЧИСЛО помеченных за сегодня, и всё. Кто и за что, посмотреть
// было нельзя, то есть среагировать не на что. Помечать и не показывать — работа впустую.
//
// Приватность. Самого текста здесь НЕТ и с сервера он не приходит: только кто, какая метка,
// в каком поле и когда. Текст лежит в своей записи — админ открывает её в нужном экране и
// читает в контексте. Второй копии личных данных не появляется.
//
// Принцип модуля не меняется: это ПОМЕТКА, а не наказание. Ничего не заблокировано,
// текст доставлен, решение принимает человек.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.PhoneLocked
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.TextFlagDto

/** Экран со списком. Загрузка и фильтр живут тут, рисование — в [AdminTextFlagsContent]. */
@Composable
internal fun AdminTextFlagsScreen(onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<TextFlagDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf("") }   // "" = все виды
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(kind, reloadKey) {
        loading = true; error = false
        ApiClient.getTextFlags(kind)
            .onSuccess { items = it; loading = false }
            .onFailure { error = true; loading = false }
    }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Помеченные тексты", "Билдәләнгән текстар"), onBack) },
    ) { padding ->
        AdminTextFlagsContent(
            loading = loading,
            error = error,
            items = items,
            kind = kind,
            onKind = { kind = it },
            onRetry = { reloadKey += 1 },
            modifier = Modifier.padding(padding),
        )
    }
}

/** Чистый рендер: данные и колбэки параметрами → без сети и эффектов, тестируется на JVM. */
@Composable
internal fun AdminTextFlagsContent(
    loading: Boolean,
    error: Boolean,
    items: List<TextFlagDto>,
    kind: String,
    onKind: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        item {
            Text(
                appText(
                    "Здесь видно, кто и за что помечен. Ничего не заблокировано — текст дошёл до получателя. Решение за тобой.",
                    "Бында кем һәм ни өсөн билдәләнгәне күренә. Бер нәмә лә быуылмаған — текст барып еткән. Ҡарар һинеке.",
                ),
                color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(FLAG_KINDS, key = { it.first }) { (key, label) ->
                    NearbyFilterChip(flagIcon(key), label(), kind == key) { onKind(if (kind == key) "" else key) }
                }
            }
        }
        if (loading) {
            item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { SkeletonCard(lines = 2) } } }
        } else if (error) {
            item {
                ListedError(
                    appText("Не удалось загрузить. Проверь сеть.", "Йөкләп булманы. Селтәрҙе тикшер."),
                    onRetry = onRetry,
                )
            }
        } else if (items.isEmpty()) {
            item {
                ListedEmpty(
                    appText("Помеченных текстов нет", "Билдәләнгән текст юҡ"),
                    appText("Это хорошая новость: никто не писал телефоны и грубости.",
                        "Был яҡшы хәбәр: бер кем дә телефон да, тупаҫлыҡ та яҙмаған."),
                )
            }
        } else {
            items(items, key = { it.id }) { f -> TextFlagCard(f) }
        }
    }
}

/** Виды меток для фильтра. Порядок — по опасности: деньги → увод → грубость. */
private val FLAG_KINDS: List<Pair<String, @Composable () -> String>> = listOf(
    // Не «фишинг» и не «контакт»: админ читает по-человечески, а не термины. Заодно
    // башкирский тут не копия русского — сторож двуязычия такое не пропускает, и правильно.
    "warn" to { appText("Обман с деньгами", "Аҡса алдауы") },
    "contact" to { appText("Телефон в тексте", "Текстта телефон") },
    "abuse" to { appText("Грубость", "Тупаҫлыҡ") },
)

private fun flagIcon(kind: String) = when (kind) {
    "warn" -> Icons.Default.Warning
    "contact" -> Icons.Default.PhoneLocked
    else -> Icons.Default.Block
}

@Composable
private fun flagLabel(kind: String): String = when (kind) {
    "warn" -> appText("Обман — уводят деньги", "Алдау — аҡса урлай")
    "contact" -> appText("Телефон / увод из приложения", "Телефон / ҡушымтанан сығарыу")
    else -> appText("Грубость — мат в тексте", "Тупаҫлыҡ — текстта әрләү")
}

/** Цвет метки: фишинг — красный (деньги), остальное — спокойное. Без «уголовного» тона. */
@Composable
private fun flagColor(kind: String) = if (kind == "warn") CanonRed else CanonMuted

@Composable
private fun TextFlagCard(f: TextFlagDto) {
    Surface(color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(flagIcon(f.kind), contentDescription = null, tint = flagColor(f.kind), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(flagLabel(f.kind), color = flagColor(f.kind), fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Text(f.placeLabel, color = CanonText, fontSize = 16.sp)
            Text(
                listOf(f.userName, f.userPhone).filter { it.isNotBlank() }.joinToString(" · "),
                color = CanonMuted, fontSize = 14.sp,
            )
            // Разовое срабатывание бывает у любого — важна повторяемость. Показываем её прямо тут,
            // чтобы админ не считал руками и не банил человека за одно неудачное слово.
            if (f.userFlagsTotal > 1) {
                Text(
                    appText("У этого человека пометок: ${f.userFlagsTotal}",
                        "Был кешелә билдә: ${f.userFlagsTotal}"),
                    color = CanonMuted, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                )
            }
            if (f.createdAt.isNotBlank()) {
                Text(formatDepart(f.createdAt), color = CanonMuted, fontSize = 12.sp)
            }
        }
    }
}
