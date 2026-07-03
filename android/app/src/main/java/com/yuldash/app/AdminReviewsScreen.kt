package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ReviewItem
import kotlinx.coroutines.launch

/**
 * Экран-обёртка («умная» часть): держит стейт, грузит данные, ходит в ApiClient.
 * Весь рендер вынесен в чистый [AdminReviewsContent] → его покрывают Robolectric-тесты на JVM.
 */
@Composable
internal fun AdminReviewsScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val items: SnapshotStateList<ReviewItem> = remember { mutableStateListOf() }
    var publishingId by remember { mutableStateOf<Int?>(null) }

    val loadErr = appText("Не удалось загрузить. Проверь интернет.", "Йөкләп булманы. Интернетты тикшер.")

    suspend fun load() {
        loading = true
        error = null
        ApiClient.getPendingReviews()
            .onSuccess { list -> items.clear(); items.addAll(list) }
            .onFailure { error = loadErr }
        loading = false
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        containerColor = CanonBg,
        topBar = { ScreenTopBar(appText("Модерация отзывов", "Фекерҙәрҙе модерациялау"), onBack) },
    ) { padding ->
        AdminReviewsContent(
            loading = loading,
            error = error,
            reviews = items,
            publishingId = publishingId,
            onRetry = { scope.launch { load() } },
            onApprove = { r ->
                if (publishingId != null) return@AdminReviewsContent
                publishingId = r.id
                scope.launch {
                    ApiClient.publishReview(r.id, true)
                        .onSuccess { items.remove(r) }
                        .onFailure { error = loadErr }
                    publishingId = null
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize(),
        )
    }
}

/**
 * Чистый рендер экрана модерации отзывов: все состояния (загрузка / ошибка+повтор / пусто / список).
 * Данные и колбэки приходят параметрами → без сети/стейта/эффектов → тестируется на JVM (Robolectric).
 */
@Composable
internal fun AdminReviewsContent(
    loading: Boolean,
    error: String?,
    reviews: List<ReviewItem>,
    publishingId: Int?,
    onRetry: () -> Unit,
    onApprove: (ReviewItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        when {
            loading -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = CanonGreen)
            }
            error != null -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(error, color = CanonRed, fontSize = 15.sp)
                Spacer(Modifier.height(14.dp))
                Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = CanonGreen, contentColor = CanonBg)) {
                    Text(appText("Повторить", "Ҡабатларға"), fontWeight = FontWeight.Bold)
                }
            }
            reviews.isEmpty() -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = CanonGreen, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text(appText("Новых отзывов нет", "Яңы фекерҙәр юҡ"), color = CanonText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(appText("Всё разобрано", "Барыһы ла ҡаралған"), color = CanonMuted, fontSize = 14.sp)
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.height(6.dp)) }
                items(reviews, key = { it.id }) { r ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CanonSurface),
                        shape = CanonCardShape,
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                repeat(r.stars.coerceIn(0, 5)) {
                                    Icon(Icons.Default.Star, contentDescription = null, tint = CanonGold, modifier = Modifier.size(16.dp))
                                }
                            }
                            Text("«${r.text}»", color = CanonText, fontSize = 15.sp, lineHeight = 20.sp)
                            Text(
                                listOfNotNull(r.name.ifBlank { null }, r.city.ifBlank { null }).joinToString(", ").ifBlank { appText("Аноним", "Аноним") },
                                color = CanonMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            )
                            Button(
                                onClick = { onApprove(r) },
                                enabled = publishingId == null,
                                colors = ButtonDefaults.buttonColors(containerColor = CanonGreen, contentColor = CanonBg),
                                shape = CanonCardShape,
                                modifier = Modifier.fillMaxWidth().height(46.dp),
                            ) {
                                if (publishingId == r.id) {
                                    CircularProgressIndicator(color = CanonBg, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Text(appText("Одобрить для сайта", "Сайт өсөн раҫларға"), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}
