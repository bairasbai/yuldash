// GPS всего рейса: право публикации живёт только в памяти этой вкладки.
// Серверный статус, open/keep, очередь и повтор сети права не выдают. Его создаёт
// только beginClick до HTTP; finishClick активирует тот же ticket без продления.
import type { ShareEnd, ShareHandle, ShareState } from "./tripLocationShare";
import { pickRideCarrier, RIDE_SHARE_MAX_MS, type CarrierRow } from "./rideCarrier";
import { serverMs } from "./serverTime";

export interface RideShareSnapshot {
  bookingId: number | null; state: ShareState | null; ride: boolean;
  grantId?: string | null; rideId?: number | null;
}
export interface RideShareHooks {
  onState(s: ShareState): void;
  onEnd(why: ShareEnd): void;
  onPermissionDenied(): void;
  allowSend(): boolean;
}
export interface RideShareDeps {
  start(bookingId: number, hooks: RideShareHooks): ShareHandle;
  fetchRows(): Promise<CarrierRow[]>;
  session(): string;
  origin(): string;
  hasToken(): boolean;
  now(): number;
  wall(): number;
  newId(): string;
  observePermission?(denied: () => void): () => void;
  setTimer(fn: () => void, ms: number): unknown;
  clearTimer(t: unknown): void;
}
export const HOLD_GRACE_MS = 1500;
export const MAX_RESOLVE_ATTEMPTS = 20;
export const HANDOFF_MS = 20_000;
export const PENDING_STEP_MS = 90_000;

/** Неподделываемая внутри вкладки квитанция конкретного живого нажатия. */
export type DriverClickTicket = symbol;
type Grant = {
  id: string; owner: string; rideId: number; until: number; wallUntil: number;
  pendingUntil: number; pendingWallUntil: number;
  lastElapsed: number; active: boolean; revoked: boolean;
};
type Click = { grant: Grant; bookingId: number; closing: boolean; validation?: boolean; until: number; wallUntil: number; timer: unknown };
type QueryClock = { elapsed: number; wall: number };
const live = (r: CarrierRow) => r.status === "onboard" || r.status === "confirmed";

export function createRideShare(deps: RideShareDeps) {
  let grant: Grant | null = null;
  let handle: ShareHandle | null = null;
  let generation = 0;
  let seq = 0;
  let bookingId: number | null = null;
  let state: ShareState | null = null;
  let bookings = new Set<number>();
  const closed = new Set<number>();
  const clicks = new Map<DriverClickTicket, Click>();
  const holders = new Map<symbol, { owner: string; bookingId?: number; rideId?: number }>();
  const listeners = new Set<(s: RideShareSnapshot) => void>();
  let candidate: number | null = null;
  let carrierUntil = 0;
  let carrierWallUntil = 0;
  let attempts = 0;
  let pendingClose = 0;
  let pendingStepUntil = 0;
  let pendingStepWall = 0;
  let retryTimer: unknown = null;
  let graceTimer: unknown = null;
  let graceUntil = 0;
  let deadlineTimer: unknown = null;
  let pendingTimer: unknown = null;
  let handoff: { token: symbol; timer: unknown } | null = null;
  let permissionOff: (() => void) | null = null;
  let resolving = false;
  let connecting = false;
  const owner = () => JSON.stringify([deps.origin(), deps.session()]);
  const snapshot = (): RideShareSnapshot => ({ bookingId, state, ride: grant !== null && grant.active && !grant.revoked,
    grantId: grant && !grant.revoked ? grant.id : null, rideId: grant && !grant.revoked ? grant.rideId : null });
  const notify = () => { const s = snapshot(); for (const fn of [...listeners]) fn(s); };
  const clear = (t: unknown) => { if (t !== null) deps.clearTimer(t); };
  const held = () => grant !== null && [...holders.values()].some((value) => value.owner === owner() && (
    value.rideId === grant!.rideId || (value.bookingId !== undefined && bookings.has(value.bookingId))));

  function stopShare() {
    generation++;
    const h = handle; handle = null;
    h?.stop();
  }
  /** Отзыв необратим для этого grant. Держатели экрана не являются новым согласием. */
  function stopAll(final: ShareState | null = null, keepBooking = false) {
    if (grant) grant.revoked = true;
    seq++;
    stopShare();
    for (const click of clicks.values()) clear(click.timer);
    clicks.clear();
    clear(retryTimer); clear(graceTimer); clear(deadlineTimer); clear(pendingTimer);
    retryTimer = graceTimer = deadlineTimer = pendingTimer = null;
    graceUntil = 0;
    if (handoff) { holders.delete(handoff.token); clear(handoff.timer); }
    handoff = null;
    permissionOff?.(); permissionOff = null;
    if (!keepBooking) bookingId = null;
    candidate = null; carrierUntil = carrierWallUntil = 0; pendingClose = 0; attempts = 0;
    pendingStepUntil = pendingStepWall = 0;
    resolving = false;
    bookings.clear(); closed.clear(); state = final;
    notify();
  }
  function valid(active = true): boolean {
    const g = grant;
    if (!g || g.revoked || (active && !g.active)) return false;
    const elapsed = deps.now();
    if (!deps.hasToken() || owner() !== g.owner || elapsed < g.lastElapsed || elapsed >= g.until || deps.wall() >= g.wallUntil) {
      stopAll(); return false;
    }
    if (!g.active && (elapsed >= g.pendingUntil || deps.wall() >= g.pendingWallUntil)) {
      stopAll("unavailable", true); return false;
    }
    g.lastElapsed = elapsed;
    return true;
  }
  function armDeadline() {
    clear(deadlineTimer);
    const g = grant;
    if (!g || g.revoked) return;
    deadlineTimer = deps.setTimer(() => {
      deadlineTimer = null;
      if (valid(false)) armDeadline();
    }, Math.max(0, Math.min(g.until - deps.now(), g.wallUntil - deps.wall())));
  }
  function permissionDenied() { stopAll("denied", true); }

  /** Вызывается непосредственно из обработчика нажатия, раньше HTTP. */
  function beginClick(id: number, rideId: number, closing: boolean): DriverClickTicket | null {
    const clickedElapsed = deps.now();
    const clickedWall = deps.wall();
    if (!Number.isSafeInteger(id) || id <= 0 || !Number.isSafeInteger(rideId) || rideId <= 0 || !deps.hasToken()) return null;
    const same = valid(false) && grant?.rideId === rideId;
    if (!same) {
      stopAll();
      const now = clickedElapsed;
      grant = { id: deps.newId(), owner: owner(), rideId, until: now + RIDE_SHARE_MAX_MS,
        wallUntil: clickedWall + RIDE_SHARE_MAX_MS, pendingUntil: now + PENDING_STEP_MS,
        pendingWallUntil: clickedWall + PENDING_STEP_MS, lastElapsed: now, active: false, revoked: false };
      bookingId = id;
      const g = grant;
      try {
        permissionOff = deps.observePermission?.(() => { if (grant === g && !g.revoked) permissionDenied(); }) ?? null;
      } catch { stopAll("unavailable", true); return null; }
      if (g.revoked) { permissionOff?.(); permissionOff = null; return null; }
      armDeadline();
    }
    const g = grant!;
    const ticket = Symbol(g.id);
    const timer = deps.setTimer(() => finishClick(ticket, false), PENDING_STEP_MS);
    clicks.set(ticket, { grant: g, bookingId: id, closing,
      until: clickedElapsed + PENDING_STEP_MS, wallUntil: clickedWall + PENDING_STEP_MS, timer });
    return ticket;
  }
  async function finishClick(ticket: DriverClickTicket | null | undefined, accepted: boolean, rows?: CarrierRow[], query?: QueryClock): Promise<void> {
    if (!ticket) return;
    const click = clicks.get(ticket);
    if (!click) return;
    clear(click.timer); clicks.delete(ticket);
    if (click.grant !== grant || !valid(false)) return;
    if (closed.has(click.bookingId) && !click.closing) {
      if (!grant!.active) stopAll();
      return; // более новый authoritative terminal уже отменил этот незакрывающий шаг
    }
    if (!accepted || deps.now() >= click.until || deps.wall() >= click.wallUntil) {
      if (click.validation) stopAll("unavailable", true);
      else if (!grant!.active) stopAll();
      return;
    }
    grant!.active = true;
    if (click.closing) closed.add(click.bookingId);
    pendingClose = 0; clear(pendingTimer); pendingTimer = null;
    pendingStepUntil = pendingStepWall = 0;
    // Авторизованный ответ связан именно с исходным ticket; HTTP не передаёт новый срок.
    if (bookingId === null || !bookings.has(bookingId)) bookingId = click.bookingId;
    notify();
    if (rows && query) apply(rows, query); else await resolve(bookingId);
  }

  /** Отдельное живое согласие: свежий GET своих броней, без изменения этапа поездки. */
  async function enable(id: number, rideId: number): Promise<void> {
    const ticket = beginClick(id, rideId, false); // срок фиксируется раньше GET
    if (!ticket) return;
    const click = clicks.get(ticket)!;
    click.validation = true;
    resolving = true; stopShare(); clear(retryTimer); retryTimer = null; state = "resolving"; notify();
    const mine = ++seq;
    try {
      const query = { elapsed: deps.now(), wall: deps.wall() }; // фиксируется до HTTP
      const rows = await deps.fetchRows();
      if (mine !== seq || !clicks.has(ticket) || click.grant !== grant || !valid(false)) return;
      const own = rows.find((r) => r.booking_id === id);
      if (!own || own.ride_id !== rideId || !live(own) || closed.has(id)) {
        stopAll("unavailable", true); return;
      }
      bookingId = id;
      attempts = 0;
      await finishClick(ticket, true, rows, query);
    } catch {
      // Отказ/таймаут не включают новую передачу и не оставляют автоматический retry.
      if (mine === seq && clicks.has(ticket) && click.grant === grant && valid(false)) stopAll("unavailable", true);
    }
  }

  function allowSend(): boolean {
    if (!valid()) return false;
    if (!held() && graceUntil && deps.now() >= graceUntil) { stopAll(); return false; }
    if (carrierUntil && (deps.now() >= carrierUntil || deps.wall() >= carrierWallUntil)) {
      const anchor = bookingId;
      stopShare();
      if (anchor !== null) void resolve(anchor);
      return false;
    }
    return true;
  }
  function connect(id: number) {
    if (connecting || resolving || !valid() || !held()) return;
    stopShare();
    const mine = generation;
    bookingId = id; state = null;
    // start может синхронно сообщить отказ: не сохраняем уже остановленный handle.
    connecting = true;
    let h: ShareHandle;
    try { h = deps.start(id, {
      onState(s) { if (mine === generation) { state = s; notify(); } },
      onEnd(why) { if (mine === generation) onEnded(why); },
      onPermissionDenied() { if (mine === generation) permissionDenied(); },
      allowSend: () => mine === generation && allowSend(),
    }); } catch {
      connecting = false; stopAll("unavailable", true); return;
    }
    connecting = false;
    if (mine === generation && valid()) handle = h; else h.stop();
    notify();
  }
  function publish() {
    if (resolving || connecting || !valid() || candidate === null || !held()) return;
    if (closed.has(candidate)) return;
    if (bookingId !== candidate || !handle) connect(candidate);
    else notify();
  }
  function onEnded(why: ShareEnd) {
    stopShare();
    if (!valid() || bookingId === null) return stopAll();
    if (why === "boarding-corrected") { void resolve(bookingId); return; }
    if (why !== "trip") return stopAll();
    closed.add(bookingId);
    if (pendingClose === bookingId) return; // сервер закрыл канал раньше HTTP-ответа
    void resolve(bookingId);
  }
  function preboardingBounds(row: CarrierRow, query: QueryClock): QueryClock | null {
    if (row.status !== "confirmed" || row.pickup_stop || !Number.isFinite(row.no_show_wait_sec)) return null;
    const ttl = row.no_show_wait_sec! * 1000;
    let elapsed = query.elapsed + ttl;
    let wall = query.wall + ttl;
    if (row.no_show_at) {
      const absolute = serverMs(row.no_show_at);
      if (!Number.isFinite(absolute)) return { elapsed: -Infinity, wall: -Infinity };
      wall = Math.min(wall, absolute);
      // UTC-срок проецируется на монотонные часы именно в начале GET: rollback не продлевает его.
      elapsed = Math.min(elapsed, query.elapsed + absolute - query.wall);
    }
    return { elapsed, wall };
  }
  function apply(rows: CarrierRow[], query: QueryClock) {
    if (!valid() || bookingId === null) return;
    const elapsed = deps.now(), wall = deps.wall();
    // Тот же R-порог >60 сек применяется к оставшемуся сроку, а не к старому wait из held GET.
    const currentRows = rows.map((row) => {
      const bounds = preboardingBounds(row, query);
      return bounds ? { ...row, no_show_wait_sec: Math.min(bounds.elapsed - elapsed, bounds.wall - wall) / 1000 } : row;
    });
    const pick = pickRideCarrier(currentRows, bookingId, closed);
    if (pick.rideId !== grant!.rideId || pick.carrier === null) return stopAll();
    const previous = candidate;
    const previousUntil = carrierUntil;
    const previousWallUntil = carrierWallUntil;
    bookings = new Set(pick.rideBookings);
    candidate = pick.carrier;
    const next = preboardingBounds(rows.find((row) => row.booking_id === candidate)!, query);
    carrierUntil = next ? (previous === candidate && previousUntil ? Math.min(previousUntil, next.elapsed) : next.elapsed) : 0;
    carrierWallUntil = next ? (previous === candidate && previousWallUntil ? Math.min(previousWallUntil, next.wall) : next.wall) : 0;
    if (carrierUntil && (deps.now() >= carrierUntil || deps.wall() >= carrierWallUntil)) return stopAll();
    resolving = false;
    notify(); // кабинет может взять уже разрешённый рейс; сам кабинет grant не создаёт
    publish();
  }
  async function resolve(anchor: number): Promise<void> {
    if (!valid()) return;
    resolving = true;
    stopShare(); state = "resolving"; notify(); clear(retryTimer); retryTimer = null;
    const mine = ++seq;
    try {
      const query = { elapsed: deps.now(), wall: deps.wall() }; // не время прихода ответа
      const rows = await deps.fetchRows();
      if (mine !== seq || !valid()) return;
      attempts = 0; bookingId = anchor;
      apply(rows, query);
    } catch (e) {
      if (mine !== seq || !valid()) return;
      if ((e as { status?: number } | null)?.status === 0 && ++attempts <= MAX_RESOLVE_ATTEMPTS) {
        retryTimer = deps.setTimer(() => { retryTimer = null; void resolve(anchor); }, Math.min(30_000, 1000 * 2 ** Math.min(attempts, 5)));
      } else stopAll();
    }
  }
  /** Сигнал API/очереди может обновить существующий grant, но не выдаёт новый. */
  async function action(id: number, closedNow: boolean): Promise<void> {
    if (!valid() || !bookings.has(id) || [...clicks.values()].some((click) => click.bookingId === id)) return;
    if (pendingClose === id && (deps.now() >= pendingStepUntil || deps.wall() >= pendingStepWall)) return stopAll();
    pendingClose = 0; clear(pendingTimer); pendingTimer = null;
    pendingStepUntil = pendingStepWall = 0;
    if (closedNow) closed.add(id);
    if (bookingId !== null && (closedNow || !handle)) await resolve(bookingId);
  }
  /** Авторитетный terminal из /role или /details: A закрываем до поиска B, без нового grant. */
  function terminal(id: number): void {
    if (!valid(false) || (!bookings.has(id) && bookingId !== id)) return;
    if (closed.has(id) && (resolving || (bookingId !== id && candidate !== id))) return;
    closed.add(id);
    if (!grant!.active) return; // ожидающий HTTP сам по себе не стал согласием
    if (bookingId !== id && candidate !== id) { bookings.delete(id); return; }
    pendingClose = 0; clear(pendingTimer); pendingTimer = null;
    pendingStepUntil = pendingStepWall = 0;
    candidate = null;
    void resolve(id); // синхронно stopShare + resolving, затем свежий OWN GET
  }
  /** Whole-ride terminal отзывает и новый pending/active grant того же закрытого рейса. */
  function rideTerminal(rideId: number, expectedOwner: string): void {
    const g = grant;
    // Не вызываем valid() для чужого события: старый HTTP не должен менять текущего owner/рейс.
    if (!g || g.revoked || !Number.isSafeInteger(rideId) || rideId <= 0 ||
        g.rideId !== rideId || g.owner !== expectedOwner || owner() !== expectedOwner) return;
    stopAll(); // синхронно: GPS, socket, все tickets/таймеры/late GET этой эпохи уже отозваны
  }
  function step(id: number, closing: boolean, kind: "start" | "fail") {
    if (!valid() || !closing || id !== bookingId) return;
    if (kind === "start") {
      pendingClose = id; clear(pendingTimer);
      pendingStepUntil = deps.now() + PENDING_STEP_MS;
      pendingStepWall = deps.wall() + PENDING_STEP_MS;
      pendingTimer = deps.setTimer(() => {
        pendingTimer = null; pendingClose = 0;
        if (!handle) stopAll();
      }, PENDING_STEP_MS);
    } else {
      pendingClose = 0; clear(pendingTimer); pendingTimer = null;
      pendingStepUntil = pendingStepWall = 0;
      if (!handle) stopAll();
    }
  }
  function hold(token: symbol, scope: { bookingId?: number; rideId?: number }) {
    holders.set(token, { owner: owner(), ...scope });
    if (held()) { clear(graceTimer); graceTimer = null; graceUntil = 0; }
    publish();
  }
  function release(token: symbol) {
    if (!holders.delete(token) || held()) return;
    if (!grant || grant.revoked) return;
    clear(graceTimer);
    graceUntil = deps.now() + HOLD_GRACE_MS;
    graceTimer = deps.setTimer(() => { graceTimer = null; if (!held()) stopAll(); }, HOLD_GRACE_MS);
  }
  function endHandoff() {
    if (!handoff) return;
    const h = handoff; handoff = null; clear(h.timer); release(h.token);
  }
  return {
    beginClick, finishClick, enable, action, terminal, rideTerminal, step,
    open(id: number): () => void {
      if (grant && grant.owner !== owner()) stopAll();
      const token = Symbol("trip"); hold(token, { bookingId: id });
      if (valid() && bookings.has(id) && !closed.has(id) && id !== bookingId) void resolve(id);
      return () => release(token);
    },
    keep(rows: CarrierRow[]): (() => void) | null {
      if (!valid() || !rows.some((r) => r.ride_id === grant!.rideId && live(r) && !closed.has(r.booking_id))) return null;
      const token = Symbol("cabinet"); hold(token, { rideId: grant!.rideId }); endHandoff();
      return () => release(token);
    },
    handoff() {
      if (!valid() || handoff) return;
      const token = Symbol("handoff"); hold(token, { rideId: grant!.rideId });
      handoff = { token, timer: deps.setTimer(endHandoff, HANDOFF_MS) };
    },
    retry() { if (valid()) handle?.retry(); },
    permissionDenied,
    subscribe(fn: (s: RideShareSnapshot) => void): () => void {
      listeners.add(fn); fn(snapshot()); return () => { listeners.delete(fn); };
    },
    snapshot,
    stop: () => stopAll(),
  };
}
export type RideShare = ReturnType<typeof createRideShare>;
