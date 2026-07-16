// ================================================================
//  Безопасность (зеркало backend/app/routers/safety.py).
//  SOS доверенным контактам + «перезвоните мне». Обе ручки — auth
//  (current_user): SOS шлёт SMS доверенным, callback — телефон из профиля.
// ================================================================
import { apiPost } from "./client";

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
