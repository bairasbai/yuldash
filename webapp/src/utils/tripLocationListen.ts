// ================================================================
//  Машина водителя на карте у пассажира — приём из канала /ws/trip/{id}/location.
//  Пара к tripLocationShare.ts (отправка с сайта водителя); у Android на обе стороны один
//  LocationSocket, и правила здесь те же:
//   • обрыв связи → повтор по лестнице 1, 2, 4 … 30 с; «сеть вернулась» → сразу;
//   • ключ входа истёк (живёт 12 ч) → тихое обновление и повтор только с новым ключом;
//     обновление отказано («Выйти со всех устройств») → конец;
//   • «поездка ещё не активна» → мягкий повтор раз в 15 с, не больше 40 раз;
//   • конец поездки, чужая бронь, блокировка (прочие 1008 / 4xxx) → конец;
//   • без ключа входа соединение не открываем; сменилась сессия → тихо останавливаемся.
//  Раньше канал открывался один раз: первый же обрыв (туннель, смена вышки) или истечение ключа —
//  и машина на карте у пассажира замирала до перезагрузки страницы (приёмка PR155).
//
//  Только логика с подставными зависимостями — её исполняет tests/passenger-trip-location-test.mjs.
// ================================================================
import { MAX_RECONNECT_MS, MAX_SOFT_RETRIES, SOFT_RETRY_MS, type SocketLike } from "./tripLocationShare";

export type DriverPoint = { lat: number; lng: number; bearing?: number };

export interface ListenDeps {
  /** Открыть сокет канала точки (`new WebSocket(tripLocationUrl(...))`). */
  openSocket: () => SocketLike;
  /** Ключ для первого кадра {type:"auth"} — не в адресе. */
  token: () => string | null;
  setTimer: (fn: () => void, ms: number) => unknown;
  clearTimer: (t: unknown) => void;
  /** Точка машины водителя (только role === "driver"). */
  onLoc: (p: DriverPoint) => void;
  /** Сессия та же (не вышли и не сменили аккаунт)? Иначе — тихо останавливаемся. */
  isCurrent?: () => boolean;
  /** Сервер отверг ключ: тихо обновить (true — ключ уже другой, false — отказано, исключение — нет сети). */
  refreshToken?: (rejected: string) => Promise<boolean>;
  /** Подписка на «сеть вернулась» (`window` «online»); возвращает отписку. */
  subscribeOnline?: (fn: () => void) => () => void;
}

/** Слушать машину водителя. `stop()` — закрыть соединение и снять все таймеры; повтор безопасен. */
export function listenDriverLocation(deps: ListenDeps): { stop(): void } {
  let stopped = false;
  let socket: SocketLike | null = null;
  let timer: unknown = null;
  let attempt = 0;
  let softAttempt = 0;
  let tokenAttempt = 0;
  /** Ключ, который сервер отверг: с ним больше не подключаемся — ждём обновлённый. */
  let rejectedToken: string | null = null;
  /** Чего ждёт назначенный повтор: обрыва сети, подтверждения брони или нового ключа. */
  let waiting: "net" | "soft" | "token" | null = null;

  const current = () => !stopped && (deps.isCurrent ? deps.isCurrent() : true);
  const ladder = (n: number) => Math.min(MAX_RECONNECT_MS, 1000 * 2 ** Math.min(n - 1, 5));

  function closeSocket() {
    const s = socket;
    socket = null;
    if (!s) return;
    s.onopen = s.onclose = s.onerror = s.onmessage = null;
    try {
      s.close(1000);
    } catch {
      /* уже закрыт */
    }
  }

  function stop() {
    stopped = true;
    if (timer !== null) deps.clearTimer(timer);
    timer = null;
    waiting = null;
    closeSocket();
    unsubscribeOnline?.();
  }

  function schedule(ms: number, why: "net" | "soft" | "token") {
    if (timer !== null) deps.clearTimer(timer);
    waiting = why;
    timer = deps.setTimer(() => {
      timer = null;
      waiting = null;
      if (current()) connect();
      else stop();
    }, ms);
  }

  /** Отвергнутый ключ не повторяем: просим тихое обновление и ждём по своей лестнице (как у водителя). */
  function waitForFreshToken() {
    const rejected = rejectedToken;
    tokenAttempt++;
    schedule(ladder(tokenAttempt), "token");
    if (!rejected || !deps.refreshToken) return;
    deps.refreshToken(rejected).then(
      (fresh) => {
        if (!fresh && current() && deps.token() === rejected) stop();
      },
      () => {
        /* нет сети — обновим на следующем шаге лестницы */
      }
    );
  }

  function connect() {
    if (!current()) {
      stop();
      return;
    }
    if (socket) return;
    const token = deps.token();
    if (!token) {
      stop();
      return;
    }
    if (token === rejectedToken) {
      waitForFreshToken();
      return;
    }
    let s: SocketLike;
    try {
      s = deps.openSocket();
    } catch {
      attempt++;
      schedule(ladder(attempt), "net");
      return;
    }
    socket = s;
    s.onopen = () => {
      if (socket !== s) return;
      if (!current()) {
        stop();
        return;
      }
      s.send(JSON.stringify({ type: "auth", token }));
      attempt = 0;
    };
    s.onmessage = (ev) => {
      if (socket !== s) return;
      try {
        const d = JSON.parse(String((ev as { data?: unknown } | null)?.data ?? ""));
        if (d?.type === "loc" && d.role === "driver" && typeof d.lat === "number" && typeof d.lng === "number") {
          // Точка пришла — ключ принят и поездка идёт: лестницы с нуля.
          softAttempt = 0;
          tokenAttempt = 0;
          deps.onLoc({ lat: d.lat, lng: d.lng, bearing: typeof d.bearing === "number" ? d.bearing : undefined });
        }
      } catch {
        /* битый кадр — игнор */
      }
    };
    s.onerror = () => {
      /* за ошибкой всегда следует onclose — повтор там */
    };
    s.onclose = (ev) => {
      if (socket !== s) return;
      socket = null;
      if (!current()) {
        stop();
        return;
      }
      const reason = String(ev?.reason ?? "");
      if (ev?.code === 1008 && /not active/i.test(reason)) {
        if (softAttempt >= MAX_SOFT_RETRIES) {
          stop();
          return;
        }
        softAttempt++;
        schedule(SOFT_RETRY_MS, "soft");
        return;
      }
      if (ev?.code === 1008 && /token/i.test(reason)) {
        rejectedToken = token;
        waitForFreshToken();
        return;
      }
      if (ev?.code === 1008 || (ev?.code >= 4000 && ev?.code <= 4999)) {
        // «Trip ended», «Forbidden», «Access ended» (блокировка) — конец, повторять бессмысленно.
        stop();
        return;
      }
      attempt++;
      schedule(ladder(attempt), "net");
    };
  }

  // Сеть вернулась — не ждём шага лестницы; мягкий повтор «бронь ещё не подтверждена» не ускоряем.
  const unsubscribeOnline = deps.subscribeOnline?.(() => {
    if (!current() || socket || timer === null || (waiting !== "net" && waiting !== "token")) return;
    attempt = 0;
    tokenAttempt = 0;
    deps.clearTimer(timer);
    timer = null;
    waiting = null;
    connect();
  });

  connect();
  return { stop };
}
