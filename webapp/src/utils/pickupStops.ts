import type { Ride } from "../api/rides";

/** Public waypoints have already been cleaned by the server. No user search strings here. */
export function pickupStops(ride: Ride): string[] {
  const ends = [(ride.from_city ?? "").trim().toLocaleLowerCase(), (ride.to_city ?? "").trim().toLocaleLowerCase()];
  return [...new Set((ride.waypoints ?? "").split("|").map(s => s.trim()).filter(s => s && !ends.includes(s.toLocaleLowerCase())))];
}

export function initialPickupStop(ride: Ride): string | null {
  const stops = pickupStops(ride);
  if (!stops.length) return null;
  if (ride.matched_stop && stops.includes(ride.matched_stop)) return ride.matched_stop;
  return ride.matched_from_start ? ride.from_city : null;
}

export function pickupStopReady(ride: Ride, picked: string | null): boolean {
  return !pickupStops(ride).length || picked === ride.from_city || (picked !== null && pickupStops(ride).includes(picked));
}

/** Route order comes only from the published route, never from a typed search. */
export function routePlaces(ride: Ride): string[] {
  return [ride.from_city, ...pickupStops(ride), ride.to_city];
}

export function dropoffStops(ride: Ride, pickup: string | null): string[] {
  if (pickup === null) return [];
  const route = routePlaces(ride), start = route.indexOf(pickup);
  return start >= 0 && start < route.length - 1 ? route.slice(start + 1) : [];
}

export function initialDropoffStop(ride: Ride, pickup: string | null): string | null {
  if (!pickupStops(ride).length) return ride.to_city;
  const place = ride.matched_to_end ? ride.to_city : ride.matched_dropoff_stop;
  return place && dropoffStops(ride, pickup).includes(place) ? place : null;
}

export function initialSegment(ride: Ride): { pickup: string | null; dropoff: string | null } {
  const pickup = pickupStops(ride).length ? initialPickupStop(ride) : ride.from_city;
  return { pickup, dropoff: initialDropoffStop(ride, pickup) };
}

export function segmentReady(ride: Ride, pickup: string | null, dropoff: string | null): boolean {
  return pickup !== null && dropoff !== null && pickupStopReady(ride, pickup) && dropoffStops(ride, pickup).includes(dropoff);
}

export function keepDropoffAfterPickup(ride: Ride, pickup: string, dropoff: string | null): string | null {
  return dropoff !== null && dropoffStops(ride, pickup).includes(dropoff) ? dropoff : null;
}

/** RS-02 Б: цена участка в целых рублях по формуле сервера (`ride_seats.segment_price`):
 *  цен остановок нет → полная; иначе cum(высадка) − cum(посадка), где cum(начало)=0,
 *  cum(конец)=полная цена. null — маршрут не собран (не выдумываем). */
export function segmentPriceRub(ride: Ride, pickup: string | null, dropoff: string | null): number | null {
  const stops = pickupStops(ride);
  const prices = ride.stop_prices ?? [];
  if (!prices.length || prices.length !== stops.length) {
    return ride.price > 0 ? ride.price : null;
  }
  const route = routePlaces(ride); // [начало, ...остановки, конец]
  const aIdx = pickup ? route.indexOf(pickup) : -1;
  const a = aIdx >= 0 ? aIdx : 0;
  const bIdx = dropoff ? route.indexOf(dropoff) : -1;
  const b = bIdx > a ? bIdx : route.length - 1;
  if (b <= a) return null;
  const cum = (point: number): number => {
    if (point <= 0) return 0;
    if (point >= route.length - 1) return ride.price;
    return prices[point - 1].price;
  };
  return cum(b) - cum(a);
}

/** RS-02 А: свободно на участке — min по его звеньям из seats_by_leg; список не сошёлся
 *  по длине или участок не собран — null (смотреть общий остаток). */
export function seatsOnSpan(ride: Ride, pickup: string | null, dropoff: string | null): number | null {
  const legs = ride.seats_by_leg ?? [];
  if (!legs.length) return null;
  const route = routePlaces(ride);
  if (legs.length !== route.length - 1) return null;
  const aIdx = pickup ? route.indexOf(pickup) : -1;
  const a = aIdx >= 0 ? aIdx : 0;
  const bIdx = dropoff ? route.indexOf(dropoff) : -1;
  const b = bIdx > a ? bIdx : route.length - 1;
  if (b <= a) return null;
  return Math.min(...legs.slice(a, b));
}

/** RS-02 Б: «Поделить по расстоянию» — как в Android (`splitPriceByLegs`): шаг 10 ₽,
 *  сломанный рост чинится «предыдущая + 1 ₽», не помещается → null. */
export function splitPriceByLegs(total: number, legKm: (number | null)[]): number[] | null {
  const stops = legKm.length - 1;
  if (stops < 1 || total <= stops) return null;
  const weights = legKm.map((d) => (d !== null && d > 0 ? d : 1));
  const sumKm = weights.reduce((s, w) => s + w, 0);
  const cum: number[] = [];
  let acc = 0;
  for (let i = 0; i < stops; i++) {
    acc += weights[i];
    cum.push(Math.floor((total * acc) / sumKm / 10) * 10);
  }
  let prev = 0;
  for (let i = 0; i < stops; i++) {
    let v = cum[i];
    if (v <= prev) v = prev + 1;
    if (v >= total) return null;
    cum[i] = v;
    prev = v;
  }
  return cum;
}

/** Суммы участков из кумулятивных цен (как в Android `legPriceAmounts`). */
export function legPriceAmounts(stopPrices: number[], total: number): number[] {
  const out: number[] = [];
  let prev = 0;
  for (const p of stopPrices) { out.push(p - prev); prev = p; }
  out.push(total - prev);
  return out;
}

export interface RideMatch {
  rideId: number;
  /**
   * Чей это выбор: поколение входа (`getSessionGeneration`) на момент, когда состояние положили в историю
   * вкладки. История переживает выход из аккаунта, а выбор посадки — след человека (куда он едет).
   * Состояние без метки или с чужой меткой не читаем. Через вход метка переезжает (`restampRideMatch`).
   */
  owner?: string;
  matched_stop: string | null;
  matched_from_start: boolean;
  matched_dropoff_stop?: string | null;
  matched_to_end?: boolean;
}

/** Keep the server's hint when the unfiltered details request has no match metadata. */
export function rideMatchState(ride: Ride, owner: string): { rideMatch: RideMatch } | undefined {
  if (!ride.matched_stop && !ride.matched_from_start && !ride.matched_dropoff_stop && !ride.matched_to_end) return undefined;
  return { rideMatch: { rideId: ride.id, owner, matched_stop: ride.matched_stop ?? null, matched_from_start: ride.matched_from_start === true,
    matched_dropoff_stop: ride.matched_dropoff_stop ?? null, matched_to_end: ride.matched_to_end === true } };
}

/** Выбор, сделанный гостем до входа, после входа принадлежит тому, кто вошёл: ставим ему новую метку. */
export function restampRideMatch(state: unknown, owner: string): unknown {
  const match = (state as { rideMatch?: RideMatch } | null)?.rideMatch;
  return match && typeof match === "object" ? { ...(state as object), rideMatch: { ...match, owner } } : state;
}

export function preserveRideMatch(ride: Ride, state: unknown, owner: string): Ride {
  const match = (state as { rideMatch?: RideMatch } | null)?.rideMatch;
  if (match?.rideId !== ride.id || typeof match.owner !== "string" || match.owner !== owner) return ride;
  return { ...ride, matched_stop: typeof match.matched_stop === "string" ? match.matched_stop : null, matched_from_start: match.matched_from_start === true,
    matched_dropoff_stop: typeof match.matched_dropoff_stop === "string" ? match.matched_dropoff_stop : null, matched_to_end: match.matched_to_end === true };
}

/** Account changes must not seed the next person from the old enriched search result. */
export function withoutRideMatch(ride: Ride): Ride {
  return { ...ride, matched_stop: null, matched_from_start: false,
    matched_dropoff_stop: null, matched_to_end: false };
}
