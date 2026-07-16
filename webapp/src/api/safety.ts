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
