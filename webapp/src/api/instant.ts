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
  /** Сколько пассажир реально платит, копейки (цена минус промокод). Точка правды для истории. */
  passenger_price_kop: number;
  promo_discount_kop: number;
  created_at: string | null;
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
  /**
   * Заказ искали только среди женщин за рулём. Нужно на экране «никого рядом»:
   * человек имеет право знать, что машин нет ИЗ-ЗА фильтра, а не потому,
   * что приложение сломалось.
   */
  women_only?: boolean;
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

/**
 * Что уходит вместе с заказом, сверх маршрута (сервер: OrderIn).
 *
 * Комментарий и подъезд — потому что в селе «Ленина 12» это пять домов без табличек,
 * а чат открывается только ПОСЛЕ принятия заказа: до этого сказать водителю нечего.
 *
 * for_name / for_phone — заказ ДЛЯ ДРУГОГО: сын из Уфы вызывает такси маме в Баймак.
 * Без них водитель звонил заказчику в другой город, а мама стояла у ворот.
 *
 * women_only — жёсткий фильтр: подсунуть мужчину значит обмануть в том, ради чего
 * галочку и ставили. Никого не нашлось — заказ честно истекает.
 */
export interface OrderExtras {
  comment?: string;
  entrance?: string;
  for_name?: string;
  for_phone?: string;
  women_only?: boolean;
}

export type OrderInput = EstimateInput & OrderExtras;

/** Ответ оценки (instant_service.estimate). */
export interface EstimateResult {
  price: number;
  /** Цена без наценок — «база». По ней видно, что именно добавила наценка. */
  base_price: number;
  distance_km: number;
  eta_min: number;
  pickup_eta_min?: number;
  zone: string;
  category: TaxiCategory | string;
  tariff_id: number;
  surge_k: number;
  surge_note: { ru: string; ba: string } | null;
  /** Ночь, погода, подача — из чего складывается итоговый коэффициент. */
  night?: boolean;
  night_k?: number;
  night_note?: { ru: string; ba: string } | null;
  pickup_k?: number;
  weather_k?: number;
  weather_code?: string;
  /** Итоговый множитель (спрос × ночь × погода × подача) — его и показываем человеку. */
  dynamic_k?: number;
  /** Потолок наценки: выше него цена не поднимется ни при каком спросе. */
  pricing_cap_k?: number;
  /** Цены обоих классов одним запросом — пассажир выбирает с открытыми глазами. */
  options: { category: TaxiCategory | string; price: number; base_price?: number }[];
  /**
   * Скидка по промокоду. Сервер применяет её сам — вводить ничего не нужно,
   * поля приходят всегда (ноль = скидки нет, а не «не пришло»).
   * Разницу платит Юлдаш из своей комиссии: водитель получает столько же.
   */
  promo_code?: string;
  promo_discount_kop?: number;
  price_with_discount?: number;
  promo_note?: { ru: string; ba: string } | null;
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
export function createInstantOrder(body: OrderInput): Promise<InstantOrder> {
  return apiPost<InstantOrder>("/instant/orders", body);
}

/** POST /instant/schedule — предзаказ «на время» (статус scheduled). */
export function createScheduledOrder(
  body: OrderInput & { scheduled_at: string }
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
/**
 * Водитель не взял заказ. Причина необязательна, но её стоит спросить ПОСЛЕ отказа:
 * без причины платформа видит только «не берут» и продолжает слать те же заказы тем же
 * людям. С причиной видно, что чинить — далеко подавать, мало денег, не по пути.
 * Это диагностика подбора, а не наказание: за отказ ничего не бывает.
 */
export type DeclineReason = "far" | "cheap" | "direction" | "busy" | "break" | "other";

export function declineOrder(id: number, reason?: DeclineReason): Promise<InstantOrder> {
  return apiPost<InstantOrder>(
    `/instant/orders/${id}/decline`,
    reason ? { reason } : undefined
  );
}

/** Подписи причин. Порядок неслучаен: сверху то, что называют чаще всего. */
export const DECLINE_REASONS: { key: DeclineReason; ru: string; ba: string }[] = [
  { key: "far", ru: "Далеко подавать", ba: "Килергә алыҫ" },
  { key: "cheap", ru: "Мало денег", ba: "Аҡса аҙ" },
  { key: "direction", ru: "Не по пути", ba: "Юл ыңғайы түгел" },
  { key: "busy", ru: "Уже занят", ba: "Мәшғүлмен" },
  { key: "break", ru: "Перерыв", ba: "Тәнәфес" },
  { key: "other", ru: "Другое", ba: "Башҡаһы" },
];
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
  permit_photo_url: string | null;
  osago_url: string | null;
  selfie_url: string | null;
  criminal_record_url: string | null;
  birth_date: string; // YYYY-MM-DD
  license_since_year: number;
  comment: string | null; // причина отказа
  created_at: string;
  reviewed_at: string | null;
  // --- сроки документов (_doc_dates на сервере; клиент даты сам не считает) ---
  osago_until: string | null;
  permit_until: string | null;
  inspection_until: string | null;
  docs_expired: boolean; // хоть один срок вышел → допуск снят
  docs_missing: string[]; // какие сроки ещё не заполнены
  docs_days_left: number | null; // до ближайшего истечения; отрицательное = просрочен
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

// --------------------- «В твоём классе никого нет» — альтернативы ---------------------
/**
 * Что предложить, если в выбранном классе машин нет. Молчаливой подмены класса у нас нет:
 * решение всегда за пассажиром, он видит цену ДО согласия и заплатит ровно её.
 * Пустой список = предлагать нечего, честно ждём дальше.
 */
export interface FallbackOption {
  category: string; // standard | comfort | business | minivan
  price: number; // ₽ — цена в этом классе
  price_diff: number; // на сколько дороже/дешевле текущего
}

export interface AlternativesOut {
  after_sec: number; // через сколько секунд поиска показывать предложение
  options: FallbackOption[];
}

export function fetchAlternatives(
  orderId: number,
  signal?: AbortSignal
): Promise<AlternativesOut> {
  return apiGet<AlternativesOut>(`/instant/orders/${orderId}/alternatives`, { signal });
}

/** Пассажир согласился искать и в соседнем классе — цена пересчитывается вниз и фиксируется. */
export function addAlternative(orderId: number, category: string): Promise<InstantOrder> {
  return apiPost<InstantOrder>(`/instant/orders/${orderId}/alternatives`, { category });
}

// --------------------- Смена водителя (лимит часов + дашборд) ---------------------
/**
 * GET /instant/workday — сколько водитель уже на линии, сколько осталось до перерыва,
 * плюс дашборд за сегодня. Лимит смены — не бюрократия: уставший водитель за рулём
 * опаснее пустого заказа.
 */
export interface Workday {
  day: string; // YYYY-MM-DD (местный)
  seconds_online: number;
  limit_sec: number;
  remaining_sec: number;
  limit_hours: number;
  blocked: boolean; // лимит исчерпан → нужен отдых
  unlock_at: string | null;
  return_ride_used: boolean; // «один попутчик домой» уже использован
  // --- дашборд за сегодня (debt.driver_dashboard) ---
  earnings_today: number; // ₽
  gross_today_kop: number;
  fee_today_kop: number;
  net_today_kop: number;
  orders_today: number;
  fee_percent: number;
  tenure_days: number;
}

export function fetchWorkday(signal?: AbortSignal): Promise<Workday> {
  return apiGet<Workday>("/instant/workday", { signal });
}

// ------------------------------- Чек за такси -------------------------------
/** GET /instant/orders/{id}/receipt. Телефонов в чеке нет — только факт поездки. */
export interface TaxiReceipt {
  order_id: number;
  role: "driver" | "passenger";
  from_text: string;
  to_text: string;
  done_at: string;
  distance_km: number | null;
  amount: number; // ₽, реально заплаченное (цена минус промокод)
  amount_kop: number;
  price_kop: number; // цена до скидки
  promo_discount_kop: number;
  waiting_fee_kop: number;
  payment_method: string;
  paid: boolean;
  driver_name: string;
  driver_verified: boolean;
}

/** 409 = поездка ещё не завершена, 403 = чужой заказ, 404 = нет заказа/эндпоинта. */
export function fetchTaxiReceipt(orderId: number, signal?: AbortSignal): Promise<TaxiReceipt> {
  return apiGet<TaxiReceipt>(`/instant/orders/${orderId}/receipt`, { signal });
}

/** Водитель подтверждает, что получил наличные (пассажир мог уйти, не отметив оплату). */
export function markCashReceived(
  orderId: number
): Promise<{ status: string; method?: string; amount_kop?: number }> {
  return apiPost(`/instant/orders/${orderId}/cash-received`);
}

/** «Забыл вещь в машине» — открывает чат заказа на запись ещё на 48 часов (обе стороны). */
export function reportLostItem(orderId: number): Promise<{ ok?: boolean; until?: string }> {
  return apiPost(`/instant/orders/${orderId}/lost-item`);
}

// ------------------------- Предрейсовая готовность (580-ФЗ) -------------------------
/** GET /taxi/pretrip — подтвердил ли водитель готовность на сегодня. */
export interface PretripState {
  required: boolean; // выключено на сервере → экран честно говорит «не обязательно»
  confirmed: boolean;
  day: string; // YYYY-MM-DD (локальный день)
  confirmed_at: string | null;
  note: string;
}

export function fetchPretrip(signal?: AbortSignal): Promise<PretripState> {
  return apiGet<PretripState>("/taxi/pretrip", { signal });
}

/** Все три пункта обязательны — «частично готов» это не готов (правило сервера). */
export function confirmPretrip(body: {
  health_ok: boolean;
  car_ok: boolean;
  no_alcohol: boolean;
  note?: string;
}): Promise<PretripState> {
  return apiPost<PretripState>("/taxi/pretrip", body);
}

// ------------------------- Сроки документов (без пере-подачи) -------------------------
/** Тело POST /taxi/documents (TaxiDocsIn) — частичное обновление, пустые поля не трогаем. */
export interface TaxiDocsInput {
  osago_until?: string | null; // YYYY-MM-DD
  permit_until?: string | null;
  inspection_until?: string | null;
  osago_url?: string;
  permit_photo_url?: string;
}

/** Продлил ОСАГО — сказал системе, не сбрасывая статус заявки в «на проверке». */
export function updateTaxiDocuments(body: TaxiDocsInput): Promise<TaxiApplication> {
  return apiPost<TaxiApplication>("/taxi/documents", body);
}

// ------------------------------- Зона работы таксиста -------------------------------
/**
 * Где водитель готов брать заказы (зеркало GET/POST /instant/zone).
 *
 * Как «Мой район» у Яндекс Про, но бесплатно: в базовом режиме ОБЕ точки заказа
 * внутри зоны, выход за неё — только по тумблеру. Без этого выбора заказы сыплются
 * отовсюду, и водитель читает каждый вручную — именно это и выжигает людей.
 *
 * Влияет только на такси. Попутка (плановые поездки) зоной не ограничивается.
 */
export interface WorkZone {
  work_zone: "city" | "district" | null; // база: мой город/село или весь район
  work_city: string | null;
  work_district: string | null;
  work_intercity: boolean; // готов на выезд загород
  work_regions: boolean; // и в соседние регионы (только вместе с загородом)
  work_direction_id: number | null; // «только в сторону …», необязательно
  work_direction?: { id: number; name_ru: string; name_ba: string } | null;
}

export interface WorkZoneInput {
  zone: "city" | "district";
  work_city?: string | null;
  work_district?: string | null;
  work_intercity?: boolean;
  work_regions?: boolean;
  work_direction_id?: number | null;
}

export function fetchWorkZone(signal?: AbortSignal): Promise<WorkZone> {
  return apiGet<WorkZone>("/instant/zone", { signal });
}

export function saveWorkZone(body: WorkZoneInput): Promise<WorkZone> {
  return apiPost<WorkZone>("/instant/zone", {
    work_zone: body.zone,
    work_city: body.work_city ?? null,
    work_district: body.work_district ?? null,
    work_intercity: body.work_intercity ?? false,
    work_regions: body.work_regions ?? false,
    work_direction_id: body.work_direction_id ?? null,
  });
}
