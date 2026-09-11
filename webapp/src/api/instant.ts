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
  /** Серверная ставка водителя из instant_service.order_payload; старый API может не прислать. */
  driver_fee_percent?: number;
  /**
   * Из чего сложилась сумма: поездка + дорога водителя к пассажиру (2026-08-23).
   * Пассажиру — чтобы видеть, за что платит; водителю — чтобы видеть, что компенсация
   * за подачу дошла до него целиком (комиссию с неё не берём).
   */
  ride_price?: number;
  pickup_fee_kop?: number;
  pickup_km?: number;
  pickup_pending?: boolean;
  /** Водителю было по пути → подача вдвое дешевле. */
  pickup_enroute?: boolean;
  /** Опции салона деньгами: уходят водителю целиком, без комиссии. */
  options_fee_kop?: number;
  /** Зимняя дорога — тоже его деньги, без комиссии. */
  weather_fee_kop?: number;
  weather_kind?: string;
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
  /** Сколько раз заказ возвращался в поиск после того, как назначенный водитель отменил.
   *  Экрану поиска это нужно, чтобы объяснить, куда делась принятая машина: без строки
   *  человек видит просто «ищем машину» и решает, что приложение сбросило заказ. */
  reassigns?: number;
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
  /** Чем рассчитываются: cash | sbp | negotiate. Видно ОБЕИМ сторонам. */
  payment_method?: string;
  /** Пассажир сменил расчёт после принятия заказа; водитель должен подтвердить «Понял». */
  payment_changed?: boolean;
  /** Подтверждение задержалось — пассажиру нельзя обещать, что водитель уже увидел смену. */
  payment_ack_overdue?: boolean;

  // --- смена адреса уже в поездке (аддитивно: старый сервер полей не шлёт) ---
  /** Сколько раз меняли адрес — объясняет в чеке, почему цена не та, что при заказе. */
  destination_changes?: number;
  /** Водитель подтвердил, что видел новый адрес. */
  destination_ack?: boolean;
  /** Прошла минута, а «Понял» так и не нажал → пассажиру честно говорим «позвони». */
  destination_ack_overdue?: boolean;
  /** Крупная смена (межгород / тройная цена) ждёт слова водителя. null = ничего не ждём. */
  pending_destination?: {
    to_text: string;
    price: number;
    reason: string;
  } | null;
  /** Водитель завершил поездку досрочно и почему — пассажир видит причину словами. */
  early_finish_reason?: string;

  // --- остановки по пути ---
  /** Точки, куда заезжаем. `done` = уже проехали, трогать нельзя. */
  stops?: TaxiStop[];
  /** Водитель нажал «Стоим»: счётчик ожидания на остановке идёт. */
  standing?: boolean;

  /** Из чего сложилась бы платная отмена ПРЯМО СЕЙЧАС (показываем ДО тапа). */
  cancel_fee_parts?: Record<string, number>;
  /** С какого момента идёт текущий круг поиска (перезапуск из очереди его сдвигает). */
  searching_at?: string | null;
  /** Очередь «рядом никого»: до какого времени ждём машину. null = не ждём. */
  wait_until?: string | null;

  /** Госномер: у подъезда две белые «Лады», и сверить нечем. Только после accept. */
  driver_plate?: string;
  /** Доверие пассажиру о водителе (только ему и только после accept). */
  driver_avatar?: string;
  driver_trips?: number;
  driver_since?: string;
  driver_from?: string;

  /** Забыл вещь: пока не истекло — чат заказа снова открыт на запись. */
  lost_item_until?: string | null;
  /** Пассажир уже сказал «рәхмәт» — второй раз не предлагаем. */
  thanked?: boolean;
  /**
   * Заказ сделан ДЛЯ ДРУГОГО человека: сын из Уфы вызвал такси маме в Баймаке.
   * Водителю это надо знать до подачи — у ворот его ждёт не тот, с кем он говорил
   * в приложении, и звонить надо по телефону из карточки, а не заказчику.
   */
  for_other?: boolean;
}

/** Остановка по пути (waypoints_json на сервере). Координаты, а не название: по ним считают цену. */
export interface TaxiStop {
  lat: number;
  lng: number;
  text: string;
  /** Проеденную остановку убрать нельзя — спорить о том, что позади, не о чем. */
  done?: boolean;
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
  /**
   * Время подачи ПРЕДЗАКАЗА (ISO-UTC). Пусто = цена «на сейчас».
   *
   * Без него экран считал цену на сейчас, а предзаказ оформлялся по цене на время подачи:
   * заказ на пять утра, сделанный днём, показывал дневную ставку и уезжал по ночной.
   */
  scheduled_at?: string;
  /**
   * Опции салона: детское кресло по группе, бустер, коляска, собака-проводник, животное,
   * большой багаж. Это НЕ класс машины: кресло возить может любая. Фильтр жёсткий —
   * машине без кресла такой заказ не предложат вообще, в этом и смысл галочки.
   */
  options?: string[];
  /** Круговой рейс: отвезти, подождать и привезти обратно. Только межгород. */
  round_trip?: boolean;
  /** Сколько водитель ждёт на месте, минут. 0 = обычная поездка в одну сторону. */
  return_wait_min?: number;
  /** Остановки по пути: A → точки → B. Не больше трёх. */
  waypoints?: { lat: number; lng: number; text?: string }[];
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
  /** Чем рассчитаются: cash | sbp | negotiate. Пусто → «договоримся на месте». */
  payment_method?: string;
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
  /**
   * Дальняя подача — ОТДЕЛЬНАЯ строка счёта в рублях (2026-08-23), а не коэффициент:
   * `price` = `ride_price` + `pickup_fee`. Эти деньги идут водителю за дорогу к пассажиру,
   * комиссию с них не берём. Старый сервер полей не шлёт → нули, строка не появится.
   */
  ride_price?: number;
  pickup_fee?: number;
  pickup_km?: number;
  /** Рядом машин нет: точной суммы не существует, обещаем потолок и фиксируем при accept. */
  pickup_pending?: boolean;
  pickup_max_rub?: number;
  pickup_note?: { ru: string; ba: string } | null;
  /** Водителю и так по пути → подача вдвое дешевле. `pickup_full_fee` — цена без скидки. */
  pickup_enroute?: boolean;
  pickup_full_fee?: number;
  /** «Сюда уже едет машина — подождёшь, и подача выйдет дешевле». null = ждать нечего. */
  pickup_wait_hint?: { minutes: number; save_rub: number; ru: string; ba: string } | null;
  /**
   * Опции салона деньгами (детское кресло 150 ₽, животное и большой багаж по 100 ₽).
   * Уходят водителю целиком, комиссия с них не берётся. Доступность (инвалидная коляска,
   * собака-проводник) — всегда 0 ₽.
   *
   * ⚠️ В веб-версии выбора опций пока НЕТ — поле придёт нулём. Оставлено для чека и для
   * будущего экрана: считать цену без него значит показать сумму, которой в заказе не будет.
   */
  options_fee?: number;
  /** На сколько секунд цена закреплена: пока человек думает, она не вырастет. 0 = выключено. */
  price_locked_sec?: number;
  option_catalog?: { code: string; price: number }[];
  /**
   * Круговой рейс: водитель везёт, ждёт на месте и возвращает обратно.
   *
   * Только межгород — в городе порожняка нет, и скидке взяться неоткуда. Обратная дорога
   * дешевле, потому что второй конец достаётся водителю без нового поиска пассажира.
   * `round_trip_price` — цена уже вместе с подачей; `null` = такой поездки тут не бывает.
   */
  round_trip_available?: boolean;
  round_trip_price?: number | null;
  round_trip_discount_percent?: number;
  /** Дольше водитель ждать не может — у него смена. */
  round_trip_max_wait_hours?: number;
  round_trip?: boolean;
  /** Сколько остановок учтено в цене — человек должен видеть, за что платит. */
  waypoints_count?: number;
  /**
   * Из чего сложилась цена: маршрут по дорогам или оценка, пробки, спрос, дальняя
   * подача, зимняя дорога, опции салона. Тексты приходят готовыми на двух языках —
   * пороги и формулы живут на сервере, клиент их не пересказывает.
   */
  price_factors?: import("../components/PriceFactors").PriceFactor[];
  /**
   * Зимняя дорога: компенсация водителю за гололёд, метель, сильный снег или мороз —
   * 1,5 ₽/км, потолок 15% от поездки. Вне наценки и без комиссии: зимой у него реально
   * выше расход и износ. `weather_kind` нужен подписи — «Гололёд» объясняет, «погода» нет.
   */
  weather_fee?: number;
  weather_kind?: string;
  weather_note?: { ru: string; ba: string } | null;
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
export function fetchDriverOffer(signal?: AbortSignal): Promise<{ offer: InstantOrder | null; blocked?: string | null }> {
  return apiGet<{ offer: InstantOrder | null; blocked?: string | null }>("/instant/driver/offer", { signal });
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
  /** Что ответил государственный реестр такси (580-ФЗ). Три состояния, различать обязательно:
   *  не спрашивали / реестр молчал (`checked=false`) — не показываем ничего, человек не виноват
   *  в нашем таймауте; подтверждено; разрешения нет — тогда показываем путь получить. */
  permit_registry_checked?: boolean;
  permit_registry_ok?: boolean;
  permit_registry_until?: string | null;
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
  /**
   * ⚠️ Устарело: класс машина больше НЕ заявляет, его считает классификатор по
   * характеристикам ниже. Поле оставлено ради старых клиентов, сервер его игнорирует.
   */
  car_class?: "economy" | "comfort";

  /**
   * Характеристики машины для классификатора.
   *
   * Без года выпуска доступен ТОЛЬКО Эконом — каким бы новым ни был автомобиль:
   * классификатор не угадывает, он считает. Поэтому спрашиваем прямо при подаче,
   * а не оставляем человека навсегда в базовом классе.
   *
   * Модератор потом сверит это с фото и документами: заявленное «есть кондиционер»
   * само по себе класс не открывает.
   */
  car_year?: number | null;
  seats?: number | null;
  car_color?: string;
  car_ac?: boolean;
  car_sedan?: boolean;
  car_leather?: boolean;
  car_light_salon?: boolean;
  /** Опции салона (детское кресло, коляска, животные) — коды из car_class.py. */
  car_options?: string[];
  /** Какие классы водитель готов брать. Пусто = все доступные ему. */
  car_classes_enabled?: string[];
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
  /**
   * Сколько километров до зоны от текущего места водителя. Поля может не быть:
   * позиции ещё нет — и тогда «зона рядом» честнее выдуманных километров.
   */
  dist_km?: number;
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
  tenure_days: number; // справка «сколько с нами», лесенку НЕ двигает
  trips_done: number; // завершённых поездок — позиция на лесенке комиссии
  fee_tiers: number[]; // ступени [3, 8, 15]
  fee_tier_trips: number[]; // границы ступеней в поездках [30, 100]
  fee_next_percent: number | null; // следующая ставка (null = верхняя ступень ИЛИ идёт промо)
  fee_trips_to_next: number | null; // сколько поездок до неё
  /** Промо запуска «первым водителям — 0%». Оно идёт по КАЛЕНДАРЮ, а лесенка выше — по
   *  поездкам: две разные шкалы. Пока `promo_active`, ставку двигает срок, и лесенка молчит
   *  (оба поля выше null); после промо водитель попадёт на `fee_after_promo_percent` —
   *  СВОЮ ступень по числу поездок, а не на следующую. */
  promo_active?: boolean;
  promo_days_left?: number | null;
  fee_after_promo_percent?: number | null;
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
  /**
   * Из чего сложилась сумма (2026-08-23). Раньше в чеке была одна цифра, и на вопрос
   * «куда делись деньги» ответить было нечем. Старый сервер полей не шлёт → нули.
   */
  ride_price?: number;
  ride_base_price?: number;
  surge_rub?: number;
  pickup_fee_kop?: number;
  pickup_km?: number;
  pickup_enroute?: boolean;
  options_fee_kop?: number;
  weather_fee_kop?: number;
  weather_kind?: string;
  /**
   * Только для водителя: он реально платит комиссию, поэтому видит её целиком.
   * Пассажиру эти поля НЕ приходят — в Модели А он платит водителю напрямую, наши 15%
   * через него не проходят, и строка «комиссия платформы» была бы неправдой о его деньгах.
   */
  driver_fee_percent?: number;
  driver_fee_kop?: number;
  driver_gross_kop?: number;
  driver_net_kop?: number;
  commission_free_kop?: number;
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

// ================================================================
//  Поездка в пути: то, что происходит ПОСЛЕ «машина найдена».
//  Зеркало instant.py (wait / im-coming / destination / waypoints /
//  stop / payment) и safety.py (stuck / winter-check по заказу).
//
//  До этой волны веб умел только создать заказ и отменить его:
//  всё, что случается между посадкой и высадкой, было доступно
//  лишь в приложении (сверка с Android, 2026-08-30).
// ================================================================

/** Ответ «подожду машину» (POST /instant/orders/{id}/wait). */
export interface WaitResult {
  ok: boolean;
  wait_until: string;
  wait_minutes: number;
  order?: InstantOrder;
}

/**
 * «Подожду машину» после «рядом никого».
 *
 * В райцентре ночью на линии две-три машины, и обе заняты — это норма, а не сбой.
 * Заказ встаёт в очередь, фоновый воркер продолжает искать и пушит, когда найдётся.
 */
export function waitForDriver(orderId: number): Promise<WaitResult> {
  return apiPost<WaitResult>(`/instant/orders/${orderId}/wait`);
}

/**
 * «Уже выхожу» — пассажир спускается, водитель это видит.
 *
 * Денег не меняет: таймер ожидания идёт как шёл. Это сообщение, а не сделка, —
 * иначе кнопкой начали бы отматывать платное ожидание.
 */
export function imComing(orderId: number): Promise<{ ok: boolean }> {
  return apiPost<{ ok: boolean }>(`/instant/orders/${orderId}/im-coming`);
}

/** Пересчёт при смене адреса или остановок (общий ответ destination/waypoints). */
export interface DestinationQuote {
  ok?: boolean;
  /** Новая цена целиком: проеденное + остаток до новой точки. */
  price: number;
  /** Сколько было до смены — чтобы показать «станет 480 ₽ вместо 320 ₽». */
  old_price: number;
  driven_km: number;
  rest_km: number;
  distance_km: number;
  /** Межгород или цена выросла втрое → сначала спрашиваем водителя. */
  needs_driver_ok: boolean;
  /** Почему спрашиваем: intercity | price_jump. */
  ask_reason?: string;
  /** Адрес уже поменялся (false у превью и когда ждём водителя). */
  applied: boolean;
  /** Предложение ушло водителю, ждём его слова. */
  waiting_driver?: boolean;
  order?: InstantOrder;
}

/** Посчитать смену адреса, ничего не меняя: человек видит цену ДО согласия. */
export function previewDestination(
  orderId: number,
  to: { lat: number; lng: number; text?: string }
): Promise<DestinationQuote> {
  return apiPost<DestinationQuote>(`/instant/orders/${orderId}/destination`, {
    to_lat: to.lat,
    to_lng: to.lng,
    to_text: (to.text ?? "").slice(0, 200),
    preview: true,
  });
}

/**
 * Сменить адрес назначения. Цену считает сервер — из клиента она не принимается.
 *
 * Сеть отвалилась → ошибка наружу, экран честно говорит «не получилось». Откладывать
 * «на потом» нельзя: человек будет уверен, что адрес сменился, а машина поедет по старому.
 */
export function changeDestination(
  orderId: number,
  to: { lat: number; lng: number; text?: string }
): Promise<DestinationQuote> {
  return apiPost<DestinationQuote>(`/instant/orders/${orderId}/destination`, {
    to_lat: to.lat,
    to_lng: to.lng,
    to_text: (to.text ?? "").slice(0, 200),
    preview: false,
  });
}

/** Водитель: «Понял, вижу новый адрес». Снимает с пассажира тревогу «а он вообще знает?». */
export function ackDestination(orderId: number): Promise<{ ok: boolean; order?: InstantOrder }> {
  return apiPost(`/instant/orders/${orderId}/destination/ack`);
}

/** Водитель согласился на крупную смену (межгород / тройная цена). */
export function acceptDestination(orderId: number): Promise<{ ok: boolean; order?: InstantOrder }> {
  return apiPost(`/instant/orders/${orderId}/destination/accept`);
}

/** Почему водитель не может ехать по новому адресу. */
export type DeclineDestinationReason = "shift_end" | "out_of_zone" | "no_fuel" | "other";

/**
 * Водитель не может ехать дальше.
 *
 * Ждали согласия на крупную смену → поездка продолжается по СТАРОМУ адресу.
 * Иначе поездка ЗАВЕРШАЕТСЯ там, где стоит машина: километры проеханы, деньги за них
 * причитаются. Это не отмена — работа сделана.
 */
export function declineDestination(
  orderId: number,
  reason: DeclineDestinationReason = "other"
): Promise<{ ok: boolean; kept_old_destination?: boolean; finished_early?: boolean; order?: InstantOrder }> {
  return apiPost(`/instant/orders/${orderId}/destination/decline`, { reason });
}

/**
 * Заменить набор остановок уже в поездке.
 *
 * Проеденные сервер сохранит сам — их не передаём и убрать нельзя. Порядок задаётся
 * порядком точек; переставлять на ходу нельзя (водитель уже едет к первой).
 */
export function setWaypoints(
  orderId: number,
  stops: { lat: number; lng: number; text?: string }[]
): Promise<DestinationQuote> {
  return apiPost<DestinationQuote>(`/instant/orders/${orderId}/waypoints`, {
    waypoints: stops.slice(0, 3).map((s) => ({
      lat: s.lat,
      lng: s.lng,
      text: (s.text ?? "").slice(0, 200),
    })),
  });
}

/**
 * Водитель отмечает «Стоим» на остановке и «Поехали», когда тронулся.
 *
 * Кнопкой, а не автоматом по координатам: машина в пробке у светофора рядом с остановкой
 * начала бы «зарабатывать» сама, а разбираться пришлось бы пассажиру.
 */
export function toggleStop(
  orderId: number
): Promise<{ ok: boolean; standing: boolean; order?: InstantOrder }> {
  return apiPost(`/instant/orders/${orderId}/stop`);
}

/** Способы расчёта, которые сервер принимает. Карты и счёт заведены, но выключены. */
export type PaymentMethod = "cash" | "sbp" | "negotiate";

/**
 * Пассажир меняет способ расчёта — до самого конца поездки.
 *
 * Про наличные человек вспоминает ровно тогда, когда лезет в карман, то есть уже сидя
 * в машине. Водителю уходит уведомление: тихая подмена договорённости хуже, чем её смена.
 */
export function setPaymentMethod(
  orderId: number,
  method: PaymentMethod
): Promise<{ payment_method: string; changed?: boolean }> {
  return apiPost(`/instant/orders/${orderId}/payment`, { method });
}

/** Водитель подтверждает, что увидел изменившийся способ расчёта. */
export function ackPaymentMethod(orderId: number): Promise<{ ok: boolean }> {
  return apiPost(`/instant/orders/${orderId}/payment/ack`);
}

// ------------------------------- «Что-то не так с ценой» -------------------------------
/**
 * Жалоба на цену (POST /instant/price-complaint). Заказ необязателен: жалуются чаще
 * на ОЦЕНКУ до поездки, чем на завершённую. Координат в теле нет и быть не должно —
 * уходят только строки счёта, как их видел человек.
 */
export interface PriceComplaintInput {
  order_id?: number | null;
  kind?: "taxi" | "courier";
  price?: number;
  reason?: string;
  comment?: string;
  breakdown?: Record<string, number | string>;
}

export function sendPriceComplaint(
  body: PriceComplaintInput
): Promise<{ ok?: boolean; id?: number }> {
  return apiPost(`/instant/price-complaint`, {
    order_id: body.order_id ?? null,
    kind: body.kind ?? "taxi",
    price: body.price ?? 0,
    reason: (body.reason ?? "other").slice(0, 32),
    comment: (body.comment ?? "").slice(0, 500),
    breakdown: body.breakdown ?? {},
  });
}

// ================================================================
//  Классы машин водителя (taxi.py: GET/POST /taxi/classes).
//
//  Класс машина ЗАСЛУЖИВАЕТ, а не заявляет: характеристики ставит
//  модератор, а водитель только включает то, что ему уже доступно.
//  В вебе экрана не было совсем — таксист с сайта не мог ни узнать,
//  чего не хватает до Комфорта, ни включить Минивэн
//  (сверка с Android, 2026-08-30).
// ================================================================

/** Класс машины. Категория заказа та же, кроме исторического economy → standard. */
export type CarClass = "economy" | "comfort" | "business" | "minivan";

/**
 * Чего машине не хватает до класса — КОДАМИ. Подписи живут в клиенте на двух языках:
 * сервер не должен решать, как это звучит по-башкирски.
 */
export type CarClassMissing =
  | "clean_salon"
  | "year_unknown"
  | "too_old"
  | "no_ac"
  | "body"
  | "few_seats"
  | "not_sedan"
  | "color_business"
  | "no_leather"
  | "not_verified_premium"
  | "too_many_seats"
  | "unknown_class";

/** Опции салона. Это НЕ класс: кресло возит машина любого класса. */
export type CarOption =
  | "seat_0_1"
  | "seat_1_4"
  | "seat_4_7"
  | "booster"
  | "wheelchair"
  | "guide_dog"
  | "stroller"
  | "pets"
  | "big_luggage"
  | "charger";

/** Один класс в витрине водителя. */
export interface CarClassRow {
  car_class: CarClass | string;
  category: string;
  /** Машина проходит по требованиям. */
  available: boolean;
  /** Водитель сам включил этот класс. */
  enabled: boolean;
  missing: (CarClassMissing | string)[];
  /**
   * Сколько водителей класса уже набралось в районе и сколько нужно, чтобы он открылся.
   * Не статистика ради статистики: видя «не хватает одного», человек зовёт знакомого —
   * и класс открывается им обоим.
   */
  drivers_have: number;
  drivers_need: number;
  open: boolean;
  /** Он будет первым в районе — это стоит сказать вслух. */
  first: boolean;
}

export interface DriverClasses {
  /** Район, по которому считается набор водителей. */
  place: string;
  classes: CarClassRow[];
  options: (CarOption | string)[];
  all_options: (CarOption | string)[];
  /** Что модератор знает о машине. Водитель это не правит — иначе классы обходятся полем. */
  car: {
    year: number | null;
    seats: number | null;
    color: string | null;
    ac: boolean;
    sedan: boolean;
    leather: boolean;
    light_salon: boolean;
    premium: boolean;
    clean: boolean;
    body_ok: boolean;
    color_ok: boolean;
  };
}

export function fetchDriverClasses(signal?: AbortSignal): Promise<DriverClasses> {
  return apiGet<DriverClasses>("/taxi/classes", { signal });
}

/**
 * Включить классы и отметить опции салона. Без пере-подачи заявки: возить кресло
 * человек начинает в среду, а не в день модерации. Включить можно только доступное —
 * лишнее сервер отфильтрует молча.
 */
export function saveDriverClasses(body: {
  car_classes_enabled?: (CarClass | string)[] | null;
  car_options?: (CarOption | string)[] | null;
}): Promise<DriverClasses> {
  return apiPost<DriverClasses>("/taxi/classes", {
    car_classes_enabled: body.car_classes_enabled ?? null,
    car_options: body.car_options ?? null,
  });
}

// ================================================================
//  Лист ожидания раннего доступа (waitlist.py: POST /waitlist).
//
//  Такси включается по городам: пока в районе нет машин, запускать
//  сервис честнее не «наполовину», а никак. Но человеку, который
//  открыл экран и увидел «пока не работает», надо оставить дверь:
//  один номер — и мы позовём, когда включим.
//
//  Ручка ПУБЛИЧНАЯ: сюда шлют и лендинг, и приложение до входа.
//  В вебе кнопки не было — экран «такси скоро» заканчивался ничем
//  (сверка с Android, 2026-08-30).
// ================================================================

/** Кем человек хочет быть, когда такси включат. Водителям — свой ответ и своя очередь. */
export type WaitlistRole = "passenger" | "driver";

export function joinWaitlist(body: {
  phone: string;
  city?: string;
  role?: WaitlistRole;
}): Promise<{ ok?: boolean }> {
  return apiPost(
    "/waitlist",
    {
      phone: body.phone.replace(/[\s\-()]/g, "").slice(0, 32),
      city: (body.city ?? "").trim().slice(0, 80) || null,
      role: body.role ?? "passenger",
    },
    // Публичная ручка: человек ещё не вошёл, и требовать вход ради записи в очередь —
    // ровно тот барьер, из-за которого он и уйдёт.
    { auth: false }
  );
}
