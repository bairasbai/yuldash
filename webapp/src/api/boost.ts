// ================================================================
//  Boost — поднятие своей поездки в ленте (payments.py).
//  GET  /boost/plans   — тарифы (цена ₽ + часы), источник истины бэк.
//  POST /boost/create  — оплата: yookassa → confirmation_url (редирект),
//                        sbp_manual → реквизиты СБП «на доверии»,
//                        mock/dev → сразу succeeded.
//  POST /boost/free    — поднять за реферальный бонус (1 бонус = 24ч).
//  GET  /payments/{id}/status — поллинг статуса после возврата из ЮKassa.
// ================================================================
import { apiGet, apiPost } from "./client";

export interface BoostPlan {
  tier: string;
  title: string;
  price: number; // ₽
  hours: number;
}

export function fetchBoostPlans(signal?: AbortSignal): Promise<BoostPlan[]> {
  return apiGet<BoostPlan[]>("/boost/plans", { auth: false, signal });
}

/** Ответ POST /boost/create. Форма зависит от провайдера платежей на бэке. */
export interface BoostCreateResult {
  status: "succeeded" | "pending" | string;
  method: "yookassa" | "sbp_manual" | string;
  payment_id: number;
  confirmation_url?: string; // yookassa pending → редирект браузера
  boosted_until?: string | null; // succeeded
  amount?: number; // sbp_manual, ₽
  payee?: { phone: string; bank: string; name: string }; // sbp_manual реквизиты
}

export function createBoost(
  ride_id: number,
  tier: string
): Promise<BoostCreateResult> {
  return apiPost<BoostCreateResult>("/boost/create", { ride_id, tier });
}

export function boostFree(
  ride_id: number
): Promise<{ ok: boolean; credits: number }> {
  return apiPost<{ ok: boolean; credits: number }>("/boost/free", { ride_id });
}

export interface PaymentStatus {
  payment_id: number;
  status: "pending" | "succeeded" | "canceled" | string;
  purpose: string;
  boosted_until?: string | null;
}

export function fetchPaymentStatus(
  paymentId: number,
  signal?: AbortSignal
): Promise<PaymentStatus> {
  return apiGet<PaymentStatus>(`/payments/${paymentId}/status`, { signal });
}
