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
  text = ""
): Promise<unknown> {
  return apiPost(`/bookings/${id}/rate`, { stars, text });
}

/** Квитанция. 404 (нет эндпоинта на проде) / 409 (не завершена) → мягкая деградация. */
export function fetchReceipt(id: number, signal?: AbortSignal): Promise<TripReceipt> {
  return apiGet<TripReceipt>(`/trips/${id}/receipt`, { signal });
}
