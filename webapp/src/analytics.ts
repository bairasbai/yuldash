// ================================================================
//  Продуктовая аналитика Юлдаша (веб) — воронка без персональных данных.
//  Зеркало android `data/Analytics.kt`, но своя реализация (нет Firebase на вебе).
//
//  ПРИВАТНОСТЬ — строго (§8 CLAUDE.md):
//   • НЕ шлём телефон, имя, точные координаты, адрес, токен, e-mail.
//   • Только имя события + безопасный контекст: экран, роль, язык, город (опц.).
//   • client_id — анонимный uuid в localStorage, НЕ привязан к личности.
//
//  Сток: best-effort POST ${VITE_API_BASE}/events (fire-and-forget). Эндпоинта
//  на бэке пока НЕТ — 404/405/сеть глотаем молча (в dev — console.debug), UI не
//  блокируем и агрессивно не ретраим. Появится ручка на бэке — просто заработает.
// ================================================================
import { API_BASE } from "./api/client";
import { ownedStorage, captureOwner } from "./utils/ownedStorage";

const CID_KEY = "yuldash.cid";
const LANG_KEY = "yuldash.lang"; // тот же ключ, что у i18n/lang
const ROLE_KEY = "yuldash.role"; // тот же ключ, что у flags

/** Разрешённые типы значений в props — только безопасный контекст. */
export type TrackProps = Record<string, string | number | boolean>;

/** Ключи, которые НИКОГДА не уходят в аналитику (защита от случайной утечки PII). */
const DENY = /phone|tel|name|lat|lng|lon|coord|token|email|address|addr|otp|code|secret|password/i;

/** Анонимный идентификатор устройства (uuid). Не персональные данные. */
function clientId(owner: string | null): string {
  try {
    const store = ownedStorage(owner);
    let id = store.getItem(CID_KEY);
    if (!id) {
      id =
        typeof crypto !== "undefined" && "randomUUID" in crypto
          ? crypto.randomUUID()
          : `yu-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
      store.setItem(CID_KEY, id);
    }
    return id;
  } catch {
    return "anon";
  }
}

/** Приложение открыто как установленная PWA (важный сигнал для «дороги к айфонам»). */
function isStandalone(): boolean {
  try {
    return (
      window.matchMedia("(display-mode: standalone)").matches ||
      (window.navigator as unknown as { standalone?: boolean }).standalone === true
    );
  } catch {
    return false;
  }
}

/** Отбрасываем чувствительные ключи и обрезаем строки — на случай неаккуратного вызова. */
function sanitize(props?: TrackProps): TrackProps {
  const out: TrackProps = {};
  if (!props) return out;
  for (const [k, v] of Object.entries(props)) {
    if (DENY.test(k)) continue;
    if (typeof v === "string") out[k] = v.slice(0, 64);
    else if (typeof v === "number" && Number.isFinite(v)) out[k] = v;
    else if (typeof v === "boolean") out[k] = v;
  }
  return out;
}

/**
 * Записать событие воронки. Имя — snake_case (`app_open`, `booking_done`…).
 * props — только безопасный контекст (роль, категория, класс, город). Без PII.
 */
export function track(event: string, props?: TrackProps): void {
  const owner = captureOwner();
  const payload = {
    event,
    client_id: clientId(owner),
    ts: Date.now(),
    // Общий безопасный контекст — подставляем автоматически, чтобы вызовы были короткими.
    lang: localStorage.getItem(LANG_KEY) === "ba" ? "ba" : "ru",
    role: ownedStorage(owner).getItem(ROLE_KEY) === "driver" ? "driver" : "passenger",
    standalone: isStandalone(),
    ...sanitize(props),
  };

  if (import.meta.env.DEV) {
    // В разработке видно, что и когда трекается (эндпоинта ещё нет — это норма).
    console.debug("[analytics]", event, payload);
  }

  try {
    // Fire-and-forget: без Authorization (не шлём токен), keepalive — переживёт переход экрана.
    void fetch(`${API_BASE}/events`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
      keepalive: true,
    }).catch(() => {
      /* нет эндпоинта / нет сети / CORS — аналитика не критична, молчим */
    });
  } catch {
    /* fetch недоступен — no-op */
  }
}

/**
 * Событие «один раз за сессию» (вкладку) — для `app_open` и показов, чтобы не
 * задваивать при ре-рендерах. Дедуп через sessionStorage.
 */
export function trackOnce(dedupeKey: string, event: string, props?: TrackProps): void {
  try {
    const k = `yuldash.tracked.${dedupeKey}`;
    if (sessionStorage.getItem(k) === "1") return;
    sessionStorage.setItem(k, "1");
  } catch {
    /* приватный режим без sessionStorage — просто трекнем как есть */
  }
  track(event, props);
}
