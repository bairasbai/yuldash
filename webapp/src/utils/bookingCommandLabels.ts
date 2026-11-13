export function bookingRefusalMessage(appText: (ru: string, ba: string) => string, code?: string | null): string {
  switch (code) {
    case "price_changed": return appText("Цена поездки изменилась.", "Сәфәр хаҡы үҙгәрҙе.");
    case "ride_terms_changed": return appText("Условия поездки изменились.", "Сәфәр шарттары үҙгәрҙе.");
    case "seats_not_enough": return appText("На этом участке не хватило мест.", "Был участкала урындар етмәне.");
    case "front_seat_taken": return appText("Переднее место уже занято.", "Алғы урын инде алынған.");
    case "guardian_phone_invalid": return appText("Проверь номер телефона взрослого.", "Оло кешенең телефон номерын тикшер.");
    case "booking_traveler_women_only": return appText("В поездку «Только женщины» можно бронировать место только для себя.",
      "«Тик ҡатын-ҡыҙ» сәфәренә урынды үҙең өсөн генә бронларға була.");
    case "booking_traveler_no_minors": return appText("Водитель не берёт пассажиров младше 18 без взрослого рядом.",
      "Йөрөтөүсе оло кешеһеҙ 18-ҙән кесе юлсыларҙы алмай.");
    case "booking_traveler_conflict": return appText("У тебя уже есть бронь с другими данными пассажира.",
      "Һинең юлсы мәғлүмәттәре икенсе булған бронең бар.");
    default: return appText("Сервер отклонил запрос на бронь.", "Сервер бронлау һорауын кире ҡаҡты.");
  }
}
