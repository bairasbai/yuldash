// ================================================================
//  Подписки на маршрут «карауль поездку» (зеркало
//  backend/app/routers/route_watch.py). Пользователь подписывается на
//  маршрут (Сибай→Уфа), и как только водитель публикует подходящую
//  поездку — приходит push. Подписка живёт 14 дней.
// ================================================================
import { apiDelete, apiGet, apiPost } from "./client";

export type WatchDirection = "forward" | "both";

/** Подписка на маршрут (GET /route-watch). */
export interface RouteWatch {
  id: number;
  from_city: string;
  to_city: string;
  watch_date?: string | null; // ISO, опц.
  direction: WatchDirection;
  created_at: string;
  expires_at: string;
}

/** Тело создания (POST /route-watch). */
export interface RouteWatchInput {
  from_city: string;
  to_city: string;
  watch_date?: string | null;
  direction?: WatchDirection;
}

export function fetchRouteWatches(signal?: AbortSignal): Promise<RouteWatch[]> {
  return apiGet<RouteWatch[]>("/route-watch", { signal });
}

export function createRouteWatch(body: RouteWatchInput): Promise<RouteWatch> {
  return apiPost<RouteWatch>("/route-watch", body);
}

export function deleteRouteWatch(id: number): Promise<{ ok: boolean }> {
  return apiDelete<{ ok: boolean }>(`/route-watch/${id}`);
}
