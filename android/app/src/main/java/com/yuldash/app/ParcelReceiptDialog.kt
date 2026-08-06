package com.yuldash.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.yuldash.app.data.ApiClient
import com.yuldash.app.data.ParcelReceiptDto

/**
 * Чек за доставку.
 *
 * Квитанция была у попутки и у такси, а у доставки её не было — хотя деньги там настоящие:
 * цена доставки, комиссия платформы, а у «купи и привези» ещё и стоимость товара, которую
 * курьер потратил из своего кармана (аудит 2026-08-06). Без чека спор «я отдал / он не отдал»
 * упирается в память двух людей.
 *
 * Диалог, а не экран: чек смотрят одним взглядом и закрывают, ради этого не стоит заводить
 * ещё одну ветку навигации.
 *
 * Телефонов и адресов тут нет и быть не может — их не отдаёт сервер: чеком делятся,
 * а адрес получателя это его дом.
 */
@Composable
fun ParcelReceiptDialog(parcelId: Int?, onDismiss: () -> Unit) {
    if (parcelId == null) return
    var receipt by remember(parcelId) { mutableStateOf<ParcelReceiptDto?>(null) }
    var error by remember(parcelId) { mutableStateOf<String?>(null) }
    val loadErr = appText(
        "Не удалось загрузить квитанцию. Проверь связь.",
        "Квитанцияны йөкләп булманы. Бәйләнеште тикшер.",
    )

    LaunchedEffect(parcelId) {
        ApiClient.getParcelReceipt(parcelId)
            .onSuccess { receipt = it; error = null }
            .onFailure { error = serverSaid(it, loadErr) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CanonSurface,
        icon = { Icon(Icons.Default.Receipt, contentDescription = null, tint = CanonGreen2) },
        title = {
            Text(
                appText("Квитанция за доставку", "Илтеү өсөн квитанция"),
                color = CanonText, fontWeight = FontWeight.Bold,
            )
        },
        text = {
            val r = receipt
            when {
                error != null -> Text(error ?: "", color = CanonRed, style = CanonCaption)
                r == null -> Text(
                    appText("Собираем чек…", "Чекты йыябыҙ…"),
                    color = CanonMuted, style = CanonCaption,
                )
                else -> Column(
                    Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(CanonSpace.sm),
                ) {
                    ReceiptRow(appText("Куда", "Ҡайҙа"), "${r.fromCity} → ${r.toCity}")
                    ReceiptRow(
                        appText("Доставка", "Илтеү"),
                        rublesOf(r.deliveryPriceKop),
                    )
                    // Товар показываем только у «купи и привези»: в обычной доставке строки
                    // с нулём нет — пустые нули в чеке путают.
                    if (r.goodsKop > 0) {
                        ReceiptRow(appText("Товар", "Тауар"), rublesOf(r.goodsKop))
                    }
                    ReceiptRow(
                        appText("Итого", "Барыһы"),
                        rublesOf(r.totalKop),
                        strong = true,
                    )
                    if (r.commissionKop > 0) {
                        ReceiptRow(
                            appText("Комиссия Юлдаша", "Юлдаш комиссияһы"),
                            rublesOf(r.commissionKop),
                        )
                    }
                    if (r.cancelFeeKop > 0) {
                        ReceiptRow(
                            appText("Компенсация за отмену", "Кире алыу өсөн компенсация"),
                            rublesOf(r.cancelFeeKop),
                        )
                    }
                    ReceiptRow(appText("Курьер", "Курьер"), r.courierName)
                    Text(
                        appText(
                            "Деньги идут напрямую между людьми — Юлдаш их не держит.",
                            "Аҡса кешеләр араһында тура йөрөй — Юлдаш уны тотмай.",
                        ),
                        color = CanonMuted, style = CanonCaption,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(appText("Закрыть", "Ябырға"), color = CanonGreen2, fontWeight = FontWeight.Bold)
            }
        },
    )
}

@Composable
private fun ReceiptRow(label: String, value: String, strong: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = CanonMuted, style = CanonCaption)
        Text(
            value,
            color = CanonText,
            style = if (strong) CanonBodyStrong else CanonCaption,
        )
    }
}

/** Копейки → «300 ₽». Рубли целыми: копейки в бытовом чеке между своими только мешают. */
private fun rublesOf(kop: Int): String = "${kop / 100} ₽"
