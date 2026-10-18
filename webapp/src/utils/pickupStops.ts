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

export interface RideMatch {
  rideId: number;
  matched_stop: string | null;
  matched_from_start: boolean;
}

/** Keep the server's hint when the unfiltered details request has no match metadata. */
export function rideMatchState(ride: Ride): { rideMatch: RideMatch } | undefined {
  if (!ride.matched_stop && !ride.matched_from_start) return undefined;
  return { rideMatch: { rideId: ride.id, matched_stop: ride.matched_stop ?? null, matched_from_start: ride.matched_from_start === true } };
}

export function preserveRideMatch(ride: Ride, state: unknown): Ride {
  const match = (state as { rideMatch?: RideMatch } | null)?.rideMatch;
  if (match?.rideId !== ride.id) return ride;
  return { ...ride, matched_stop: typeof match.matched_stop === "string" ? match.matched_stop : null, matched_from_start: match.matched_from_start === true };
}
