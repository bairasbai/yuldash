// ================================================================
//  География: справочник населённых пунктов и точки сбора.
//  Зеркало backend/app/routers/settlements.py + pickup.py.
//
//  GET /settlements?q=&limit=        — автоподсказки «откуда/куда» (RU и BA)
//  GET /settlements/districts?q=     — районы (зона работы водителя)
//  GET /settlements/popular-routes   — чипы популярных маршрутов
//  GET /pickup-points?city=&limit=   — ориентиры «у мечети», «автовокзал»
//
//  Всё публичное: названия сёл и ориентиры — не персональные данные.
//  Сёл ~6600, поэтому поиск идёт на сервере по снимку в памяти, а не в БД.
// ================================================================
import { apiGet } from "./client";

export interface Settlement {
  id: number;
  name_ru: string;
  name_ba: string;
  region: string;
  kind: string; // city | district_center | village | neighbor
  district: string;
  lat: number | null;
  lng: number | null;
}

export interface District {
  district: string;
  region: string;
  count: number;
}

export interface PopularRoute {
  from: Settlement;
  to: Settlement;
}

/** Ориентир для встречи: «у мечети», «автовокзал», «у Магнита». */
export interface PickupPoint {
  id: number;
  city: string;
  title_ru: string;
  title_ba: string;
  lat: number | null;
  lng: number | null;
  usage_count: number;
}

export function searchSettlements(
  q: string,
  limit = 10,
  signal?: AbortSignal
): Promise<Settlement[]> {
  const p = new URLSearchParams({ limit: String(limit) });
  if (q.trim()) p.set("q", q.trim());
  return apiGet<{ items: Settlement[] }>(`/settlements?${p}`, { auth: false, signal }).then(
    (r) => r.items ?? []
  );
}

export function fetchDistricts(q = "", signal?: AbortSignal): Promise<District[]> {
  const p = new URLSearchParams();
  if (q.trim()) p.set("q", q.trim());
  const qs = p.toString();
  return apiGet<{ items: District[] }>(`/settlements/districts${qs ? `?${qs}` : ""}`, {
    auth: false,
    signal,
  }).then((r) => r.items ?? []);
}

export function fetchPopularRoutes(signal?: AbortSignal): Promise<PopularRoute[]> {
  return apiGet<{ routes: PopularRoute[] }>("/settlements/popular-routes", {
    auth: false,
    signal,
  }).then((r) => r.routes ?? []);
}

export function fetchPickupPoints(
  city?: string,
  limit = 12,
  signal?: AbortSignal
): Promise<PickupPoint[]> {
  const p = new URLSearchParams({ limit: String(limit) });
  if (city?.trim()) p.set("city", city.trim());
  return apiGet<PickupPoint[]>(`/pickup-points?${p}`, { auth: false, signal });
}
