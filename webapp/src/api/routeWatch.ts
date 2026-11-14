// ================================================================
//  Подписки на маршрут «карауль поездку» (зеркало
//  backend/app/routers/route_watch.py). Пользователь подписывается на
//  маршрут (Сибай→Уфа). Подписка живёт 14 дней.
// ================================================================
import { apiCommand, type getAccountSession } from "./client";
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

export function matchingRouteWatch(watches: RouteWatch[], route: WatchRoute): RouteWatch | undefined {
  const from = watchPlaceKey(route.from);
  const to = watchPlaceKey(route.to);
  let exact: RouteWatch | undefined;
  for (const watch of watches) {
    if (watch.watch_kind === "rides" && watch.direction === "forward"
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

export function fetchRouteWatches(owner: WatchOwner, signal?: AbortSignal): Promise<RouteWatch[]> {
  return apiCommand<RouteWatch[]>("/route-watch", owner, { method: "GET", signal });
}

export function createRouteWatch(owner: WatchOwner, body: RouteWatchInput): Promise<RouteWatch> {
  return apiCommand<RouteWatch>("/route-watch", owner, { method: "POST", body: JSON.stringify(body) });
}

export function deleteRouteWatch(owner: WatchOwner, id: number): Promise<{ ok: boolean }> {
  return apiCommand<{ ok: boolean }>(`/route-watch/${id}`, owner, { method: "DELETE" });
}
