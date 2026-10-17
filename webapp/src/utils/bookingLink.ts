// ================================================================
//  Куда вести человека, открывшего ссылку на бронь (`/booking/:id`).
//
//  Ссылка на бронь приходит из центра уведомлений, веб-пуша (его адрес собирает сервер,
//  services.py) и чата. Экран «Бронь» написан для ПАССАЖИРА: пока водитель молчит, он говорит
//  «ждём ответа водителя» и предлагает «Отменить бронь». Водитель, которому пришло «новая бронь»,
//  попадал именно туда: подтвердить негде, а чужую бронь можно отменить одним нажатием.
//
//  Зеркало Android BookingLinkDestination.kt (bookingLinkScreen):
//   • водитель + бронь ждёт подтверждения (или отменена) → кабинет водителя, там «Подтвердить»;
//   • водитель + подтверждена / в пути / завершена → экран поездки;
//   • пассажир → «Бронь», как раньше (возвращаем null: оставайся на месте).
// ================================================================
import type { BookingStatus } from "../api/bookings";

/** Статусы, при которых у брони есть экран поездки (Android bookingStatusAllowsActiveTrip). */
export function bookingStatusAllowsActiveTrip(status: BookingStatus | string): boolean {
  return status === "confirmed" || status === "onboard" || status === "done";
}

/**
 * Куда уйти с `/booking/:id`. `null` — никуда: человек пассажир, экран «Бронь» ему и нужен.
 * Роль берём из ответа сервера (GET /bookings/{id}/details → role), а не из догадок.
 */
export function bookingLinkPath(
  role: "driver" | "passenger" | string,
  status: BookingStatus | string,
  bookingId: number
): string | null {
  if (role !== "driver") return null;
  return bookingStatusAllowsActiveTrip(status) ? `/trip/${bookingId}` : "/driver";
}
