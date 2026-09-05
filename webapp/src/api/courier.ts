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
/**
 * Заявка «стать курьером».
 *
 * Имя, госномер и согласие с правилами сервер пока принимает мягко (флагом
 * `courier_identity_required`), но спрашивать их нужно с самого начала: мы
 * доверяем человеку чужую посылку, и знать о нём хотя бы столько же, сколько
 * о попутчике, — минимум приличия. По госномеру его узнают у подъезда.
 */
export function applyCourier(body: {
  transport: CourierTransport;
  selfie_url: string;
  full_name?: string;
  car_plate?: string;
  rules_accepted?: boolean;
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

// ------------------------------- Заработок курьера -------------------------------
/** День в разрезе заработка (суммы — в КОПЕЙКАХ, в отличие от водительского /driver/earnings). */
export interface CourierEarningsDay {
  date: string; // YYYY-MM-DD
  net_kop: number;
  deliveries: number;
}

/** GET /courier/earnings?period=week|month|all — «чистыми» = цена минус комиссия. */
export interface CourierEarnings {
  period: string;
  net_kop: number;
  commission_kop: number;
  deliveries: number;
  by_day: CourierEarningsDay[];
}

export function fetchCourierEarnings(
  period: "week" | "month" | "all" = "week",
  signal?: AbortSignal
): Promise<CourierEarnings> {
  return apiGet<CourierEarnings>(`/courier/earnings?period=${period}`, { signal });
}

// ----------------------------- ⭐ Приоритет исполнителя -----------------------------
/** Одна строка приоритета: за что дали (или сняли) баллы и с какой цифрой. */
export interface PriorityPart {
  code: string;
  points: number;
  value: number;
}

/**
 * Приоритет: кому заказ достаётся первым (backend/app/priority.py).
 *
 * Показываем ЦЕЛИКОМ и всегда: скрытый приоритет человек читает как «заказы раздают
 * по блату» — это ровно та боль Яндекса, против которой мы строимся.
 */
export interface Priority {
  kind: "taxi" | "courier";
  points: number;
  plus: number;
  minus: number;
  max_points: number;
  parts: PriorityPart[];
  rules: PriorityPart[];
  /** Курьер: через сколько секунд заказ увидят остальные. 0 = видишь сразу. */
  feed_delay_sec?: number;
}

/** GET /driver/priority — мой приоритет водителя. */
export function fetchDriverPriority(signal?: AbortSignal): Promise<Priority> {
  return apiGet<Priority>("/driver/priority", { signal });
}

/** GET /courier/priority — мой приоритет курьера (роли считаются раздельно). */
export function fetchCourierPriority(signal?: AbortSignal): Promise<Priority> {
  return apiGet<Priority>("/courier/priority", { signal });
}

// ================================================================
//  Заказать курьера: оценка цены и создание заказа (courier.py:
//  GET /courier/estimate, POST /courier/orders).
//
//  В вебе «Посылки» умели только попутную отправку — платного курьера
//  нельзя было ни оценить, ни заказать (сверка с Android, 2026-08-30).
// ================================================================

/** Размер посылки: цена растёт ступенями, а не по весу — так понятнее человеку. */
export type ParcelSize = "small" | "medium" | "large";
/** bypath — «по пути, когда получится», now — «нужно сейчас» (дороже). */
export type CourierUrgency = "bypath" | "now";
/** courier — просто отвезти, buy_bring — «купи и привези» (курьер платит своими). */
export type DeliveryType = "courier" | "buy_bring";

/**
 * Оценка доставки. Всё в копейках — деньги на клиенте не пересчитываем.
 *
 * Комиссия здесь ОРИЕНТИРОВОЧНАЯ: точный процент зависит от стажа курьера, который заказ
 * ещё не взял. Дорога курьера к посылке — тоже: пока его нет, честного числа не существует,
 * поэтому `pickup_pending` и потолок `pickup_max_kop`.
 */
export interface CourierEstimate {
  price_kop: number;
  commission_kop: number;
  distance_km: number;
  zone: "city" | "intercity" | string;
  delivery_kop: number;
  pickup_kop: number;
  pickup_pending: boolean;
  pickup_max_kop: number;
  /** Зимняя дорога: гололёд не разбирает, человек в машине или коробка. */
  weather_kop: number;
  weather_kind: string;
  night_k: number;
  /** На сколько секунд цена закреплена, пока человек думает. */
  price_locked_sec?: number;
  /**
   * Комиссия здесь ОРИЕНТИРОВОЧНАЯ: точный процент зависит от стажа курьера,
   * который заказ ещё не взял. Финал считается при вручении.
   */
  commission_estimated?: boolean;
  /**
   * Во сколько обойдётся возврат, если получателя не найдут.
   *
   * Не украшение: Конституционный суд признал недопустимым брать плату за возврат
   * с человека, которого о ней заранее не предупредили. Без этой строки наша
   * компенсация курьеру юридически висит в воздухе.
   */
  return_fee_estimate_kop?: number;
  breakdown?: Record<string, number | string | boolean>;
}

export function estimateCourier(
  q: {
    from_lat: number;
    from_lng: number;
    to_lat: number;
    to_lng: number;
    size?: ParcelSize;
    urgency?: CourierUrgency;
    delivery_type?: DeliveryType;
  },
  signal?: AbortSignal
): Promise<CourierEstimate> {
  const s = new URLSearchParams({
    from_lat: String(q.from_lat),
    from_lng: String(q.from_lng),
    to_lat: String(q.to_lat),
    to_lng: String(q.to_lng),
    size: q.size ?? "small",
    urgency: q.urgency ?? "bypath",
    delivery_type: q.delivery_type ?? "courier",
  });
  return apiGet<CourierEstimate>(`/courier/estimate?${s}`, { signal });
}

/** Тело POST /courier/orders (CourierOrderIn). Цену считает сервер — из клиента её не берут. */
export interface CourierOrderInput {
  from_city?: string;
  to_city?: string;
  from_address?: string;
  to_address?: string;
  from_lat?: number | null;
  from_lng?: number | null;
  to_lat?: number | null;
  to_lng?: number | null;
  size?: ParcelSize;
  description?: string;
  receiver_name?: string;
  receiver_phone?: string;
  rules_accepted?: boolean;
  delivery_type?: DeliveryType;
  urgency?: CourierUrgency;
  /** Объявленная ценность — по ней считают возмещение при утрате. */
  declared_value_kop?: number;
  /** «Купи и привези»: сколько курьер потратит своими и получит на руки. Обязательно. */
  cod_amount_kop?: number;
  shopping_list?: string;
  /** «Не позже этого дня» (ГГГГ-ММ-ДД). Пусто = «когда получится». */
  deliver_by?: string | null;
  weight_kg?: number;
  cargo_type?: string;
  fragile?: boolean;
}

/**
 * Создать заказ курьера. В ответ приходит заявка и код подтверждения:
 * отправитель передаёт его получателю, курьер без кода вещь не отдаст.
 */
export function createCourierOrder(
  body: CourierOrderInput
): Promise<{ id: number; confirm_code?: string; [k: string]: unknown }> {
  return apiPost(`/courier/orders`, body);
}

/**
 * Заказчик поднимает сумму, на которую согласен: «в аптеке дороже — ладно, бери».
 *
 * Без этой двери отказ курьеру («сумма выше согласованной») превращался в тупик:
 * товар уже куплен на его деньги, провести расчёт нельзя, и оба висят. Поднимать
 * может ТОЛЬКО заказчик и только вверх — курьер сумму своего же счёта не двигает.
 *
 * Правила держит сервер: до вручения, только «купи и привези», не выше потолка.
 */
export function raiseCourierBudget(
  parcelId: number,
  codAmountKop: number
): Promise<{ ok?: boolean; cod_amount_kop?: number }> {
  return apiPost(`/courier/orders/${parcelId}/raise-budget`, {
    cod_amount_kop: Math.max(0, Math.round(codAmountKop)),
  });
}
