package com.yuldash.app.data

// ═══════════ M3: Доставка посылок ═══════════

/** Курьер, взявший посылку (виден отправителю после accept).
 *  rating=null — у курьера пока нет оценок («новый курьер»); ratingCount — сколько оценок. */
data class ParcelCourierDto(val id: Int, val name: String, val rating: Double?, val ratingCount: Int, val phone: String)

/** Потолок «где забрать / куда привезти»: столько же принимает сервер, и столько же режет форма. */
internal const val PARCEL_ADDRESS_MAX_LEN = 200

/** Посылка. Форма зависит от роли: у отправителя есть confirmCode/receiverPhone/courier;
 *  в списке «доступные» (курьер) телефон и код скрыты (пустые).
 *  status: created/accepted/in_transit/delivered/canceled/returning/returned. */
data class ParcelDto(
    val id: Int,
    val senderId: Int,
    val courierId: Int?,
    val fromCity: String, val toCity: String,
    val fromLat: Double?, val fromLng: Double?, val toLat: Double?, val toLng: Double?,
    val size: String,
    val description: String,
    val receiverName: String,
    val receiverPhone: String,   // "" если скрыт
    val senderName: String = "", // после accept: контакт точки забора/возврата
    val senderPhone: String = "",// после accept; до него сервер не отдаёт
    val feeKop: Int,
    val status: String,
    val confirmCode: String,     // "" если скрыт
    val createdAt: String,
    val acceptedAt: String?,
    val deliveredAt: String?,
    val courier: ParcelCourierDto?,
    // C1: курьер Юлдаша (профессиональная доставка). Для обычной попутки deliveryType="poputka".
    // Что за груз (разбор №2). Видны ДО принятия, в том числе в открытом списке: именно по ним
    // курьер решает «берусь или нет». 0 / "" / false = отправитель не указал.
    val weightKg: Double = 0.0,
    val cargoType: String = "",             // documents|medicine|food|clothes|tech|other
    val fragile: Boolean = false,
    val deliveryType: String = "poputka",   // poputka | courier | buy_bring
    val urgency: String = "",                // bypath | now (для courier/buy_bring)
    val declaredValueKop: Int = 0,           // объявленная ценность (courier)
    val codAmountKop: Int = 0,               // сумма выкупа товара (buy_bring)
    val commissionKop: Int = 0,              // наш сбор (уже входит в priceKop)
    val priceKop: Int = 0,                   // итоговая цена доставки (courier/buy_bring)
    val settlement: ParcelSettlementDto? = null,   // C2: расчёт «купи и привези» (null для остальных типов)
    // Возврат: сервер отдавал эти поля, но старый клиент их терял и не мог объяснить состояние.
    val returnReason: String = "",
    val returnedAt: String? = null,
    val deliveryAttempts: Int = 0,
    val cancelFeeKop: Int = 0,
    // Сколько будет стоить отмена, если нажать ПРЯМО СЕЙЧАС. Нужно, чтобы человек видел
    // сумму ДО решения: раньше диалог честно предупреждал «будет компенсация», но саму
    // цифру показывал уже после отмены — человек соглашался на деньги вслепую.
    val cancelFeePreviewKop: Int = 0,
    // Из ЧЕГО сложится эта сумма. Одно число человек читает как «нас обобрали»; «100 ₽ штраф
    // + 300 ₽ дорога курьера + 70 ₽ ожидание» — то же самое, но с ним не спорят. Нули =
    // старый сервер, тогда показываем только итог.
    val cancelFineKop: Int = 0,
    val cancelPickupKop: Int = 0,
    val cancelWaitingKop: Int = 0,
    // Возврат «получателя не было»: сколько отправитель вернёт курьеру за дорогу и ожидание.
    // Ноль, пока курьер не отметил ни одной попытки вручения — за слова мы не платим.
    val returnFeeKop: Int = 0,
    val returnFeePreviewKop: Int = 0,
    // Из чего сложится возврат: свой маршрут, повторные заезды по просьбе отправителя и
    // сколько срезал потолок «не дороже самой доставки». Ноль = старый сервер.
    val returnRouteKop: Int = 0,
    val returnRedeliverKop: Int = 0,
    val returnCappedKop: Int = 0,
    // Во сколько обойдётся СЛЕДУЮЩИЙ заезд, если попросить его сейчас. Считает сервер:
    // цена зависит от зоны, коэффициента и потолка — телефон, посчитавший её сам, однажды
    // показал бы не то число, которое спишется.
    val nextRedeliverKop: Int = 0,
    // Повторный заезд «получатель уже дома». Сколько уже попросили, сколько всего можно
    // и открыта ли кнопка сейчас — считает сервер: у телефона нет ни попыток, ни предела.
    val redeliverRequests: Int = 0,
    val redeliverMax: Int = 0,
    val canRequestRedelivery: Boolean = false,
    // Две границы ответственности: «взял целой» и «отдал целой». Сервер хранил оба снимка,
    // но клиент не показывал ни одного — в споре о повреждении смотреть было не на что.
    val pickupPhotoUrl: String = "",
    val deliveryPhotoUrl: String = "",
    // «Где именно забрать и куда привезти» — свободный ориентир от отправителя («у мечети,
    // синие ворота»). Приватность: в открытой ленте свободных заказов сервер их НЕ отдаёт, они
    // приходят только принявшему курьеру и самому отправителю → "" = «не пришло», блок не рисуем.
    // Значения по умолчанию обязательны: старый сервер этих ключей не пришлёт.
    val fromAddress: String = "",
    val toAddress: String = "",
    // Срок доставки — не прогноз, а договорённость: отправитель говорит, к какому дню нужно,
    // курьер видит это ДО того, как взяться. Формат сервера — 2026-08-05; null = «не срочно».
    // overdue считает сервер («срок вышел, а доставка не завершена») — клиент его не выводит сам.
    val deliverBy: String? = null,
    val overdue: Boolean = false,
)

/** C2: расчёт «купи и привези» — сколько получатель вернёт курьеру (товар + доставка).
 *  Приходит только для deliveryType="buy_bring", иначе null. goodsActualKop=0 — курьер ещё не указал стоимость покупки. */
data class ParcelSettlementDto(
    val goodsActualKop: Int,   // фактическая стоимость купленного товара (0 — ещё не указана)
    val deliveryKop: Int,      // стоимость доставки
    val totalDueKop: Int,      // всего к оплате получателем (товар + доставка)
    val settled: Boolean,      // расчёт закрыт (оплата получена)
)
