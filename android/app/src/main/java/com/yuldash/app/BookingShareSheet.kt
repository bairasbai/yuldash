package com.yuldash.app

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.yuldash.app.data.ApiClient
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookingShareSheet(
    bookingId: Int?, contacts: List<TrustedContact>, tripSession: Long,
    isCurrent: () -> Boolean, onDismiss: () -> Unit, onTrustedContacts: () -> Unit,
) {
    val currentParent by rememberUpdatedState(isCurrent)
    val context = LocalContext.current
    val shareSheet = rememberModalBottomSheetState()
    val tripSharedPrefix = appText("Ссылка готова", "Һылтанма әҙер")
    val shareRevokedMsg = appText("Ссылка отозвана", "Һылтанма кире алынды")
    // A fresh scope for each opening rejects its predecessor's results and retained callbacks.
    val sharingScope = rememberCoroutineScope()
    fun isShareCurrent() = sharingScope.isActive && currentParent()
    AppModalBottomSheet(onDismissRequest = { if (isShareCurrent()) onDismiss() }, sheetState = shareSheet, containerColor = CanonSurface) {
        // Ссылка live-поездки (B7c): после выбора близкого показываем её тут же —
        // скопировать или отправить самому через системный share-sheet.
        var liveLink by remember { mutableStateOf<String?>(null) }
        // Приватность: кому сейчас открыта поездка + возможность отозвать.
        var activeShares by remember { mutableStateOf<List<Pair<com.yuldash.app.data.TripShareDto, String>>>(emptyList()) }
        var showContacts by remember { mutableStateOf(true) }
        var listLoading by remember { mutableStateOf(true) }
        var listError by remember { mutableStateOf(false) }
        var mutationUncertain by remember { mutableStateOf(false) }
        var listErrorText by remember { mutableStateOf<String?>(null) }   // объяснение сервера (429 и т.п.); null — обрыв сети
        var mutationBusy by remember { mutableStateOf(false) }
        var listRequest by remember { mutableIntStateOf(0) }
        fun canMutate() = isShareCurrent() && !listLoading && !listError && !mutationBusy
        fun reloadShares() {
            if (!isShareCurrent() || listLoading || mutationBusy) return
            // Set before launch: retained callbacks in the same frame must see the lock.
            listLoading = true
            listError = false; listErrorText = null
            listRequest++
        }
        fun reconcileShares(cause: Throwable?) {
            // A failed POST/DELETE can follow a committed server change. Only GET settles access.
            mutationUncertain = true
            listError = true; listErrorText = serverText(cause)
        }
        LaunchedEffect(bookingId, listRequest) {
            if (!isShareCurrent()) return@LaunchedEffect
            val bid = bookingId
            if (bid == null) {
                listError = true
                listLoading = false
                return@LaunchedEffect
            }
            ApiClient.getBookingShares(bid).onSuccess { srv ->
                if (!isShareCurrent()) return@onSuccess
                activeShares = srv.map { s ->
                    s to (contacts.firstOrNull { it.id == s.contactId }?.name ?: "")
                }
                liveLink = srv.firstNotNullOfOrNull { it.link?.takeIf(String::isNotBlank) }
                showContacts = srv.isEmpty()
                mutationUncertain = false
                listError = false; listErrorText = null
            }.onFailure {
                if (isShareCurrent()) { listError = true; listErrorText = serverText(it) }
            }
            if (isShareCurrent()) listLoading = false
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = CanonSpace.lg).padding(bottom = CanonSpace.xl)) {
            val link = liveLink
            if (listLoading) {
                Text(appText("Проверяем ссылки…", "Һылтанмаларҙы тикшерәбеҙ…"), color = CanonText, modifier = Modifier.padding(vertical = CanonSpace.lg))
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("tripSharesLoading"), color = CanonGreen2)
            } else if (listError) {
                Text(
                    if (bookingId == null) appText("Не удалось открыть ссылки. Открой поездку заново.", "Һылтанмаларҙы асып булманы. Сәфәрҙе яңынан ас.")
                    else if (mutationUncertain) listOfNotNull(listErrorText, appText("Не получилось подтвердить результат. Обнови список ссылок.", "Һөҙөмтәне раҫлап булманы. Һылтанмалар исемлеген яңырт.")).joinToString("\n")
                    else listErrorText ?: appText("Не удалось загрузить ссылки. Проверь сеть и повтори.", "Һылтанмаларҙы йөкләп булманы. Селтәрҙе тикшереп ҡабатла."),
                    color = CanonText, modifier = Modifier.padding(vertical = CanonSpace.lg).testTag("tripSharesError"),
                )
                TextButton(onClick = { if (bookingId == null) { if (isShareCurrent()) onDismiss() } else reloadShares() }, modifier = Modifier.heightIn(min = CanonSpace.huge).testTag("tripSharesRetry")) {
                    Text(if (bookingId == null) appText("Закрыть", "Ябыу") else if (mutationUncertain) appText("Обновить список", "Исемлекте яңыртыу") else appText("Повторить", "Ҡабатлау"), color = CanonGreen2)
                }
            } else if (activeShares.isNotEmpty() && !showContacts) {
                Text(appText("Ссылка для близкого", "Яҡын кеше өсөн һылтанма"), style = CanonTitle, modifier = Modifier.padding(vertical = CanonSpace.sm))
                if (!link.isNullOrBlank()) LiveLinkCard(link, ownerGeneration = tripSession,
                    isCurrentParent = { isShareCurrent() && !listLoading && !listError && !mutationBusy &&
                        !showContacts && liveLink == link && activeShares.any { it.first.link == link } })
                ActiveSharesList(activeShares, enabled = !mutationBusy) { share ->
                    val bid = bookingId
                    if (bid != null && canMutate() && !showContacts && activeShares.any { it.first.id == share.id }) {
                        mutationBusy = true
                        sharingScope.launch {
                            if (!isShareCurrent()) return@launch
                            try { ApiClient.revokeBookingShare(bid, share.id)
                            .onSuccess {
                                if (!isShareCurrent()) return@onSuccess
                                activeShares = activeShares.filterNot { it.first.id == share.id }
                                liveLink = activeShares.firstNotNullOfOrNull { it.first.link?.takeIf(String::isNotBlank) }
                                if (activeShares.isEmpty()) showContacts = true
                                Toast.makeText(context, shareRevokedMsg, Toast.LENGTH_SHORT).show()
                            }
                            .onFailure { if (isShareCurrent()) reconcileShares(it) }
                            } finally { if (isShareCurrent()) mutationBusy = false }
                        }
                    }
                }
                Spacer(Modifier.height(CanonSpace.md))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(enabled = !mutationBusy, onClick = { if (canMutate()) showContacts = true }) {
                        Text(appText("Поделиться ещё", "Йәнә бүлешеү"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { if (isShareCurrent()) onDismiss() }) {
                        Text(appText("Готово", "Әҙер"), color = CanonGreen2, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
            Text(appText("Кому отправить поездку", "Сәфәрҙе кемгә ебәрергә"), style = CanonTitle, modifier = Modifier.padding(vertical = CanonSpace.sm))
            if (contacts.isEmpty()) NoTrustedContactsHint { if (isShareCurrent()) { onDismiss(); onTrustedContacts() } }   // RideshareTripContacts.kt
            contacts.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !mutationBusy) {
                        val bid = bookingId
                        if (bid != null && canMutate() && showContacts) {
                            mutationBusy = true
                            sharingScope.launch {
                                if (!isShareCurrent()) return@launch
                                try { ApiClient.shareTrip(bid, c.id)
                                .onSuccess { share ->
                                    if (!isShareCurrent()) return@onSuccess
                                    if (share != null) {
                                        activeShares = activeShares.filterNot { it.first.id == share.id } + (share to c.name)
                                        liveLink = share.link?.takeIf(String::isNotBlank)
                                        showContacts = false
                                        Toast.makeText(context, "$tripSharedPrefix: ${c.name}", Toast.LENGTH_SHORT).show()
                                    } else reconcileShares(null)
                                }
                                .onFailure {
                                    if (!isShareCurrent()) return@onFailure
                                    reconcileShares(it)
                                }
                                } finally { if (isShareCurrent()) mutationBusy = false }
                            }
                        }
                    }.padding(vertical = CanonSpace.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(CanonSpace.huge).background(CanonMint, CircleShape), contentAlignment = Alignment.Center) {
                        Text(c.name.take(1), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(CanonSpace.md))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontWeight = FontWeight.Bold)
                        Text(c.relation, color = CanonMuted, style = CanonCaption)
                    }
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = CanonMuted)
                }
            }
            }
            AnimatedVisibility(visible = mutationBusy, enter = fadeIn(tween(CanonMotion.QUICK)), exit = fadeOut(tween(CanonMotion.QUICK))) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = CanonGreen2)
            }
        }
    }
}
