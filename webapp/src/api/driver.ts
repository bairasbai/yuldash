// ================================================================
//  Водительский слой API (зеркало backend release):
//  drivers.py (online/status/verify/upload/public), rides.py
//  (/driver/rides, /rides), driver_schedule.py, instant.py
//  (/driver/earnings). Точные пути/поля — из этих роутеров.
// ================================================================
import { apiGet, apiPost, apiDelete, apiUpload } from "./client";
import type { Ride, RideCategory } from "./rides";

// ----------------------------- Публикация поездки -----------------------------
/** Тело POST /rides (RideIn). Оставлены поля, нужные вебу; остальное — дефолты бэка. */
export interface RideCreateInput {
  from_city: string;
  to_city: string;
  depart_at: string; // ISO
  seats_total?: number; // 1..8 (клампит бэк)
  price?: number; // ₽, 0..100000 (клампит бэк)
  category?: RideCategory;
  comment?: string;
  pickup?: string;
  /**
   * Ориентир из справочника города («у мечети», «автовокзал»). Сервер сам подставит
   * по нему название и координаты — тогда пассажир увидит точку на карте, а не
   * строку текста, и справочник заодно узнает, что этим ориентиром пользуются.
   */
  pickup_point_id?: number | null;
  pickup_lat?: number | null;
  pickup_lng?: number | null;
  pets_allowed?: boolean;
  child_seat?: boolean;
  women_only?: boolean;
  baggage?: boolean;
  air_conditioner?: boolean;
  non_smoking?: boolean; // маппится клиентом в smoking=false (бэк поле smoking)
  smoking?: boolean;
  quiet?: boolean;
  only_trusted?: boolean;
  /** Не беру пассажиров младше 18 без взрослого рядом. */
  no_minors?: boolean;
  recurrence?: string; // none | daily | weekdays | weekly
  /**
   * Поездка «везу посылку»: кому отдать и что за груз.
   *
   * Только для категорий parcel и cargo. Без этих двух строк объявление «еду в Уфу,
   * возьму посылку» бесполезно: отправитель не знает, влезет ли его коробка, а водитель
   * — кому её вручить на том конце.
   */
  receiver_name?: string;
  parcel_size?: string;
  /** Остановки по пути одной строкой через « | »: A → точки → B. */
  waypoints?: string;
  partner_id?: number | null; // клиника-назначение (category=hospital)
}

/** POST /rides → созданная поездка (Ride). RequireAuth. */
export function publishRide(body: RideCreateInput): Promise<Ride> {
  return apiPost<Ride>("/rides", body);
}

// ----------------------------- Онлайн-статус / профиль -----------------------------
/** GET /driver/status — сводка своего водительского профиля (только для себя). */
export interface DriverStatus {
  docs_status: "none" | "pending" | "verified" | "rejected" | string;
  verified: boolean;
  car_make: string;
  car_model: string;
  car_color: string;
  car_plate: string;
  seats: number;
  license_url: string;
  car_photo_url: string;
  online: boolean;
  gender: string;
  autocheck_result: string;
  autocheck_score: number;
  autocheck_data: string;
}

export function fetchDriverStatus(signal?: AbortSignal): Promise<DriverStatus> {
  return apiGet<DriverStatus>("/driver/status", { signal });
}

/** POST /driver/online {online} → DriverProfile (нам важен online). */
export function setDriverOnline(online: boolean): Promise<{ online: boolean }> {
  return apiPost<{ online: boolean }>("/driver/online", { online });
}

/**
 * POST /driver/gender — водитель по желанию указывает пол.
 *
 * Это ЗАЯВКА, а не подтверждение: бейдж «женщина за рулём» и женские заказы
 * такси включает модератор, сверив с фото прав. Иначе любой мог бы назваться
 * женщиной и попасть в выдачу — а этот фильтр женщины включают ради безопасности.
 *
 * Наружу раскрывается только полезный сигнал «female»; «male» и пустое
 * значение никому не показываются.
 */
export function setDriverGender(gender: "female" | "male" | ""): Promise<DriverStatus> {
  return apiPost<DriverStatus>("/driver/gender", { gender });
}

// ----------------------------- Мои поездки / архив -----------------------------
export type DriverRidesStatus = "active" | "all" | "done" | "cancelled";

/** GET /driver/rides?status= — поездки водителя (RideOut[]). */
export function fetchDriverRides(
  status: DriverRidesStatus = "active",
  signal?: AbortSignal
): Promise<Ride[]> {
  const q = status && status !== "active" ? `?status=${status}` : "";
  return apiGet<Ride[]>(`/driver/rides${q}`, { signal });
}

/**
 * Водитель двигает статус брони: «выехал» → «подъезжаю» → «завершил».
 *
 * Закрывает тревогу ожидания: пассажир видит, что за ним уже едут, а не гадает.
 * «done» водитель тоже может нажать сам — раньше закрыть бронь мог ТОЛЬКО пассажир,
 * и забытая им кнопка держала места поездки занятыми.
 *
 * 409 = поездка ещё не началась (завершить можно после времени выезда).
 */
export type DriverPhase = "departed" | "arriving" | "done";

export function setDriverStatus(
  bookingId: number,
  status: DriverPhase
): Promise<{ ok?: boolean; status?: string }> {
  return apiPost(`/bookings/${bookingId}/driver-status`, { status });
}

/** Завершить весь рейс (все брони разом) — POST /rides/{id}/complete. */
export function completeRide(rideId: number): Promise<Ride> {
  return apiPost<Ride>(`/rides/${rideId}/complete`);
}

// ----------------------------- Подсказка цены -----------------------------
/**
 * GET /rides/price_hint?from_city=&to_city= — ориентир, а не правило.
 * `avg` — средняя цена прошлых поездок по маршруту, `fuel_estimate_kop` — честная
 * оценка бензина на весь путь. `distance_km`/`fuel_estimate_kop` = null, если координаты
 * городов неизвестны (без краша — просто не показываем).
 */
export interface PriceHint {
  avg: number; // ₽
  count: number; // сколько прошлых поездок в основе
  distance_km: number | null;
  fuel_estimate_kop: number | null;
}

export function fetchPriceHint(
  fromCity: string,
  toCity: string,
  signal?: AbortSignal
): Promise<PriceHint> {
  const p = new URLSearchParams();
  if (fromCity.trim()) p.set("from_city", fromCity.trim());
  if (toCity.trim()) p.set("to_city", toCity.trim());
  return apiGet<PriceHint>(`/rides/price_hint?${p.toString()}`, { signal });
}

// ----------------------------- Публичный профиль -----------------------------
export interface PublicReview {
  author: string;
  stars: number;
  text: string;
  created_at: string;
}

/** GET /drivers/{id}/public — публичная витрина водителя (без ПДн). */
export interface DriverPublic {
  id: number;
  name: string;
  avatar_url: string;
  verified: boolean;
  joined_at: string;
  days_in_service: number;
  trips_count: number;
  car: string;
  rating?: number | null;
  rating_count: number;
  reviews: PublicReview[];
}

export function fetchDriverPublic(
  id: number,
  signal?: AbortSignal
): Promise<DriverPublic> {
  return apiGet<DriverPublic>(`/drivers/${id}/public`, { auth: false, signal });
}

// ----------------------------- Регулярные маршруты (расписание) -----------------------------
/** DriverSchedule (мой). weekdays — CSV ISO 1=Пн..7=Вс, time — "HH:MM". */
export interface DriverSchedule {
  id: number;
  driver_id: number;
  from_city: string;
  to_city: string;
  weekdays: string;
  time: string;
  comment: string;
  active: boolean;
  created_at?: string;
}

export interface ScheduleInput {
  from_city: string;
  to_city: string;
  weekdays: string; // "1,3,5"
  time: string; // "08:00"
  comment?: string;
}

export function fetchMySchedules(signal?: AbortSignal): Promise<DriverSchedule[]> {
  return apiGet<DriverSchedule[]>("/driver/schedule", { signal });
}

export function createSchedule(body: ScheduleInput): Promise<DriverSchedule> {
  return apiPost<DriverSchedule>("/driver/schedule", body);
}

export function deleteSchedule(id: number): Promise<{ ok: boolean; id: number }> {
  return apiDelete<{ ok: boolean; id: number }>(`/driver/schedule/${id}`);
}

// ----------------------------- Заработок -----------------------------
export interface EarningsDay {
  date: string; // YYYY-MM-DD
  sum: number; // ₽ (НЕ копейки)
  trips: number;
}

/** GET /driver/earnings?period= — суммы в РУБЛЯХ (debt.py::driver_earnings). */
export interface DriverEarnings {
  period: string;
  total: number; // ₽
  trips: number;
  by_day: EarningsDay[];
}

export type EarningsPeriod = "week" | "month" | "all";

export function fetchDriverEarnings(
  period: EarningsPeriod = "week",
  signal?: AbortSignal
): Promise<DriverEarnings> {
  return apiGet<DriverEarnings>(`/driver/earnings?period=${period}`, { signal });
}

// ----------------------------- Мои поездки такси (расшифровка денег) -----------------------------
/** Одна завершённая такси-поездка: цена → комиссия → чистыми (debt.py::driver_rides).
 *  Закрывает вопрос «Юлдаш говорит 4200, я насчитал 4600 — где мои 400?». */
export interface DriverTaxiRide {
  order_id: number;
  done_at: string | null;
  from: string;
  to: string;
  price: number; // ₽ — как видел пассажир
  promo_discount_kop: number; // скидку пассажиру оплатила платформа
  promo_comp_kop: number; // её доплата в кошелёк водителя
  fee_kop: number; // комиссия платформы
  net_kop: number; // «чистыми» водителю
  paid: boolean;
  /**
   * Разбор признал: пассажир не заплатил. Показывать такую поездку как обычную нельзя —
   * комиссию с неё сняли, и «чистыми» выходит БОЛЬШЕ, чем за честную. Водитель видел
   * поездку, где его обманули, как самую выгодную в списке.
   */
  unpaid_confirmed?: boolean;
  payment_method: string;
  fee_status: string; // unpaid | pending | paid | none
}

/** GET /driver/taxi-rides — только СВОИ поездки (по токену). */
export interface DriverTaxiRides {
  rides: DriverTaxiRide[];
  total_price: number; // ₽
  total_fee_kop: number;
  total_net_kop: number;
}

export function fetchDriverTaxiRides(
  limit = 100,
  signal?: AbortSignal
): Promise<DriverTaxiRides> {
  return apiGet<DriverTaxiRides>(`/driver/taxi-rides?limit=${limit}`, { signal });
}

// ----------------------------- Проверка водителя (документы) -----------------------------
/** POST /upload/photo (multipart `file`) → {url} защищённого документа. */
export function uploadDoc(
  file: File,
  signal?: AbortSignal
): Promise<{ url: string }> {
  const form = new FormData();
  form.append("file", file);
  return apiUpload<{ url: string }>("/upload/photo", form, { signal });
}

/** POST /driver/profile — реальные данные авто (перед отправкой на проверку). */
export interface DriverProfileInput {
  car_make?: string;
  car_model?: string;
  car_color?: string;
  car_plate?: string;
  seats?: number; // 1..8
}

export function setDriverProfile(
  body: DriverProfileInput
): Promise<{ car_make: string; car_model: string; seats: number }> {
  return apiPost<{ car_make: string; car_model: string; seats: number }>(
    "/driver/profile",
    body
  );
}

/** POST /driver/verify — отправить фото прав/авто на проверку → DriverProfile.
 * Нам важен только новый docs_status (после — перечитываем /driver/status). */
export function submitDriverVerify(body: {
  license_url: string;
  car_photo_url: string;
}): Promise<{ docs_status: string }> {
  return apiPost<{ docs_status: string }>("/driver/verify", body);
}

// ----------------------------- Долг по комиссии за такси -----------------------------
/**
 * Сколько водитель должен сервису за такси (зеркало backend/app/routers/debt.py).
 *
 * Комиссию мы не списываем автоматически: раз в неделю водитель переводит её по СБП
 * и нажимает «Я оплатил», админ подтверждает. Пока долг висит — такси блокируется,
 * но попутка работает как обычно: плановые поездки к комиссии отношения не имеют.
 */
export interface DebtWeek {
  week: string; // ISO-неделя
  amount_kop: number;
  status: string; // unpaid | pending
}

export interface DriverDebt {
  unpaid_kop: number;
  pending_kop: number; // заявлено к оплате, ждёт подтверждения админом
  due_at: string | null;
  overdue: boolean;
  blocked: boolean;
  block_reason: string | null; // overdue | threshold | null
  threshold_kop: number;
  sbp: { phone: string; name: string };
  weeks: DebtWeek[];
  /**
   * Сколько из долга нужно заплатить СРАЗУ после поездки, не дожидаясь недельного счёта.
   *
   * Так работает дальний межгород: комиссия с одной такой поездки сравнима с недельной,
   * и копить её до воскресенья — значит подвести водителя под блокировку разом. Клиент
   * по этой сумме поднимает оплату сам, а не ждёт, пока человек зайдёт в кабинет.
   */
  pay_now_kop?: number;
  pay_now_due_at?: string | null;
}

export function fetchDriverDebt(signal?: AbortSignal): Promise<DriverDebt> {
  return apiGet<DriverDebt>("/driver/debt", { signal });
}

/** Ответ POST /driver/debt/paid: карта (ЮKassa) или «я перевёл по СБП» (на доверии). */
export interface DebtPaidResult {
  ok?: boolean;
  method?: string; // yookassa | sbp_manual
  status?: string;
  amount_kop?: number;
  confirmation_url?: string;
  /** Номер платежа — по нему проверяем оплату после возврата из банка. */
  payment_id?: number;
}

/** «Я оплатил» — долг уходит на подтверждение админу (или открывается оплата картой). */
export function declareDebtPaid(): Promise<DebtPaidResult> {
  return apiPost<DebtPaidResult>("/driver/debt/paid");
}

// ----------------------------- Денежные чаевые (opt-in) -----------------------------
/**
 * POST /me/tips-sbp — водитель включает или выключает денежные чаевые,
 * указав свой номер СБП. Пустая строка = выключить.
 *
 * Номер показывается пассажиру ТОЛЬКО после завершённой поездки и только
 * если водитель сам его оставил: телефон — личное, отдаём по согласию
 * и минимально. Без этого «дать чаевые» у пассажира просто не появится.
 */
export function setTipsSbp(sbp: string): Promise<{ tips_sbp: string; accepting: boolean }> {
  return apiPost<{ tips_sbp: string; accepting: boolean }>("/me/tips-sbp", { sbp });
}

// ----------------------------- «Как получить больше заявок» -----------------------------
/**
 * GET /rides/{id}/tips — добрые советы по конкретной поездке: нет фото профиля,
 * не пройдена проверка, цена выше средней по маршруту, нет описания.
 *
 * Это не упрёк и не рейтинг: пусто (`all_good`) значит «всё выглядит хорошо».
 * Только своя поездка — чужие сервер не раскрывает.
 */
export interface RideTip {
  code: string;
  ru: string;
  ba: string;
}

export interface RideTips {
  ride_id: number;
  tips: RideTip[];
  all_good: boolean;
  route_avg_price: number | null;
  route_sample: number;
}

export function fetchRideTips(rideId: number, signal?: AbortSignal): Promise<RideTips> {
  return apiGet<RideTips>(`/rides/${rideId}/tips`, { signal });
}
