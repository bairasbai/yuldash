// ================================================================
//  Сохранённые и недавние адреса (зеркало backend/app/routers/places.py).
//  Строго про себя (анти-IDOR): бэкенд отдаёт/пишет только свои строки.
//  Дом/Работа — по одному (upsert по kind), произвольные — до лимита.
//  Эндпоинтов может ещё не быть на проде (release-2026-07) → 404
//  ловим в экране мягкой деградацией, без краша.
// ================================================================
import { apiDelete, apiGet, apiPost } from "./client";

export type SavedPlaceKind = "home" | "work" | "custom";

/** Сохранённое место (GET /places/saved). */
export interface SavedPlace {
  id: number;
  kind: SavedPlaceKind | string;
  label: string;
  address: string;
  lat?: number | null;
  lng?: number | null;
  created_at: string;
}

/** Тело создания/обновления (POST /places/saved). */
export interface SavedPlaceInput {
  kind?: SavedPlaceKind;
  label?: string;
  address?: string;
  lat?: number | null;
  lng?: number | null;
}

/** Недавняя точка (GET /places/recent). */
export interface RecentPlace {
  id: number;
  address: string;
  lat?: number | null;
  lng?: number | null;
  used_at: string;
}

export function fetchSavedPlaces(signal?: AbortSignal): Promise<SavedPlace[]> {
  return apiGet<SavedPlace[]>("/places/saved", { signal });
}

export function upsertSavedPlace(body: SavedPlaceInput): Promise<SavedPlace> {
  return apiPost<SavedPlace>("/places/saved", body);
}

export function deleteSavedPlace(id: number): Promise<{ ok: boolean }> {
  return apiDelete<{ ok: boolean }>(`/places/saved/${id}`);
}

export function fetchRecentPlaces(signal?: AbortSignal): Promise<RecentPlace[]> {
  return apiGet<RecentPlace[]>("/places/recent", { signal });
}

/**
 * «Этим адресом только что воспользовались» (POST /places/saved/{id}/used).
 *
 * По этой отметке строится порядок быстрого списка: наверху то, куда ездят.
 * Без неё список навсегда застывал в том виде, в каком его один раз завели.
 */
export function markSavedPlaceUsed(id: number): Promise<{ ok: boolean }> {
  return apiPost<{ ok: boolean }>(`/places/saved/${id}/used`);
}

/**
 * Запомнить точку в «недавних» (POST /places/recent). Зовём при заказе.
 * Дедуп по адресу держит сервер: повтор обновляет время и координаты.
 */
export function addRecentPlace(body: {
  address: string;
  lat?: number | null;
  lng?: number | null;
}): Promise<RecentPlace> {
  return apiPost<RecentPlace>("/places/recent", {
    address: body.address.slice(0, 500),
    lat: body.lat ?? null,
    lng: body.lng ?? null,
  });
}

/**
 * Убрать одну недавнюю точку.
 *
 * Список копится сам, из каждого заказа, и человек его не выбирал. Значит право убрать
 * оттуда строку — не украшение: там оседают адрес больницы, дом бывшего, работа,
 * с которой ушёл.
 */
export function deleteRecentPlace(id: number): Promise<{ ok: boolean }> {
  return apiDelete<{ ok: boolean }>(`/places/recent/${id}`);
}

/**
 * Очистить весь список недавних.
 *
 * Нужно отдельно от удаления по одной: когда телефон отдают в чужие руки, чистить
 * по строке — десять жестов вместо одного.
 */
export function clearRecentPlaces(): Promise<{ ok: boolean }> {
  return apiDelete<{ ok: boolean }>("/places/recent");
}
