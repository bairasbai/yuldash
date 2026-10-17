// ================================================================
//  Точка водителя с сайта → тот же канал, что у Android: WS /ws/trip/{id}/location.
//  Зеркало Android TripLocationService.kt + LocationSocket.kt (паритет 16.10.2026).
//
//  Раньше водитель с сайта точку не отправлял вовсе: пассажир и мама по ссылке видели
//  машину, только если водитель ехал с приложением.
//
//  Личные данные (AGENTS.md §7, §8):
//   • Место спрашиваем в нужный момент — когда водитель в пути: нажал «Выехал» / «Подъезжаю»
//     или открыл экран, когда пассажир уже сел. До этого браузер о месте не спрашивают.
//   • Кому и когда точку показывать, решает СЕРВЕР (`visibility.trip_is_live`: после «Выехал»
//     или за час до выезда). Клиент своих правил показа не придумывает — только отправляет.
//   • Координаты не пишем ни в журнал (console), ни в аналитику — только в канал поездки.
//   • Без места экран не падает: состояние «закрыт доступ» с подсказкой и «Повторить».
//   • Браузер шлёт точку, только пока страница открыта (фоновой службы, как в Android, у сайта
//     нет) — об этом честно говорит подпись на экране.
//
//  Здесь только логика с подставными зависимостями (геолокация, сокет, часы), без React —
//  её исполняет набор `tests/driver-location-share-test.mjs`.
// ================================================================

/** Что видит водитель под кнопками статуса. */
export type ShareState =
  | "asking" // браузер спрашивает доступ / ищет первую точку
  | "sharing" // точка уходит в канал поездки
  | "reconnecting" // точка есть, а связи с сервером нет — переподключаемся
  | "denied" // доступ к месту закрыт
  | "unavailable" // место не определяется (выключен GPS, нет сигнала)
  | "unsupported" // браузер не умеет геолокацию
  | "ended"; // поездка закончилась на сервере — больше не шлём

/** Не чаще раза в 7 секунд — как Android (TripLocationService.MIN_INTERVAL_MS). */
export const SEND_EVERY_MS = 7000;
/** «Поездка ещё не активна» — мягкий повтор раз в 15 с, не больше 40 раз (LocationSocket.kt). */
export const SOFT_RETRY_MS = 15_000;
export const MAX_SOFT_RETRIES = 40;
/** Обрыв связи — повтор с паузой 1, 2, 4… но не реже раза в 30 с (LocationSocket.kt). */
export const MAX_RECONNECT_MS = 30_000;

/**
 * Водитель в пути — пора просить место. Только водитель и только живая бронь:
 * «Выехал» / «Подъезжаю» уже отмечены или пассажир сам нажал «Я сел».
 */
export function driverInTransit(
  role: string | null | undefined,
  status: string | null | undefined,
  phase: string | null | undefined
): boolean {
  if (role !== "driver") return false;
  if (status === "onboard") return true;
  return status === "confirmed" && (phase === "departed" || phase === "arriving");
}

/** Адрес канала точки — тот же, что у Android LocationSocket. */
export function tripLocationUrl(wsBase: string, bookingId: number): string {
  return `${wsBase}/ws/trip/${bookingId}/location`;
}

/** Кадр точки: {type:"loc", lat, lng, ts, bearing?} — формат Android LocationSocket.sendLoc. */
export function locationFrame(lat: number, lng: number, bearing: number | null | undefined, nowMs: number): string {
  const frame: { type: "loc"; lat: number; lng: number; ts: number; bearing?: number } = {
    type: "loc",
    lat,
    lng,
    ts: Math.floor(nowMs / 1000),
  };
  if (typeof bearing === "number" && Number.isFinite(bearing)) frame.bearing = bearing;
  return JSON.stringify(frame);
}

/** Минимум того, что нужно от navigator.geolocation (легко подделать в наборе). */
export interface GeoLike {
  watchPosition(
    ok: (p: { coords: { latitude: number; longitude: number; heading?: number | null } }) => void,
    fail: (e: { code: number }) => void,
    opts?: { enableHighAccuracy?: boolean; maximumAge?: number; timeout?: number }
  ): number;
  clearWatch(id: number): void;
}

/** Минимум того, что нужно от WebSocket. */
export interface SocketLike {
  readonly readyState: number;
  onopen: ((ev: unknown) => void) | null;
  onclose: ((ev: { code: number; reason: string }) => void) | null;
  onerror: ((ev: unknown) => void) | null;
  onmessage: ((ev: unknown) => void) | null;
  send(data: string): void;
  close(code?: number, reason?: string): void;
}

export interface ShareDeps {
  /** `navigator.geolocation` или null, если браузер не умеет. */
  geolocation: GeoLike | null;
  /** Открыть сокет канала точки (`new WebSocket(tripLocationUrl(...))`). */
  openSocket: () => SocketLike;
  /** Токен для первого кадра {type:"auth"} — как в чате, не в адресе. */
  token: () => string | null;
  now: () => number;
  setTimer: (fn: () => void, ms: number) => unknown;
  clearTimer: (t: unknown) => void;
  onState: (s: ShareState) => void;
  /** Сессия та же (не вышли и не сменили аккаунт)? Иначе — тихо останавливаемся. */
  isCurrent?: () => boolean;
}

export interface ShareHandle {
  /** Остановить всё: слежение за местом, сокет, таймеры. Повторный вызов безопасен. */
  stop(): void;
  /** «Повторить» после отказа или «место не определяется» — спросить заново. */
  retry(): void;
}

const OPEN = 1; // WebSocket.OPEN — без глобального WebSocket в наборе проверок

/**
 * Начать отправку точки водителя. Сокет открываем только когда есть первая точка:
 * отказ в доступе к месту не должен оставлять за собой пустое соединение.
 */
export function startDriverLocationShare(deps: ShareDeps): ShareHandle {
  let stopped = false;
  let watchId: number | null = null;
  let socket: SocketLike | null = null;
  let authed = false;
  let timer: unknown = null;
  let attempt = 0;
  let softAttempt = 0;
  let lastSentAt = Number.NEGATIVE_INFINITY;
  let last: { lat: number; lng: number; bearing: number | null } | null = null;
  let state: ShareState | null = null;

  const current = () => !stopped && (deps.isCurrent ? deps.isCurrent() : true);
  const setState = (s: ShareState) => {
    if (state === s) return;
    state = s;
    deps.onState(s);
  };

  function clearWatch() {
    if (watchId !== null && deps.geolocation) deps.geolocation.clearWatch(watchId);
    watchId = null;
  }

  function clearTimerIfAny() {
    if (timer !== null) deps.clearTimer(timer);
    timer = null;
  }

  function closeSocket() {
    const s = socket;
    socket = null;
    authed = false;
    if (!s) return;
    s.onopen = s.onclose = s.onerror = s.onmessage = null;
    try {
      s.close(1000);
    } catch {
      /* уже закрыт — нечего закрывать */
    }
  }

  function halt(final?: ShareState) {
    clearWatch();
    clearTimerIfAny();
    closeSocket();
    if (final) setState(final);
  }

  /** Отправить последнюю точку, если сокет готов и прошло ≥ 7 с. */
  function flush(force = false) {
    if (!current()) {
      halt();
      stopped = true;
      return;
    }
    if (!last || !socket || !authed || socket.readyState !== OPEN) return;
    const now = deps.now();
    if (!force && now - lastSentAt < SEND_EVERY_MS) return;
    try {
      socket.send(locationFrame(last.lat, last.lng, last.bearing, now));
      lastSentAt = now;
    } catch {
      /* сокет закрывается прямо сейчас — onclose переподключит, точку отправим следующей */
    }
  }

  function schedule(ms: number) {
    clearTimerIfAny();
    timer = deps.setTimer(() => {
      timer = null;
      if (current()) connect();
    }, ms);
  }

  function connect() {
    if (!current() || socket) return;
    const token = deps.token();
    let s: SocketLike;
    try {
      s = deps.openSocket();
    } catch {
      // Сокет не создался (нет сети на уровне браузера) — пробуем позже, как при обрыве.
      attempt++;
      setState("reconnecting");
      schedule(Math.min(MAX_RECONNECT_MS, 1000 * 2 ** Math.min(attempt - 1, 5)));
      return;
    }
    socket = s;
    s.onopen = () => {
      if (socket !== s || !current()) return;
      if (token) s.send(JSON.stringify({ type: "auth", token }));
      authed = true;
      attempt = 0;
      setState("sharing");
      flush(true); // первая точка — сразу, не ждём следующего сдвига машины
    };
    s.onmessage = () => {
      // Сюда приходит точка пассажира — водительскому экрану сайта она не нужна.
      softAttempt = 0;
    };
    s.onerror = () => {
      /* за ошибкой всегда следует onclose — переподключение там */
    };
    s.onclose = (ev) => {
      if (socket !== s) return;
      socket = null;
      authed = false;
      if (!current()) return;
      const reason = String(ev?.reason ?? "");
      if (ev?.code === 1008 && /ended|forbidden/i.test(reason)) {
        // Поездку завершили или отменили, бронь не наша — точка больше никому не нужна.
        halt("ended");
        stopped = true;
        return;
      }
      if (ev?.code === 1008 && /not active/i.test(reason)) {
        // Бронь ещё не подтверждена — подождём, но не вечно (Android: ~10 минут).
        if (softAttempt >= MAX_SOFT_RETRIES) {
          halt("ended");
          stopped = true;
          return;
        }
        softAttempt++;
        setState("reconnecting");
        schedule(SOFT_RETRY_MS);
        return;
      }
      attempt++;
      setState("reconnecting");
      schedule(Math.min(MAX_RECONNECT_MS, 1000 * 2 ** Math.min(attempt - 1, 5)));
    };
  }

  function begin() {
    if (!current()) return;
    if (!deps.geolocation) {
      setState("unsupported");
      return;
    }
    setState("asking");
    watchId = deps.geolocation.watchPosition(
      (p) => {
        if (!current()) return;
        const heading = p.coords.heading;
        last = {
          lat: p.coords.latitude,
          lng: p.coords.longitude,
          bearing: typeof heading === "number" && Number.isFinite(heading) ? heading : null,
        };
        if (!socket) connect();
        else flush();
        if (state === "unavailable") setState(authed ? "sharing" : "asking");
      },
      (e) => {
        if (!current()) return;
        if (e?.code === 1) {
          // Доступ закрыт: точку не шлём, соединение не держим. Экран живёт дальше.
          last = null;
          halt("denied");
          return;
        }
        // Нет сигнала / не успели — следим дальше; если точки ещё не было, говорим об этом.
        if (!last) setState("unavailable");
      },
      { enableHighAccuracy: true, maximumAge: 10_000, timeout: 20_000 }
    );
  }

  begin();

  return {
    stop() {
      stopped = true;
      halt();
    },
    retry() {
      if (stopped) return;
      halt();
      last = null;
      attempt = 0;
      softAttempt = 0;
      state = null;
      begin();
    },
  };
}
