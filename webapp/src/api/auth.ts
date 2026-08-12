// ================================================================
//  Авторизация Юлдаша (зеркало backend/app/routers/auth.py).
//  Основной путь — вход через Telegram-бота (код в чате).
//  SMS-вход ЗАМОРОЖЕН за флагом VITE_SMS_LOGIN_ENABLED — ровно как в
//  приложении (BuildConfig.SMS_LOGIN_ENABLED): форма и запросы готовы,
//  включается одной переменной, когда появится юрлицо для sms.ru.
// ================================================================
import {
  apiGet,
  apiPost,
  apiUpload,
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
  /** "" | female | male. Нужен для поездок «только женщины» — сервер сверяет обе стороны. */
  gender?: string;
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

// ---------------------------- Вход по SMS (за флагом) ----------------------------
/** Включён ли SMS-вход в этой сборке. Пока юрлица нет — выключен, как в приложении. */
export const SMS_LOGIN_ENABLED =
  String(import.meta.env.VITE_SMS_LOGIN_ENABLED ?? "").trim() === "1";

/**
 * POST /auth/request-code {phone} — сервер шлёт код в SMS.
 * 429 = слишком часто (не больше трёх кодов в минуту на номер).
 */
export function requestSmsCode(phone: string): Promise<{ sent: boolean; dev_code?: string }> {
  return apiPost<{ sent: boolean; dev_code?: string }>(
    "/auth/request-code",
    { phone },
    { auth: false }
  );
}

/** POST /auth/verify {phone, code, name} → пара токенов + профиль. 400 = код неверный/истёк. */
export function verifySmsCode(
  phone: string,
  code: string,
  name = ""
): Promise<TokenPair> {
  return apiPost<TokenPair>("/auth/verify", { phone, code, name }, { auth: false });
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

// ---------------------------- Редактирование профиля ----------------------------
/**
 * Тело POST /me/update. Все поля необязательные — шлём только изменённое.
 * Телефон здесь не меняется никогда: он привязан к входу.
 *
 * `gender` нужен не «для статистики»: отметку «только женщины» сервер проверяет
 * у ОБЕИХ сторон поездки. Не указан пол — женщина не сможет ни забронировать такую
 * поездку, ни осмысленно её опубликовать. Пустая строка = «не указывать» (снять).
 *
 * `language` сервер запоминает, чтобы слать пуши на языке человека.
 * `city` — свободная строка из справочника; пустая сбрасывает город.
 */
export interface MeUpdateInput {
  name?: string;
  avatar_url?: string;
  city?: string;
  language?: "ru" | "ba";
  gender?: "" | "female" | "male";
}

export function updateMe(body: MeUpdateInput): Promise<Me> {
  return apiPost<Me>("/me/update", body);
}

/** Загрузка фото профиля (POST /upload/photo, multipart `file`) → публичный URL. */
export function uploadProfilePhoto(file: File, signal?: AbortSignal): Promise<{ url: string }> {
  const form = new FormData();
  form.append("file", file);
  return apiUpload<{ url: string }>("/upload/photo", form, { signal });
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
