export function bookingRefusalMessage(appText: (ru: string, ba: string) => string, code?: string | null): string {
  switch (code) {
    case "price_changed": return appText("Цена поездки изменилась.", "Сәфәр хаҡы үҙгәрҙе.");
    case "ride_terms_changed": return appText("Условия поездки изменились.", "Сәфәр шарттары үҙгәрҙе.");
    case "seats_not_enough": return appText("На этом участке не хватило мест.", "Был участкала урындар етмәне.");
    case "front_seat_taken": return appText("Переднее место уже занято.", "Алғы урын инде алынған.");
    default: return appText("Сервер отклонил запрос на бронь.", "Сервер бронлау һорауын кире ҡаҡты.");
  }
}
