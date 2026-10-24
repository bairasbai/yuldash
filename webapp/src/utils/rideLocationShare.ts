// ================================================================
//  Точка водителя на ВЕСЬ рейс с сайта (Д2, решение Александра 24.10.2026). Зеркало Android
//  TripLocationService (режим рейса) — поверх той же отправки в канал брони (tripLocationShare.ts).
//
//  Водитель везёт нескольких и нажал «Завершить» по одному — точка идёт дальше через канал другой живой
//  брони того же рейса («несущей», выбор — rideCarrier.ts); гаснет, когда закрыта последняя.
//
//  Границы (проверка плана, P1-1…P1-7):
//   • только в режиме рейса — его включает шаг водителя НА ЭТОМ устройстве (driverActions.ts). Без него —
//     как раньше: канал закрылся или ушли со страницы — отправка гаснет;
//   • отправку держат только страница поездки и «Едут с тобой» в кабинете (и только пока в списке есть живая
//     бронь этого рейса); ушли с обеих — гаснет через [HOLD_GRACE_MS]; кабинет отправку САМ не начинает;
//     после перезагрузки страницы ничего не возобновляется — всё живёт в памяти вкладки;
//   • рейс — не дольше 6 часов от начала режима; несущая в начале маршрута — только до срока «Не пришёл»;
//   • вход сверяется перед запросом списка и после ответа; «ended» без причины «trip» (вход отозван) —
//     стоп без запросов; отказ сервера — стоп; нет сети — повтор, не дольше ~10 минут.
//  Кому и когда точку показать, по-прежнему решает сервер по правилам каждой брони.
//
//  Здесь только логика с подставными зависимостями — её исполняет tests/driver-ride-share-test.mjs.
// ================================================================
import type { ShareEnd, ShareHandle, ShareState } from "./tripLocationShare";
import { pickRideCarrier, RIDE_SHARE_MAX_MS, type CarrierRow } from "./rideCarrier";

/** Что видят экраны: через чей канал идёт точка, её состояние и ведём ли рейс. */
export interface RideShareSnapshot {
  bookingId: number | null;
  state: ShareState | null;
  ride: boolean;
}

export interface RideShareHooks {
  onState(s: ShareState): void;
  onEnd(why: ShareEnd): void;
  allowSend(): boolean;
}

export interface RideShareDeps {
  /** Начать отправку в канал брони (startDriverLocationShare с браузером). */
  start(bookingId: number, hooks: RideShareHooks): ShareHandle;
  /** Свежий список `/driver/bookings` — без кэша. Ошибка со `status` 0 — нет сети. */
  fetchRows(): Promise<CarrierRow[]>;
  /** Поколение входа (getSessionGeneration). */
  session(): string;
  hasToken(): boolean;
  /** Монотонные часы, мс. */
  now(): number;
  setTimer(fn: () => void, ms: number): unknown;
  clearTimer(t: unknown): void;
}

/** Пауза на смену маршрута (страница поездки → кабинет), чтобы отправка не мигала. */
export const HOLD_GRACE_MS = 1500;
/** Нет сети при поиске следующей брони — столько повторов (≈ 9 минут), потом гаснем. */
export const MAX_RESOLVE_ATTEMPTS = 20;
/**
 * Переход «Завершить» → кабинет (приёмка Д2, P1-Б): кабинет подхватывает отправку только после своих
 * загрузок, а на трассе это дольше паузы на смену маршрута. Столько держим отправку сами, пока «Едут с тобой»
 * не возьмёт её; не взял — гаснем.
 */
export const HANDOFF_MS = 20_000;

const isLive = (r: CarrierRow) => r.status === "onboard" || r.status === "confirmed";

export function createRideShare(deps: RideShareDeps) {
  let handle: ShareHandle | null = null;
  let gen = 0; // какая отправка «наша»: колбэки старой ничего не трогают
  let bookingId: number | null = null;
  let state: ShareState | null = null;
  let ride: { rideId: number; bookings: Set<number>; until: number } | null = null;
  const closed = new Set<number>();
  let carrierUntil = 0;
  let owner: string | null = null;
  let seq = 0; // последний запрос списка; ответ на старый не применяем
  let attempts = 0;
  let pendingClose = 0; // «Завершить» по несущей без режима рейса: ждём список, а не гаснем
  let retryTimer: unknown = null;
  let graceTimer: unknown = null;
  let handoff: { token: symbol; timer: unknown } | null = null; // «Завершить» → кабинет: держим, пока он не взял
  const holders = new Set<symbol>();
  const listeners = new Set<(s: RideShareSnapshot) => void>();

  const snapshot = (): RideShareSnapshot => ({ bookingId, state, ride: ride !== null });
  const notify = () => {
    const s = snapshot();
    for (const fn of [...listeners]) fn(s);
  };
  const sessionOk = () => owner !== null && deps.session() === owner && deps.hasToken();

  function stopShare() {
    gen++;
    const h = handle;
    handle = null;
    h?.stop();
  }

  function clearTimer(t: unknown) {
    if (t !== null) deps.clearTimer(t);
  }

  /** Всё: отправка, режим рейса, таймеры. Повтор безопасен. */
  function stopAll() {
    seq++;
    stopShare();
    clearTimer(retryTimer);
    clearTimer(graceTimer);
    retryTimer = graceTimer = null;
    if (handoff) clearTimer(handoff.timer);
    handoff = null;
    holders.clear();
    bookingId = null;
    state = null;
    ride = null;
    closed.clear();
    carrierUntil = 0;
    pendingClose = 0;
    attempts = 0;
    owner = null;
    notify();
  }

  /** Точку можно слать: вход тот же, рейс не старше 6 часов, срок несущей не вышел. */
  function allowSend(): boolean {
    if (!sessionOk() || (ride !== null && deps.now() >= ride.until)) {
      deps.setTimer(stopAll, 0);
      return false;
    }
    if (carrierUntil && deps.now() >= carrierUntil) {
      carrierUntil = 0;
      const anchor = bookingId;
      if (anchor !== null) deps.setTimer(() => void resolve(anchor), 0);
      return false;
    }
    return true;
  }

  function connect(id: number) {
    stopShare();
    const my = gen;
    bookingId = id;
    state = null;
    handle = deps.start(id, {
      onState: (s) => {
        if (my !== gen) return;
        state = s;
        notify();
      },
      onEnd: (why) => {
        if (my === gen) onEnded(why);
      },
      allowSend: () => my === gen && allowSend(),
    });
    notify();
  }

  function onEnded(why: ShareEnd) {
    stopShare();
    // Вход отозван или бронь так и не стала активной — не ищем ничего. Без режима рейса — как раньше.
    if (why !== "trip" || !sessionOk()) return stopAll();
    if (!ride && pendingClose !== bookingId) return stopAll();
    if (bookingId !== null) closed.add(bookingId);
    if (ride && bookingId !== null) void resolve(bookingId);
  }

  async function resolve(anchor: number): Promise<void> {
    if (!sessionOk()) return stopAll();
    stopShare(); // старый канал больше ни кадра (бронь закрыта / срок вышел)
    clearTimer(retryTimer);
    retryTimer = null;
    const my = ++seq;
    let rows: CarrierRow[];
    try {
      rows = await deps.fetchRows();
    } catch (e) {
      if (my !== seq) return;
      if (!sessionOk()) return stopAll();
      if ((e as { status?: number } | null)?.status === 0 && ++attempts <= MAX_RESOLVE_ATTEMPTS) {
        retryTimer = deps.setTimer(() => {
          retryTimer = null;
          void resolve(anchor);
        }, Math.min(30_000, 1000 * 2 ** Math.min(attempts, 5)));
        return;
      }
      return stopAll(); // отказ сервера (401/403/…) или сеть так и не вернулась
    }
    if (my !== seq) return;
    if (!sessionOk()) return stopAll();
    attempts = 0;
    bookingId = anchor;
    apply(rows);
  }

  function apply(rows: CarrierRow[]) {
    const r = ride;
    const anchor = bookingId;
    if (!r || anchor === null) return stopAll();
    const pick = pickRideCarrier(rows, anchor, closed);
    // Рейс — только из строки сервера и только тот же: иначе точку получили бы пассажиры чужого рейса.
    if (pick.rideId !== r.rideId || pick.carrier === null) return stopAll();
    ride = { ...r, bookings: new Set(pick.rideBookings) };
    carrierUntil = pick.carrierTtlSec ? deps.now() + pick.carrierTtlSec * 1000 : 0;
    if (pick.carrier !== anchor || !handle) connect(pick.carrier);
    else notify();
  }

  /** Шаг водителя на этом устройстве принят сервером: включить режим рейса / сразу сменить несущую. */
  async function action(id: number, closedNow: boolean): Promise<void> {
    if (bookingId === null || !sessionOk()) return;
    if (ride && ride.bookings.has(id)) {
      if (!closedNow) return;
      closed.add(id);
      if (id === bookingId) await resolve(id);
      return;
    }
    const my = ++seq;
    if (closedNow && id === bookingId) pendingClose = id;
    let rows: CarrierRow[];
    try {
      rows = await deps.fetchRows();
    } catch {
      if (my !== seq) return;
      pendingClose = 0;
      if (!handle) stopAll(); // режим не включили, а канал уже закрыт — гаснем, как раньше
      return;
    }
    if (my !== seq) return;
    pendingClose = 0;
    if (!sessionOk()) return stopAll();
    const actionRide = rows.find((r) => r.booking_id === id)?.ride_id ?? 0;
    const carrierRide = rows.find((r) => r.booking_id === bookingId)?.ride_id ?? 0;
    if (!actionRide || actionRide !== carrierRide) {
      if (!handle) stopAll(); // шаг по другому рейсу — этот рейс не ведём
      return;
    }
    const bookings = new Set(rows.filter((r) => r.ride_id === actionRide).map((r) => r.booking_id));
    ride = ride && ride.rideId === actionRide ? { ...ride, bookings } : { rideId: actionRide, bookings, until: deps.now() + RIDE_SHARE_MAX_MS };
    if (closedNow) closed.add(id);
    if ((closedNow && id === bookingId) || !handle) apply(rows);
    else notify();
  }

  function hold(token: symbol) {
    holders.add(token);
    clearTimer(graceTimer);
    graceTimer = null;
  }

  function endHandoff() {
    const h = handoff;
    if (!h) return;
    handoff = null;
    clearTimer(h.timer);
    release(h.token);
  }

  function release(token: symbol) {
    if (!holders.delete(token) || holders.size > 0) return;
    clearTimer(graceTimer);
    graceTimer = deps.setTimer(() => {
      graceTimer = null;
      if (holders.size === 0) stopAll();
    }, HOLD_GRACE_MS);
  }

  return {
    /** Страница поездки водителя «в пути»: отправка в канал этой брони. Возвращает «отпустить». */
    open(id: number): () => void {
      if (owner !== null && deps.session() !== owner) stopAll(); // другой вход — с чистого листа
      if (owner === null) owner = deps.session();
      // Устаревшая страница закрытой брони, а рейс везёт живая (или её уже ищут) — в мёртвый канал не идём.
      if (!(ride && closed.has(id))) {
        if (ride && !ride.bookings.has(id)) {
          ride = null; // другая бронь не этого рейса — режим рейса снят
        }
        carrierUntil = 0;
        seq++; // явный выбор страницы важнее ответа, который ещё в пути
        if (bookingId !== id || !handle) connect(id);
      }
      const token = Symbol("trip");
      hold(token);
      return () => release(token);
    },
    /**
     * «Едут с тобой» в кабинете: держать уже идущую отправку рейса, пока в списке есть его живая бронь.
     * Сам ничего не начинает. null — держать нечего.
     */
    keep(rows: CarrierRow[]): (() => void) | null {
      const r = ride;
      if (bookingId === null || !r || !sessionOk()) return null;
      if (!rows.some((x) => x.ride_id === r.rideId && isLive(x) && !closed.has(x.booking_id))) return null;
      const token = Symbol("cabinet");
      hold(token);
      endHandoff(); // кабинет взял отправку — переходная держалка больше не нужна
      return () => release(token);
    },
    /**
     * «Завершить» прошло, водитель уходит к «Едут с тобой»: держать отправку на время перехода, пока кабинет
     * не возьмёт её (не дольше [HANDOFF_MS]). Отправки нет — ничего.
     */
    handoff(): void {
      if (bookingId === null || handoff) return;
      const token = Symbol("handoff");
      hold(token);
      handoff = { token, timer: deps.setTimer(endHandoff, HANDOFF_MS) };
    },
    action,
    retry() {
      handle?.retry();
    },
    subscribe(fn: (s: RideShareSnapshot) => void): () => void {
      listeners.add(fn);
      fn(snapshot());
      return () => {
        listeners.delete(fn);
      };
    },
    snapshot,
    /** Выход из аккаунта и т.п. */
    stop: stopAll,
  };
}

export type RideShare = ReturnType<typeof createRideShare>;
