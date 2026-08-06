// ================================================================
//  Такси-режим «Быстрый заказ» (зеркало backend release:
//  routers/instant.py + routers/taxi.py). Отдельный поток от попутки
//  (Ride/Booking) — тот не трогаем.
//
//  Приватность: телефоны сторон и точная точка подачи раскрываются
//  ТОЛЬКО после accept (unlocked). Координаты не логируем.
//
//  Эндпоинты instant/* и taxi/* появятся на проде после мержа
//  release-2026-07 → до этого 404/405 ловим мягкой деградацией.
// ================================================================
import { apiGet, apiPost } from "./client";

// ------------------------------- Статусы -------------------------------
/** InstantOrderStatus (models.py). scheduled — предзаказ «на время». */
export type InstantStatus =
  | "scheduled"
  | "created"
  | "searching"
  | "offered"
  | "accepted"
  | "arriving"
  | "onboard"
  | "done"
  | "cancelled"
  | "expired";

/** standard = Эконом, comfort = Комфорт. */
export type TaxiCategory = "standard" | "comfort";

// ------------------------------- Заказ -------------------------------
/** Витрина заказа (instant_service.order_payload). Приватные поля — только после accept. */
export interface InstantOrder {
  id: number;
  status: InstantStatus;
  role: "driver" | "passenger";
  from_lat: number | null;
  from_lng: number | null;
  to_lat: number | null;
  to_lng: number | null;
  from_text: string;
  to_text: string;
  category: TaxiCategory | string;
  price_estimate: number;
  price_final: number | null;
  surge_k: number;
  distance_km: number;
  eta_min: number;
  scheduled_at: string | null;
  driver_id: number | null;
  offer_expires_at: string | null;
  cancel_by: string | null;
  cancel_reason: string | null;
  contact_then_cancel: boolean;
  waiting_started_at: string | null;
  waiting_fee_kop: number;
  cancel_fee_kop: number;
  no_show: boolean;
  wait_free_min: number;
  wait_fee_rub_per_min: number;
  no_show_at: string | null;
  cancel_fee_now_kop: number;
  // Пассажир глазами водителя (в оффере и активном заказе).
  passenger_rating: number | null;
  passenger_trips: number;
  // Раскрывается только после accept:
  driver_name: string;
  driver_car: string;
  driver_verified: boolean;
  driver_rating: number;
  driver_phone: string;
  passenger_name: string;
  passenger_phone: string;
}

/** Тело оценки/заказа — сервер считает цену сам, клиенту не верит. */
export interface EstimateInput {
  from_lat: number;
  from_lng: number;
  to_lat: number;
  to_lng: number;
  from_text?: string;
  to_text?: string;
  category?: TaxiCategory;
}

/** Ответ оценки (instant_service.estimate). */
export interface EstimateResult {
  price: number;
  distance_km: number;
  eta_min: number;
  zone: string;
  category: TaxiCategory | string;
  tariff_id: number;
  surge_k: number;
  surge_note: { ru: string; ba: string } | null;
  /** Цены обоих классов одним запросом — пассажир выбирает с открытыми глазами. */
  options: { category: TaxiCategory | string; price: number }[];
}

// ------------------------------- Доступность (гейт города) -------------------------------
export interface TaxiAvailability {
  enabled: boolean;
  reason: "global_off" | "city_off" | "ok" | string;
  message: { ru: string; ba: string };
  city: string | null;
}

/** GET /instant/availability — доступно ли такси в точке (глобальный флаг + города). */
export function fetchTaxiAvailability(
  lat?: number,
  lng?: number,
  signal?: AbortSignal
): Promise<TaxiAvailability> {
  const q = new URLSearchParams();
  if (lat != null) q.set("lat", String(lat));
  if (lng != null) q.set("lng", String(lng));
  const s = q.toString();
  return apiGet<TaxiAvailability>(`/instant/availability${s ? `?${s}` : ""}`, { signal });
}

// ------------------------------- Пассажир -------------------------------
/** POST /instant/estimate — оценка цены ДО заказа. */
export function instantEstimate(body: EstimateInput): Promise<EstimateResult> {
  return apiPost<EstimateResult>("/instant/estimate", body);
}

/** POST /instant/orders — вызвать машину сейчас (created→searching→offered|expired). */
export function createInstantOrder(body: EstimateInput): Promise<InstantOrder> {
  return apiPost<InstantOrder>("/instant/orders", body);
}

/** POST /instant/schedule — предзаказ «на время» (статус scheduled). */
export function createScheduledOrder(
  body: EstimateInput & { scheduled_at: string }
): Promise<InstantOrder> {
  return apiPost<InstantOrder>("/instant/schedule", body);
}

export interface ScheduledResponse {
  scheduled: InstantOrder[];
  activated: InstantOrder[];
}

/** GET /instant/scheduled — мои предзаказы (+ленивая авто-активация наступивших). */
export function fetchScheduled(signal?: AbortSignal): Promise<ScheduledResponse> {
  return apiGet<ScheduledResponse>("/instant/scheduled", { signal });
}

/** POST /instant/scheduled/{id}/activate — «Начать поиск сейчас». */
export function activateScheduled(id: number): Promise<InstantOrder> {
  return apiPost<InstantOrder>(`/instant/scheduled/${id}/activate`, undefined);
}

/** POST /instant/scheduled/{id}/cancel — отменить предзаказ (до поиска, без штрафа). */
export function cancelScheduled(id: number): Promise<InstantOrder> {
  return apiPost<InstantOrder>(`/instant/scheduled/${id}/cancel`, undefined);
}

/** GET /instant/orders/mine — заказы пассажира (свежие сверху). */
export function fetchMyOrders(limit = 20, signal?: AbortSignal): Promise<InstantOrder[]> {
  return apiGet<InstantOrder[]>(`/instant/orders/mine?limit=${limit}`, { signal });
}

/** GET /instant/orders/{id} — детали заказа (обе стороны + кандидат-оффер). */
export function fetchInstantOrder(id: number, signal?: AbortSignal): Promise<InstantOrder> {
  return apiGet<InstantOrder>(`/instant/orders/${id}`, { signal });
}

/** POST /instant/orders/{id}/cancel — отмена (обе стороны). reason опционален. */
export function cancelInstantOrder(id: number, reason = ""): Promise<InstantOrder> {
  return apiPost<InstantOrder>(`/instant/orders/${id}/cancel`, { reason });
}

/** POST /instant/orders/{id}/rate — оценить вторую сторону завершённого заказа (1..5). */
export function rateInstantOrder(
  id: number,
  stars: number
): Promise<{ ratee_id: number; rating: number; count: number }> {
  return apiPost(`/instant/orders/${id}/rate`, { stars });
}

// ------------------------------- Водитель -------------------------------
/** POST /instant/presence — heartbeat координат «на линии» → Redis GEO. */
export interface PresenceResult {
  ok: boolean;
  ttl_sec: number;
  shift_seconds_online: number;
  shift_remaining_sec: number;
}
export function sendPresence(lat: number, lng: number): Promise<PresenceResult> {
  return apiPost<PresenceResult>("/instant/presence", { lat, lng });
}

/** GET /instant/driver/offer — активный оффер для водителя (поллинг-фолбэк к пушу). */
export function fetchDriverOffer(signal?: AbortSignal): Promise<{ offer: InstantOrder | null }> {
  return apiGet<{ offer: InstantOrder | null }>("/instant/driver/offer", { signal });
}

export function acceptOrder(id: number): Promise<InstantOrder> {
  return apiPost<InstantOrder>(`/instant/orders/${id}/accept`, undefined);
}
export function declineOrder(id: number): Promise<InstantOrder> {
  return apiPost<InstantOrder>(`/instant/orders/${id}/decline`, undefined);
}
export function arrivedOrder(id: number): Promise<InstantOrder> {
  return apiPost<InstantOrder>(`/instant/orders/${id}/arrived`, undefined);
}
export function onboardOrder(id: number): Promise<InstantOrder> {
  return apiPost<InstantOrder>(`/instant/orders/${id}/onboard`, undefined);
}
export function doneOrder(id: number): Promise<InstantOrder> {
  return apiPost<InstantOrder>(`/instant/orders/${id}/done`, undefined);
}

/** GET /instant/nearby-drivers — анонимные свободные машины рядом (для карты пассажира). */
export interface NearbyDriver {
  lat: number;
  lng: number;
  eta_min: number;
}
export function fetchNearbyDrivers(
  lat: number,
  lng: number,
  signal?: AbortSignal
): Promise<{ drivers: NearbyDriver[] }> {
  return apiGet<{ drivers: NearbyDriver[] }>(
    `/instant/nearby-drivers?lat=${lat}&lng=${lng}`,
    { signal }
  );
}

// ------------------------------- Онбординг таксиста (580-ФЗ) -------------------------------
export type TaxiAppStatus = "pending" | "approved" | "rejected" | string;

/** GET /taxi/application (заявка таксиста). Не подавал → 404. */
export interface TaxiApplication {
  id: number;
  status: TaxiAppStatus;
  inn: string;
  permit_number: string;
  permit_photo_url: string;
  osago_url: string;
  selfie_url: string;
  criminal_record_url: string;
  birth_date: string; // YYYY-MM-DD
  license_since_year: number;
  comment: string; // причина отказа
  created_at: string;
  reviewed_at: string | null;
}

/** Тело POST /taxi/apply (TaxiApplyIn). */
export interface TaxiApplyInput {
  inn: string;
  permit_number: string;
  birth_date: string; // YYYY-MM-DD
  license_since_year: number;
  permit_photo_url?: string;
  osago_url?: string;
  selfie_url?: string;
  criminal_record_url?: string;
  car_class?: "economy" | "comfort";
}

export function fetchTaxiApplication(signal?: AbortSignal): Promise<TaxiApplication> {
  return apiGet<TaxiApplication>("/taxi/application", { signal });
}

export function applyTaxi(body: TaxiApplyInput): Promise<TaxiApplication> {
  return apiPost<TaxiApplication>("/taxi/apply", body);
}

// ------------------------------- Хелперы UI -------------------------------
/** Активный заказ пассажира — тот, что ещё «живой» (для восстановления экрана). */
export const ACTIVE_PASSENGER_STATUSES: InstantStatus[] = [
  "created",
  "searching",
  "offered",
  "accepted",
  "arriving",
  "onboard",
];

/** Раскрыты ли контакты (телефоны/точная точка) — после accept и до завершения. */
export function isUnlocked(s: InstantStatus): boolean {
  return s === "accepted" || s === "arriving" || s === "onboard";
}

// ------------------------------- Карта спроса (водителю) -------------------------------
/** Анонимная тепловая зона «где сейчас ищут такси» (~1 км сетка, без личностей). */
export interface DemandZone {
  lat: number;
  lng: number;
  weight: number; // 0..1 относительно самой горячей зоны
  requests: number; // активных поисков в зоне
}

export interface DemandMap {
  zones: DemandZone[];
  updated_at: string;
}

/** GET /instant/demand?city= — только одобренный таксист (403 → скрыть блок).
 *  До деплоя release отдаёт 404 → мягкая деградация. */
export function fetchDemand(city?: string, signal?: AbortSignal): Promise<DemandMap> {
  const q = city ? `?city=${encodeURIComponent(city)}` : "";
  return apiGet<DemandMap>(`/instant/demand${q}`, { signal });
}
