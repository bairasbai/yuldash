// ================================================================
//  Где живёт отправка точки водителя на весь рейс (Д2) — без сети и браузера.
//  Создаёт её проводка (driverRideShare.ts), когда открывается страница поездки; кабинет и подписи
//  берут её отсюда и потому не тянут за собой api/* (их собирают наборы проверок с урезанными подменами).
//  Нет отправки — держать нечего, подпись молчит.
// ================================================================
import type { RideShare, RideShareSnapshot } from "./rideLocationShare";
import type { CarrierRow } from "./rideCarrier";

let shared: RideShare | null = null;
let last: RideShareSnapshot = { bookingId: null, state: null, ride: false };
const listeners = new Set<(s: RideShareSnapshot) => void>();

/** Проводка отдаёт сюда единственную на вкладку отправку. */
export function installRideShare(share: RideShare): void {
  if (shared) return;
  shared = share;
  share.subscribe((s) => {
    last = s;
    for (const fn of [...listeners]) fn(s);
  });
}

/** «Едут с тобой»: держать отправку рейса, пока в списке его живая бронь; null — держать нечего. */
export function rideShareKeep(rows: CarrierRow[]): (() => void) | null {
  return shared ? shared.keep(rows) : null;
}

/** «Завершить» → «Едут с тобой»: держать отправку, пока кабинет её не возьмёт (rideLocationShare.HANDOFF_MS). */
export function rideShareHandoff(): void {
  shared?.handoff();
}

/** Состояние отправки для подписи на экране; сразу присылает текущее. */
export function subscribeRideShare(fn: (s: RideShareSnapshot) => void): () => void {
  listeners.add(fn);
  fn(last);
  return () => {
    listeners.delete(fn);
  };
}

let ridingFocus = false;

/** Водитель закрыл бронь, остальные едут: кабинет один раз подведёт «Едут с тобой» к верху. */
export function requestRidingFocus(): void {
  ridingFocus = true;
}

/** Забрать просьбу (один раз). */
export function takeRidingFocus(): boolean {
  const f = ridingFocus;
  ridingFocus = false;
  return f;
}
