// ================================================================
//  Погода на маршруте: гололёд, метель, туман, мороз перед выездом.
//  Зеркало backend routers/weather.py.
//
//  Ручка отдаёт ГОТОВЫЕ фразы на двух языках — клиент их только
//  рисует. Решение «что считать опасным» живёт на сервере: пороги
//  правятся без выпуска нового приложения.
//
//  Приватность: координаты округляются до ~5 км ещё на сервере,
//  точный адрес человека наружу не уходит.
// ================================================================
import { apiGet } from "./client";

export interface WeatherWarning {
  kind: string; // ice | blizzard | snow | fog | wind | frost | thunder
  ru: string;
  ba: string;
  severe: boolean; // true = красная карточка, а не жёлтая
}

export interface RouteWeather {
  available: boolean; // false → карточку не показывать вовсе
  warnings: WeatherWarning[];
  temperature_c: number | null;
}

/** Маршрут задаётся координатами ИЛИ названиями городов («Баймак → Сибай»). */
export interface RouteWeatherQuery {
  fromLat?: number | null;
  fromLng?: number | null;
  toLat?: number | null;
  toLng?: number | null;
  fromCity?: string;
  toCity?: string;
  at?: string; // ISO времени выезда; пусто = сейчас
}

export function fetchRouteWeather(
  q: RouteWeatherQuery,
  signal?: AbortSignal
): Promise<RouteWeather> {
  const p = new URLSearchParams();
  if (q.fromLat != null && q.fromLng != null) {
    p.set("from_lat", String(q.fromLat));
    p.set("from_lng", String(q.fromLng));
  }
  if (q.toLat != null && q.toLng != null) {
    p.set("to_lat", String(q.toLat));
    p.set("to_lng", String(q.toLng));
  }
  if (q.fromCity) p.set("from_city", q.fromCity);
  if (q.toCity) p.set("to_city", q.toCity);
  if (q.at) p.set("at", q.at);
  const qs = p.toString();
  return apiGet<RouteWeather>(`/weather/route${qs ? `?${qs}` : ""}`, { signal });
}
