// ================================================================
//  Админка Юлдаша (Волна 8А — ядро модерации).
//  Зеркало admin-эндпоинтов backend:
//    requests.py  · POST /admin/request-for-phone, GET /requests/{id}/responses,
//                   POST /responses/{id}/accept
//    drivers.py   · GET /admin/drivers/pending, POST /admin/drivers/{id}/moderate,
//                   GET /secure/docs/{name} (защищённые фото — только админ/владелец)
//    safety.py    · GET /admin/reports, POST /admin/reports/{id}/resolve|reject,
//                   POST /admin/quality/{id}/pause|unpause
//    payments.py  · GET /admin/payments/pending, POST /admin/payments/{id}/confirm|reject,
//                   GET /admin/payments/summary
//  Все ручки требуют role == "admin" (иначе 403). До мержа release на прод — 404/405 (мягко).
// ================================================================
import { apiGet, apiPost, getToken } from "./client";
import type { ResponseItem } from "./requests";

// ----------------------------- 2. Заявка за юзера -----------------------------
export interface AdminRequestIn {
  phone: string;
  name?: string;
  from_city: string;
  to_city: string;
  desired_at?: string | null;
  seats?: number;
  comment?: string;
}

/** POST /admin/request-for-phone — оформить заявку ЗА пользователя по телефону. */
export function adminRequestForPhone(body: AdminRequestIn): Promise<{ id: number }> {
  return apiPost<{ id: number }>("/admin/request-for-phone", body);
}

// ----------------------------- 3. Принять отклик за юзера -----------------------------
/** GET /requests/{id}/responses — отклики на заявку (админ видит любые). */
export function adminRequestResponses(requestId: number, signal?: AbortSignal): Promise<ResponseItem[]> {
  return apiGet<ResponseItem[]>(`/requests/${requestId}/responses`, { signal });
}

/** POST /responses/{id}/accept — принять отклик ЗА пользователя (создаёт поездку+бронь). */
export function adminAcceptResponse(responseId: number): Promise<{ booking_id: number }> {
  return apiPost<{ booking_id: number }>(`/responses/${responseId}/accept`);
}

// ----------------------------- 4. Модерация водителей -----------------------------
export interface PendingDriver {
  user_id: number;
  name: string;
  phone: string;
  car: string;
  license_url: string;
  car_photo_url: string;
  autocheck_result: string; // pass / needs_human / reject / error / ""
  autocheck_score: number;
  autocheck_data: string; // JSON: распознанные поля + коды причин
}

/** GET /admin/drivers/pending — водители на проверке (docs_status=pending). */
export function fetchPendingDrivers(signal?: AbortSignal): Promise<PendingDriver[]> {
  return apiGet<PendingDriver[]>("/admin/drivers/pending", { signal });
}

/** POST /admin/drivers/{id}/moderate — подтвердить (verified) или отклонить. */
export function moderateDriver(
  userId: number,
  approve: boolean
): Promise<{ user_id: number; verified: boolean; docs_status: string }> {
  return apiPost(`/admin/drivers/${userId}/moderate`, { approve });
}

/**
 * Загрузить защищённое фото документа с Bearer-токеном и вернуть blob-URL.
 * <img src> не умеет слать заголовок Authorization, поэтому тянем сами → objectURL.
 * URL из бэка абсолютный (secure_docs_url). Вызывающий обязан URL.revokeObjectURL при размонтировании.
 */
export async function fetchSecureDoc(url: string, signal?: AbortSignal): Promise<string> {
  const token = getToken();
  const res = await fetch(url, {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
    signal,
  });
  if (!res.ok) throw new Error(`doc ${res.status}`);
  const blob = await res.blob();
  return URL.createObjectURL(blob);
}

// ----------------------------- 5. Жалобы -----------------------------
export interface AdminReport {
  id: number;
  reporter_name: string; // виден ТОЛЬКО админу
  target_name: string;
  target_phone: string;
  reason: string;
  created_at: string;
  category: string;
  status: string; // new | reviewing | resolved | rejected
  resolution?: string | null;
  order_id?: number | null;
  booking_id?: number | null;
  parcel_id?: number | null;
  target_user_id: number;
}

/** GET /admin/reports — жалобы для разбора. Фильтры ?status ?category. */
export function fetchAdminReports(
  opts?: { status?: string; category?: string; signal?: AbortSignal }
): Promise<AdminReport[]> {
  const q = new URLSearchParams();
  if (opts?.status) q.set("status", opts.status);
  if (opts?.category) q.set("category", opts.category);
  const qs = q.toString();
  return apiGet<AdminReport[]>(`/admin/reports${qs ? `?${qs}` : ""}`, { signal: opts?.signal });
}

/** POST /admin/reports/{id}/resolve — жалоба подтверждена. keep_pause держит паузу «до разбора». */
export function resolveReport(
  reportId: number,
  body?: { resolution?: string; keep_pause?: boolean }
): Promise<AdminReport> {
  return apiPost<AdminReport>(`/admin/reports/${reportId}/resolve`, body ?? {});
}

/** POST /admin/reports/{id}/reject — жалоба не подтвердилась. */
export function rejectReport(reportId: number, resolution?: string): Promise<AdminReport> {
  return apiPost<AdminReport>(`/admin/reports/${reportId}/reject`, { resolution: resolution ?? "" });
}

/** POST /admin/quality/{id}/pause — вручную поставить/продлить паузу такси (попутка работает). */
export function qualityPause(
  userId: number,
  hours: number
): Promise<{ ok: boolean; taxi_paused_until: string; reason: string }> {
  return apiPost(`/admin/quality/${userId}/pause`, { hours });
}

/** POST /admin/quality/{id}/unpause — снять паузу такси. */
export function qualityUnpause(userId: number): Promise<{ ok: boolean }> {
  return apiPost(`/admin/quality/${userId}/unpause`);
}

// ----------------------------- 6. Заявки на оплату (СБП «на доверии») -----------------------------
export interface PendingPayment {
  payment_id: number;
  purpose: string; // boost | donate | support | ad
  tier?: string | null;
  amount: number; // рубли (бэк уже делит копейки на 100)
  ride_id?: number | null;
  note: string;
  payer_name: string;
  payer_phone: string;
  created_at: string;
}

/** GET /admin/payments/pending — платежи на подтверждение (СБП). */
export function fetchPendingPayments(signal?: AbortSignal): Promise<PendingPayment[]> {
  return apiGet<PendingPayment[]>("/admin/payments/pending", { signal });
}

/** POST /admin/payments/{id}/confirm — деньги пришли → активировать. */
export function confirmPayment(paymentId: number): Promise<{ payment_id: number; status: string }> {
  return apiPost(`/admin/payments/${paymentId}/confirm`);
}

/** POST /admin/payments/{id}/reject — деньги не пришли → отменить. */
export function rejectPayment(paymentId: number): Promise<{ payment_id: number; status: string }> {
  return apiPost(`/admin/payments/${paymentId}/reject`);
}

export interface PaymentsSummary {
  donate: { count: number; sum_rub: number };
  boost: { count: number; sum_rub: number };
  support: { count: number; sum_rub: number };
}

/** GET /admin/payments/summary — сводка подтверждённых платежей. */
export function fetchPaymentsSummary(signal?: AbortSignal): Promise<PaymentsSummary> {
  return apiGet<PaymentsSummary>("/admin/payments/summary", { signal });
}
