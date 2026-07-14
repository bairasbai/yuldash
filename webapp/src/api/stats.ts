// ================================================================
//  Личная статистика «Мой Юлдаш» (зеркало backend/app/routers/stats.py).
//  GET /me/stats — агрегаты только по своим данным (км/поездки/₽/CO₂),
//  звание попутчика + прогресс до следующего. Новичок → всё нули.
// ================================================================
import { apiGet } from "./client";

/** Звание попутчика + прогресс (для полоски в UI). */
export interface StatsRank {
  level: number;
  title_ru: string;
  title_ba: string;
  next_title_ru: string | null;
  next_title_ba: string | null;
  next_at: number | null;
  to_next: number;
}

/** Ответ GET /me/stats. */
export interface MyStats {
  trips: number;
  km: number;
  saved_rub: number;
  co2_saved_kg: number;
  rank: StatsRank;
  coeffs: {
    taxi_rub_per_km: number;
    co2_grams_per_km: number;
  };
}

export function fetchMyStats(signal?: AbortSignal): Promise<MyStats> {
  return apiGet<MyStats>("/me/stats", { signal });
}
