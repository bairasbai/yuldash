// ================================================================
//  «Скидки по пути» + кабинет партнёра (зеркало backend/app/routers/coupons.py).
//
//  Пассажир (витрина):
//    GET  /coupons?city=&route=        — активные купоны рядом (без входа)
//    GET  /coupons/{id}                — деталь купона
//    POST /coupons/{id}/activate       — забронировать погашение → короткий КОД
//    GET  /my/coupons                  — мои купоны (активные коды + история)
//
//  Партнёр («Мой бизнес»):
//    GET  /partner/plans               — тарифы подписки (прайс)
//    GET  /partner/me                  — мой бизнес + подписка + выписка
//    POST /partner                     — зарегистрировать бизнес
//    POST /partner/{id}                — правка бизнеса
//    POST /partner/subscribe           — оплатить подписку (СБП «на доверии»)
//    GET  /partner/coupons             — мои купоны (все статусы)
//    POST /partner/coupons             — создать купон (draft)
//    POST /partner/coupons/{id}        — правка купона
//    POST /partner/coupons/{id}/status — сменить статус
//    POST /coupons/redeem              — бизнес гасит код у себя
//
//  Часть эндпоинтов появится на проде после мержа release → мягкая деградация.
// ================================================================
import { apiGet, apiPost } from "./client";

// ---------- Публичная витрина ----------

export interface CouponPartner {
  id: number;
  name: string;
  category: string;
  city: string;
  address: string;
  lat: number | null;
  lng: number | null;
  phone: string;
}

export interface Coupon {
  id: number;
  partner: CouponPartner | null;
  title: string;
  description: string;
  discount_text: string;
  city: string;
  route_hint: string[];
  valid_from: string | null;
  valid_until: string | null;
  limit_total: number;
  limit_per_user: number;
  redeemed_count: number;
  remaining: number | null; // null = без общего лимита
  premium: boolean;
  status: string;
}

/** Активация/бронь купона (POST /coupons/{id}/activate, GET /my/coupons). */
export interface CouponActivation {
  code: string;
  status: string; // reserved | redeemed | canceled | expired
  reserved_at?: string | null;
  redeemed_at?: string | null;
  coupon: Coupon | null;
}

export function fetchCoupons(
  opts?: { city?: string; route?: string; signal?: AbortSignal }
): Promise<Coupon[]> {
  const p = new URLSearchParams();
  if (opts?.city) p.set("city", opts.city);
  if (opts?.route) p.set("route", opts.route);
  const qs = p.toString();
  return apiGet<Coupon[]>(`/coupons${qs ? `?${qs}` : ""}`, {
    auth: false,
    signal: opts?.signal,
  });
}

export function fetchCoupon(id: number, signal?: AbortSignal): Promise<Coupon> {
  return apiGet<Coupon>(`/coupons/${id}`, { auth: false, signal });
}

export function activateCoupon(id: number): Promise<CouponActivation> {
  return apiPost<CouponActivation>(`/coupons/${id}/activate`);
}

export function fetchMyCoupons(signal?: AbortSignal): Promise<CouponActivation[]> {
  return apiGet<CouponActivation[]>("/my/coupons", { signal });
}

// ---------- Кабинет партнёра ----------

export interface PartnerPlan {
  code: string;
  title: string;
  title_ba: string;
  amount_kop: number;
  period_days: number;
  premium: boolean;
}

export interface Partner {
  id: number;
  name: string;
  category: string;
  city: string;
  address: string;
  phone: string;
  description: string;
  lat: number | null;
  lng: number | null;
  status: "pending" | "active" | "rejected" | "paused" | "archived" | string;
  reject_reason: string;
  subscription_plan: string;
  subscription_until: string | null;
  subscription_active: boolean;
  has_premium: boolean;
  created_at: string | null;
}

export interface PartnerStatement {
  redeemed_total: number;
  fee_per_redemption_kop: number;
  amount_kop: number;
}

export interface PartnerMe {
  partner: Partner | null;
  statement?: PartnerStatement;
}

export interface PartnerCoupon {
  id: number;
  partner_id: number;
  title: string;
  description: string;
  discount_text: string;
  city: string;
  route_hint: string[];
  valid_from: string | null;
  valid_until: string | null;
  limit_total: number;
  limit_per_user: number;
  redeemed_count: number;
  activations: number; // reserved + redeemed
  premium: boolean;
  status: string; // draft | active | paused | archived
  created_at: string | null;
}

export interface PartnerIn {
  name: string;
  category?: string;
  city: string;
  address?: string;
  phone?: string;
  description?: string;
  lat?: number | null;
  lng?: number | null;
}

export interface CouponIn {
  title: string;
  description?: string;
  discount_text?: string;
  city?: string;
  route_hint?: string;
  valid_from?: string | null;
  valid_until?: string | null;
  limit_total?: number;
  limit_per_user?: number;
  premium?: boolean;
}

export function fetchPartnerPlans(signal?: AbortSignal): Promise<PartnerPlan[]> {
  return apiGet<PartnerPlan[]>("/partner/plans", { auth: false, signal });
}

export function fetchPartnerMe(signal?: AbortSignal): Promise<PartnerMe> {
  return apiGet<PartnerMe>("/partner/me", { signal });
}

export function registerPartner(body: PartnerIn): Promise<Partner> {
  return apiPost<Partner>("/partner", body);
}

export function updatePartner(id: number, body: PartnerIn): Promise<Partner> {
  return apiPost<Partner>(`/partner/${id}`, body);
}

/** Оплата подписки бизнеса (СБП «на доверии», подтверждает админ). */
export interface SubscribeResult {
  payment_id: number;
  amount_kop: number;
  plan: string;
  status: string; // pending
}

export function subscribePartner(plan: string): Promise<SubscribeResult> {
  return apiPost<SubscribeResult>("/partner/subscribe", { plan });
}

export function fetchPartnerCoupons(signal?: AbortSignal): Promise<PartnerCoupon[]> {
  return apiGet<PartnerCoupon[]>("/partner/coupons", { signal });
}

export function createPartnerCoupon(body: CouponIn): Promise<PartnerCoupon> {
  return apiPost<PartnerCoupon>("/partner/coupons", body);
}

export function updatePartnerCoupon(
  id: number,
  body: CouponIn
): Promise<PartnerCoupon> {
  return apiPost<PartnerCoupon>(`/partner/coupons/${id}`, body);
}

export function setPartnerCouponStatus(
  id: number,
  status: string
): Promise<PartnerCoupon> {
  return apiPost<PartnerCoupon>(`/partner/coupons/${id}/status`, { status });
}

/** Бизнес гасит код клиента у себя. Ответ: имя держателя (без телефона/гео). */
export interface RedeemResult {
  ok: boolean;
  coupon_title: string;
  discount_text: string;
  customer_name: string;
}

export function redeemCoupon(code: string): Promise<RedeemResult> {
  return apiPost<RedeemResult>("/coupons/redeem", { code });
}
