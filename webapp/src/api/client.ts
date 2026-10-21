import { sessionMutation, assertSessionPermit, storageFenceEnabled, storageFenced, quarantineGeneration, rememberSession, storageOperation, reportStorageFailure, type SessionWritePermit } from "../utils/storageHealth";
// ================================================================
//  Базовый HTTP-клиент Юлдаша.
//  Точка правды для API base и токена.
//  База: import.meta.env.VITE_API_BASE, дефолт https://yulbash.ru
// ================================================================

export const API_BASE = (
  import.meta.env.VITE_API_BASE ?? "https://yulbash.ru"
).replace(/\/+$/, "");

const TOKEN_KEY = "yuldash.token";
const REFRESH_KEY = "yuldash.refresh";
const SESSION_KEY = "yuldash.session";

// A committed intent survives a lost response/reload. IndexedDB serializes
// creation across tabs even when Web Locks is unavailable.
async function refreshIntentTransaction<T>(work: (store: IDBObjectStore, done: (value: T) => void) => void): Promise<T> {
  const db = await new Promise<IDBDatabase>((resolve, reject) => {
    const request = storageOperation(() => indexedDB.open("yuldash-refresh-intents", 1));
    let blocked = false;
    request.onupgradeneeded = () => request.result.createObjectStore("intents");
    request.onerror = () => { reportStorageFailure(); reject(request.error); };
    request.onblocked = () => { blocked = true; reject(new Error("Refresh storage blocked")); };
    request.onsuccess = () => {
      if (blocked) request.result.close();
      else resolve(request.result);
    };
  });
  return new Promise<T>((resolve, reject) => {
    const tx = db.transaction("intents", "readwrite");
    let result: T;
    tx.oncomplete = () => { db.close(); resolve(result); };
    tx.onabort = () => { db.close(); reject(tx.error ?? new Error("Refresh storage aborted")); };
    try { work(tx.objectStore("intents"), value => { result = value; }); }
    catch (error) { tx.abort(); reject(error); }
  });
}

export function getRefreshRotationId(generation: string, refresh: string): Promise<string> {
  return refreshIntentTransaction((store, done) => {
    const request = store.get([generation, refresh]);
    request.onsuccess = () => {
      if (generation !== getSessionGeneration() || refresh !== getRefreshToken()) {
        done(""); return;
      }
      if (typeof request.result === "string" && /^[a-f0-9]{64}$/.test(request.result)) {
        done(request.result); return;
      }
      try {
        const nonce = Array.from(crypto.getRandomValues(new Uint8Array(32)), byte => byte.toString(16).padStart(2, "0")).join("");
        store.put(nonce, [generation, refresh]);
        done(nonce);
      } catch { store.transaction.abort(); }
    };
  });
}

export function clearRefreshRotationId(generation: string, refresh: string): Promise<void> {
  return refreshIntentTransaction((store, done) => { store.delete([generation, refresh]); done(undefined); });
}

function discardPreviousRefreshIntents(): void {
  try { void Promise.resolve(sessionMutation(() => refreshIntentTransaction<void>((store, done) => {
    const request = store.openCursor();
    request.onsuccess = () => {
      const cursor = request.result;
      if (!cursor) { done(undefined); return; }
      // Read the current owner here: delayed cleanup from A must preserve B.
      if (!Array.isArray(cursor.key) || cursor.key[0] !== getSessionGeneration()) cursor.delete();
      cursor.continue();
    };
  }))).catch(() => { /* Cleanup can retry at the next session boundary. */ }); } catch { /* Already quarantined. */ }
}

/** Вход/выход меняет поколение, тихое продление токенов — нет. */
export function getSessionGeneration(): string {
  if (storageFenced()) return quarantineGeneration();
  try { const generation = storageOperation(() => localStorage.getItem(SESSION_KEY) ?? ""); rememberSession(generation); return generation; }
  catch (error) { if (storageFenceEnabled()) return quarantineGeneration(); throw error; }
}

/**
 * Ссылка ведёт на НАШ сервер?
 *
 * Нужна везде, где к запросу подставляется токен входа. Адреса приватных файлов (селфи курьера,
 * документы водителя и таксиста, фото-доказательства спора) приходят с сервера, а туда их кладёт
 * сам проверяемый человек — это его заявка. Пропусти сервер хоть одно поле без проверки, и в
 * очереди модерации окажется ссылка на чужой сервер; браузер честно отправит туда заголовок
 * `Authorization: Bearer <токен админа>` (CORS этому не мешает — чужой сервер сам разрешает
 * себе такой запрос), и админка окажется в чужих руках. Так и было с селфи курьера
 * (аудит 2026-08-08): серверную проверку починили, но токен не должен уходить на чужой домен
 * даже при дыре на сервере.
 */
export function isOwnApiUrl(url: string): boolean {
  const raw = (url ?? "").trim();
  if (!raw) return false;
  if (raw.startsWith("//")) return false; // «//хост/путь» — абсолютный адрес чужого хоста
  if (raw.startsWith("/")) return true; // путь на нашей же базе
  try {
    return new URL(raw).origin === new URL(API_BASE, location.href).origin;
  } catch {
    return false;
  }
}

function readCredential(key: string): string | null {
  if (storageFenced()) return null;
  try { return storageOperation(() => localStorage.getItem(key)); }
  catch (error) { if (storageFenceEnabled()) return null; throw error; }
}
export function getToken(): string | null { return readCredential(TOKEN_KEY); }
function permitted(permit: SessionWritePermit): void { if (storageFenceEnabled()) assertSessionPermit(permit); }
function markNewSession(): void {
  const generation = typeof crypto !== "undefined" && "randomUUID" in crypto ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`;
  storageOperation(() => localStorage.setItem(SESSION_KEY, generation));
  rememberSession(generation);
  if (!storageFenced()) discardPreviousRefreshIntents();
}
function writeToken(token: string | null): void {
  storageOperation(() => token ? localStorage.setItem(TOKEN_KEY, token) : localStorage.removeItem(TOKEN_KEY));
}
function writeRefresh(token: string | null): void {
  storageOperation(() => token ? localStorage.setItem(REFRESH_KEY, token) : localStorage.removeItem(REFRESH_KEY));
}
export function setToken(token: string | null): void | Promise<void> {
  return sessionMutation(permit => { permitted(permit); writeToken(null); markNewSession(); writeToken(token); markNewSession(); });
}
export function getRefreshToken(): string | null { return readCredential(REFRESH_KEY); }
export function setRefreshToken(token: string | null): void | Promise<void> {
  return sessionMutation(permit => { permitted(permit); writeRefresh(token); });
}
/** Only an active lock capability may bypass the publication queue. */
export function setSession(access: string | null, refresh?: string | null, permit?: SessionWritePermit): void | Promise<void> {
  const commit = (p: SessionWritePermit) => {
    permitted(p); writeToken(null); writeRefresh(null); markNewSession();
    writeRefresh(refresh ?? null); writeToken(access); markNewSession();
  };
  return permit ? commit(permit) : sessionMutation(commit);
}
export function rotateSession(access: string, refresh: string, generation: string): boolean | Promise<boolean> {
  return sessionMutation(permit => {
    permitted(permit);
    if (generation !== getSessionGeneration() || !access || !refresh) return false;
    writeToken(access); writeRefresh(refresh); return true;
  });
}

/** Ошибка API с кодом статуса — экраны решают, как показать. */
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string
  ) {
    super(message);
    this.name = "ApiError";
  }
}

/** Кого оповестить об истечении сессии (401) — заполнит слой авторизации позже. */
let onUnauthorized: (() => void) | null = null;
export function setUnauthorizedHandler(fn: (() => void) | null): void {
  onUnauthorized = fn;
}

// ---- Тихое обновление access-токена по refresh (access живёт минуты) ----
// Слой авторизации регистрирует функцию: она дергает /auth/refresh, кладёт новую
// пару в localStorage и возвращает true при успехе. Здесь мы её только вызываем.
let refreshHandler: (() => Promise<boolean>) | null = null;
export function setRefreshHandler(fn: (() => Promise<boolean>) | null): void {
  refreshHandler = fn;
}
// Один общий полёт обновления: параллельные 401 не запускают N рефрешей.
let refreshInFlight: { generation: string; promise: Promise<boolean> } | null = null;
function runRefresh(generation: string, staleToken: string | null): Promise<boolean> {
  if (!refreshHandler) return Promise.resolve(false);
  if (!refreshInFlight || refreshInFlight.generation !== generation) {
    const flight = { generation, promise: Promise.resolve(false) };
    const refresh = async () => {
      if (generation !== getSessionGeneration()) return false;
      if (getToken() !== staleToken) return true;
      return refreshHandler ? refreshHandler() : false;
    };
    // Web Locks разделяет очередь между вкладками одного сайта.
    const pending = typeof navigator !== "undefined" && navigator.locks
      ? navigator.locks.request(`yuldash-auth-refresh:${generation}`, refresh)
      : refresh();
    flight.promise = Promise.resolve(pending).catch(error => {
      // Недоступность refresh не означает, что вход отозван.
      // Ошибка этого запроса не является отказом исходному действию очереди.
      throw new ApiError(0, error instanceof ApiError ? error.message : genericByStatus(0, isBashkir()));
    }).finally(() => {
      if (refreshInFlight === flight) refreshInFlight = null;
    });
    refreshInFlight = flight;
  }
  return refreshInFlight.promise;
}

/**
 * Живой канал (точка водителя) получил отказ в ключе — 1008 «Invalid token» / «Token revoked».
 * Обновляем ключ тем же общим полётом, что запросы после 401: одна очередь на все вкладки, своё
 * поколение сессии. true — ключ уже другой (обновили здесь или раньше), false — обновление отказано.
 * Выход из аккаунта здесь не делаем: это решает ближайший запрос к серверу, как и раньше.
 */
export function refreshRejectedToken(rejected: string): Promise<boolean> {
  return runRefresh(getSessionGeneration(), rejected);
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/** Язык интерфейса для текстов ошибок. Читаем из того же места, что и i18n. */
function isBashkir(): boolean {
  try {
    return localStorage.getItem("yuldash.lang") === "ba";
  } catch {
    return false;
  }
}

/** Общая фраза, когда сервер не прислал понятного текста. */
function genericByStatus(status: number, ba: boolean): string {
  if (status === 0)
    return ba
      ? "Бәйләнеш юҡ. Интернетты тикшереп ҡабатла."
      : "Нет связи. Проверь интернет и попробуй ещё раз.";
  if (status === 403)
    return ba ? "Быға хоҡуғың юҡ." : "Нет доступа к этому действию.";
  if (status === 404)
    return ba ? "Табылманы." : "Не найдено.";
  if (status === 409)
    return ba ? "Хәл үҙгәргән — экранды яңырт." : "Состояние изменилось — обнови экран.";
  if (status === 422)
    return ba ? "Мәғлүмәт дөрөҫ түгел. Тикшереп ҡара." : "Данные заполнены неверно. Проверь поля.";
  if (status === 429)
    return ba ? "Артыҡ йыш. Бер аҙ көт." : "Слишком часто. Подожди немного.";
  if (status >= 500)
    return ba ? "Серверҙа хата. Аҙыраҡтан ҡабатла." : "Ошибка на сервере. Попробуй чуть позже.";
  return ba ? "Булманы. Ҡабатлап ҡара." : "Не получилось. Попробуй ещё раз.";
}

/**
 * Имена полей, как их называет человек. Сервер присылает служебные («from_city»),
 * и «проверь поля» на форме из десяти строк никому не помогает.
 * Чего нет в списке — не называем: лучше общая фраза, чем английское слово.
 */
const FIELD_RU: Record<string, [string, string]> = {
  phone: ["телефон", "телефон"],
  name: ["имя", "исем"],
  from_city: ["город отправления", "сығыу ҡалаһы"],
  to_city: ["город назначения", "барыр ҡала"],
  depart_at: ["время выезда", "сығыу ваҡыты"],
  desired_at: ["время", "ваҡыт"],
  scheduled_at: ["время подачи", "килеү ваҡыты"],
  seats: ["число мест", "урын һаны"],
  seats_total: ["число мест", "урын һаны"],
  price: ["цена", "хаҡ"],
  code: ["код", "код"],
  text: ["текст", "текст"],
  reason: ["причина", "сәбәп"],
  comment: ["комментарий", "иҫкәрмә"],
  receiver_name: ["имя получателя", "алыусы исеме"],
  receiver_phone: ["телефон получателя", "алыусы телефоны"],
  weight_kg: ["вес", "ауырлыҡ"],
  inn: ["ИНН", "ИНН"],
  permit_number: ["номер разрешения", "рөхсәт номеры"],
  birth_date: ["дата рождения", "тыуған көн"],
  amount: ["сумма", "сумма"],
  amount_kop: ["сумма", "сумма"],
  stars: ["оценка", "баһа"],
  city: ["город", "ҡала"],
  title: ["название", "атама"],
};

/** Ошибка валидации FastAPI → «Проверь: телефон, цена». */
function validationMessage(detail: unknown[], ba: boolean): string | null {
  const names: string[] = [];
  for (const item of detail) {
    const loc = (item as { loc?: unknown[] })?.loc;
    if (!Array.isArray(loc)) continue;
    // loc = ["body", "phone"] — берём последний осмысленный кусок.
    const key = String(loc[loc.length - 1] ?? "");
    const pair = FIELD_RU[key];
    if (pair && !names.includes(pair[ba ? 1 : 0])) names.push(pair[ba ? 1 : 0]);
  }
  if (names.length === 0) return null;
  return ba
    ? `Тикшер: ${names.join(", ")}.`
    : `Проверь: ${names.join(", ")}.`;
}

/**
 * Тело ошибки → фраза для человека.
 *
 * Сервер отвечает тремя разными формами, и раньше все три превращались в
 * `String(detail)`:
 *   • {ru, ba} — двуязычная ошибка (280 мест в бэкенде) давала «[object Object]»;
 *   • массив — ошибка валидации FastAPI, тоже «[object Object]»;
 *   • строка — единственная форма, которая работала.
 *
 * То есть почти все объяснения сервера до человека не доходили. Теперь берём
 * нужный язык, а для форм без готового текста — общую фразу по коду.
 */
function errorMessage(status: number, detail: unknown): string {
  const ba = isBashkir();
  if (Array.isArray(detail)) {
    return validationMessage(detail, ba) ?? genericByStatus(status, ba);
  }
  if (detail && typeof detail === "object") {
    const d = detail as { ru?: string; ba?: string };
    const ru = (d.ru ?? "").trim();
    const bashkir = (d.ba ?? "").trim();
    if (ba && bashkir) return bashkir;
    if (ru) return ru;
    if (bashkir) return bashkir;
  }
  // Строка от сервера всегда по-русски: башкиру отдаём общую фразу, а не чужой язык.
  if (typeof detail === "string" && detail.trim()) {
    return ba ? genericByStatus(status, true) : detail;
  }
  return genericByStatus(status, ba);
}

async function request<T>(
  path: string,
  init: RequestInit & { auth?: boolean; _retried?: boolean; _generation?: string } = {}
): Promise<T> {
  const { auth = true, headers, _retried = false, _generation = getSessionGeneration(), ...rest } = init;
  const checkSession = () => {
    if (auth && _generation !== getSessionGeneration()) {
      throw new ApiError(409, genericByStatus(409, isBashkir()));
    }
  };
  checkSession();
  const sentToken = getToken();
  let res: Response;
  try {
    res = await fetch(`${API_BASE}${path}`, {
      ...rest,
      headers: {
        Accept: "application/json",
        ...(auth ? authHeaders() : {}),
        ...(headers as Record<string, string> | undefined),
      },
    });
  } catch {
    // Сеть недоступна / CORS / таймаут. Сообщение браузера сюда класть НЕЛЬЗЯ:
    // это «Failed to fetch» (Chrome), «Load failed» (Safari) — английский технический
    // текст, который экраны показывают человеку как есть (`e.message`, 146 мест).
    // Кладём сразу человеческую фразу на его языке.
    checkSession();
    throw new ApiError(0, genericByStatus(0, isBashkir()));
  }

  checkSession();

  if (res.status === 401) {
    // Access протух → один раз пробуем обновить по refresh и повторить запрос.
    if (auth && !_retried && getRefreshToken()) {
      const refreshed = getToken() !== sentToken || await runRefresh(_generation, sentToken);
      checkSession();
      if (refreshed) return request<T>(path, { ...init, _retried: true, _generation });
    }
    if (auth) {
      await sessionMutation(permit => {
        checkSession();
        try { setSession(null, null, permit); } finally { onUnauthorized?.(); }
      });
    }
    throw new ApiError(
      401,
      isBashkir() ? "Яңынан инергә кәрәк." : "Нужно войти заново."
    );
  }

  if (!res.ok) {
    // statusText — это «Bad Gateway» и «Internal Server Error»: английский текст
    // от nginx, который экраны показали бы человеку как объяснение. Берём общую
    // фразу по коду и меняем её только если сервер прислал понятное тело.
    let detail = genericByStatus(res.status, isBashkir());
    try {
      const body = await res.json();
      detail = errorMessage(res.status, body?.detail);
    } catch {
      /* тело не JSON (упал прокси, отдал HTML) — остаётся человеческая фраза */
    }
    checkSession();
    throw new ApiError(res.status, detail);
  }

  if (res.status === 204) return undefined as T;
  const body = await res.json();
  checkSession();
  return body as T;
}

export function apiGet<T>(
  path: string,
  opts?: { auth?: boolean; signal?: AbortSignal }
): Promise<T> {
  return request<T>(path, { method: "GET", auth: opts?.auth, signal: opts?.signal });
}

export function apiPost<T>(
  path: string,
  body?: unknown,
  opts?: { auth?: boolean; signal?: AbortSignal; idempotencyKey?: string; _generation?: string }
): Promise<T> {
  return request<T>(path, {
    method: "POST",
    auth: opts?.auth,
    signal: opts?.signal,
    _generation: opts?._generation,
    headers: {
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(opts?.idempotencyKey ? { "Idempotency-Key": opts.idempotencyKey } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
}

export function apiDelete<T>(
  path: string,
  opts?: { auth?: boolean; signal?: AbortSignal }
): Promise<T> {
  return request<T>(path, { method: "DELETE", auth: opts?.auth, signal: opts?.signal });
}

/** Загрузка файла (multipart). Content-Type НЕ ставим — браузер сам добавит boundary. */
export function apiUpload<T>(
  path: string,
  form: FormData,
  opts?: { signal?: AbortSignal; _generation?: string }
): Promise<T> {
  return request<T>(path, { method: "POST", body: form, signal: opts?.signal, _generation: opts?._generation });
}
