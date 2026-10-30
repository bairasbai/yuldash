/**
 * RS-11А: правила истории поиска без хранилища и сетевых запросов.
 * Подключение localStorage откладывается до очереди RS-07А → privacy.ts:
 * личный ключ должен попасть в очистку при выходе ДО первой записи.
 * Этот модуль пока не подключён к экранам (RS-10Б → RidesScreen).
 */
export interface SearchRoute {
  readonly from: readonly string[];
  readonly to: readonly string[];
}

export interface SearchHistoryEntry extends SearchRoute {
  readonly at: number;
}

export const SEARCH_HISTORY_LIMIT = 5;
const REFINE_WINDOW_MS = 2 * 60 * 1000;
const fold = (name: string) => name.toLowerCase().replace(/ё/g, "е");

function names(value: unknown): string[] | null {
  if (!Array.isArray(value) || value.length > 3) return null;
  if (value.some((name) => typeof name !== "string" || !name.trim())) return null;
  const seen = new Set<string>();
  return (value as string[]).map((name) => name.trim()).filter((name) => {
    const key = fold(name);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function route(value: unknown): SearchRoute | null {
  if (!value || typeof value !== "object") return null;
  const data = value as Record<string, unknown>;
  const from = names(data.from), to = names(data.to);
  return from && to && (from.length || to.length) ? { from, to } : null;
}

/** Ключ не зависит от языка интерфейса, порядка городов, регистра и ё/е. */
export function searchHistoryKey(value: SearchRoute): string | null {
  const valid = route(value);
  if (!valid) return null;
  // JSON отделяет названия друг от друга даже при запятых и символе > в имени.
  return `${JSON.stringify(valid.from.map(fold).sort())}>${JSON.stringify(valid.to.map(fold).sort())}`;
}

/** Битая запись не мешает поиску. Никаких дат поездки, координат или аккаунта. */
export function parseSearchHistory(json: string | null): SearchHistoryEntry[] {
  try {
    const parsed: unknown = JSON.parse(json ?? "null");
    if (!Array.isArray(parsed)) return [];
    const entries: SearchHistoryEntry[] = [];
    const seen = new Set<string>();
    for (const value of parsed) {
      const valid = route(value);
      const at: unknown = value?.at;
      if (!valid || typeof at !== "number" || !Number.isFinite(at) || at < 0) continue;
      const key = searchHistoryKey(valid)!;
      if (seen.has(key)) continue;
      seen.add(key);
      entries.push({ ...valid, at });
      if (entries.length === SEARCH_HISTORY_LIMIT) break;
    }
    return entries;
  } catch {
    return [];
  }
}

function contains(next: readonly string[], previous: readonly string[]): boolean {
  const chosen = new Set(next.map(fold));
  return previous.every((name) => chosen.has(fold(name)));
}

/** Записывать при закрытии шторки/выборе маршрута, а не на каждую галочку. */
export function recordSearchHistory(
  entries: readonly SearchHistoryEntry[],
  value: SearchRoute,
  now = Date.now()
): SearchHistoryEntry[] {
  const valid = route(value);
  if (!valid || !Number.isFinite(now) || now < 0) return [...entries];
  const key = searchHistoryKey(valid);
  const last = entries[0];
  const elapsed = last ? now - last.at : Infinity;
  const refinesLast = last && elapsed >= 0 && elapsed < REFINE_WINDOW_MS
    && contains(valid.from, last.from) && contains(valid.to, last.to);
  return [
    { ...valid, at: now },
    ...entries.filter((entry, index) => !(index === 0 && refinesLast) && searchHistoryKey(entry) !== key),
  ].slice(0, SEARCH_HISTORY_LIMIT);
}

export function removeSearchHistory(entries: readonly SearchHistoryEntry[], key: string): SearchHistoryEntry[] {
  return entries.filter((entry) => searchHistoryKey(entry) !== key);
}

export function clearSearchHistory(): SearchHistoryEntry[] {
  return [];
}

/** Повтор меняет только маршрут: текущие день (включая null), условия и места остаются. */
export function repeatSearchHistory<T extends SearchRoute>(current: T, entry: SearchRoute): T {
  return { ...current, from: [...entry.from], to: [...entry.to] };
}
