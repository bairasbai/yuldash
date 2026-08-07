// ================================================================
//  C1 — Профессиональный курьер Юлдаша (зеркало backend release:
//  routers/courier.py). Онбординг (заявка → админ approve/reject),
//  выход «на линию» с зоной, доступные курьер-заказы (courier/
//  buy_bring, БЕЗ телефона), кабинет (statement комиссии + рейтинг +
//  мягкая пауза по качеству) и оплата комиссии «на доверии» (СБП).
//
//  Флоу заказа переиспользует M3 (parcels.ts): accept/status/
//  carrying — те же эндпоинты, но courier/buy_bring гейтятся
//  _guard_courier на бэке.
//
//  Все эндпоинты courier/* появятся на проде после мержа release →
//  до этого 404/405/403 ловим мягкой деградацией.
// ================================================================
import { apiGet, apiPost } from "./client";
import type { Parcel } from "./parcels";

// ------------------------------- Типы -------------------------------
export type CourierTransport = "car" | "cargo";
export type CourierZone = "city" | "intercity" | "region";
export type CourierAppStatus = "pending" | "approved" | "rejected" | string;

/** Заявка «Стать курьером» (_application_payload). */
export interface CourierApplication {
  id: number;
  transport: CourierTransport | string;
  status: CourierAppStatus;
  selfie_url: string;
  invited_by: number | null;
  reject_reason: string; // причина отказа (пусто, если не отклонена)
  created_at: string | null;
  reviewed_at: string | null;
}

/** Профиль курьера на линии (_profile_payload). */
export interface CourierProfile {
  id: number;
  online: boolean;
  car_class: string;
  zone: CourierZone | string;
  work_city: string;
  work_direction_id: number | null;
  paused_until: string | null; // C3: мягкая пауза по качеству
  updated_at: string | null;
}

/** Выписка комиссии платформы по моим доставленным курьер-заказам. */
export interface CourierStatement {
  delivered_count: number;
  commission_earned_kop: number; // всего начислено платформе
  commission_owed_kop: number; // к оплате прямо сейчас
  commission_paid_kop: number; // уже оплачено
  commission_kop: number; // легаси-алиас (= earned)
  current_fee_percent: number; // C4: сколько % платишь сейчас
  fee_tier: string; // tier1|tier2|tier3|promo
  commission_min_kop: number; // пол комиссии
}

/** Кабинет курьера (GET /courier/me). */
export interface CourierMe {
  application: CourierApplication | null;
  profile: CourierProfile | null;
  statement: CourierStatement;
  rating: { avg: number | null; count: number };
  paused_until: string | null;
}

// ------------------------------- Онбординг -------------------------------
/** GET /courier/application — моя заявка ({application: null}, если не подавал). */
export function fetchCourierApplication(
  signal?: AbortSignal
): Promise<{ application: CourierApplication | null }> {
  return apiGet<{ application: CourierApplication | null }>("/courier/application", {
    signal,
  });
}

/** POST /courier/apply — подать заявку (transport ∈ car|cargo, селфи обязательно). */
export function applyCourier(body: {
  transport: CourierTransport;
  selfie_url: string;
}): Promise<CourierApplication> {
  return apiPost<CourierApplication>("/courier/apply", body);
}

// ------------------------------- На линии -------------------------------
/** POST /courier/online — выйти на линию с зоной (гейт курьера). */
export function courierOnline(body: {
  zone: CourierZone;
  work_city?: string;
  work_direction_id?: number | null;
}): Promise<CourierProfile> {
  return apiPost<CourierProfile>("/courier/online", body);
}

/** POST /courier/offline — уйти с линии. */
export function courierOffline(): Promise<CourierProfile> {
  return apiPost<CourierProfile>("/courier/offline", undefined);
}

/** GET /courier/available — доступные курьер-заказы (courier|buy_bring, БЕЗ телефона). */
export function fetchCourierAvailable(
  opts: { from_city?: string; to_city?: string } = {},
  signal?: AbortSignal
): Promise<Parcel[]> {
  const q = new URLSearchParams();
  if (opts.from_city) q.set("from_city", opts.from_city);
  if (opts.to_city) q.set("to_city", opts.to_city);
  const s = q.toString();
  return apiGet<Parcel[]>(`/courier/available${s ? `?${s}` : ""}`, { signal });
}

// ------------------------------- Кабинет -------------------------------
/** GET /courier/me — кабинет: заявка + профиль + statement + рейтинг + пауза. */
export function fetchCourierMe(signal?: AbortSignal): Promise<CourierMe> {
  return apiGet<CourierMe>("/courier/me", { signal });
}

/** «Купи и привези»: курьер вводит фактическую стоимость товара перед вручением. */
export function setGoodsCost(
  orderId: number,
  actual_kop: number
): Promise<{ id: number; settlement: ParcelSettlementLike }> {
  return apiPost(`/courier/orders/${orderId}/goods-cost`, { actual_kop });
}
type ParcelSettlementLike = Parcel["settlement"];

// ------------------------------- Оплата комиссии «на доверии» -------------------------------
export interface CommissionPayment {
  status: string; // pending | succeeded
  payment_id: number;
  amount_kop: number;
  amount: number; // ₽
  method: "sbp_manual" | "yookassa" | string;
  confirmation_url?: string; // yookassa
  payee?: { phone: string; bank: string; name: string }; // sbp_manual
}

/** POST /courier/pay-commission — оплатить накопленную комиссию (СБП «на доверии»/ЮKassa). */
export function payCourierCommission(): Promise<CommissionPayment> {
  return apiPost<CommissionPayment>("/courier/pay-commission", undefined);
}
