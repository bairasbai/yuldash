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
 * Параметры ленты «Ближайшие». Названия ключей — как в приложении (`ApiClient.getNearbyRidesPaged`):
 * сервер один, и расхождение между сайтом и приложением значило бы, что на одном и том же
 * запросе люди видят разные поездки.
 */
export interface NearQuery {
  lat?: number;
  lng?: number;
  radius_km?: number;
  limit?: number;
  /** YYYY-MM-DD. Пусто = все дни. */
  date?: string;
  offset?: number;
  /**
   * Условия пассажира: women, child, pets, baggage, ac, nosmoke, quiet. Сервер режет ими ДО
   * подсчёта `count` и разбивки на порции — иначе «Показать ещё» и число в кнопке «Показать N
   * поездок» считались бы по поездкам, которых человек не просил.
   */
  conditions?: readonly string[];
  /** «Только проверенные» из раздела «Безопасность». */
  verified_only?: boolean;
  /** «Откуда»: любой из этих городов (русские имена — сервер сам ищет и по башкирскому). */
  from_cities?: readonly string[];
  /** «Куда»: так же. */
  to_cities?: readonly string[];
}

/**
 * Строка запроса `/rides/near`. Списки — через запятую и по алфавиту: один и тот же выбор даёт
 * один и тот же адрес (удобно кешу и тестам), порядок нажатий не важен.
 */
export function nearQueryString(params: NearQuery = {}): string {
  const q = new URLSearchParams();
  const list = (key: string, values: readonly string[] | undefined) => {
    const clean = [...new Set((values ?? []).map((v) => v.trim()).filter(Boolean))].sort();
    if (clean.length) q.set(key, clean.join(","));
  };
  if (params.lat != null) q.set("lat", String(params.lat));
  if (params.lng != null) q.set("lng", String(params.lng));
  if (params.radius_km != null) q.set("radius_km", String(params.radius_km));
  if (params.date) q.set("date", params.date);
  if (params.offset) q.set("offset", String(params.offset));
  list("conditions", params.conditions);
  if (params.verified_only) q.set("verified_only", "true");
  list("from_cities", params.from_cities);
  list("to_cities", params.to_cities);
  q.set("limit", String(params.limit ?? 30));
  return q.toString();
}

/** Ответ `/rides/near`: порция, всего подходящих и — при `verified_only` — сколько спрятал этот фильтр. */
export interface NearRidesPage {
  count: number;
  items: NearRide[];
  /**
   * Сколько поездок ЭТОГО поиска спрятал «только проверенные» (сервер с 17.10.2026, только при
   * `verified_only=true`). Пустая лента по нему говорит «Есть N поездок непроверенных водителей —
   * показать?». Старый сервер поля не шлёт — считаем 0.
   */
  hidden_unverified?: number;
}

/**
 * Ближайшие поездки. Токен (если есть) уходит с запросом — см. комментарий в теле.
 *
 * `date` — «еду завтра» — самый частый вопрос в ленте: человек ищет не «когда-нибудь»,
 * а конкретный день. Без него список смешивал сегодняшние поездки с теми, что через
 * неделю, и нужную приходилось искать глазами.
 *
 * `limit` и `count` — «показать ещё». Отдавать сразу всё нельзя: на сельском интернете длинный
 * список это лишние секунды и лишний трафик. `count` в ответе — сколько всего подходит под
 * условия (не сколько в порции): по нему лента решает, показывать ли «Показать ещё».
 */
export function fetchRidesNear(
  params: NearQuery = {},
  signal?: AbortSignal
): Promise<NearRidesPage> {
  // Токен (если есть) уходит с запросом: сервер по нему прячет заблокированных водителей
  // и показывает «только для своих» своим. Гость без токена получает то же, что и раньше —
  // сервер на отсутствующий или негодный токен не отвечает 401, а считает человека гостем.
  return apiGet(`/rides/near?${nearQueryString(params)}`, { signal });
}

/** Город для шторок «Откуда»/«Куда»: одно место — два написания («Баймак» / «Баймаҡ»). */
export interface RideCity {
  ru: string;
  ba: string;
}

/** Города, откуда и куда сейчас есть поездки (`GET /rides/cities`). Пусто — старый сервер или поездок нет. */
export interface RideCities {
  from: RideCity[];
  to: RideCity[];
}

/**
 * Ответ `/rides/cities` → города (зеркало `parseRideCities` в приложении). Без русского имени
 * город пропускаем; без башкирского — берём русское. Мусор в ответе ленту не роняет.
 */
export function parseRideCities(raw: unknown): RideCities {
  const list = (value: unknown): RideCity[] => {
    if (!Array.isArray(value)) return [];
    const out: RideCity[] = [];
    for (const item of value) {
      if (!item || typeof item !== "object") continue;
      const o = item as { ru?: unknown; ba?: unknown };
      const ru = typeof o.ru === "string" ? o.ru.trim() : "";
      if (!ru) continue;
      const ba = typeof o.ba === "string" ? o.ba.trim() : "";
      out.push({ ru, ba: ba || ru });
    }
    return out;
  };
  const o = (raw && typeof raw === "object" ? raw : {}) as { from?: unknown; to?: unknown };
  return { from: list(o.from), to: list(o.to) };
}

/**
 * Города для «Откуда»/«Куда», где есть поездки. Токен уходит с запросом: сервер прячет города
 * заблокированных водителей так же, как в ленте. Старый сервер без ручки отвечает 404 — для
 * ленты это «чипов городов нет», а не ошибка.
 */
export function fetchRideCities(signal?: AbortSignal): Promise<RideCities> {
  return apiGet<unknown>("/rides/cities", { signal }).then(parseRideCities);
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
