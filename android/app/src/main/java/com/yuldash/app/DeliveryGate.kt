package com.yuldash.app

// Доставка при запуске волнами (13.10.2026): у неё две независимые половины, и сервер
// включает их по отдельности — посылки «по пути» (PARCELS_ENABLED) и курьер (COURIER_ENABLED).
// «Доставка» в приложении открыта, если включена хоть одна; что именно показать внутри — здесь,
// а не в больших экранах (`ParcelsScreen.kt`, `CourierScreen.kt` держит храповик размера).
//
// Флаг сменился, пока форма открыта, — форму не перестраиваем: сервер ответит двуязычным
// «скоро», а при следующем открытии закрытого способа уже не будет.

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
        if (parcelsOn) PoputkaDeliveryTabs()
    }
}
