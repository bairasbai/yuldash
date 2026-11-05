import type { Ride } from "../api/rides";

/** Count wins over a legacy seeded average; absent count remains compatible with older servers. */
export function rideDriverRating(ride: Pick<Ride, "driver_rating" | "driver_rating_count">): number | null {
  return (ride.driver_rating_count === undefined || ride.driver_rating_count > 0) &&
    Number.isFinite(ride.driver_rating) && ride.driver_rating > 0 ? ride.driver_rating : null;
}
