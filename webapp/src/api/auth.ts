// ================================================================
//  Авторизация Юлдаша (зеркало backend/app/routers/auth.py).
//  Основной путь — вход через Telegram-бота (код в чате).
//  SMS-вход отключён (нет юрлица) — см. LoginScreen (спокойная заглушка).
// ================================================================
import {
  apiGet,
  apiPost,
  getRefreshToken,
  setRefreshToken,
  setToken,
} from "./client";

/** Пользователь из GET /me (User + рейтинг). Поля — по backend User-модели. */
export interface Me {
  id: number;
  name: string;
  phone: string;
  role: "passenger" | "driver" | "admin";
  verified: boolean;
  avatar_url?: string | null;
  telegram_id?: string | null;
  referral_code?: string | null;
  referral_credits?: number;
  referred_by?: number | null;
  city?: string; // родной город (свободная строка из справочника Settlement)
  rating: number | null;
  rating_count: number;
  created_at?: string;
}

interface TokenPair {
  access_token: string;
  refresh_token: string;
  token_type: string;
  user: Me;
}

/** GET /me — профиль текущего пользователя (требует токен). */
export function fetchMe(signal?: AbortSignal): Promise<Me> {
  return apiGet<Me>("/me", { signal });
}

/** POST /auth/tg/start → request_id. По нему строим ссылку t.me/<bot>?start=<id>. */
export function tgStart(): Promise<{ request_id: string }> {
  return apiPost<{ request_id: string }>("/auth/tg/start", undefined, {
    auth: false,
  });
}

/**
 * POST /auth/tg/verify {request_id, code} → пара токенов + user.
 * Коды ошибок (различает LoginScreen):
 *  403 phone_required · 409 код ещё идёт · 410 истёк · 429 много попыток · 400 неверный.
 */
export function tgVerify(
  request_id: string,
  code: string
): Promise<TokenPair> {
  return apiPost<TokenPair>(
    "/auth/tg/verify",
    { request_id, code },
    { auth: false }
  );
}

/** Обновить пару токенов по refresh (ротация: старый гасится). */
export async function refreshSession(): Promise<boolean> {
  const rt = getRefreshToken();
  if (!rt) return false;
  try {
    const pair = await apiPost<Omit<TokenPair, "user">>(
      "/auth/refresh",
      { refresh_token: rt },
      { auth: false }
    );
    setToken(pair.access_token);
    setRefreshToken(pair.refresh_token);
    return true;
  } catch {
    return false;
  }
}

/** POST /auth/logout — гасит сессию на сервере (best-effort). */
export function logoutServer(): Promise<{ ok: boolean }> {
  return apiPost<{ ok: boolean }>("/auth/logout");
}

/** POST /me/delete — необратимое удаление аккаунта и всех персональных данных
 *  (152-ФЗ, право на удаление). После успеха токен на сервере становится недействителен —
 *  вызывающий экран сам чистит локальную сессию и уводит на старт. */
export function deleteAccount(): Promise<{ ok: boolean }> {
  return apiPost<{ ok: boolean }>("/me/delete");
}

/** Имя бота из окружения (VITE_TELEGRAM_BOT). Пусто → Telegram-вход показывает заглушку. */
export const TELEGRAM_BOT = (import.meta.env.VITE_TELEGRAM_BOT ?? "").trim();

export function telegramStartUrl(requestId: string): string {
  return `https://t.me/${TELEGRAM_BOT}?start=${requestId}`;
}
export function telegramChatUrl(): string {
  return `https://t.me/${TELEGRAM_BOT}`;
}
