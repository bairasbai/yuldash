package com.yuldash.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.yuldash.app.ui.theme.YuldashTheme

/**
 * Preview-каталог редизайна. Он лежит в debug-source set и не попадает в релизный APK.
 * В Android Studio: открыть файл → Split/Design, чтобы сравнить такси и курьера без API.
 */

@Preview(name = "Такси · заказ", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun TaxiOrderPreview() {
    MobilityPreviewTheme {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MobilityScreenIntro(
                mode = MobilityMode.Taxi,
                title = "Куда едем?",
                subtitle = "Маршрут, время подачи и цена — до вызова машины.",
                badge = "Такси",
            )
            TaxiMapFrame(nearbyCount = 4) { MobilityPreviewMap() }
            MobilityRouteTimeline(
                from = "Текущая позиция · Баймак",
                to = "Центральная районная больница",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TaxiServiceClassTile(
                    title = "Эконом",
                    subtitle = "быстро и рядом",
                    price = 290,
                    selected = true,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
                TaxiServiceClassTile(
                    title = "Комфорт",
                    subtitle = "просторнее",
                    price = 390,
                    selected = false,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
            }
            TaxiFareSummary(
                price = 290,
                distance = "≈ 7 км",
                duration = "≈ 14 мин",
                pickup = "Машина будет через ≈4 мин",
                loading = false,
                error = null,
            )
        }
    }
}

@Preview(name = "Курьер · работа", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun CourierWorkPreview() {
    MobilityPreviewTheme {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CourierLineHero(
                online = true,
                toggling = false,
                onToggle = {},
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewPill("Город", true)
                    PreviewPill("Межгород", false)
                    PreviewPill("Регион", false)
                }
            }
            Surface(
                color = CanonSurface,
                shape = CanonItemShape,
                border = BorderStroke(1.dp, CanonBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MobilitySegmentTab("Заказы", true, {}, Modifier.weight(1f), "3")
                    MobilitySegmentTab("Везу", false, {}, Modifier.weight(1f), "1")
                    MobilitySegmentTab("Кабинет", false, {}, Modifier.weight(1f))
                }
            }
            MobilityScreenIntro(
                mode = MobilityMode.Courier,
                title = "Заказы рядом",
                subtitle = "Маршрут и твой доход видны до принятия.",
            )
            CourierOfferCard(
                from = "Баймак",
                to = "Сибай",
                sizeLabel = "Средняя",
                deliveryLabel = "Курьер",
                priceLabel = "620 ₽",
                description = "Документы в плотном конверте. Забрать у администратора.",
            ) {
                AppButton(
                    text = "Взять заказ",
                    onClick = {},
                    style = AppButtonStyle.Primary,
                    icon = Icons.Default.LocalShipping,
                )
            }
            CourierDeliveryProgress(status = "in_transit")
        }
    }
}

@Preview(name = "Доставка · оформление", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun DeliveryOrderPreview() {
    MobilityPreviewTheme {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MobilityScreenIntro(
                mode = MobilityMode.Courier,
                title = "Что доставим?",
                subtitle = "Выбери способ — цена и условия будут видны до заказа.",
                badge = "Доставка",
            )
            CourierServiceTypeTile(
                title = "По пути",
                subtitle = "Попутчик, который и так едет",
                icon = Icons.Default.LocalShipping,
                selected = false,
                onClick = {},
            )
            CourierServiceTypeTile(
                title = "Заказать курьера",
                subtitle = "Заберёт сейчас и доставит в руки",
                icon = Icons.Default.DeliveryDining,
                selected = true,
                onClick = {},
            )
            CourierServiceTypeTile(
                title = "Купи и привези",
                subtitle = "Курьер купит товар до 5000 ₽",
                icon = Icons.Default.ShoppingBag,
                selected = false,
                onClick = {},
            )
            CourierFareSummary(
                total = "≈ 540 ₽",
                courierGets = "500 ₽",
                fee = "40 ₽",
                distance = "12 км",
            ) {
                MobilityRouteTimeline("Баймак", "Темясово", compact = true)
            }
        }
    }
}

@Composable
private fun MobilityPreviewTheme(content: @Composable () -> Unit) {
    YuldashTheme(darkTheme = false) {
        Surface(color = CanonBg, modifier = Modifier.fillMaxSize(), content = content)
    }
}

@Composable
private fun MobilityPreviewMap() {
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(CanonMint, CanonBg, CanonTaxiBg)),
        ),
    ) {
        Box(Modifier.align(Alignment.CenterStart).padding(start = 54.dp).size(15.dp).background(CanonGreen2, CircleShape))
        Box(Modifier.align(Alignment.Center).fillMaxWidth(0.55f).height(4.dp).background(CanonTaxi))
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 58.dp).size(15.dp).background(CanonTaxi, CircleShape))
    }
}

@Composable
private fun PreviewPill(text: String, active: Boolean) {
    Surface(
        color = if (active) CanonGreen2 else CanonBg,
        shape = CircleShape,
        border = BorderStroke(1.dp, if (active) CanonGreen2 else CanonBorder),
    ) {
        androidx.compose.material3.Text(
            text,
            color = if (active) androidx.compose.ui.graphics.Color.White else CanonText,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
