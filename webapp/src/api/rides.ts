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
  quiet?: boolean; // тихая поездка: без лишних разговоров и громкой музыки
  boosted?: boolean;
  status?: "active" | "done" | "cancelled" | string; // есть в /driver/rides (RideOut)
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

/**
 * Публичная лента активных поездок. Токен не обязателен (auth: false).
 *
 * Фильтры считает СЕРВЕР, а не клиент: на 11 тысячах поездок тянуть всё и резать
 * в браузере — это лишний мегабайт трафика на телефоне в селе. Без фильтров
 * ответ отдаётся из кеша, с фильтрами — запросом в базу.
 */
export interface RidesQuery {
  from_city?: string;
  to_city?: string;
  category?: RideCategory;
  date?: string; // YYYY-MM-DD — только поездки этого дня
  women_only?: boolean;
  pets_allowed?: boolean;
  child_seat?: boolean;
  baggage?: boolean;
}

export function fetchRides(signal?: AbortSignal, q?: RidesQuery): Promise<Ride[]> {
  const p = new URLSearchParams();
  Object.entries(q ?? {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== "" && v !== false) p.set(k, String(v));
  });
  const qs = p.toString();
  return apiGet<Ride[]>(`/rides${qs ? `?${qs}` : ""}`, { auth: false, signal });
}

/** Карточка поездки (GET /rides/{id}) — публичная, без личных данных. */
export function fetchRide(id: number, signal?: AbortSignal): Promise<Ride> {
  return apiGet<Ride>(`/rides/${id}`, { auth: false, signal });
}
