package com.yuldash.app

// Доставка при запуске волнами (13.10.2026): у неё две независимые половины, и сервер
// включает их по отдельности — посылки «по пути» (PARCELS_ENABLED) и курьер (COURIER_ENABLED).
// «Доставка» в приложении открыта, если включена хоть одна; что именно показать внутри — здесь,
// а не в больших экранах (`ParcelsScreen.kt`, `CourierScreen.kt` держит храповик размера).
//
// Флаг сменился, пока форма открыта, — форму не перестраиваем: сервер ответит двуязычным
// «скоро», а при следующем открытии закрытого способа уже не будет.

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ParcelDto
import com.yuldash.app.data.CourierApplicationDto

/**
 * Вкладка «Отправить» в посылках. Закрыты обе половины — «Скоро в Юлдаше» (вкладка «Мои»
 * при этом работает: отправленное до выключения должно доехать и быть видно). Открыта одна —
 * форма только с её способами: «по пути» — за флагом посылок, курьер и «купи и привези» —
 * за флагом курьера.
 */
@Composable
internal fun ParcelsSendOrSoon(onSent: () -> Unit, onFindRide: () -> Unit) {
    val features = rememberFeatures()
    if (features.deliveryOn) {
        SendParcelTab(
            onSent = onSent,
            poputkaOn = features.isOn(Feature.Parcels),
            courierOn = features.isOn(Feature.Courier),
        )
    } else {
        ComingSoonContent(kind = ComingSoonKind.Delivery, onFindRide = onFindRide)
    }
}

/**
 * «Режим курьера» для того, у кого нет работы курьером: заявка не одобрена — или сам режим
 * курьера ещё не запущен. Во втором случае честно говорим «скоро» одной строкой (кабинет
 * сервер всё равно не отдаст) и отдаём то, что работает, — попутные посылки. Выключены
 * посылки — их ленту не показываем: сервер ответил бы «скоро», и экран выглядел бы сломанным.
 */
@Composable
internal fun CourierPoputkaOrApply(
    application: CourierApplicationDto?,
    refreshFailed: Boolean,
    onRetry: () -> Unit,
    onBecomeCourier: () -> Unit,
) {
    val features = rememberFeatures()
    val parcelsOn = features.isOn(Feature.Parcels)
    Column(Modifier.fillMaxSize()) {
        if (features.isOn(Feature.Courier)) {
            CourierNotApprovedView(
                application = application,
                refreshFailed = refreshFailed,
                onRetry = onRetry,
                onBecomeCourier = onBecomeCourier,
                compact = parcelsOn,
            )
        } else {
            AppNoticeCard(
                text = appText(
                    "Работа курьером скоро откроется. А пока можно отвезти посылку по пути.",
                    "Курьер эше тиҙҙән асыла. Ә әлегә бандеролде юл ыңғайында илтергә була.",
                ),
                icon = Icons.Default.Schedule,
                modifier = Modifier.padding(horizontal = CanonSpace.lg, vertical = CanonSpace.md),
            )
        }
        PoputkaDeliveryTabs(allowNewOrders = parcelsOn)
    }
}


/** Три безопасные двери: новая отправка, свои посылки и уже взятые доставки. */
@Composable
internal fun DeliveryParcelTabs(tab: Int, onSelect: (Int) -> Unit) {
    Surface(
        color = CanonSurface, shape = CanonItemShape, border = BorderStroke(1.dp, CanonBorder),
        modifier = Modifier.fillMaxWidth().padding(horizontal = CanonSpace.lg, vertical = CanonSpace.md),
    ) {
        Row(Modifier.fillMaxWidth().padding(CanonSpace.xs), horizontalArrangement = Arrangement.spacedBy(CanonSpace.xs)) {
            ParcelTab(appText("Отправить", "Ебәреү"), tab == 0, Modifier.weight(1f)) { onSelect(0) }
            ParcelTab(appText("Мои", "Минеке"), tab == 1, Modifier.weight(1f)) { onSelect(1) }
            ParcelTab(appText("Везу", "Илтәм"), tab == 2, Modifier.weight(1f)) { onSelect(2) }
        }
    }
}

/** Без кабинета и линии: старую доставку можно завершить при любых выключателях. */
@Composable
internal fun DeliveryCarryContent(onFindRide: () -> Unit) {
    var carrying by remember { mutableStateOf<List<ParcelDto>>(emptyList()) }
    if (!ApiClient.isLoggedIn()) {
        ComingSoonContent(ComingSoonKind.Delivery, onFindRide)
        return
    }
    CourierCarryingTab(
        online = false, list = carrying, onList = { carrying = it },
        allowNewOrders = false, onGoOrders = onFindRide,
    )
    CourierLiveLocationLink(carrying)
}

@Composable
internal fun ClosedCourierDeliveryScreen(onBack: () -> Unit, onFindRide: () -> Unit, onApply: () -> Unit) {
    Scaffold(containerColor = CanonBg, topBar = { ScreenTopBar(appText("Режим курьера", "Курьер режимы"), onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            AppButton(
                text = becomeCourierEarlyLabel(), onClick = onApply, style = AppButtonStyle.Secondary,
                modifier = Modifier.padding(CanonSpace.lg),
            )
            DeliveryCarryContent(onFindRide)
        }
    }
}

@Composable
internal fun CourierCarryEmpty(allowNewOrders: Boolean, onGoOrders: () -> Unit) {
    if (!allowNewOrders) {
        ComingSoonContent(ComingSoonKind.Delivery, onGoOrders, scrollable = false)
    } else {
        AppEmptyState(
            title = appText("Ты пока ничего не везёшь", "Әлегә бер нәмә лә илтмәйһең"),
            text = appText("Возьми заказ во вкладке «Заказы» — он появится здесь.", "«Заказдар» бүлегендә заказ ал — ул бында күренер."),
            icon = Icons.Default.LocalShipping,
            actionLabel = appText("Смотреть заказы", "Заказдарҙы ҡарау"), onAction = onGoOrders,
        )
    }
}
