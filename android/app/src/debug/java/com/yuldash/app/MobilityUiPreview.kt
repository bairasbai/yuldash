package com.yuldash.app

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuldash.app.data.InstantOrderDto
import com.yuldash.app.ui.theme.YuldashTheme

/**
 * Preview-каталог редизайна. Он лежит в debug-source set и не попадает в релизный APK.
 * В Android Studio: открыть файл → Split/Design. Здесь собраны главные пользовательские
 * состояния такси, курьера и отправителя, включая BA, dark theme и крупный шрифт.
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
                title = appText("Куда едем?", "Ҡайҙа барабыҙ?"),
                subtitle = appText(
                    "Маршрут, время подачи и цена — до вызова машины.",
                    "Маршрут, килеү ваҡыты һәм хаҡ — машина саҡырғансы.",
                ),
                badge = appText("Такси", "Такси"),
            )
            TaxiMapFrame(nearbyCount = 4) { MobilityPreviewMap() }
            MobilityRouteTimeline(
                from = appText("Текущая позиция · Баймак", "Хәҙерге урын · Баймаҡ"),
                to = appText("Центральная районная больница", "Үҙәк район дауаханаһы"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TaxiServiceClassTile(
                    title = appText("Эконом", "Эконом"),
                    subtitle = appText("быстро и рядом", "тиҙ һәм яҡында"),
                    price = 290,
                    selected = true,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
                TaxiServiceClassTile(
                    title = appText("Комфорт", "Комфорт"),
                    subtitle = appText("просторнее", "иркенерәк"),
                    price = 390,
                    selected = false,
                    onClick = {},
                    modifier = Modifier.weight(1f),
                )
            }
            TaxiFareSummary(
                price = 290,
                distance = appText("≈ 7 км", "≈ 7 км"),
                duration = appText("≈ 14 мин", "≈ 14 мин"),
                pickup = appText("Машина будет через ≈4 мин", "Машина ≈4 минуттан килә"),
                loading = false,
                error = null,
            )
        }
    }
}

@Preview(name = "Такси · поиск", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun TaxiSearchingPreview() {
    MobilityPreviewTheme {
        TaxiSearchingExperience(
            order = previewInstantOrder(status = "searching"),
            onCancel = {},
        )
    }
}

@Preview(
    name = "Такси · поездка · BA · dark",
    widthDp = 390,
    heightDp = 960,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 1.3f,
)
@Composable
private fun TaxiActiveBashkirDarkPreview() {
    MobilityPreviewTheme(darkTheme = true, language = AppLanguage.Ba) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MobilityScreenIntro(
                mode = MobilityMode.Taxi,
                title = appText("Поездка началась", "Сәфәр башланды"),
                subtitle = appText(
                    "Маршрут и статус всегда перед глазами.",
                    "Маршрут һәм статус һәр саҡ күҙ алдында.",
                ),
                badge = appText("в пути", "юлда"),
            )
            TaxiMapFrame(nearbyCount = 1) { MobilityPreviewMap() }
            Surface(
                color = CanonSurface,
                shape = CanonCardShape,
                border = BorderStroke(1.dp, CanonBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    MobilityRouteTimeline(
                        from = appText("Улица Салавата Юлаева, 14", "Салауат Юлаев урамы, 14"),
                        to = appText("Автовокзал Баймак", "Баймаҡ автовокзалы"),
                    )
                    TaxiTripProgress(status = "onboard")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PreviewPill(appText("Ринат · 4,9", "Ринат · 4,9"), true)
                        PreviewPill(appText("Белая Granta", "Аҡ Granta"), false)
                    }
                }
            }
            AppButton(
                text = appText("Связаться с водителем", "Водитель менән бәйләнеш"),
                onClick = {},
                style = AppButtonStyle.Primary,
            )
            AppButton(
                text = appText("Безопасность", "Хәүефһеҙлек"),
                onClick = {},
                style = AppButtonStyle.Secondary,
            )
        }
    }
}

@Preview(name = "Курьер · заказы", widthDp = 390, heightDp = 844, showBackground = true)
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
                    PreviewPill(appText("Город", "Ҡала"), true)
                    PreviewPill(appText("Межгород", "Ҡала-ара"), false)
                    PreviewPill(appText("Регион", "Төбәк"), false)
                }
            }
            PreviewCourierTabs(selected = 0)
            MobilityScreenIntro(
                mode = MobilityMode.Courier,
                title = appText("Заказы рядом", "Яҡындағы заказдар"),
                subtitle = appText(
                    "Маршрут и твой доход видны до принятия.",
                    "Маршрут һәм килем заказды алғансы күренә.",
                ),
            )
            CourierOfferCard(
                from = appText("Баймак", "Баймаҡ"),
                to = appText("Сибай", "Сибай"),
                sizeLabel = appText("Средняя", "Уртаса"),
                deliveryLabel = appText("Курьер", "Курьер"),
                priceLabel = "620 ₽",
                description = appText(
                    "Документы в плотном конверте. Забрать у администратора.",
                    "Документтар ҡалын конвертта. Администраторҙан алырға.",
                ),
            ) {
                AppButton(
                    text = appText("Взять заказ", "Заказды алырға"),
                    onClick = {},
                    style = AppButtonStyle.Primary,
                    icon = Icons.Default.LocalShipping,
                )
            }
        }
    }
}

@Preview(
    name = "Курьер · везу · BA · dark",
    widthDp = 390,
    heightDp = 980,
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
    fontScale = 1.3f,
)
@Composable
private fun CourierCarryingBashkirDarkPreview() {
    MobilityPreviewTheme(darkTheme = true, language = AppLanguage.Ba) {
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
                    PreviewPill(appText("Город", "Ҡала"), true)
                    PreviewPill(appText("Межгород", "Ҡала-ара"), false)
                }
            }
            PreviewCourierTabs(selected = 1)
            MobilityScreenIntro(
                mode = MobilityMode.Courier,
                title = appText("Активная доставка", "Әүҙем доставка"),
                subtitle = appText(
                    "Отправитель видит движение, а следующий шаг всегда понятен.",
                    "Ебәреүсе хәрәкәтте күрә, ә киләһе аҙым һәр саҡ аңлашыла.",
                ),
                badge = appText("в пути", "юлда"),
            )
            CourierOfferCard(
                from = appText("ТЦ «Яшма» · Баймак", "«Яшма» сауҙа үҙәге · Баймаҡ"),
                to = appText("Улица Ленина, 32", "Ленин урамы, 32"),
                sizeLabel = appText("Малая", "Бәләкәй"),
                deliveryLabel = appText("В пути", "Юлда"),
                priceLabel = "480 ₽",
                description = appText(
                    "Небольшой заказ из магазина. Позвонить у подъезда.",
                    "Магазиндан бәләкәй заказ. Подъезд янында шылтыратырға.",
                ),
            ) {
                CourierDeliveryProgress(status = "in_transit")
                AppButton(
                    text = appText("Подтвердить вручение", "Тапшырыуҙы раҫларға"),
                    onClick = {},
                    style = AppButtonStyle.Primary,
                )
                AppButton(
                    text = appText("Проблема или возврат", "Проблема йәки кире илтеү"),
                    onClick = {},
                    style = AppButtonStyle.Secondary,
                )
            }
        }
    }
}

@Preview(
    name = "Доставка · возврат · 320dp · шрифт 1.5",
    widthDp = 320,
    heightDp = 900,
    showBackground = true,
    fontScale = 1.5f,
)
@Composable
private fun CourierReturningAccessibilityPreview() {
    MobilityPreviewTheme {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MobilityScreenIntro(
                mode = MobilityMode.Courier,
                title = appText("Возврат отправителю", "Ебәреүсегә кире илтеү"),
                subtitle = appText(
                    "Причина и следующий шаг остаются видимыми.",
                    "Сәбәп һәм киләһе аҙым күренеп тора.",
                ),
                badge = appText("возврат", "кире илтеү"),
            )
            CourierOfferCard(
                from = appText("Улица Ленина, 32", "Ленин урамы, 32"),
                to = appText("ТЦ «Яшма» · Баймак", "«Яшма» сауҙа үҙәге · Баймаҡ"),
                sizeLabel = appText("Малая", "Бәләкәй"),
                deliveryLabel = appText("Возврат", "Кире илтеү"),
                priceLabel = "480 ₽",
                description = appText(
                    "Получатель не смог принять заказ.",
                    "Алыусы заказды ҡабул итә алманы.",
                ),
            ) {
                CourierDeliveryProgress(status = "returning")
                ParcelReturnNotice(
                    status = "returning",
                    reason = appText(
                        "Получатель не вышел на связь",
                        "Алыусы бәйләнешкә сыҡманы",
                    ),
                    forCourier = true,
                )
                AppButton(
                    text = appText("Подтвердить возврат", "Кире ҡайтарыуҙы раҫлау"),
                    onClick = {},
                    style = AppButtonStyle.Primary,
                )
            }
        }
    }
}

@Preview(name = "Доставка · отправить", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun DeliveryOrderPreview() {
    MobilityPreviewTheme {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MobilityScreenIntro(
                mode = MobilityMode.Courier,
                title = appText("Что доставим?", "Нимә еткерәбеҙ?"),
                subtitle = appText(
                    "Выбери способ — цена и условия будут видны до заказа.",
                    "Ысулды һайла — хаҡ һәм шарттар заказға тиклем күренә.",
                ),
                badge = appText("Доставка", "Доставка"),
            )
            CourierServiceTypeTile(
                title = appText("По пути", "Юл ыңғайы"),
                subtitle = appText("Попутчик, который и так едет", "Барыбер шул яҡҡа барған юлдаш"),
                icon = Icons.Default.LocalShipping,
                selected = false,
                onClick = {},
            )
            CourierServiceTypeTile(
                title = appText("Заказать курьера", "Курьер саҡырырға"),
                subtitle = appText("Заберёт сейчас и доставит в руки", "Хәҙер ала һәм ҡулға тапшыра"),
                icon = Icons.Default.DeliveryDining,
                selected = true,
                onClick = {},
            )
            CourierServiceTypeTile(
                title = appText("Купи и привези", "Һатып ал да килтер"),
                subtitle = appText("Курьер купит товар до 5000 ₽", "Курьер 5000 ₽ тиклем тауар һатып ала"),
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
                MobilityRouteTimeline(
                    appText("Баймак", "Баймаҡ"),
                    appText("Темясово", "Темәс"),
                    compact = true,
                )
            }
        }
    }
}

@Preview(name = "Состояния · loading и error", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
private fun MobilityResiliencePreview() {
    MobilityPreviewTheme {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MobilityScreenIntro(
                mode = MobilityMode.Taxi,
                title = appText("Состояния интерфейса", "Интерфейс тороштары"),
                subtitle = appText(
                    "Экран остаётся понятным при медленной сети и ошибке.",
                    "Селтәр яй булғанда ла, хата сыҡҡанда ла экран аңлашыла.",
                ),
            )
            TaxiFareSummary(
                price = null,
                distance = null,
                duration = null,
                pickup = null,
                loading = true,
                error = null,
            )
            TaxiFareSummary(
                price = null,
                distance = null,
                duration = null,
                pickup = null,
                loading = false,
                error = appText(
                    "Не удалось рассчитать цену. Проверь интернет и повтори.",
                    "Хаҡты иҫәпләп булманы. Интернетты тикшереп ҡабатла.",
                ),
            )
            Surface(
                color = CanonSurface,
                shape = CanonCardShape,
                border = BorderStroke(1.dp, CanonBorder),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        appText("Возврат доставки", "Доставканы кире илтеү"),
                        color = CanonText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                    )
                    CourierDeliveryProgress(status = "returning")
                    Text(
                        appText(
                            "Причина и следующий шаг сохраняются — ничего не пропадает.",
                            "Сәбәп һәм киләһе аҙым һаҡлана — бер нәмә лә юғалмай.",
                        ),
                        color = CanonMutedStrong,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewCourierTabs(selected: Int) {
    Surface(
        color = CanonSurface,
        shape = CanonItemShape,
        border = BorderStroke(1.dp, CanonBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            MobilitySegmentTab(appText("Заказы", "Заказдар"), selected == 0, {}, Modifier.weight(1f), "3")
            MobilitySegmentTab(appText("Везу", "Илтәм"), selected == 1, {}, Modifier.weight(1f), "1")
            MobilitySegmentTab(appText("Кабинет", "Кабинет"), selected == 2, {}, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MobilityPreviewTheme(
    darkTheme: Boolean = false,
    language: AppLanguage = AppLanguage.Ru,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalAppLanguage provides language) {
        YuldashTheme(darkTheme = darkTheme) {
            Surface(color = CanonBg, modifier = Modifier.fillMaxSize(), content = content)
        }
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
        Text(
            text,
            color = if (active) androidx.compose.ui.graphics.Color.White else CanonText,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

private fun previewInstantOrder(status: String) = InstantOrderDto(
    id = 101,
    status = status,
    role = "passenger",
    fromLat = 52.593,
    fromLng = 58.322,
    toLat = 52.589,
    toLng = 58.303,
    fromText = "Улица Салавата Юлаева, 14",
    toText = "Автовокзал Баймак",
    category = "standard",
    priceEstimate = 290,
    priceFinal = null,
    distanceKm = 7.2,
    etaMin = 14.0,
    driverId = null,
    offerExpiresAt = null,
    cancelBy = "",
    cancelReason = "",
    surgeK = 1.0,
    waitingStartedAt = null,
    waitingFeeKop = 0,
    cancelFeeKop = 0,
    noShow = false,
    waitFreeMin = 3,
    waitFeeRubPerMin = 8,
    noShowAt = null,
    cancelFeeNowKop = 0,
    passengerRating = 4.9,
    passengerTrips = 28,
    driverName = "",
    driverCar = "",
    driverVerified = false,
    driverRating = 0.0,
    driverPhone = "",
    passengerName = "Айгуль",
    passengerPhone = "",
)
