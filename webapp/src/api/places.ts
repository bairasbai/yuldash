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
