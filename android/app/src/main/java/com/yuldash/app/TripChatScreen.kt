package com.yuldash.app

// Чат попутки во весь экран — как в мессенджерах (Screen.TripChat).
//
// Жалоба, с которой всё началось: строка ввода и кнопка голосового висели посередине экрана.
// Причина — чат был одним из пунктов длинного списка «Моя поездка»: при двух сообщениях поле
// стояло сразу под ними, при сорока — уезжало вниз за край. Здесь лента занимает всю высоту
// и прижата к низу (новое — внизу), а строка ввода прибита к нижнему краю и поднимается
// вместе с клавиатурой. Состояние и сеть — в TripChatState (общий экземпляр с экраном поездки).

import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.MessageDto
import kotlinx.coroutines.launch
import java.text.BreakIterator

/**
 * Каркас переписки, общий для чата попутки и чатов такси, посылки и поддержки (через [ChatContent]):
 *  • лента на всю свободную высоту, прижата к низу — `reverseLayout`; [feed] отдают «от нового к
 *    старому», [messages] — видимые сообщения «от старого к новому» (для слежения за низом);
 *  • строка ввода прибита к нижнему краю над системной навигацией и поднимается с клавиатурой.
 *    Отступ снизу ОДИН — больший из «клавиатура» и «навигация», а не их сумма: сумма дала бы
 *    щель высотой с навигационную полосу между полем и клавиатурой;
 *  • человек прокрутил вверх — справа внизу кнопка «к новым» (с числом новых, если оно известно).
 */
@Composable
internal fun ChatLayout(
    listState: LazyListState,
    messages: List<MessageDto>,
    myId: Int,
    modifier: Modifier = Modifier,
    unreadBelow: Int = 0,
    header: @Composable ColumnScope.() -> Unit = {},
    composer: @Composable ColumnScope.() -> Unit,
    feed: LazyListScope.() -> Unit,
) {
    ResizeWindowForKeyboard()
    StickToNewest(listState, messages, myId)
    val scope = rememberCoroutineScope()
    val atBottom by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val showJump = !atBottom && messages.isNotEmpty()
    Column(modifier.fillMaxSize()) {
        header()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize().testTag("chatFeed"),
                contentPadding = PaddingValues(horizontal = CanonSpace.lg, vertical = CanonSpace.md),
                verticalArrangement = Arrangement.spacedBy(CanonSpace.sm, Alignment.Bottom),
                content = feed,
            )
            androidx.compose.animation.AnimatedVisibility(   // не ColumnScope-вариант: кнопка лежит в Box над лентой
                visible = showJump,
                modifier = Modifier.align(Alignment.BottomEnd).padding(CanonSpace.lg),
                enter = fadeIn(tween(CanonMotion.QUICK)) + scaleIn(tween(CanonMotion.QUICK)),
                exit = fadeOut(tween(CanonMotion.QUICK)) + scaleOut(tween(CanonMotion.QUICK)),
            ) {
                JumpToNewestButton(unreadBelow) { scope.launch { listState.animateScrollToItem(0) } }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))
                .padding(horizontal = CanonSpace.lg, vertical = CanonSpace.sm)
                .testTag("chatComposerBar"),
            verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
            content = composer,
        )
    }
}

/**
 * Лента держится за низ, как в мессенджерах:
 *  • переписку открыли — сразу у последнего сообщения (позиция могла остаться от прошлого показа,
 *    а сообщения, пришедшие за это время, иначе оказались бы под краем экрана);
 *  • пришло новое: своё — всегда к низу; чужое — только если человек был внизу. «Был внизу» считаем
 *    ДО прихода: LazyColumn держит прежний нижний пузырь по ключу, и его номер вырос ровно на число
 *    пришедших — поэтому ждём кадр раскладки и вычитаем их, а не смотрим на номер после сдвига.
 */
@Composable
private fun StickToNewest(listState: LazyListState, messages: List<MessageDto>, myId: Int) {
    LaunchedEffect(listState) { listState.scrollToItem(0) }
    val newest = messages.lastOrNull()
    val shown = remember(listState) { intArrayOf(NO_MESSAGE) }
    LaunchedEffect(newest?.id) {
        val previousId = shown[0]
        shown[0] = newest?.id ?: NO_MESSAGE
        if (newest == null || previousId == NO_MESSAGE) return@LaunchedEffect
        val previousIndex = messages.indexOfLast { it.id == previousId }
        val arrived = if (previousIndex < 0) 0 else messages.lastIndex - previousIndex
        withFrameNanos { }
        if (newest.senderId == myId || listState.firstVisibleItemIndex - arrived <= 0) listState.animateScrollToItem(0)
    }
}

private const val NO_MESSAGE = Int.MIN_VALUE

/** Круглая кнопка «к новым сообщениям» поверх ленты; [unread] > 0 — бейдж с числом. */
@Composable
private fun JumpToNewestButton(unread: Int, onClick: () -> Unit) {
    val label = appText("К новым сообщениям", "Яңы хәбәрҙәргә")
    Box {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = CanonSurface,
            shadowElevation = CanonDepth.raised,
            border = BorderStroke(1.dp, CanonBorder),
            modifier = Modifier.size(48.dp).semantics { contentDescription = label },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = CanonGreen2)
            }
        }
        if (unread > 0) UnreadBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = CanonSpace.xs, y = -CanonSpace.xs))
    }
}

/** Бейдж непрочитанных: число на зелёном круге, для озвучки — «Новых сообщений: N». */
@Composable
internal fun UnreadBadge(count: Int, modifier: Modifier = Modifier) {
    val description = appText("Новых сообщений: $count", "Яңы хәбәрҙәр: $count")
    Box(
        modifier.defaultMinSize(minWidth = 20.dp, minHeight = 20.dp)
            .background(CanonGreen2, CircleShape)
            .padding(horizontal = CanonSpace.xs)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (count > 99) "99+" else count.toString(), style = CanonMicro, color = CanonOnFilled)
    }
}

/** Первая буква имени для аватара: целым символом, а не половинкой эмодзи или буквы с диакритикой. */
internal fun firstGrapheme(text: String): String? {
    val t = text.trim()
    if (t.isEmpty()) return null
    val it = BreakIterator.getCharacterInstance().apply { setText(t) }
    return t.substring(0, it.next()).uppercase()
}

/**
 * Переход «вглубь», как в iOS: чат въезжает справа поверх поездки, поездка чуть отступает
 * влево; «назад» — зеркально, и чат уходит поверх поездки. Длительности — со шкалы.
 */
internal fun tripChatTransition(opening: Boolean): ContentTransform =
    (slideInHorizontally(tween(CanonMotion.SLOW)) { w -> if (opening) w else -w / 4 } + fadeIn(tween(CanonMotion.NORMAL)))
        .togetherWith(slideOutHorizontally(tween(CanonMotion.SLOW)) { w -> if (opening) -w / 4 else w } + fadeOut(tween(CanonMotion.NORMAL)))
        .apply { targetContentZIndex = if (opening) 1f else -1f }

/**
 * Полноэкранный чат поездки. [title] — с кем переписка, [subtitle] — маршрут.
 * В шапке — красная SOS и «Открыть поездку» (код посадки, статусы): из чата туда один тап.
 */
@Composable
internal fun TripChatScreen(
    chat: TripChatState,
    title: String,
    peerInitial: String?,
    subtitle: String?,
    listState: LazyListState,
    onBack: () -> Unit,
    onOpenTrip: () -> Unit,
    onSos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = chat.visibleMessages
    val newest = visible.lastOrNull()
    // Прочитано — только то, что человек правда видит: лента внизу, приложение на экране,
    // история уже пришла (иначе метка встала бы до загрузки и старое считалось бы новым).
    val atBottom by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    LaunchedEffect(atBottom, newest?.id, resumed, chat.historyLoading) {
        if (atBottom && resumed && !chat.historyLoading) chat.markSeen()
    }
    // WS лежит → сообщения уходят по REST. Пока грузится история, сокет ещё только подключается — не пугаем.
    val reconnecting = chat.bookingId != null && !chat.wsConnected && !chat.historyLoading
    val editing = chat.editingId != null
    ChatLayout(
        listState = listState,
        messages = visible,
        myId = chat.myId,
        unreadBelow = chat.unread,
        modifier = modifier.background(CanonBg).testTag("tripChatScreen"),
        header = {
            TripChatTopBar(title, peerInitial, subtitle, onBack, onOpenTrip, onSos)
            AnimatedVisibility(visible = reconnecting) { ReconnectingStrip() }
        },
        composer = {
            // B8-6: дисклеймер безопасности при первом открытии чата (закрывается «Понятно») — над полем,
            // чтобы его видел и тот, кто открыл длинную переписку на последнем сообщении.
            ChatSafetyDisclaimer()
            AnimatedVisibility(visible = visible.isNotEmpty() && (chat.historyError || chat.historyLoading)) {
                TripChatFeedState(loading = chat.historyLoading, error = chat.historyError,
                    hasBooking = chat.bookingId != null, onRetry = chat::retryHistory, compact = true)
            }
            AnimatedVisibility(visible = chat.canWrite() && chat.actionBusy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CanonSpace.sm)) {
                    CircularProgressIndicator(Modifier.size(CanonSpace.lg), color = CanonGreen2)
                    Text(appText("Ждём подтверждения…", "Раҫлау көтәбеҙ…"), style = CanonBody, color = CanonMuted)
                }
            }
            AnimatedVisibility(visible = chat.canWrite() && chat.actionError.isNotBlank()) {
                Column(
                    Modifier.fillMaxWidth().background(CanonWarnBg, CanonFieldShape).padding(CanonSpace.md),
                    verticalArrangement = Arrangement.spacedBy(CanonSpace.xs),
                ) {
                    Text(chat.actionError, style = CanonBody, color = CanonText)
                    if (chat.canRetryAction) OutlinedButton(onClick = chat::retryAction) {
                        Text(appText("Повторить", "Ҡабатлау"))
                    }
                }
            }
            AnimatedVisibility(visible = editing) { EditingStrip(onCancel = chat::cancelEdit) }
            ChatComposer(
                draft = chat.draft,
                recordingAllowed = chat.canWrite(),
                actionsAllowed = !chat.actionBusy,
                onDraftChange = chat::updateDraft,
                onSend = chat::submitDraft,
                onVoiceRecorded = { path, _ -> chat.sendVoice(path) },
                onPhotoPicked = chat::sendPhoto,
                onQuickSend = chat::sendText,   // готовая фраза — тот же надёжный путь (WS→REST)
            )
        },
    ) {
        items(visible.asReversed(), key = { it.id }) { m ->
            val saved = m.id > 0   // оптимистичные (id<0) ещё не на сервере — без меню
            val mine = m.senderId == chat.myId
            Box(Modifier.fillMaxWidth().animateItem()) {   // плавное появление/перестановка пузыря в списке
                MessageBubble(
                    text = m.text,
                    voiceUrl = m.voiceUrl,
                    mine = mine,
                    failed = m.id in chat.failedIds,
                    queued = m.id in chat.queuedIds,
                    deleted = m.deleted,
                    edited = m.edited,
                    flag = m.flag,
                    fromAdmin = m.fromAdmin,
                    canEdit = saved && mine && m.voiceUrl == null && !m.deleted && !chat.actionBusy,
                    canDeleteAll = saved && mine && !m.deleted && !chat.actionBusy,
                    canDeleteMine = saved && !m.deleted && !chat.actionBusy,
                    onRetry = { chat.retry(m.id, m.text) },
                    onEdit = { chat.startEdit(m) },
                    onDelete = { who -> chat.delete(m.id, who) },
                )
            }
        }
        // Лента пуста: загрузка / ошибка с «Повторить» / «напиши первым» — там, где появится
        // первое сообщение. Оптимистично отправленное уже наполняет ленту → состояния гаснут.
        if (visible.isEmpty()) item(key = "chat-state") {
            TripChatFeedState(
                loading = chat.historyLoading,
                error = chat.historyError,
                hasBooking = chat.bookingId != null,
                onRetry = chat::retryHistory,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripChatTopBar(
    title: String,
    peerInitial: String?,
    subtitle: String?,
    onBack: () -> Unit,
    onOpenTrip: () -> Unit,
    onSos: () -> Unit,
) {
    Column {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(CanonMint, CircleShape), contentAlignment = Alignment.Center) {
                        if (peerInitial != null) Text(peerInitial, style = CanonBodyStrong, color = CanonGreen2)
                        else Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(CanonSpace.md))
                    Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
                        Text(title, style = CanonBodyStrong, color = CanonText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) {
                            Text(subtitle, style = CanonCaption, color = CanonMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBackIosNew, contentDescription = appText("Назад", "Артҡа"))
                }
            },
            actions = {
                // В беде человек не ищет красную кнопку по экранам (SafetyEverywhereGuardTest) — она и тут.
                IconButton(onClick = onSos) {
                    Icon(Icons.Default.Shield, contentDescription = appText("Экстренный вызов", "Ашығыс саҡырыу"), tint = CanonRed)
                }
                IconButton(onClick = onOpenTrip) {
                    Icon(Icons.Default.DirectionsCar, contentDescription = appText("Открыть поездку", "Сәфәрҙе асыу"), tint = CanonGreen2)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = CanonBg,
                titleContentColor = CanonText,
                navigationIconContentColor = CanonText,
                actionIconContentColor = CanonGreen2,
            ),
        )
        HorizontalDivider(color = CanonBorder)
    }
}

@Composable
private fun ReconnectingStrip() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = CanonSpace.lg, vertical = CanonSpace.xs)
            .background(CanonMint, CanonFieldShape).padding(horizontal = CanonSpace.md, vertical = CanonSpace.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = CanonGreen2)
        Spacer(Modifier.width(CanonSpace.sm))
        Text(appText("Соединение восстанавливается…", "Бәйләнеш тергеҙелә…"), color = CanonGreen2, style = CanonCaption)
    }
}

@Composable
private fun EditingStrip(onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(CanonWarnBg, CanonFieldShape).padding(start = CanonSpace.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Edit, contentDescription = null, tint = CanonWarn, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(CanonSpace.sm))
        Text(
            appText("Редактирование сообщения", "Хәбәрҙе үҙгәртеү"), color = CanonWarn, style = CanonCaption,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.minimumInteractiveComponentSize().bounceClick(onCancel).padding(horizontal = CanonSpace.md), contentAlignment = Alignment.Center) {
            // Текстом, не зелёным: зелёный на жёлтой подложке в тёмной теме ниже 4.5:1.
            Text(appText("Отмена", "Кире алыу"), color = CanonText, style = CanonBodyStrong)
        }
    }
}

@Composable
private fun TripChatFeedState(loading: Boolean, error: Boolean, hasBooking: Boolean, onRetry: () -> Unit, compact: Boolean = false) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = if (compact) CanonSpace.sm else CanonSpace.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
    ) {
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp, color = CanonGreen2)
            error -> {
                Text(
                    appText("Не удалось загрузить чат", "Чатты йөкләп булманы"),
                    color = CanonMuted, style = CanonCaption, textAlign = TextAlign.Center,
                )
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = CanonFieldShape,
                    border = BorderStroke(1.dp, CanonGreen2),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(CanonSpace.sm))
                    Text(appText("Повторить", "Ҡабатлау"), color = CanonGreen2, style = CanonBodyStrong)
                }
            }
            hasBooking -> {
                Box(Modifier.size(56.dp).background(CanonMint, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(28.dp))
                }
                Text(
                    appText("Пока нет сообщений. Напиши первым", "Әлегә хәбәрҙәр юҡ. Беренсе булып яҙ"),
                    color = CanonMuted, style = CanonCaption, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Последнее сообщение одной строкой для карточки: голос/фото словами, своё — с «Ты:». */
@Composable
private fun chatPreview(m: MessageDto, mine: Boolean): String {
    val body = when {
        m.deleted -> appText("Сообщение удалено", "Хәбәр юйылды")
        m.voiceUrl != null -> appText("🎤 Голосовое", "🎤 Тауыш")
        m.text.startsWith(ApiClient.IMG_PREFIX) -> appText("📷 Фото", "📷 Фотоһүрәт")
        else -> m.text
    }
    return if (mine) appText("Ты: $body", "Һин: $body") else body
}

/**
 * Карточка «Чат по поездке» на экране «Моя поездка»: последнее сообщение и бейдж новых.
 * Тап (как и «Написать» в нижней панели) открывает полноэкранный чат.
 */
@Composable
internal fun TripChatCard(chat: TripChatState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val last = chat.visibleMessages.lastOrNull()
    val unread = chat.unread
    // Бейдж уезжает с тем числом, что человек видел, а не с нулём.
    val shownUnread = remember { IntArray(1) }
    if (unread > 0) shownUnread[0] = unread
    val preview = when {
        last != null -> chatPreview(last, mine = last.senderId == chat.myId)
        chat.historyLoading -> appText("Загружаем переписку…", "Хәбәрҙәрҙе йөкләйбеҙ…")
        chat.historyError -> appText("Не удалось загрузить чат", "Чатты йөкләп булманы")
        else -> appText("Пока нет сообщений. Напиши первым", "Әлегә хәбәрҙәр юҡ. Беренсе булып яҙ")
    }
    AppCard(modifier = modifier.testTag("tripChatCard"), onClick = onOpen, shape = CanonItemShape) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = CanonSpace.lg, vertical = CanonSpace.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CanonSpace.md),
        ) {
            Box(Modifier.size(44.dp).background(CanonMint, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.ChatBubbleOutline, contentDescription = null, tint = CanonGreen2, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
                Text(appText("Чат по поездке", "Сәфәр буйынса чат"), style = CanonBodyStrong, color = CanonText)
                Text(
                    preview, style = CanonCaption, color = if (unread > 0) CanonText else CanonMuted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            AnimatedVisibility(
                visible = unread > 0,
                enter = scaleIn(tween(CanonMotion.QUICK)) + fadeIn(tween(CanonMotion.QUICK)),
                exit = fadeOut(tween(CanonMotion.QUICK)),
            ) { UnreadBadge(shownUnread[0]) }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
        }
    }
}
