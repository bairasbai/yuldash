// ================================================================
//  Подписки на маршрут «карауль поездку» (зеркало
//  backend/app/routers/route_watch.py). Пользователь подписывается на
//  маршрут (Сибай→Уфа). Подписка живёт 14 дней.
// ================================================================
import { ApiError, apiCommand, type getAccountSession } from "./client";
import { serverDate } from "../utils/serverTime";

export type WatchDirection = "forward" | "both";
export type WatchKind = "rides" | "requests" | "both";
export type WatchOwner = NonNullable<ReturnType<typeof getAccountSession>>;
export type WatchRoute = { from: string; to: string; day?: string | null };

/** Подписка на маршрут (GET /route-watch). */
export interface RouteWatch {
  id: number;
  from_city: string;
  to_city: string;
  watch_date?: string | null; // ISO, опц.
  direction: WatchDirection;
  watch_kind: WatchKind;
  created_at: string;
  expires_at: string;
  last_notified_at: string | null;
  registered_push_channels?: { app: boolean; web: boolean };
  quiet_from?: number;
  quiet_to?: number;
}

/** Тело создания (POST /route-watch). */
export interface RouteWatchInput {
  from_city: string;
  to_city: string;
  watch_date?: string | null;
  direction?: WatchDirection;
  watch_kind?: WatchKind;
}

const yuldashCalendar = new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Yekaterinburg", year: "numeric", month: "2-digit", day: "2-digit" });
const localInputDate = /^(\d{4}-\d{2}-\d{2})(?:[T ]\d{2}:\d{2}(?::\d{2})?(?:\.\d+)?)?$/;

export function validWatchPlace(name: string): boolean {
  const letters = name.match(/\p{L}/gu);
  return Array.from(name).length <= 120 && (letters?.length ?? 0) >= 2 && !/\p{N}/u.test(name);
}

function watchPlaceKey(name: string): string {
  // Full case fold works per character, without the surrounding word's final-sigma rule.
  return Array.from(name.trim(), (character) => {
    if (character === "ı") return character;
    if (character === "ẞ") return "ss";
    if (/\p{Script=Cherokee}/u.test(character)) return character.toUpperCase();
    return character.toUpperCase().toLowerCase();
  }).join("");
}

export function watchLocalDay(iso?: string | null): string | null {
  if (!iso) return null;
  if (/^\d{4}-\d{2}-\d{2}$/.test(iso)) return iso;
  const instant = serverDate(iso);
  return instant ? yuldashCalendar.format(instant) : null;
}

export function matchingRouteWatch(watches: RouteWatch[], route: WatchRoute, direction: WatchDirection = "forward"): RouteWatch | undefined {
  const from = watchPlaceKey(route.from);
  const to = watchPlaceKey(route.to);
  let exact: RouteWatch | undefined;
  for (const watch of watches) {
    if (watch.watch_kind === "rides" && watch.direction === direction
      && watchPlaceKey(watch.from_city) === from && watchPlaceKey(watch.to_city) === to
      && (watch.watch_date ? watchLocalDay(watch.watch_date) === route.day : !route.day)
      && (!exact || watch.id < exact.id)) exact = watch;
  }
  return exact;
}

export function routeWatchPath({ from, to, day }: WatchRoute): string {
  const query = new URLSearchParams();
  if (from.trim()) query.set("from", from.trim());
  if (to.trim()) query.set("to", to.trim());
  if (day) query.set("date", day);
  return query.size ? `/route-watches?${query}` : "/route-watches";
}

export function routeWatchOutcomeUnknown(error: unknown): boolean {
  return !(error instanceof ApiError) || error.status === 0 || error.status >= 500;
}

function readRouteWatch(value: unknown): RouteWatch {
  if (!value || typeof value !== "object") throw new TypeError("Invalid route watch");
  const row = value as Partial<RouteWatch>;
  const direction = row.direction === undefined ? "forward" : row.direction;
  const kind = row.watch_kind === undefined ? "rides" : row.watch_kind;
  if (typeof row.id !== "number" || !Number.isSafeInteger(row.id) || row.id <= 0 ||
      typeof row.from_city !== "string" || !row.from_city.trim() ||
      typeof row.to_city !== "string" || !row.to_city.trim() ||
      (direction !== "forward" && direction !== "both") ||
      (kind !== "rides" && kind !== "requests" && kind !== "both") ||
      (row.watch_date != null && (typeof row.watch_date !== "string" || !serverDate(row.watch_date)))) {
    throw new TypeError("Invalid route watch identity");
  }
  return { ...row, direction, watch_kind: kind } as RouteWatch;
}

export async function fetchRouteWatches(owner: WatchOwner, signal?: AbortSignal): Promise<RouteWatch[]> {
  const rows = await apiCommand<unknown>("/route-watch", owner, { method: "GET", signal });
  if (!Array.isArray(rows)) throw new TypeError("Invalid route watch list");
  return rows.map(readRouteWatch);
}

export async function createRouteWatch(owner: WatchOwner, body: RouteWatchInput): Promise<RouteWatch> {
  const row = readRouteWatch(await apiCommand<unknown>("/route-watch", owner, { method: "POST", body: JSON.stringify(body) }));
  // Input without an offset is Ufa time. Returned timestamps without an offset are UTC.
  const inputDate = body.watch_date?.trim();
  const requestedDay = inputDate?.match(localInputDate)?.[1] ?? watchLocalDay(inputDate);
  if (watchPlaceKey(row.from_city) !== watchPlaceKey(body.from_city) ||
      watchPlaceKey(row.to_city) !== watchPlaceKey(body.to_city) ||
      row.direction !== (body.direction ?? "forward") || row.watch_kind !== (body.watch_kind ?? "rides") ||
      watchLocalDay(row.watch_date) !== requestedDay) {
    throw new TypeError("Unexpected route watch identity");
  }
  return row;
}

export async function deleteRouteWatch(owner: WatchOwner, id: number): Promise<{ ok: boolean }> {
  const result = await apiCommand<{ ok?: unknown } | null>(`/route-watch/${id}`, owner, { method: "DELETE" });
  if (result?.ok !== true) throw new TypeError("Unconfirmed route watch deletion");
  return { ok: true };
}
