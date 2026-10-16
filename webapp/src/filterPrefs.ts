// ================================================================
//  Фильтры по умолчанию (localStorage) — зеркало FilterPrefs в приложении.
//  Не персональные данные — только UX-настройка ленты/карты на устройстве.
//  Применяются к списку поездок клиентски (мягко, без нового API).
// ================================================================
import type { Ride } from "./api/rides";

export type Amenity =
  | "women_only"
  | "non_smoking"
  | "air_conditioner"
  | "baggage"
  | "child_seat"
  | "pets"
  | "quiet";

export interface FilterPrefs {
  city: string; // «мой город» — фильтр по совпадению в маршруте (откуда/куда)
  maxPrice: number | null; // ₽, потолок цены
  amenities: Amenity[]; // удобства
  onlyTrusted: boolean; // «только свои» (проверенные)
}

export const emptyFilters: FilterPrefs = {
  city: "",
  maxPrice: null,
  amenities: [],
  onlyTrusted: false,
};

const KEY = "yuldash.filterPrefs";

export function loadFilters(): FilterPrefs {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return { ...emptyFilters };
    const o = JSON.parse(raw) as Partial<FilterPrefs>;
    return {
      city: typeof o.city === "string" ? o.city : "",
      maxPrice:
        typeof o.maxPrice === "number" && o.maxPrice > 0 ? o.maxPrice : null,
      amenities: Array.isArray(o.amenities) ? (o.amenities as Amenity[]) : [],
      onlyTrusted: !!o.onlyTrusted,
    };
  } catch {
    return { ...emptyFilters };
  }
}

export function saveFilters(f: FilterPrefs): void {
  localStorage.setItem(KEY, JSON.stringify(f));
}

/**
 * Сброс фильтров ленты: город, потолок цены, удобства.
 *
 * «Только проверенные» (`onlyTrusted`) — это настройка раздела «Безопасность», а не чип ленты
 * (в приложении этого переключателя в ряду фильтров нет). Поэтому обычный сброс её оставляет:
 * иначе одно нажатие «Сбросить» молча выключило бы защиту, которую человек включил сам.
 * Полный сброс (`keepTrusted = false`) — только с экрана «Фильтры», где переключатель виден.
 * Возвращает то, что осталось после сброса.
 */
export function clearFilters(keepTrusted = true): FilterPrefs {
  const keep = keepTrusted && loadFilters().onlyTrusted;
  const next: FilterPrefs = { ...emptyFilters, amenities: [], onlyTrusted: keep };
  try {
    if (keep) saveFilters(next);
    else localStorage.removeItem(KEY);
  } catch {
    /* хранилище недоступно — фильтры живут только на экране */
  }
  return next;
}

/**
 * Сколько условий ленты включено — для чипа «Сбросить · N».
 * «Только проверенные» не считаем: сбросом она не снимается (см. `clearFilters`).
 */
export function feedFilterCount(f: FilterPrefs): number {
  return f.amenities.length + (f.city.trim() ? 1 : 0) + (f.maxPrice != null ? 1 : 0);
}

/** Активен ли хоть один фильтр (для баннера «фильтры включены»). */
export function isFilterActive(f: FilterPrefs): boolean {
  return (
    f.city.trim().length > 0 ||
    f.maxPrice != null ||
    f.amenities.length > 0 ||
    f.onlyTrusted
  );
}

/**
 * Клиентское применение фильтров к списку поездок.
 * Проверяем только те поля, что есть в публичной карточке Ride
 * (non_smoking/air_conditioner в неё не входят — они для заявки пассажира,
 * поэтому к ленте не применяются, но сохраняются как дефолт формы).
 */
export function applyRideFilters(rides: Ride[], f: FilterPrefs): Ride[] {
  if (!isFilterActive(f)) return rides;
  const city = f.city.trim().toLowerCase();
  return rides.filter((r) => {
    if (city) {
      const inRoute =
        r.from_city.toLowerCase().includes(city) ||
        r.to_city.toLowerCase().includes(city);
      if (!inRoute) return false;
    }
    if (f.maxPrice != null && r.price > f.maxPrice) return false;
    if (f.amenities.includes("women_only") && !r.women_only) return false;
    if (f.amenities.includes("baggage") && !r.baggage) return false;
    if (f.amenities.includes("child_seat") && !r.child_seat) return false;
    if (f.amenities.includes("pets") && !r.pets_allowed) return false;
    // Тихая поездка: условие водитель отмечал давно, а искать по нему было нельзя.
    if (f.amenities.includes("quiet") && !r.quiet) return false;
    if (f.onlyTrusted && !r.driver_verified) return false;
    return true;
  });
}
