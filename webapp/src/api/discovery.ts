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

/**
 * Ближайшие поездки. Публично (токен не нужен).
 *
 * `date` — «еду завтра» — самый частый вопрос в ленте: человек ищет не «когда-нибудь»,
 * а конкретный день. Без него список смешивал сегодняшние поездки с теми, что через
 * неделю, и нужную приходилось искать глазами.
 *
 * `offset` — «показать ещё». Отдавать сразу всё нельзя: на сельском интернете длинный
 * список это лишние секунды и лишний трафик.
 */
export function fetchRidesNear(
  params: {
    lat?: number;
    lng?: number;
    radius_km?: number;
    limit?: number;
    /** YYYY-MM-DD. Пусто = все дни. */
    date?: string;
    offset?: number;
  } = {},
  signal?: AbortSignal
): Promise<{ count: number; items: NearRide[] }> {
  const q = new URLSearchParams();
  if (params.lat != null) q.set("lat", String(params.lat));
  if (params.lng != null) q.set("lng", String(params.lng));
  if (params.radius_km != null) q.set("radius_km", String(params.radius_km));
  if (params.date) q.set("date", params.date);
  if (params.offset) q.set("offset", String(params.offset));
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

/** Мои частые маршруты — из истории броней (GET /my-routes). Требует вход. */
export function fetchMyRoutes(signal?: AbortSignal): Promise<PopularRoute[]> {
  return apiGet<PopularRoute[]>("/my-routes", { signal });
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

/**
 * Адрес по координатам (GET /geocode/reverse) — обратный геокодер.
 *
 * Нужен, когда человек ставит точку пином на карте: без него в заказ уходит безымянное
 * «Точка на карте», и адреса не знает никто — ни водитель в списке заказов, ни сам
 * пассажир в истории поездок.
 *
 * Пустой `title` — нормальный ответ: ключа нет, геокодер молчит или координаты мусорные.
 * Показывать в этом случае надо «Точка на карте», а не ошибку.
 */
export function reverseGeocode(
  lat: number,
  lng: number,
  signal?: AbortSignal
): Promise<{ title: string }> {
  return apiGet<{ title: string }>(
    `/geocode/reverse?lat=${encodeURIComponent(lat)}&lng=${encodeURIComponent(lng)}`,
    { signal }
  );
}

/**
 * Живая лента сообщества (GET /feed). Публичная, кеш на сервере 60 секунд.
 *
 * Настоящие числа, а не украшение: сколько поездок было за день, месяц и год, сколько
 * человек за рулём, какой маршрут чаще всего ездили на этой неделе и сколько люди
 * собрали донатами. Для нового человека это единственный способ увидеть, что сервис
 * живой, — в райцентре на карте может не быть ни одной машины прямо сейчас.
 *
 * В вебе этой ленты не было вовсе: карта молчала (сверка с Android, 2026-08-30).
 */
export interface CommunityFeed {
  today: number;
  week: number;
  month: number;
  year: number;
  /** Сколько разных водителей вообще выходило на линию. */
  drivers: number;
  /** Самый частый маршрут недели. null = поездок пока мало. */
  top_route: { from_city: string; to_city: string; count: number } | null;
  /** Собрано донатами за всё время, ₽. */
  donations_total: number;
}

export function fetchCommunityFeed(signal?: AbortSignal): Promise<CommunityFeed> {
  return apiGet<CommunityFeed>("/feed", { auth: false, signal });
}
