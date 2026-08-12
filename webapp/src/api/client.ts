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

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string | null): void {
  if (token) localStorage.setItem(TOKEN_KEY, token);
  else localStorage.removeItem(TOKEN_KEY);
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_KEY);
}

export function setRefreshToken(token: string | null): void {
  if (token) localStorage.setItem(REFRESH_KEY, token);
  else localStorage.removeItem(REFRESH_KEY);
}

/** Сохранить/очистить обе части сессии одним швом. */
export function setSession(access: string | null, refresh?: string | null): void {
  setToken(access);
  if (refresh !== undefined) setRefreshToken(refresh);
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
let refreshInFlight: Promise<boolean> | null = null;
function runRefresh(): Promise<boolean> {
  if (!refreshHandler) return Promise.resolve(false);
  if (!refreshInFlight) {
    refreshInFlight = refreshHandler().finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function request<T>(
  path: string,
  init: RequestInit & { auth?: boolean; _retried?: boolean } = {}
): Promise<T> {
  const { auth = true, headers, _retried = false, ...rest } = init;
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
  } catch (e) {
    // Сеть недоступна / CORS / таймаут — единый тип для UI.
    throw new ApiError(0, e instanceof Error ? e.message : "network");
  }

  if (res.status === 401) {
    // Access протух → один раз пробуем обновить по refresh и повторить запрос.
    if (auth && !_retried && getRefreshToken() && (await runRefresh())) {
      return request<T>(path, { ...init, _retried: true });
    }
    setToken(null);
    setRefreshToken(null);
    onUnauthorized?.();
    throw new ApiError(401, "unauthorized");
  }

  if (!res.ok) {
    let detail = res.statusText;
    try {
      const body = await res.json();
      if (body?.detail) detail = String(body.detail);
    } catch {
      /* тело не JSON — оставляем statusText */
    }
    throw new ApiError(res.status, detail);
  }

  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
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
  opts?: { auth?: boolean; signal?: AbortSignal }
): Promise<T> {
  return request<T>(path, {
    method: "POST",
    auth: opts?.auth,
    signal: opts?.signal,
    headers: body !== undefined ? { "Content-Type": "application/json" } : undefined,
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
  opts?: { signal?: AbortSignal }
): Promise<T> {
  return request<T>(path, { method: "POST", body: form, signal: opts?.signal });
}
