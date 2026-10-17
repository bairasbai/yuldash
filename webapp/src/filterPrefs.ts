// ================================================================
//  Фильтры по умолчанию (localStorage) — зеркало `FilterPrefs` в приложении (SecondaryScreens.kt)
//  и `AppPrefs.verifiedOnly` («Только проверенные» из раздела «Безопасность»).
//  Не персональные данные — только UX-настройка ленты на устройстве.
//
//  Условия — семь ключей приложения: women, child, pets, baggage, ac, nosmoke, quiet
//  (список и подписи — nearbyFeed.ts). Ни «города», ни «потолка цены» здесь нет: в приложении
//  их нет, город заменён чипом «Откуда» в ленте, а цену сервер не фильтрует — клиентский потолок
//  давал бы ложную пустоту, когда подходящие поездки остались на следующих порциях.
//
//  «Только проверенные» с 17.10.2026 включены по умолчанию (решение Александра). Поэтому в хранилище
//  лежит не флаг, а ВЫБОР человека: `verifiedOnly` есть, только если он сам нажимал переключатель
//  в «Безопасности» (`saveVerifiedOnly`); нет — действует значение по умолчанию. Прежняя версия сайта
//  писала `onlyTrusted` при любом сохранении, в том числе с экрана «Фильтры»: `false` там значит и
//  «выключил сам», и «не трогал», — различить нельзя, поэтому он считается «не выбирал» (включено).
//  `true` прежде мог поставить только сам человек — это выбор, он сохраняется.
// ================================================================
import type { Ride } from "./api/rides";
import { isNearbyCondition, matchesNearbyConditions, NEARBY_CONDITION_KEYS, type NearbyCondition } from "./nearbyFeed";

export interface FilterPrefs {
  conditions: NearbyCondition[]; // условия пассажира — стартовые для ленты «Ближайшие»
  onlyTrusted: boolean; // «Проверенные водители на карте» (раздел «Безопасность»), только для «Ближайших»
}

/** «Только проверенные» у того, кто ни разу не трогал переключатель (решение Александра, 17.10.2026). */
export const TRUSTED_BY_DEFAULT = true;

export const emptyFilters: FilterPrefs = {
  conditions: [],
  onlyTrusted: TRUSTED_BY_DEFAULT,
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

type Stored = { conditions?: unknown; amenities?: unknown; onlyTrusted?: unknown; verifiedOnly?: unknown } | null;

/** Сохранённое как есть; битое или недоступное хранилище — null. */
function readStored(): Stored {
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return null;
    const o = JSON.parse(raw) as unknown;
    return o && typeof o === "object" && !Array.isArray(o) ? (o as Stored) : null;
  } catch {
    return null;
  }
}

/** Выбор человека в «Безопасности»: true/false — нажимал сам; null — не выбирал. */
function trustedChoice(o: Stored): boolean | null {
  if (typeof o?.verifiedOnly === "boolean") return o.verifiedOnly;
  return o?.onlyTrusted === true ? true : null; // прежнее `true` — включал сам; `false` — неразличимо
}

export function loadFilters(): FilterPrefs {
  const o = readStored();
  const stored = Array.isArray(o?.conditions) ? o.conditions : Array.isArray(o?.amenities) ? o.amenities : [];
  const keys = new Set<NearbyCondition>();
  for (const k of stored) {
    const key = typeof k === "string" ? LEGACY_CONDITION[k] ?? k : k;
    if (isNearbyCondition(key)) keys.add(key);
  }
  return {
    conditions: NEARBY_CONDITION_KEYS.filter((k) => keys.has(k)), // порядок — как в шторке
    onlyTrusted: trustedChoice(o) ?? TRUSTED_BY_DEFAULT,
  };
}

function write(conditions: readonly NearbyCondition[], choice: boolean | null): void {
  try {
    localStorage.setItem(KEY, JSON.stringify(choice == null ? { conditions } : { conditions, verifiedOnly: choice }));
  } catch {
    /* хранилище недоступно (приватный режим) — настройка живёт, пока открыт экран */
  }
}

/**
 * Сохранить условия «по умолчанию». «Только проверенные» отсюда не меняются (`f.onlyTrusted` не
 * читается): иначе экран «Фильтры» записал бы значение по умолчанию как выбор человека. Уже сделанный
 * выбор остаётся как был.
 */
export function saveFilters(f: FilterPrefs): void {
  write(f.conditions, trustedChoice(readStored()));
}

/** Переключатель «Проверенные водители на карте» в «Безопасности» — это и есть выбор человека. */
export function saveVerifiedOnly(on: boolean): void {
  write(loadFilters().conditions, on);
}

/**
 * Включено ли хоть одно условие с экрана «Фильтры». «Только проверенные» сюда не входит: её
 * переключатель живёт в «Безопасности» и, как в приложении (`AppPrefs.verifiedOnly`), режет только
 * «Ближайшие» на карте. Иначе чип «Фильтры включены» вёл бы на экран, где выключить нечего
 * (приёмка PR151).
 */
export function isFilterActive(f: FilterPrefs): boolean {
  return f.conditions.length > 0;
}

/**
 * Клиентское применение условий к списку поездок на вкладке «Поездки» (ленту «Ближайшие»
 * режет сервер — HomeScreen.tsx). Только условия: «проверенные» — настройка карты, см. выше.
 */
export function applyRideFilters(rides: Ride[], f: FilterPrefs): Ride[] {
  if (!isFilterActive(f)) return rides;
  return rides.filter((r) => matchesNearbyConditions(r, f.conditions, false));
}
