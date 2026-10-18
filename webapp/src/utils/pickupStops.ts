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

export interface RideMatch {
  rideId: number;
  matched_stop: string | null;
  matched_from_start: boolean;
  matched_dropoff_stop?: string | null;
  matched_to_end?: boolean;
}

/** Keep the server's hint when the unfiltered details request has no match metadata. */
export function rideMatchState(ride: Ride): { rideMatch: RideMatch } | undefined {
  if (!ride.matched_stop && !ride.matched_from_start && !ride.matched_dropoff_stop && !ride.matched_to_end) return undefined;
  return { rideMatch: { rideId: ride.id, matched_stop: ride.matched_stop ?? null, matched_from_start: ride.matched_from_start === true,
    matched_dropoff_stop: ride.matched_dropoff_stop ?? null, matched_to_end: ride.matched_to_end === true } };
}

export function preserveRideMatch(ride: Ride, state: unknown): Ride {
  const match = (state as { rideMatch?: RideMatch } | null)?.rideMatch;
  if (match?.rideId !== ride.id) return ride;
  return { ...ride, matched_stop: typeof match.matched_stop === "string" ? match.matched_stop : null, matched_from_start: match.matched_from_start === true,
    matched_dropoff_stop: typeof match.matched_dropoff_stop === "string" ? match.matched_dropoff_stop : null, matched_to_end: match.matched_to_end === true };
}
