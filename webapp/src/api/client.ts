// ================================================================
//  Базовый HTTP-клиент Юлдаша.
//  Точка правды для API base и токена.
//  База: import.meta.env.VITE_API_BASE, дефолт https://yulbash.ru
// ================================================================

export const API_BASE = (
  import.meta.env.VITE_API_BASE ?? "https://yulbash.ru"
).replace(/\/+$/, "");

const TOKEN_KEY = "yuldash.token";

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string | null): void {
  if (token) localStorage.setItem(TOKEN_KEY, token);
  else localStorage.removeItem(TOKEN_KEY);
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

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

async function request<T>(
  path: string,
  init: RequestInit & { auth?: boolean } = {}
): Promise<T> {
  const { auth = true, headers, ...rest } = init;
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
    setToken(null);
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
