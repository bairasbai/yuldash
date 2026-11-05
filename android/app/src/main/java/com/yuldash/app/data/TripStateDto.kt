package com.yuldash.app.data

/** Состояние активной поездки: роль + статус брони + подфаза водителя (""/departed/arriving). */
/** arrivalVerified — сервер сам сверил «подъезжаю» с GPS водителя: машина правда рядом. */
data class TripStateDto(
    val role: String,
    val status: String,
    val driverPhase: String,
    val arrivalVerified: Boolean = false,
    // Ехал(а) не один(на), а теперь остался(ась) в машине один на один с водителем. Клиент
    // ТИХО (без пуша и звука) предлагает поделиться поездкой с близким. Водителю не видно.
    val aloneWithDriver: Boolean = false,
    val peerId: Int = 0,            // вторая сторона брони (пассажиру — водитель): «Сообщить о проблеме»; 0 — старый сервер
    val boardingUndoSec: Int = 0,   // водителю: сколько секунд ещё можно отменить свою отметку «Сел» (17.10.2026)
    val problemReportSec: Int = 0,  // пассажиру: сколько ещё показывать «Сообщить о проблеме» (после поездки и отмены в пути)
    // Отменили, когда пассажир был в машине (23.10.2026): экран поездки не закрывается сам. Старый сервер — false.
    val cancelledInTransit: Boolean = false,
    val boardingUndo: BoardingUndoTerms = BoardingUndoTerms(),
)
