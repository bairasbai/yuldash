package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Redeem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.PartnerMeDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** Живой владелец старых кодов; смена аккаунта и закрытие имеют приоритет над Result. */
internal class BenefitOwner(val generation: Long, private val context: CoroutineContext) {
    @Volatile var alive = true
    fun isCurrent(): Boolean = alive && context[Job]?.isActive == true && ApiClient.isCurrentSession(generation)
    fun publish(action: () -> Unit): Boolean {
        var accepted = false
        ApiClient.runIfCurrentSession(generation) { if (isCurrent()) { action(); accepted = true } }
        return accepted
    }
}

/** API снимает session snapshot до первого suspend; UNDISPATCHED выполняет этот вход под owner lock. */
internal fun <T> launchBenefitOperation(scope: CoroutineScope, owner: BenefitOwner?, call: suspend () -> Result<T>, accept: (Result<T>) -> Unit) {
    val start = { scope.launch(start = if (owner == null) CoroutineStart.DEFAULT else CoroutineStart.UNDISPATCHED) {
        if (owner != null && !owner.isCurrent()) return@launch
        val reply = call()
        currentCoroutineContext().ensureActive()
        if (owner == null) accept(reply) else owner.publish { accept(reply) }
    } }
    if (owner == null) start() else owner.publish { start() }
}

@Composable
private fun BenefitOwnerHost(onBack: () -> Unit, onFindRide: () -> Unit, content: @Composable (BenefitOwner) -> Unit) {
    val generation by ApiClient.sessionChanges.collectAsState()
    val scope = rememberCoroutineScope()
    val owner = remember(generation) { BenefitOwner(generation, scope.coroutineContext) }
    DisposableEffect(owner) { onDispose { owner.alive = false } }
    if (!owner.isCurrent()) ComingSoonScreen(ComingSoonKind.Business, onBack, onFindRide)
    else key(generation) { content(owner) }
}

/** Старые коды сохраняются отдельно от новой витрины, выпуска купонов и подписки. */
@Composable
internal fun ExistingCouponHistoryScreen(onBack: () -> Unit, onFindRide: () -> Unit) {
    BenefitOwnerHost(onBack, onFindRide) { owner ->
        Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Мои купоны", "Минең купондар"), onBack) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) { MyCouponsTab(onFindRide, allowNewCoupons = false, owner = owner) }
        }
    }
}

@Composable
internal fun ExistingPartnerRedeemScreen(onBack: () -> Unit, onFindRide: () -> Unit) {
    BenefitOwnerHost(onBack, onFindRide) { owner -> ExistingPartnerRedeemContent(owner, onBack, onFindRide) }
}

@Composable
private fun ExistingPartnerRedeemContent(owner: BenefitOwner, onBack: () -> Unit, onFindRide: () -> Unit) {
    val scope = rememberCoroutineScope()
    var me by remember { mutableStateOf<PartnerMeDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var redeem by remember { mutableStateOf(false) }
    LaunchedEffect(reload) {
        owner.publish { loading = true; failed = false }
        launchBenefitOperation(scope, owner, { ApiClient.getPartnerMe() }) { reply ->
            reply.onSuccess { me = it }.onFailure { failed = true }
            loading = false
        }
    }
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Погасить код клиента", "Клиент кодын һүндереү"), onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(CanonSpace.lg), verticalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
            when {
                loading -> SkeletonCard(lines = 2)
                failed -> AppErrorState(onRetry = { owner.publish { reload++ } })
                me?.partner == null -> ComingSoonContent(ComingSoonKind.Business, onFindRide)
                else -> {
                    Text(me!!.partner!!.name, style = CanonTitle, color = CanonText)
                    AppButton(appText("Погасить код клиента", "Клиент кодын һүндереү"), { owner.publish { redeem = true } }, icon = Icons.Default.Redeem)
                }
            }
        }
    }
    if (redeem) RedeemDialog(onDismiss = { owner.publish { redeem = false } }, owner = owner)
}

@Composable
internal fun CouponHistoryEmpty(allowNewCoupons: Boolean, onGoNearby: () -> Unit) {
    if (!allowNewCoupons) ComingSoonContent(ComingSoonKind.Business, onGoNearby, scrollable = false)
    else AppEmptyState(
        title = appText("Пока нет купонов", "Әлегә купондар юҡ"),
        text = appText("Активируй скидку на вкладке «Скидки рядом» — код появится здесь.", "«Яҡындағы ташламалар» бүлегендә ташламаны активлаштыр — код бында күренер."),
        icon = Icons.Default.LocalOffer, actionLabel = appText("Посмотреть скидки", "Ташламаларҙы ҡарау"), onAction = onGoNearby,
    )
}

@Composable
internal fun ExistingBenefitActions(onCoupons: () -> Unit, onRedeem: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CanonSpace.md)) {
        ProfileActionCard(appText("Мои купоны", "Минең купондар"), appText("Уже выданные коды", "Алдан бирелгән кодтар"), Icons.Default.LocalOffer, onCoupons)
        ProfileActionCard(appText("Погасить код клиента", "Клиент кодын һүндереү"), appText("Ранее выданные купоны", "Алдан бирелгән купондар"), Icons.Default.Redeem, onRedeem)
    }
}
