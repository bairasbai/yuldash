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
import { apiGet, apiPost, apiUpload } from "./client";

// ------------------------------- Типы -------------------------------
/** Движение посылки: created→accepted→in_transit→delivered (или canceled). */
export type ParcelStatus =
  | "created"
  | "accepted"
  | "in_transit"
  | "returning"
  | "delivered"
  | "returned"
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
  /**
   * Компенсация курьеру, если отменить сейчас. Курьер мог уже выехать и проехать
   * 40 км — за время и дорогу платят ему напрямую, мимо платформы.
   * preview — сколько это будет; cancel_fee_kop — сколько уже зафиксировано.
   * Сумму называем ДО решения: соглашаться на деньги вслепую человек не должен.
   */
  cancel_fee_kop?: number;
  cancel_fee_preview_kop?: number;
  /**
   * Кто отправитель — курьеру, когда контакты открыты. Гаснет вместе с телефонами
   * после закрытия доставки: адрес и номер не должны лежать в чужом телефоне вечно.
   */
  sender_name?: string;
  sender_phone?: string;
  /** Сколько раз курьер пытался вручить и почему повезли обратно. */
  delivery_attempts?: number;
  return_reason?: string;
  returned_at?: string | null;
  /** Из чего сложится компенсация за отмену: штраф + дорога курьера + его ожидание.
   *  Одно число человек читает как «нас обобрали» — со строками не спорят. */
  cancel_fee_parts?: {
    base_kop: number;
    pickup_kop: number;
    waiting_kop: number;
    total_kop: number;
  } | null;
  /** Возврат «получателя не было»: сколько отправитель вернёт курьеру за дорогу и ожидание.
   *  Ноль, пока курьер не отметил ни одной попытки вручения — за слова мы не платим. */
  return_fee_kop?: number;
  return_fee_parts?: {
    attempts: number;
    /** Заездов по просьбе отправителя, за которые платим. */
    redeliveries: number;
    route_kop: number;
    redeliver_kop: number;
    /** Во сколько обойдётся СЛЕДУЮЩИЙ заезд, если попросить его сейчас. Считает сервер:
     *  цена зависит от зоны, коэффициента и потолка. Ноль = предел исчерпан. */
    next_redeliver_kop: number;
    pickup_kop: number;
    waiting_kop: number;
    /** Потолок «не дороже самой доставки» и сколько он срезал — чтобы «сумма меньше
     *  слагаемых» не читалась как ошибка. */
    cap_kop: number;
    capped_kop: number;
    total_kop: number;
  } | null;
  /** Повторный заезд «получатель уже дома»: сколько попросили, сколько всего можно и
   *  открыта ли кнопка сейчас. Считает СЕРВЕР — у клиента нет ни попыток, ни предела. */
  redeliver_requests?: number;
  redeliver_max?: number;
  can_request_redelivery?: boolean;
  cod_amount_kop: number;
  commission_kop: number;
  price_kop: number; // цена доставки (courier/buy_bring); 0 для poputka
  settlement: ParcelSettlement | null;
  created_at: string | null;
  accepted_at: string | null;
  delivered_at: string | null;
  // «Что везём» — курьер решает по этим полям, браться или нет.
  weight_kg?: number;
  cargo_type?: string;
  fragile?: boolean;
  /** «Нужно не позже» (ГГГГ-ММ-ДД). null = не срочно. */
  deliver_by?: string | null;
  /** Срок вышел, а посылка ещё не вручена — считает сервер, клиент не гадает по часам. */
  overdue?: boolean;
  /** Точные адреса — открываются принявшему курьеру (у чужих их нет). */
  from_address?: string;
  to_address?: string;
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
  /**
   * «Куда именно» — дом, квартира, ориентир. В селе адрес чаще ориентир («у мечети»,
   * «синие ворота»), чем улица с табличкой, поэтому это свободный текст.
   */
  from_address?: string;
  to_address?: string;
  /**
   * Сколько отправитель платит за доставку, копейки. Без цены курьер видел маршрут и размер,
   * а за сколько везти — нигде: заявку просто не брали. 0 — тоже честный ответ,
   * «по-соседски, бесплатно», и так и подписано. Деньги идут напрямую.
   */
  price_kop?: number;
  /** Объявленная ценность, копейки. Ориентир при споре: без неё разбор всегда упирался
   *  в ветку «ценность не объявлена». Потолок 100 000 ₽. */
  declared_value_kop?: number;
  /** «Нужно доставить не позже» (ГГГГ-ММ-ДД). Пусто = «не срочно, когда получится». */
  deliver_by?: string | null;
  /** Вес, кг. Больше 100 — это уже не посылка между своими, а грузоперевозка. */
  weight_kg?: number;
  cargo_type?: string;
  /** Хрупкое — курьер повезёт аккуратнее и не поставит сверху ничего тяжёлого. */
  fragile?: boolean;
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

/**
 * POST /parcels/{id}/accept — стать курьером заявки (created→accepted, откроется телефон).
 *
 * pickupPhotoUrl — снимок «взял целой». Это первая граница ответственности: без него
 * спор «привёз битой» упирается в слово против слова. Сервер принимает только НАШ адрес
 * (/media, /secure/evidence) — чужой хост при открытии у оппонента слил бы его IP.
 */
export function acceptParcel(id: number, pickupPhotoUrl?: string): Promise<Parcel> {
  return apiPost<Parcel>(
    `/parcels/${id}/accept`,
    pickupPhotoUrl ? { pickup_photo_url: pickupPhotoUrl } : undefined
  );
}

/** Ответ на «Я на месте»: где курьер стоит и по каким правилам пошло ожидание. */
export interface ParcelArrived {
  ok: boolean;
  /** sender — у отправителя, receiver — у получателя. */
  where: "sender" | "receiver";
  waiting_started_at: string;
  wait_free_min: number;
  wait_fee_rub_per_min: number;
  waiting_fee_kop: number;
}

/**
 * POST /parcels/{id}/arrived — «Я на месте».
 *
 * Одна кнопка на ОБА конца: сервер сам понимает по статусу, у кого курьер стоит. С этой
 * минуты идёт платное ожидание по тем же правилам, что у такси — раньше курьер стоял
 * у двери сорок минут бесплатно.
 */
export function parcelArrived(id: number): Promise<ParcelArrived> {
  return apiPost<ParcelArrived>(`/parcels/${id}/arrived`, {});
}

/**
 * POST /parcels/{id}/status — двигать статус. delivered требует code вручения.
 * deliveryPhotoUrl — снимок «отдал целой», вторая граница ответственности.
 */
export function setParcelStatus(
  id: number,
  status: "in_transit" | "delivered",
  code = "",
  deliveryPhotoUrl?: string
): Promise<Parcel> {
  return apiPost<Parcel>(`/parcels/${id}/status`, {
    status,
    code,
    delivery_photo_url: deliveryPhotoUrl || undefined,
  });
}

/**
 * Фото-доказательство спора (POST /upload/evidence). Кладётся в ПРИВАТНУЮ область:
 * на снимке бывают лица и номера, поэтому отдаётся только участникам спора и админу,
 * а не публично, как фото в чате.
 */
export function uploadEvidence(file: File, signal?: AbortSignal): Promise<{ url: string }> {
  const form = new FormData();
  form.append("file", file);
  return apiUpload<{ url: string }>("/upload/evidence", form, { signal });
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

// ------------------------------- Когда доставка не сложилась -------------------------------
/**
 * «Приехал — никого нет дома», но посылка остаётся у курьера и он попробует ещё раз.
 * Раньше выбора не было: либо вручить (кому?), либо бросить заявку висеть.
 */
export function parcelAttemptFailed(id: number, reason = ""): Promise<Parcel> {
  return apiPost<Parcel>(`/parcels/${id}/attempt-failed`, { reason });
}

/**
 * Отправитель просит курьера заехать ещё раз: «получатель уже дома».
 *
 * Ступенька между «не застал» и возвратом. Раньше её не было: посылка либо чудом вручалась,
 * либо ехала обратно, и отправитель платил почти полную стоимость доставки за то, что
 * человека не оказалось дома. Заезд по просьбе оплачивается как половина маршрута — платит
 * тот, кто попросил (правило UPS, забранное себе).
 *
 * Открыта ли просьба прямо сейчас — решает сервер (`can_request_redelivery`).
 */
export function parcelRedeliverRequest(id: number, reason = ""): Promise<Parcel> {
  return apiPost<Parcel>(`/parcels/${id}/redeliver-request`, { reason });
}

/**
 * Курьер везёт посылку ОБРАТНО отправителю: получателя нет, отказался, не выходит на связь.
 * Комиссию за возврат платформа не берёт — услуга не оказана.
 */
export function parcelReturnStart(id: number, reason = ""): Promise<Parcel> {
  return apiPost<Parcel>(`/parcels/${id}/return-start`, { reason });
}

/** Курьер вернул посылку отправителю → заявка закрыта. */
export function parcelReturnDone(id: number): Promise<Parcel> {
  return apiPost<Parcel>(`/parcels/${id}/return-done`);
}

/** Типы спора по доставке (совпадают с backend `_PARCEL_INCIDENT_TYPES`). */
export type ParcelDisputeType =
  | "parcel_damage"
  | "parcel_lost"
  | "parcel_delay"
  | "recipient_absent"
  | "wrong_contents";

/**
 * Спор по доставке — через настоящую «Справедливость», а не урезанную жалобу:
 * обвинённый объясняется, решение приходит с причиной. Можно приложить фото «до/после».
 * Доступно отправителю и назначенному курьеру.
 */
export function parcelDispute(
  id: number,
  body: { reason: string; type?: ParcelDisputeType; evidence_urls?: string[] }
): Promise<{ incident_id?: number; id?: number }> {
  return apiPost(`/parcels/${id}/dispute`, {
    reason: body.reason,
    type: body.type ?? "",
    evidence_urls: body.evidence_urls?.length ? body.evidence_urls : undefined,
  });
}

// ------------------------------- Хелперы -------------------------------
/** Заявка «в работе» у курьера (можно двигать статус). */
export function isCarrying(s: ParcelStatus | string): boolean {
  return s === "accepted" || s === "in_transit";
}

// ================================================================
//  Курьер снимает себя с заказа + квитанция за доставку
//  (parcels.py: /parcels/{id}/release, /parcels/{id}/receipt).
//
//  В вебе не было ни того, ни другого: отказаться от взятого заказа
//  было нельзя вообще, а чек за доставку — единственный из трёх
//  (попутка, такси, доставка), которого веб не показывал
//  (сверка с Android, 2026-08-30).
// ================================================================

/**
 * «Не смогу везти» — посылка возвращается в общий список, отправителю уходит причина.
 *
 * Только пока коробка ещё не у курьера. Дальше это уже не отказ, а «уехал с чужой вещью»:
 * там работают возврат и спор. Сервер это проверяет сам и отвечает понятной подсказкой.
 */
export function releaseParcel(id: number, reason = ""): Promise<Parcel> {
  return apiPost<Parcel>(`/parcels/${id}/release`, { reason: reason.slice(0, 200) });
}

/**
 * Квитанция за доставку. Телефонов и адресов тут нет: чеком делятся, а адрес получателя —
 * это его дом.
 *
 * Деньги разделены по карманам: доставка отдельно, товар «купи и привези» отдельно.
 * Компенсации курьеру (дорога к посылке, зимняя дорога, ожидание) идут ему целиком,
 * комиссия с них не берётся.
 */
export interface ParcelReceipt {
  parcel_id: number;
  role: "courier" | "sender";
  status: ParcelStatus | string;
  from_city: string;
  to_city: string;
  delivery_type: string;
  created_at: string;
  delivered_at: string;
  returned_at: string;
  delivery_price_kop: number;
  goods_kop: number;
  total_kop: number;
  /** Сколько отправитель возвращает курьеру за товар, купленный на свои. */
  owed_to_courier_kop: number;
  amount: number; // ₽
  commission_kop: number;
  commission_paid: boolean;
  cancel_fee_kop: number;
  /** Возврат: за доставку не берём, но дорогу и ожидание курьера отправитель возвращает. */
  return_fee_kop: number;
  distance_km: number;
  delivery_attempts_final: number;
  redeliver_requests: number;
  settled: boolean;
  declared_value_kop: number;
  pickup_fee_kop: number;
  pickup_km: number;
  weather_fee_kop: number;
  weather_kind: string;
  night_k: number;
  /** Ожидание раздельно по концам: задерживают курьера разные люди. */
  waiting_sender_kop: number;
  waiting_receiver_kop: number;
  waiting_fee_kop: number;
  courier_name: string;
  courier_verified: boolean;
}

export function fetchParcelReceipt(id: number, signal?: AbortSignal): Promise<ParcelReceipt> {
  return apiGet<ParcelReceipt>(`/parcels/${id}/receipt`, { signal });
}
