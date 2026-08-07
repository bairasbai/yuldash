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
  pets_allowed?: boolean;
  child_seat?: boolean;
  women_only?: boolean;
  baggage?: boolean;
  air_conditioner?: boolean;
  non_smoking?: boolean; // маппится клиентом в smoking=false (бэк поле smoking)
  smoking?: boolean;
  quiet?: boolean;
  only_trusted?: boolean;
  recurrence?: string; // none | daily | weekdays | weekly
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
