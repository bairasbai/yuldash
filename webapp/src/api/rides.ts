// Модель поездки — зеркало backend RideOut (app/schemas.py).
// Оставлены поля, нужные первому экрану-ленте; остальные добавим по мере надобности.
import { apiGet } from "./client";

export type RideCategory =
  | "regular"
  | "hospital"
  | "parcel"
  | "cargo"
  | "urgent";

export interface Ride {
  id: number;
  driver_id: number;
  from_city: string;
  to_city: string;
  depart_at: string; // ISO
  seats_total: number;
  seats_left: number;
  price: number; // ₽
  category: RideCategory;
  comment: string;
  pickup?: string;
  women_only?: boolean;
  baggage?: boolean;
  child_seat?: boolean;
  pets_allowed?: boolean;
  boosted?: boolean;
  driver_name: string;
  driver_rating: number;
  driver_verified: boolean;
  driver_car: string;
  driver_avatar?: string;
  driver_online?: boolean;
  // Появляются только после подтверждённой брони (иначе пусто/0).
  pickup_lat?: number | null;
  pickup_lng?: number | null;
  driver_trips?: number;
  driver_since?: string; // "YYYY-MM"
}

/** Публичная лента активных поездок. Токен не обязателен (auth: false). */
export function fetchRides(signal?: AbortSignal): Promise<Ride[]> {
  return apiGet<Ride[]>("/rides", { auth: false, signal });
}

/** Карточка поездки (GET /rides/{id}) — публичная, без личных данных. */
export function fetchRide(id: number, signal?: AbortSignal): Promise<Ride> {
  return apiGet<Ride>(`/rides/${id}`, { auth: false, signal });
}
