// ================================================================
//  Одна отправка точки водителя на вкладку (Д2): логика — rideLocationShare.ts, здесь — проводка
//  к настоящему браузеру и серверу. Создаётся на странице поездки (rideShareOpen); кабинет и подписи
//  берут её через rideShareStore.ts, не таща за собой api/*.
// ================================================================
// Модулями целиком: этот файл тянет экран поездки, который наборы проверок собирают с урезанными
// подменами api/* — так недостающее имя не валит сборку набора, а до вызова дело там не доходит.
import * as client from "../api/client";
import * as chat from "../api/chat";
import * as bookingsApi from "../api/bookings";
import * as share from "./tripLocationShare";
import type { SocketLike } from "./tripLocationShare";
import { createRideShare, type RideShare } from "./rideLocationShare";
import { pickRideCarrier } from "./rideCarrier";
import { onDriverAction } from "./driverActions";
import { installRideShare } from "./rideShareStore";

let shared: RideShare | null = null;

function rideShare(): RideShare {
  if (shared) return shared;
  const ride = createRideShare({
    start: (bookingId, hooks) => {
      const generation = client.getSessionGeneration();
      const geo = typeof navigator !== "undefined" && navigator.geolocation ? navigator.geolocation : null;
      return share.startDriverLocationShare({
        geolocation: geo,
        // Настоящий WebSocket шире SocketLike (у его обработчиков this и Event) — логике хватает подмножества.
        openSocket: () => new WebSocket(share.tripLocationUrl(chat.wsBase(), bookingId)) as unknown as SocketLike,
        token: client.getToken,
        now: Date.now,
        setTimer: (fn, ms) => window.setTimeout(fn, ms),
        clearTimer: (t) => window.clearTimeout(t as number),
        onState: hooks.onState,
        isCurrent: () => generation === client.getSessionGeneration(),
        // Ключ входа истёк посреди поездки — обновить тихо и продолжить (приёмка PR155).
        refreshToken: client.refreshRejectedToken,
        subscribeOnline: (fn) => {
          if (typeof window === "undefined") return () => {};
          window.addEventListener("online", fn);
          return () => window.removeEventListener("online", fn);
        },
        onEnd: hooks.onEnd,
        allowSend: hooks.allowSend,
      });
    },
    fetchRows: () => bookingsApi.fetchDriverBookings(),
    session: () => client.getSessionGeneration(),
    hasToken: () => Boolean(client.getToken()),
    now: () => (typeof performance !== "undefined" ? performance.now() : Date.now()),
    setTimer: (fn, ms) => window.setTimeout(fn, ms),
    clearTimer: (t) => window.clearTimeout(t as number),
  });
  onDriverAction((id, closed) => void ride.action(id, closed));
  installRideShare(ride);
  shared = ride;
  return ride;
}

/** Страница поездки водителя «в пути»: отправка в канал этой брони; вернёт «отпустить». */
export function rideShareOpen(bookingId: number): () => void {
  return rideShare().open(bookingId);
}

/** «Повторить» после отказа в доступе к месту. */
export function rideShareRetry(): void {
  rideShare().retry();
}

/**
 * «Завершить» прошло: остались ли у рейса брони живые брони (тогда водителю — «Едут с тобой»).
 * null — список не пришёл.
 */
export async function driverRideRemains(bookingId: number): Promise<boolean | null> {
  try {
    const rows = await bookingsApi.fetchDriverBookings();
    return pickRideCarrier(rows, bookingId, [bookingId]).live.length > 0;
  } catch {
    return null;
  }
}
