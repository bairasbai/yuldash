// ================================================================
//  Одна отправка точки водителя на вкладку (Д2): логика — rideLocationShare.ts, здесь — проводка
//  к настоящему браузеру и серверу. Контроллер регистрируется до нажатия, но GPS начинает только
//  действующее разрешение от живого нажатия. Страница/кабинет лишь держат уже разрешённый рейс.
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
import { installDriverClickHandler, onDriverAction, onDriverRideTerminal } from "./driverActions";
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
        onPermissionDenied: hooks.onPermissionDenied,
        allowSend: hooks.allowSend,
      });
    },
    fetchRows: () => bookingsApi.fetchDriverBookings(),
    session: () => client.getSessionGeneration(),
    origin: () => {
      const base = String(client.API_BASE ?? "");
      try { return new URL(base, typeof location !== "undefined" ? location.href : undefined).origin; }
      catch { return base; }
    },
    wall: Date.now,
    newId: () => {
      const bytes = globalThis.crypto.getRandomValues(new Uint8Array(16));
      return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
    },
    observePermission: (denied) => {
      let alive = true;
      let status: PermissionStatus | null = null;
      let wasGranted = false;
      let permissionRead = 0;
      const changed = () => {
        if (!alive || !status) return;
        if (status.state === "granted") wasGranted = true;
        else if (status.state === "denied" || wasGranted) denied();
      };
      const check = () => {
        if (typeof navigator === "undefined" || !navigator.permissions?.query) return;
        const read = ++permissionRead;
        let query: Promise<PermissionStatus>;
        try { query = navigator.permissions.query({ name: "geolocation" }); }
        catch { return; } // неподдержанная Permissions API: остаётся отказ watchPosition
        void query.then((value) => {
          if (!alive || read !== permissionRead) return;
          if (status) status.removeEventListener?.("change", changed);
          status = value; status.addEventListener?.("change", changed); changed();
        }).catch(() => { /* watchPosition сообщает PERMISSION_DENIED и без Permissions API */ });
      };
      check();
      if (typeof window !== "undefined") window.addEventListener("focus", check);
      return () => {
        alive = false; status?.removeEventListener?.("change", changed);
        if (typeof window !== "undefined") window.removeEventListener("focus", check);
      };
    },
    hasToken: () => Boolean(client.getToken()),
    now: () => (typeof performance !== "undefined" ? performance.now() : Date.now()),
    setTimer: (fn, ms) => window.setTimeout(fn, ms),
    clearTimer: (t) => window.clearTimeout(t as number),
  });
  installRideShare(ride);
  shared = ride;
  return ride;
}

// Установлено до первого нажатия: регистрация сама не создаёт отправку или grant.
installDriverClickHandler({
  begin: (id, rideId, closing) => rideShare().beginClick(id, rideId, closing),
  finish: (ticket, accepted) => rideShare().finishClick(ticket, accepted),
});
onDriverAction((id, closed, step) => {
  // API/очередь уведомляют только уже созданный контроллер. Ни одного нового разрешения.
  if (step === "done") void shared?.action(id, closed);
  else shared?.step(id, closed, step);
});
onDriverRideTerminal((rideId, owner) => {
  // Cold API/replay не создаёт контроллер, не запрашивает строки и не выдаёт grant.
  shared?.rideTerminal(rideId, owner);
});

/** Держатель страницы поездки. Сам не выдаёт право GPS; вернёт «отпустить». */
export function rideShareOpen(bookingId: number): () => void {
  return rideShare().open(bookingId);
}

/** «Повторить» при временной недоступности места, только с ещё действующим разрешением. */
export function rideShareRetry(): void {
  rideShare().retry();
}

/** Живое отдельное «включить место»: pending до свежего GET, без POST этапа поездки. */
export function rideShareEnable(bookingId: number, rideId: number): Promise<void> {
  return rideShare().enable(bookingId, rideId);
}

/** Сигнал от уже проверенного ответа сервера. Не создаёт контроллер или разрешение. */
export function rideShareTerminal(bookingId: number): void {
  shared?.terminal(bookingId);
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
