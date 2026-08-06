// ================================================================
//  M3 — Посылки «между сёлами» (зеркало backend release:
//  routers/parcels.py). Отправитель создаёт заявку → любой попутчик
//  (poputka) видит её в /available (БЕЗ телефона) → принимает
//  (accept, теперь виден телефон) → двигает статус до delivered
//  по коду вручения, который получатель называет при передаче.
//
//  Приватность: телефон получателя и точная точка — только после
//  accept. Точный код вручения видит только отправитель (он его и
//  передаёт получателю вне приложения).
//
//  Эндпоинты /parcels/* уже есть на проде; курьер-часть (courier/
//  buy_bring) появится после мержа release → мягкая деградация 404.
// ================================================================
import { apiGet, apiPost } from "./client";

// ------------------------------- Типы -------------------------------
/** Движение посылки: created→accepted→in_transit→delivered (или canceled). */
export type ParcelStatus =
  | "created"
  | "accepted"
  | "in_transit"
  | "delivered"
  | "canceled";

/** Размер посылки (тариф на сервере). */
export type ParcelSize = "small" | "medium" | "large";

/** Тип доставки. poputka — «по пути» (бесплатно, без гейта);
 *  courier/buy_bring — профессиональный курьер (гейт _guard_courier). */
export type DeliveryType = "poputka" | "courier" | "buy_bring";

/** Срочность (курьер). */
export type ParcelUrgency = "bypath" | "now";

/** Публичная карточка курьера для отправителя (без ПДн — имя/рейтинг/телефон водителя). */
export interface ParcelCourier {
  id: number;
  name: string;
  rating: number | null; // null = ещё нет оценок
  rating_count: number;
  phone: string; // публичный контакт водителя по доставке
}

/** C2 «купи и привези»: расчёт с получателем (товар + доставка). */
export interface ParcelSettlement {
  goods_actual_kop: number; // 0 = курьер ещё не ввёл стоимость
  delivery_kop: number;
  total_due_kop: number;
  settled: boolean;
}

/** Заявка на доставку (сериализация _parcel_base + приватные поля по ролям). */
export interface Parcel {
  id: number;
  sender_id: number;
  courier_id: number | null;
  from_city: string;
  to_city: string;
  from_lat: number | null;
  from_lng: number | null;
  to_lat: number | null;
  to_lng: number | null;
  size: ParcelSize | string;
  description: string;
  receiver_name: string;
  fee_kop: number; // символический сбор платформы (poputka)
  status: ParcelStatus | string;
  delivery_type: DeliveryType | string;
  urgency: ParcelUrgency | string;
  declared_value_kop: number;
  cod_amount_kop: number;
  commission_kop: number;
  price_kop: number; // цена доставки (courier/buy_bring); 0 для poputka
  settlement: ParcelSettlement | null;
  created_at: string | null;
  accepted_at: string | null;
  delivered_at: string | null;
  // Приватные (по роли): отправитель видит confirm_code + receiver_phone (свои данные);
  // принявший курьер видит receiver_phone (после accept), но НЕ confirm_code.
  receiver_phone?: string;
  confirm_code?: string;
  courier?: ParcelCourier | null;
}

/** Тело POST /parcels (ParcelIn) — «по пути» доставка. */
export interface ParcelCreateInput {
  from_city: string;
  to_city: string;
  size: ParcelSize;
  description?: string;
  receiver_name: string;
  receiver_phone?: string;
  rules_accepted: boolean;
  from_lat?: number | null;
  from_lng?: number | null;
  to_lat?: number | null;
  to_lng?: number | null;
}

// ------------------------------- Отправитель -------------------------------
/** POST /parcels — создать заявку. Ответ: заявка + confirm_code (передаёшь получателю). */
export function createParcel(body: ParcelCreateInput): Promise<Parcel> {
  return apiPost<Parcel>("/parcels", body);
}

/** GET /parcels/mine — мои отправленные (все статусы, свежие сверху) + код + курьер. */
export function fetchMyParcels(signal?: AbortSignal): Promise<Parcel[]> {
  return apiGet<Parcel[]>("/parcels/mine", { signal });
}

/** POST /parcels/{id}/cancel — отменить свою заявку, пока не доставлена. */
export function cancelParcel(id: number): Promise<Parcel> {
  return apiPost<Parcel>(`/parcels/${id}/cancel`, undefined);
}

// ------------------------------- Курьер («по пути») -------------------------------
/** GET /parcels/available — открытые заявки «по пути» (БЕЗ телефона, coords размыты). */
export function fetchAvailableParcels(
  opts: { from_city?: string; to_city?: string } = {},
  signal?: AbortSignal
): Promise<Parcel[]> {
  const q = new URLSearchParams();
  if (opts.from_city) q.set("from_city", opts.from_city);
  if (opts.to_city) q.set("to_city", opts.to_city);
  const s = q.toString();
  return apiGet<Parcel[]>(`/parcels/available${s ? `?${s}` : ""}`, { signal });
}

/** POST /parcels/{id}/accept — стать курьером заявки (created→accepted, откроется телефон). */
export function acceptParcel(id: number): Promise<Parcel> {
  return apiPost<Parcel>(`/parcels/${id}/accept`, undefined);
}

/** POST /parcels/{id}/status — двигать статус. delivered требует code вручения. */
export function setParcelStatus(
  id: number,
  status: "in_transit" | "delivered",
  code = ""
): Promise<Parcel> {
  return apiPost<Parcel>(`/parcels/${id}/status`, { status, code });
}

/** GET /parcels/carrying — что я везу (accepted|in_transit), телефон получателя виден. */
export function fetchCarrying(signal?: AbortSignal): Promise<Parcel[]> {
  return apiGet<Parcel[]>("/parcels/carrying", { signal });
}

// ------------------------------- Взаимная оценка доставки -------------------------------
/** POST /parcels/{id}/rate — оценить вторую сторону после вручения (1..5 + опц. текст). */
export function rateParcel(
  id: number,
  stars: number,
  text = ""
): Promise<{ ratee_id: number; rating: number; count: number }> {
  return apiPost(`/parcels/${id}/rate`, { stars, text });
}

// ------------------------------- Хелперы -------------------------------
/** Заявка «в работе» у курьера (можно двигать статус). */
export function isCarrying(s: ParcelStatus | string): boolean {
  return s === "accepted" || s === "in_transit";
}
