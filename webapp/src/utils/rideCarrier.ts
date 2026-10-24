// ================================================================
//  GPS водителя на весь рейс (Д2, решение Александра 24.10.2026): через чей канал идёт точка, когда
//  прежняя бронь-«несущая» закрылась. Зеркало Android DriverRideGps.kt (pickRideCarrier) — один набор
//  случаев на оба клиента: android/app/src/test/resources/driver-ride-carrier-cases.json
//  (tests/driver-ride-share-test.mjs и DriverRideGpsTest.kt). Кому и когда показать точку, решает сервер.
// ================================================================

/** Минимум строки `/driver/bookings`, нужный для выбора. */
export interface CarrierRow {
  booking_id: number;
  ride_id?: number;
  status: string;
  pickup_stop?: string | null;
  no_show_wait_sec?: number;
}

export interface CarrierPick {
  /** Рейс закрытой брони по строке сервера; 0 — строки нет или старый сервер: ничего не берём. */
  rideId: number;
  /** Живые брони того же рейса, кроме закрытых: есть хоть одна — водителю «Едут с тобой». */
  live: number[];
  /** Через чей канал дальше идёт точка; null — отправка гаснет. */
  carrier: number | null;
  /** Несущая годится только столько секунд (начало маршрута до срока «Не пришёл»); null — без срока. */
  carrierTtlSec: number | null;
  /** Все брони этого рейса. */
  rideBookings: number[];
}

/** До срока «Не пришёл» должно оставаться больше минуты, иначе канал закрылся бы сразу. */
export const RIDE_CARRIER_MIN_TTL_SEC = 60;
/** Потолок отправки на весь рейс — 6 часов от его начала, как у приложения. */
export const RIDE_SHARE_MAX_MS = 6 * 3600_000;

const isLive = (r: CarrierRow) => r.status === "onboard" || r.status === "confirmed";
const atStartOrUnknown = (r: CarrierRow) => !r.pickup_stop;

/**
 * Ступени (в пределах ступени — меньший номер брони): «в пути» → подтверждена на остановке (окно её
 * канала держит сервер) → подтверждена в начале маршрута, пока до «Не пришёл» больше минуты (свой канал
 * такой брони сервер держит без срока — не севшая пассажирка иначе часами видела бы машину).
 * Рейс — ТОЛЬКО из строки `anchor`: номер брони мог совпасть с номером другого рейса водителя.
 */
export function pickRideCarrier(rows: CarrierRow[], anchor: number, closed: Iterable<number>): CarrierPick {
  const shut = new Set(closed);
  const rideId = rows.find((r) => r.booking_id === anchor)?.ride_id ?? 0;
  if (!rideId || rideId <= 0) return { rideId: 0, live: [], carrier: null, carrierTtlSec: null, rideBookings: [] };
  const ofRide = rows.filter((r) => r.ride_id === rideId);
  const live = ofRide.filter((r) => isLive(r) && !shut.has(r.booking_id)).sort((a, b) => a.booking_id - b.booking_id);
  const onboard = live.find((r) => r.status === "onboard");
  const atStop = live.find((r) => r.status === "confirmed" && !atStartOrUnknown(r));
  const atStart = live.find(
    (r) => r.status === "confirmed" && atStartOrUnknown(r) && (r.no_show_wait_sec ?? 0) > RIDE_CARRIER_MIN_TTL_SEC
  );
  const carrier = onboard ?? atStop ?? atStart ?? null;
  return {
    rideId,
    live: live.map((r) => r.booking_id),
    carrier: carrier ? carrier.booking_id : null,
    carrierTtlSec: !onboard && !atStop && atStart ? (atStart.no_show_wait_sec ?? 0) : null,
    rideBookings: ofRide.map((r) => r.booking_id),
  };
}
