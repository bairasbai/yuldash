// ================================================================
//  Фильтры по умолчанию (localStorage) — зеркало `FilterPrefs` в приложении (SecondaryScreens.kt)
//  и `AppPrefs.verifiedOnly` («Только проверенные» из раздела «Безопасность»).
//  Не персональные данные — только UX-настройка ленты на устройстве.
//
//  Условия — семь ключей приложения: women, child, pets, baggage, ac, nosmoke, quiet
//  (список и подписи — nearbyFeed.ts). Ни «города», ни «потолка цены» здесь нет: в приложении
//  их нет, город заменён чипом «Откуда» в ленте, а цену сервер не фильтрует — клиентский потолок
//  давал бы ложную пустоту, когда подходящие поездки остались на следующих порциях.
// ================================================================
import type { Ride } from "./api/rides";
import { isNearbyCondition, matchesNearbyConditions, NEARBY_CONDITION_KEYS, type NearbyCondition } from "./nearbyFeed";

export interface FilterPrefs {
  conditions: NearbyCondition[]; // условия пассажира — стартовые для ленты «Ближайшие»
  onlyTrusted: boolean; // «только проверенные» (раздел «Безопасность»)
}

export const emptyFilters: FilterPrefs = {
  conditions: [],
  onlyTrusted: false,
};

const KEY = "yuldash.filterPrefs";

/**
 * Старые имена условий из прежней версии сайта → ключи приложения. Человек, который однажды
 * включил «Только женщины», не должен потерять это при обновлении. Всё незнакомое (в том числе
 * прежние «город» и «цена до») при загрузке молча отбрасывается и не ломает её.
 */
const LEGACY_CONDITION: Record<string, NearbyCondition> = {
  women_only: "women",
  child_seat: "child",
  non_smoking: "nosmoke",
  air_conditioner: "ac",
  pets_allowed: "pets",
};

export function loadFilters(): FilterPrefs {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return { ...emptyFilters, conditions: [] };
    const o = JSON.parse(raw) as { conditions?: unknown; amenities?: unknown; onlyTrusted?: unknown } | null;
    const stored = Array.isArray(o?.conditions) ? o.conditions : Array.isArray(o?.amenities) ? o.amenities : [];
    const keys = new Set<NearbyCondition>();
    for (const k of stored) {
      const key = typeof k === "string" ? LEGACY_CONDITION[k] ?? k : k;
      if (isNearbyCondition(key)) keys.add(key);
    }
    return {
      conditions: NEARBY_CONDITION_KEYS.filter((k) => keys.has(k)), // порядок — как в шторке
      onlyTrusted: !!o?.onlyTrusted,
    };
  } catch {
    return { ...emptyFilters, conditions: [] };
  }
}

export function saveFilters(f: FilterPrefs): void {
  try {
    localStorage.setItem(KEY, JSON.stringify({ conditions: f.conditions, onlyTrusted: f.onlyTrusted }));
  } catch {
    /* хранилище недоступно (приватный режим) — настройка живёт, пока открыт экран */
  }
}

/** Активен ли хоть один фильтр по умолчанию. */
export function isFilterActive(f: FilterPrefs): boolean {
  return f.conditions.length > 0 || f.onlyTrusted;
}

/**
 * Клиентское применение фильтров к списку поездок на вкладке «Поездки» (ленту «Ближайшие»
 * режет сервер — HomeScreen.tsx). Правила те же, что у подстраховки для старого сервера.
 */
export function applyRideFilters(rides: Ride[], f: FilterPrefs): Ride[] {
  if (!isFilterActive(f)) return rides;
  return rides.filter((r) => matchesNearbyConditions(r, f.conditions, f.onlyTrusted));
}
