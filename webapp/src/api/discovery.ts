// ================================================================
//  Витрина/карта (зеркало backend/app/routers/discovery.py + rides.py
//  /rides/near + requests.py /requests/near).
//  Ближайшие поездки/заявки для карты Home, популярные маршруты,
//  геокодер для подсказки городов в форме заявки.
// ================================================================
import { apiGet } from "./client";
import type { Ride } from "./rides";

/** Ближайшая заявка пассажира (GET /requests/near). Координаты округлены ~2 знака. */
export interface NearRequest {
  id: number;
  passenger_name: string;
  from_city: string;
  to_city: string;
  from_lat?: number | null;
  from_lng?: number | null;
  desired_at?: string | null;
  seats: number;
  comment: string;
  distance_km?: number | null;
}

/** Поездка рядом (GET /rides/near) — RideOut + distance_km. */
export type NearRide = Ride & { distance_km?: number | null };

export interface PopularRoute {
  from_city: string;
  to_city: string;
  count: number;
}

/** Ближайшие поездки. Публично (токен не нужен). */
export function fetchRidesNear(
  params: { lat?: number; lng?: number; radius_km?: number; limit?: number } = {},
  signal?: AbortSignal
): Promise<{ count: number; items: NearRide[] }> {
  const q = new URLSearchParams();
  if (params.lat != null) q.set("lat", String(params.lat));
  if (params.lng != null) q.set("lng", String(params.lng));
  if (params.radius_km != null) q.set("radius_km", String(params.radius_km));
  q.set("limit", String(params.limit ?? 30));
  return apiGet(`/rides/near?${q.toString()}`, { auth: false, signal });
}

/** Ближайшие заявки пассажиров (для водителя/карты). Требует вход. */
export function fetchRequestsNear(
  params: { lat?: number; lng?: number; radius_km?: number; limit?: number } = {},
  signal?: AbortSignal
): Promise<{ count: number; items: NearRequest[] }> {
  const q = new URLSearchParams();
  if (params.lat != null) q.set("lat", String(params.lat));
  if (params.lng != null) q.set("lng", String(params.lng));
  if (params.radius_km != null) q.set("radius_km", String(params.radius_km));
  q.set("limit", String(params.limit ?? 30));
  return apiGet(`/requests/near?${q.toString()}`, { signal });
}

/** Популярные маршруты. Публично. */
export function fetchPopularRoutes(signal?: AbortSignal): Promise<PopularRoute[]> {
  return apiGet<PopularRoute[]>("/popular-routes", { auth: false, signal });
}

/** Геокодер подсказки городов. Требует вход. Поле координаты — `lon` (не `lng`). */
export interface GeoHit {
  title: string;
  lat: number;
  lon: number;
}
export function geocode(q: string, signal?: AbortSignal): Promise<{ items: GeoHit[] }> {
  return apiGet<{ items: GeoHit[] }>(`/geocode?q=${encodeURIComponent(q)}`, {
    signal,
  });
}
