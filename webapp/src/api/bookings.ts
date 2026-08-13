// ================================================================
//  Брони и активная поездка (зеркало backend/app/routers/bookings.py
//  + оценка из family.py POST /bookings/{id}/rate).
//  Пассажир: бронирует поездку, видит детали (телефон/точка после
//  подтверждения), следит за live-статусом, показывает код посадки,
//  оценивает завершённую поездку. Квитанция — GET /trips/{id}/receipt.
// ================================================================
import { apiGet, apiPost } from "./client";

export type BookingStatus =
  | "pending"
  | "confirmed"
  | "onboard"
  | "done"
  | "cancelled";
export type PayMethod = "cash" | "sbp" | "negotiate";
/** Фаза водителя внутри активной поездки. */
export type DriverPhase = "" | "departed" | "arriving";

export interface CreateBookingInput {
  ride_id: number;
  seats?: number;
  pay_method?: PayMethod;
  pay_amount?: number;
}

/** Полная строка брони (ответ POST /bookings). */
export interface BookingRow {
  id: number;
  ride_id: number;
  passenger_id: number;
  seats: number;
  price: number;
  status: BookingStatus;
  driver_phase: DriverPhase;
  boarding_code: string;
  paid: boolean;
  pay_method: PayMethod;
  pay_amount?: number | null;
  created_at: string;
}

/** Детали брони — экран Booking (GET /bookings/{id}/details). */
export interface BookingDetails {
  booking_id: number;
  ride_id: number;
  role: "driver" | "passenger";
  status: BookingStatus;
  contact_unlocked: boolean; // телефон/точка открыты (confirmed/onboard/done)
  from_city: string;
  to_city: string;
  depart_at: string; // ISO
  seats: number;
  price: number;
  pay_method: PayMethod;
  pay_amount?: number | null;
  driver_name: string;
  driver_verified: boolean;
  driver_phone: string; // пусто до разблокировки
  driver_car: string;
  /** Госномер и цвет — чтобы сверить машину у обочины. Пусто до подтверждения брони (ПДн). */
  driver_plate?: string;
  driver_car_color?: string;
  pickup: string; // пусто до разблокировки
  pickup_lat?: number | null;
  pickup_lng?: number | null;
  from_lat?: number | null;
  from_lng?: number | null;
  to_lat?: number | null;
  to_lng?: number | null;
}

/** Live-состояние поездки (поллинг на активном экране). */
export interface TripState {
  role: "driver" | "passenger";
  status: BookingStatus;
  driver_phase: DriverPhase;
  /** Сервер сверил «подъезжаю» с живым GPS водителя: машина правда рядом с точкой подачи. */
  arrival_verified?: boolean;
}

/** Квитанция завершённой поездки (GET /trips/{id}/receipt). */
export interface TripReceipt {
  booking_id: number;
  ride_id: number;
  role: string;
  from_city: string;
  to_city: string;
  depart_at: string;
  seats: number;
  amount: number;
  pay_method: PayMethod;
  paid: boolean;
  driver_name: string;
  driver_verified: boolean;
}

/** Элемент списка моих броней (GET /bookings/mine). */
export interface MyBooking {
  id: number;
  ride_id: number;
  seats: number;
  price: number;
  status: BookingStatus;
  boarding_code: string;
  pay_method: PayMethod;
  pay_amount?: number | null;
  from_city: string;
  to_city: string;
  depart_at: string;
  driver_name: string;
  driver_verified: boolean;
}

export function createBooking(body: CreateBookingInput): Promise<BookingRow> {
  return apiPost<BookingRow>("/bookings", body);
}

export function fetchBookingDetails(
  id: number,
  signal?: AbortSignal
): Promise<BookingDetails> {
  return apiGet<BookingDetails>(`/bookings/${id}/details`, { signal });
}

export function fetchTripState(id: number, signal?: AbortSignal): Promise<TripState> {
  return apiGet<TripState>(`/bookings/${id}/role`, { signal });
}

export function fetchBoardingCode(
  id: number,
  signal?: AbortSignal
): Promise<{ code: string }> {
  return apiGet<{ code: string }>(`/bookings/${id}/boarding-code`, { signal });
}

export function fetchMyBookings(signal?: AbortSignal): Promise<MyBooking[]> {
  return apiGet<MyBooking[]>("/bookings/mine", { signal });
}

export function cancelBooking(id: number): Promise<BookingRow> {
  return apiPost<BookingRow>(`/bookings/${id}/cancel`);
}

/** Зафиксировать договорённость об оплате (способ/сумма). */
export function setPayAgreement(
  id: number,
  pay_method?: PayMethod | null,
  pay_amount?: number | null
): Promise<{ ok: boolean; pay_method: PayMethod; pay_amount?: number | null }> {
  return apiPost(`/bookings/${id}/pay-agreement`, { pay_method, pay_amount });
}

/** Оценить вторую сторону (только завершённую поездку). 409 — ещё не done. */
export function rateBooking(
  id: number,
  stars: number,
  text = "",
  tags: string[] = []
): Promise<unknown> {
  return apiPost(`/bookings/${id}/rate`, { stars, text, tags: tags.join(",") });
}

/**
 * Быстрые метки к оценке. Список закрытый — сервер отбрасывает всё, чего тут нет
 * (`safety_logic.RATING_TAGS`), максимум пять.
 *
 * Смысл в том, что развёрнутый отзыв пишут единицы, а метку ставят все: из меток
 * складывается понятный портрет, который виден сразу, без чтения чужих сочинений.
 * И метки публикуются без модерации — оскорбить закрытым списком нельзя.
 *
 * Набор зависит и от оценки, и от того, кого оцениваем: «Чисто в машине»
 * пассажиру не адресуешь — машина не его.
 */
export function ratingTags(
  stars: number,
  rateeIsDriver: boolean
): { key: string; ru: string; ba: string }[] {
  if (stars >= 4) {
    return rateeIsDriver
      ? [
          { key: "polite", ru: "Вежливый", ba: "Итәғәтле" },
          { key: "ontime", ru: "Приехал вовремя", ba: "Ваҡытында килде" },
          { key: "clean", ru: "Чисто в машине", ba: "Машинала таҙа" },
          { key: "safe", ru: "Везёт аккуратно", ba: "Һаҡ йөрөтә" },
          { key: "comfortable", ru: "Ехать удобно", ba: "Барыуы уңайлы" },
        ]
      : [
          { key: "polite", ru: "Вежливый", ba: "Итәғәтле" },
          { key: "ontime", ru: "Вовремя вышел", ba: "Ваҡытында сыҡты" },
          { key: "helpful", ru: "Помог в дороге", ba: "Юлда ярҙам итте" },
        ];
  }
  return rateeIsDriver
    ? [
        { key: "late", ru: "Опоздал", ba: "Һуңланы" },
        { key: "rude", ru: "Грубый", ba: "Тупаҫ" },
        { key: "unsafe", ru: "Опасная езда", ba: "Хәүефле йөрөтөү" },
        { key: "dirty", ru: "Грязно в машине", ba: "Машинала бысраҡ" },
        { key: "detour", ru: "Вёз кругами", ba: "Урап йөрөттө" },
      ]
    : [
        { key: "late", ru: "Опоздал", ba: "Һуңланы" },
        { key: "rude", ru: "Грубый", ba: "Тупаҫ" },
      ];
}

/** Квитанция. 404 (нет эндпоинта на проде) / 409 (не завершена) → мягкая деградация. */
export function fetchReceipt(id: number, signal?: AbortSignal): Promise<TripReceipt> {
  return apiGet<TripReceipt>(`/trips/${id}/receipt`, { signal });
}
