// ================================================================
//  Сезонные события (зеркало backend/app/routers/seasonal.py).
//  Сабантуй, курбан, начало учебного года, ярмарки — то, из-за чего
//  люди массово едут в одну сторону. Публичная ручка (без токена):
//  баннер видит и гость.
// ================================================================
import { apiGet } from "./client";

export interface SeasonalEvent {
  code: string;
  name_ru: string;
  name_ba: string;
  note_ru: string;
  note_ba: string;
  emoji: string;
  category: string;
  anchor: string; // YYYY-MM-DD — «привязка» события
  starts_at: string;
  ends_at: string;
  active: boolean; // уже идёт (а не «скоро»)
}

/** GET /seasonal-events?days= — окно вперёд (по умолчанию 21 день). */
export function fetchSeasonalEvents(
  days?: number,
  signal?: AbortSignal
): Promise<{ items: SeasonalEvent[] }> {
  const q = days ? `?days=${days}` : "";
  return apiGet<{ items: SeasonalEvent[] }>(`/seasonal-events${q}`, { auth: false, signal });
}
