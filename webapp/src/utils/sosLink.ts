// ================================================================
//  Ссылка на экран SOS из поездки: `/sos?booking=<id>`.
//
//  Без брони дежурный получает «человеку плохо» и не знает, в какой машине он едет.
//  С бронью сервер дописывает в сигнал маршрут, водителя и машину (как Android: bookingId
//  из YuldashApp.kt в SosVerifyScreens.kt). Все входы в SOS из поездки строят ссылку здесь,
//  а экран SOS читает её отсюда же — формат не разъедется.
// ================================================================

/** Ссылка на SOS. Есть бронь — кладём её в адрес; нет — просто `/sos`. */
export function sosPath(bookingId?: number | null): string {
  return typeof bookingId === "number" && Number.isInteger(bookingId) && bookingId > 0
    ? `/sos?booking=${bookingId}`
    : "/sos";
}

/** Бронь из адреса. Мусор, ноль, минус — «брони нет»: сигнал уходит и без неё. */
export function bookingIdFromSearch(raw: string | null | undefined): number | null {
  if (!raw || !/^\d{1,9}$/.test(raw)) return null;
  const id = Number(raw);
  return id > 0 ? id : null;
}
