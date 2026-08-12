// ================================================================
//  Безопасность (зеркало backend/app/routers/safety.py).
//  SOS доверенным контактам + «перезвоните мне». Обе ручки — auth
//  (current_user): SOS шлёт SMS доверенным, callback — телефон из профиля.
// ================================================================
import { apiDelete, apiGet, apiPost } from "./client";

export type SosCategory = "medical" | "breakdown" | "other";

/** Тело POST /sos (SosIn). booking_id/order_id — контекст поездки, опц. */
export interface SosInput {
  category: SosCategory;
  booking_id?: number | null;
  order_id?: number | null;
  note?: string;
}

/** Ответ POST /sos (SosEvent). */
export interface SosEvent {
  id: number;
  user_id: number;
  category: string;
  note: string;
  status: string; // open | handled
  created_at: string;
}

/** SOS: событие пишется всегда, доверенным уходит SMS (кеп на сервере). Требует входа. */
export function sendSos(body: SosInput): Promise<SosEvent> {
  return apiPost<SosEvent>("/sos", {
    category: body.category,
    booking_id: body.booking_id ?? null,
    order_id: body.order_id ?? null,
    note: body.note ?? "",
  });
}

/** «Перезвоните мне» (POST /callback): админ получает телефон из профиля и перезванивает.
 *  Требует входа — сервер берёт телефон из аккаунта (гостевого пути на бэке нет). */
export function requestCallback(note: string): Promise<{ ok: boolean }> {
  return apiPost<{ ok: boolean }>("/callback", { note: note.slice(0, 500) });
}

// ================================================================
//  Чёрный список и жалобы (safety.py: /blocks, /reports, /reportable-users).
// ================================================================

/** Заблокированный пользователь (GET /blocks → BlockOut). */
export interface BlockedUser {
  blocked_user_id: number;
  name: string;
}

/** Чёрный список текущего пользователя (кого он заблокировал, с именами). Требует входа. */
export function fetchBlocks(signal?: AbortSignal): Promise<BlockedUser[]> {
  return apiGet<BlockedUser[]>("/blocks", { signal });
}

/** Заблокировать пользователя (POST /blocks). Идемпотентно на сервере. */
export function blockUser(blockedUserId: number): Promise<unknown> {
  return apiPost("/blocks", { blocked_user_id: blockedUserId });
}

/** Убрать пользователя из чёрного списка (DELETE /blocks/{id}). */
export function unblockUser(blockedUserId: number): Promise<{ ok: boolean }> {
  return apiDelete<{ ok: boolean }>(`/blocks/${blockedUserId}`);
}

/** Попутчик, на которого можно пожаловаться/заблокировать (GET /reportable-users). */
export interface ReportableUser {
  id: number;
  name: string;
}

/** С кем была поездка — на них можно пожаловаться или заблокировать. Требует входа. */
export function fetchReportableUsers(signal?: AbortSignal): Promise<ReportableUser[]> {
  return apiGet<ReportableUser[]>("/reportable-users", { signal });
}

/** Категории жалоб (§9, закрытый перечень — зеркало ReportCategory на бэке). */
export type ReportCategory =
  | "rude"
  | "kicked_out"
  | "dangerous_driving"
  | "price_fraud"
  | "dirty_car"
  | "late"
  | "safety_threat"
  | "no_show"
  | "damage"
  | "unpaid"
  | "other";

/** Тело POST /reports (ReportIn). Либо target_user_id, либо привязка к поездке/заказу. */
export interface ReportInput {
  target_user_id?: number | null;
  category: ReportCategory;
  reason?: string;
  order_id?: number | null;
  booking_id?: number | null;
}

/** Ответ POST /reports (ReportCreatedOut) — без автора (анонимность). */
export interface ReportCreated {
  id: number;
  category: string;
  status: string;
  created_at: string;
}

/** Пожаловаться на попутчика (POST /reports). Анонимно: цель не видит автора. Требует входа. */
export function sendReport(body: ReportInput): Promise<ReportCreated> {
  return apiPost<ReportCreated>("/reports", {
    target_user_id: body.target_user_id ?? null,
    category: body.category,
    reason: (body.reason ?? "").slice(0, 1000),
    order_id: body.order_id ?? null,
    booking_id: body.booking_id ?? null,
  });
}

// ================================================================
//  Зимняя проверка «доехал?» (safety.py: /bookings/{id}/winter-check).
//  Клиент сам считает ETA (сервер страхует по depart_at) и по её
//  истечении дергает winter-check → сервер шлёт пуш обеим сторонам,
//  а при молчании ≥30 мин и активном шаринге — эскалирует близким.
//  До деплоя release эндпоинты отдают 404 → мягкая деградация.
// ================================================================

/** Состояние проверки (ответ POST /bookings/{id}/winter-check). */
export type WinterCheckState =
  | "closed" // поездка done/cancelled — проверять нечего
  | "ok" // участник уже ответил «всё в порядке»
  | "too_early" // поездка ещё не началась (по depart_at)
  | "check_sent" // пуш «всё в порядке?» отправлен обеим сторонам
  | "waiting" // пуш был, ждём ответа
  | "no_share" // эскалировать некому (нет шаринга близким)
  | "escalated"; // близкие уведомлены

export interface WinterCheckResult {
  state: WinterCheckState | string;
  waited_min?: number;
  sos_event_id?: number;
  contacts_notified?: number;
}

/** Запустить/продвинуть проверку «доехал?» (идемпотентна, можно дергать повторно). */
export function winterCheck(bookingId: number): Promise<WinterCheckResult> {
  return apiPost<WinterCheckResult>(`/bookings/${bookingId}/winter-check`);
}

/** «Я доехал(а), всё в порядке» — гасит эскалацию доверенным. */
export function winterCheckOk(bookingId: number): Promise<{ ok: boolean }> {
  return apiPost<{ ok: boolean }>(`/bookings/${bookingId}/winter-check/ok`);
}

// ------------------------------- Мои ограничения (право объяснения) -------------------------------
/**
 * GET /me/restrictions — что именно ограничено, почему и до когда. Автора жалобы НЕ
 * раскрываем. Смысл: человек не должен догадываться, почему у него «вдруг не работает».
 * Пустой список = ограничений нет.
 */
export interface Restriction {
  kind: string; // taxi_pause | ...
  reason: string;
  category: string | null;
  category_ru?: string;
  category_ba?: string;
  until: string | null; // null = до решения человека
  title_ru: string;
  title_ba: string;
  note_ru: string;
  note_ba: string;
}

export function fetchMyRestrictions(
  signal?: AbortSignal
): Promise<{ items?: Restriction[]; restrictions?: Restriction[] }> {
  return apiGet("/me/restrictions", { signal });
}

/** GET /safety/policy — пороги «Справедливости» приходят с сервера, клиент их не хардкодит. */
export interface SafetyPolicy {
  strikes_to_limit: number;
  strikes_to_suspend: number;
  strike_decay_days: number;
}

export function fetchSafetyPolicy(signal?: AbortSignal): Promise<SafetyPolicy> {
  return apiGet<SafetyPolicy>("/safety/policy", { signal });
}
