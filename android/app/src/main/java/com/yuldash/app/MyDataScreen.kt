package com.yuldash.app

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CreditCardOff
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EventSeat
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MyDataDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * «Мои данные» — выписка о том, что Юлдаш хранит о человеке.
 *
 * Зачем экран. «Удалите мои данные» люди просят не потому, что данные им мешают, а потому что
 * не знают, что именно у нас лежит и надолго ли. Оферта на этот страх не отвечает: там общие
 * слова. Отвечают числа и сроки — переписка уходит сама через месяц, поездки через полгода,
 * точную геолокацию мы не храним вовсе. Раньше единственным ответом было «удалить аккаунт
 * целиком»: человеку, которому просто неуютно, приходилось уходить из приложения.
 *
 * Сроки приходят с сервера, а не зашиты в приложении: иначе экран начнёт обещать одно,
 * а чистилка базы делать другое — то же враньё, только про приватность.
 *
 * Состояния: загрузка / ошибка (повтор) / данные. Нули у новичка — валидные данные,
 * а не пустой экран: «ничего не накопилось» здесь и есть хороший ответ.
 */
@Composable
internal fun MyDataScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Язык нужен внутри корутин: там @Composable-хелпер appText недоступен.
    val language = LocalAppLanguage.current

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf<MyDataDto?>(null) }
    var askDeleteDocs by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // Отказ сервера показываем текстом сервера: он знает причину точнее приложения
    // (на линии / документы на проверке), и текст уже приходит на двух языках.
    var docsError by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        loading = true; error = false
        ApiClient.getMyData()
            .onSuccess { data = it; loading = false }
            .onFailure { error = true; loading = false }
    }
    LaunchedEffect(Unit) { load() }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Мои данные", "Минең мәғлүмәттәр"), onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
        ) {
            item {
                Text(
                    appText(
                        "Вот всё, что Юлдаш о тебе хранит — и когда оно исчезнет само.",
                        "Юлдаш һинең хаҡта нимә һаҡлай — һәм ул үҙе ҡасан юғала.",
                    ),
                    color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }

            when {
                loading -> item { AppLoading(appText("Смотрим, что у нас есть…", "Нимә барлығын ҡарайбыҙ…")) }
                error -> item { AppErrorState(onRetry = { scope.launch { load() } }) }
                data != null -> {
                    val d = data!!
                    item {
                        FadeInCard(delayMs = 0) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = CanonSurface),
                                shape = CanonCardShape,
                                elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
                            ) {
                                Column(Modifier.padding(vertical = 4.dp)) {
                                    DataRow(
                                        Icons.Default.DirectionsCar,
                                        appText("Поездки", "Сәфәрҙәр"),
                                        countText(d.rides, "поездка", "поездки", "поездок", "сәфәр"),
                                        selfDeleteText(d.ridesDays),
                                    )
                                    RowDivider()
                                    DataRow(
                                        Icons.Default.EventSeat,
                                        appText("Забронированные места", "Алынған урындар"),
                                        countText(d.bookings, "бронь", "брони", "броней", "урын"),
                                        selfDeleteText(d.ridesDays),
                                    )
                                    RowDivider()
                                    DataRow(
                                        Icons.Default.Chat,
                                        appText("Сообщения в чатах", "Чаттарҙағы хәбәрҙәр"),
                                        countText(d.messages, "сообщение", "сообщения", "сообщений", "хәбәр"),
                                        selfDeleteText(d.messagesDays),
                                    )
                                    RowDivider()
                                    DataRow(
                                        Icons.Default.Mic,
                                        appText("Голосовые", "Тауышлы хәбәрҙәр"),
                                        countText(d.voices, "запись", "записи", "записей", "яҙма"),
                                        selfDeleteText(d.voicesDays),
                                    )
                                    RowDivider()
                                    DataRow(
                                        Icons.Default.Notifications,
                                        appText("Уведомления", "Иҫкәртеүҙәр"),
                                        countText(d.notifications, "штука", "штуки", "штук", "дана"),
                                        selfDeleteText(d.notificationsDays),
                                    )
                                }
                            }
                        }
                    }

                    // Документы водителя стоят отдельно: это единственное, что не удаляется
                    // само никогда — и единственное, что можно убрать точечно, не трогая аккаунт.
                    if (d.driverDocs > 0) {
                        item {
                            FadeInCard(delayMs = CanonMotion.QUICK) {
                                DriverDocsCard(
                                    count = d.driverDocs,
                                    removable = d.driverDocsRemovable,
                                    deleting = deleting,
                                    onDelete = { askDeleteDocs = true },
                                )
                            }
                        }
                    }

                    item {
                        FadeInCard(delayMs = CanonMotion.NORMAL) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = CanonSurface),
                                shape = CanonCardShape,
                                elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
                            ) {
                                Column(Modifier.padding(vertical = 4.dp)) {
                                    Text(
                                        appText("Чего у нас нет", "Беҙҙә юҡ нәмәләр"),
                                        color = CanonText, fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp, lineHeight = 23.sp,
                                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                                    )
                                    if (!d.locationStored) {
                                        DataRow(
                                            Icons.Default.LocationOff,
                                            appText("Точная геолокация", "Теүәл геолокация"),
                                            appText("Не храним", "Һаҡламайбыҙ"),
                                            appText(
                                                "Видно только попутчикам и только во время поездки",
                                                "Юлдаштарға ғына һәм сәфәр барышында ғына күренә",
                                            ),
                                        )
                                    }
                                    if (!d.locationStored && !d.cardStored) RowDivider()
                                    if (!d.cardStored) {
                                        DataRow(
                                            Icons.Default.CreditCardOff,
                                            appText("Данные карты", "Карта мәғлүмәттәре"),
                                            appText("Не храним", "Һаҡламайбыҙ"),
                                            appText(
                                                "Деньги идут мимо нас — напрямую водителю",
                                                "Аҡса беҙҙән үтмәй — тура шоферға бара",
                                            ),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Выгрузка внизу, после всех счётчиков: сначала человек видит, что данных
                    // немного, и часто этого хватает. Кому мало — скачает файл.
                    item {
                        FadeInCard(delayMs = CanonMotion.SLOW) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = CanonSurface),
                                shape = CanonCardShape,
                                elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Surface(color = CanonMint, shape = CanonTinyShape) {
                                            Icon(
                                                Icons.Default.Download, contentDescription = null,
                                                tint = CanonGreen2, modifier = Modifier.padding(8.dp),
                                            )
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                appText("Скачать мои данные", "Мәғлүмәттәремде йөкләү"),
                                                color = CanonText, fontWeight = FontWeight.Bold,
                                                fontSize = 16.sp, lineHeight = 23.sp,
                                            )
                                            Text(
                                                appText(
                                                    "Обычный текстовый файл — откроется на любом телефоне",
                                                    "Ябай текст файлы — теләһә ниндәй телефонда асыла",
                                                ),
                                                color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                                            )
                                        }
                                    }
                                    AppButton(
                                        text = appText("Скачать", "Йөкләргә"),
                                        onClick = {
                                            scope.launch {
                                                exporting = true; exportError = null
                                                val code = if (language == AppLanguage.Ba) "ba" else "ru"
                                                ApiClient.exportMyData(code)
                                                    .onSuccess { dump ->
                                                        val uri = withContext(Dispatchers.IO) {
                                                            runCatching { writeMyDataFile(context, dump.filename, dump.text) }.getOrNull()
                                                        }
                                                        exporting = false
                                                        if (uri != null) {
                                                            shareMyDataFile(context, uri, dump.filename)
                                                        } else {
                                                            exportError = appTextFor(
                                                                language,
                                                                "Не получилось сохранить файл.",
                                                                "Файлды һаҡлап булманы.",
                                                            )
                                                        }
                                                    }
                                                    .onFailure {
                                                        exporting = false
                                                        exportError = it.message ?: appTextFor(
                                                            language,
                                                            "Не получилось собрать файл. Попробуй ещё раз.",
                                                            "Файл йыйып булманы. Тағы бер тапҡыр ҡара.",
                                                        )
                                                    }
                                            }
                                        },
                                        style = AppButtonStyle.Secondary,
                                        loading = exporting,
                                        enabled = !exporting,
                                    )
                                    exportError?.let {
                                        Text(it, color = CanonRed, fontSize = 12.sp, lineHeight = 17.sp)
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            appText(
                                "Сроки — те же, по которым база чистится сама. Ничего вручную удалять не нужно.",
                                "Ваҡыттар — база үҙе таҙарынған ваҡыттар. Ҡулдан бер нәмә лә юйырға кәрәкмәй.",
                            ),
                            color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        )
                    }
                }
            }
        }
    }

    if (askDeleteDocs) {
        AlertDialog(
            onDismissRequest = { if (!deleting) askDeleteDocs = false },
            containerColor = CanonSurface,
            shape = CanonCardShape,
            title = {
                Text(
                    appText("Удалить документы?", "Документтарҙы юяһыңмы?"),
                    color = CanonText, fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        appText(
                            "Фото прав и машины удалятся с сервера навсегда. Вместе с ними снимется галочка «проверен» — пассажиры перестанут её видеть.",
                            "Права һәм машина фотоһы серверҙан бөтөнләй юйыла. Улар менән бергә «тикшерелгән» билдәһе лә юйыла — юлсылар уны күрмәйәсәк.",
                        ),
                        color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                    )
                    Text(
                        appText(
                            "Аккаунт и поездки останутся. Захочешь возить снова — просто загрузишь документы заново.",
                            "Иҫәп һәм сәфәрҙәр ҡала. Тағы йөрөтөргә теләһәң — документтарҙы яңынан һалаһың.",
                        ),
                        color = CanonMuted, fontSize = 14.sp, lineHeight = 20.sp,
                    )
                    docsError?.let {
                        Text(it, color = CanonRed, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        scope.launch {
                            deleting = true; docsError = null
                            ApiClient.deleteDriverDocs()
                                .onSuccess {
                                    deleting = false
                                    askDeleteDocs = false
                                    load()
                                }
                                .onFailure {
                                    deleting = false
                                    docsError = it.message ?: appTextFor(
                                        language,
                                        "Не получилось удалить. Попробуй ещё раз.",
                                        "Юйып булманы. Тағы бер тапҡыр ҡара.",
                                    )
                                }
                        }
                    },
                ) {
                    Text(
                        if (deleting) appText("Удаляем…", "Юябыҙ…") else appText("Удалить", "Юйырға"),
                        color = CanonRed, fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !deleting, onClick = { askDeleteDocs = false; docsError = null }) {
                    Text(appText("Отмена", "Баш тартыу"), color = CanonMuted)
                }
            },
        )
    }
}

/** Одна строка выписки: иконка — что это — сколько — когда исчезнет. */
@Composable
private fun DataRow(icon: ImageVector, title: String, value: String, note: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = CanonMint, shape = CanonTinyShape) {
            Icon(icon, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp))
        }
        Spacer(Modifier.width(12.dp))
        // weight(1f) обязателен: башкирский почти всегда длиннее русского,
        // без него подпись уезжала бы за край карточки.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 23.sp)
            Text(note, color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.width(12.dp))
        Text(value, color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 60.dp, end = 16.dp),
        color = CanonBorder,
    )
}

/** Документы водителя: единственное, что не чистится само — и единственное, что удаляется точечно. */
@Composable
private fun DriverDocsCard(count: Int, removable: Boolean, deleting: Boolean, onDelete: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CanonSurface),
        shape = CanonCardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = CanonDepth.card),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = CanonMint, shape = CanonTinyShape) {
                    Icon(Icons.Default.Badge, contentDescription = null, tint = CanonGreen2, modifier = Modifier.padding(8.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        appText("Документы водителя", "Шофер документтары"),
                        color = CanonText, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 23.sp,
                    )
                    Text(
                        appText("Хранятся, пока ты сам их не удалишь", "Үҙең юймайынса һаҡлана"),
                        color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    countText(count, "файл", "файла", "файлов", "файл"),
                    color = CanonGreen2, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp,
                )
            }
            if (removable) {
                Spacer(Modifier.height(4.dp))
                AppButton(
                    text = appText("Удалить документы", "Документтарҙы юйырға"),
                    onClick = onDelete,
                    style = AppButtonStyle.Danger,
                    enabled = !deleting,
                )
            } else {
                // Кнопку не показываем вовсе, а не показываем серой: обещание действия,
                // которое сервер всё равно отклонит, злит сильнее, чем его отсутствие.
                Text(
                    appText(
                        "Пока идёт проверка или ты на линии — удалить нельзя. Закончится проверка, сойдёшь с линии — кнопка появится.",
                        "Тикшереү барғанда йәки линияла булғанда — юйып булмай. Тикшереү бөткәс, линиянан сыҡҡас — төймә килеп сыға.",
                    ),
                    color = CanonMuted, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
        }
    }
}

/** Плавное появление карточки со сдвигом снизу — как в iOS-списках. */
@Composable
private fun FadeInCard(delayMs: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(CanonMotion.NORMAL, delayMillis = delayMs)) +
            slideInVertically(tween(CanonMotion.NORMAL, delayMillis = delayMs)) { it / 6 },
    ) { content() }
}

/** «12 поездок» / «12 сәфәр»: башкирский (тюркский) счётное существительное не склоняет. */
@Composable
private fun countText(n: Int, one: String, few: String, many: String, ba: String): String =
    appText("$n " + pluralRu(n, one, few, many), "$n $ba")

/** «Исчезнут сами через 180 дней» — срок приходит с сервера, здесь только формулировка. */
@Composable
private fun selfDeleteText(days: Int): String =
    appText(
        "Исчезнут сами через $days " + pluralRu(days, "день", "дня", "дней"),
        "$days көндән үҙҙәре юғала",
    )

/**
 * Кладём выгрузку в кеш приложения и возвращаем ссылку для системного «Поделиться».
 *
 * Почему не «сохранить в Загрузки». Прямая запись в общую папку требует разрешения или
 * диалога выбора места — лишний шаг там, где человек уже нажал «Скачать». Системный лист
 * даёт всё сразу: сохранить в файлы, отправить себе в мессенджер, положить на диск.
 * Кеш система чистит сама — копия личных данных не оседает на телефоне навсегда.
 */
private fun writeMyDataFile(context: Context, filename: String, text: String): android.net.Uri {
    val dir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(dir, filename)
    file.writeText(text)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun shareMyDataFile(context: Context, uri: android.net.Uri, filename: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, filename).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
