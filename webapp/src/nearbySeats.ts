import type { NearRidesPage } from "./api/discovery";
import type { Ride } from "./api/rides";
import { pluralRu } from "./utils/format";

export const GROUP_SIZES = Array.from({ length: 8 }, (_, index) => index + 1);

export function groupSize(value: unknown): number {
  return typeof value === "number" && Number.isInteger(value) && value >= 1 && value <= GROUP_SIZES.length
    ? value
    : 1;
}

export type SmallerGroup = { seats: number; rides: number };

export function rideFitsGroup(ride: Ride, seats: number): boolean {
  return (ride.seats_on_segment ?? Math.max(ride.seats_left, ...(ride.seats_by_leg ?? []))) >= seats;
}

export function smallerGroup(page: NearRidesPage, requested: number): SmallerGroup | null {
  const seats = page.max_free_seats;
  const rides = page.count_at_max_free;
  if (typeof seats !== "number" || groupSize(seats) !== seats || seats >= requested) return null;
  if (typeof rides !== "number" || !Number.isInteger(rides) || rides <= 0 || rides > (page.hidden_by_seats ?? 0)) return null;
  return { seats, rides };
}

export function smallerGroupLabel(group: SmallerGroup, ru: boolean): string {
  const { seats, rides } = group;
  return ru
    ? `Есть ${rides} ${pluralRu(rides, "поездка", "поездки", "поездок")}, где свободно ${seats} ${pluralRu(seats, "место", "места", "мест")}.`
    : `${seats} буш урынлы ${rides} сәфәр бар.`;
}
