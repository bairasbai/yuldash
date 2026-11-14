import { getSessionGeneration } from "../api/client";
import { SEARCH_HISTORY_KEY } from "./privacy";
import { readSessionRecord } from "./sessionRecord";
import { sessionMutation, storageFenceEnabled, storageFenced, storageOperation } from "./storageHealth";

const LIMIT = 5;
const REFINE_MS = 2 * 60 * 1000;
const listeners = new Set<() => void>();

export function subscribeSearchHistory(listener: () => void): () => void {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
}

export interface SearchRoute {
  from: readonly string[];
  to: readonly string[];
  labels?: Readonly<Record<string, string>>;
}

export interface SearchEntry extends SearchRoute {
  at: number;
}

const cityKey = (value: string) => value.trim().toLowerCase().replaceAll("ё", "е");
const sideKey = (cities: readonly string[]) => cities.map(cityKey).sort();
export const searchRouteKey = ({ from, to }: SearchRoute) => JSON.stringify([sideKey(from), sideKey(to)]);

function names(value: unknown): string[] | null {
  if (!Array.isArray(value) || value.length > 3 || value.some(city =>
    typeof city !== "string" || !city.trim() || city.length > 120)) return null;
  const unique = new Map<string, string>();
  for (const city of value as string[]) unique.set(cityKey(city), city.trim());
  return [...unique.values()];
}

function routeOf(value: unknown): SearchRoute | null {
  if (!value || typeof value !== "object") return null;
  const row = value as Partial<SearchRoute>;
  const from = names(row.from);
  const to = names(row.to);
  if (!from || !to || (!from.length && !to.length)) return null;
  const labels: Record<string, string> = Object.create(null);
  for (const city of [...from, ...to]) {
    const translated = row.labels && Object.prototype.hasOwnProperty.call(row.labels, city) ? row.labels[city] : undefined;
    if (typeof translated === "string" && translated.trim() && translated.length <= 120 && translated !== city) {
      labels[city] = translated.trim();
    }
  }
  return Object.keys(labels).length ? { from, to, labels } : { from, to };
}

export function updateSearchHistory(
  entries: readonly SearchEntry[], route: SearchRoute, at: number, refine = true
): SearchEntry[] {
  const next = routeOf(route);
  if (!next || !Number.isSafeInteger(at) || at < 0) return [...entries];
  const key = searchRouteKey(next);
  const last = entries[0];
  const extendsSide = (before: readonly string[], after: readonly string[]) => {
    const keys = sideKey(after);
    return before.every(city => keys.includes(cityKey(city)));
  };
  const age = last ? at - last.at : -1;
  const refines = refine && last && age >= 0 && age < REFINE_MS && extendsSide(last.from, next.from) && extendsSide(last.to, next.to);
  return [{ ...next, at }, ...entries.filter((entry, i) => !(i === 0 && refines) && searchRouteKey(entry) !== key)].slice(0, LIMIT);
}

function readLocal(generation: string): SearchEntry[] {
  const raw = storageOperation(() => localStorage.getItem(SEARCH_HISTORY_KEY));
  if (!raw) return [];
  try {
    const saved = JSON.parse(raw);
    if (saved?.generation !== generation || !Array.isArray(saved?.entries)) return [];
    return saved.entries.slice(0, LIMIT).flatMap((row: unknown) => {
      const route = routeOf(row);
      const at = (row as Partial<SearchEntry> | null)?.at;
      return route && typeof at === "number" && Number.isSafeInteger(at) && at >= 0 ? [{ ...route, at }] : [];
    });
  } catch {
    return [];
  }
}

/** Fence durable reads and mutations against login/logout in this and other tabs. */
async function withHistory<T>(generation: string, work: () => T): Promise<T | undefined> {
  const current = () => !!generation && !storageFenced() && generation === getSessionGeneration();
  if (!current() || !storageFenceEnabled() || typeof navigator === "undefined" || !navigator.locks) return;
  try {
    return await sessionMutation(async () => {
      const saved = await readSessionRecord();
      if (current() && saved.generation === generation) return work();
    });
  } catch {
    // An unavailable store just hides this optional history.
  }
}

export function readSearchHistory(generation: string): Promise<SearchEntry[] | undefined> {
  return withHistory(generation, () => readLocal(generation));
}

function editHistory(generation: string, edit: (entries: SearchEntry[]) => SearchEntry[]) {
  return withHistory(generation, () => {
    const existing = readLocal(generation);
    const next = edit(existing);
    if (next === existing) return existing;
    storageOperation(() => {
      if (next.length) localStorage.setItem(SEARCH_HISTORY_KEY, JSON.stringify({ generation, entries: next }));
      else localStorage.removeItem(SEARCH_HISTORY_KEY);
    });
    listeners.forEach(listener => listener());
    return next;
  });
}

export const recordSearch = (generation: string, route: SearchRoute, refine = true) => {
  const snapshot = routeOf(route);
  return snapshot ? editHistory(generation, entries => updateSearchHistory(entries, snapshot, Date.now(), refine)) : Promise.resolve(undefined);
};

export async function repeatSearch(generation: string, key: string, canApply: () => boolean): Promise<SearchEntry | undefined> {
  let selected: SearchEntry | undefined;
  const saved = await editHistory(generation, entries => {
    if (!canApply()) return entries;
    selected = entries.find(entry => searchRouteKey(entry) === key);
    return selected ? updateSearchHistory(entries, selected, Date.now(), false) : entries;
  });
  return saved ? selected : undefined;
}

export const removeSearch = (generation: string, key: string) =>
  editHistory(generation, entries => entries.filter(entry => searchRouteKey(entry) !== key));

export const clearSearchHistory = (generation: string) => editHistory(generation, () => []);
