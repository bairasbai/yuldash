import type { RideCreateInput } from "../api/driver";
import { COMMAND_KEY_PREFIX, commandFingerprint } from "./commandFingerprint";
import { serverDate } from "./serverTime";

export const MAX_RIDE_SERIES_DATES = 10;

export type RideSeriesOutcome = "applied" | "rejected" | "unknown";
export interface RideSeriesResult {
  depart_at: string;
  outcome: RideSeriesOutcome;
  ride_id?: number | null;
  already?: boolean;
  code?: string | null;
  ru?: string | null;
  ba?: string | null;
}

export interface RideSeriesDate extends RideSeriesResult {
  commandKey: string;
  hash: string;
}

export interface RideSeriesIntent {
  mode: "series";
  key: string;
  createdAt: number;
  rideBody: string;
  draftHash?: string;
  items: RideSeriesDate[];
}

/** Первый POST и повтор используют тот же UTC-отпечаток каждой даты. */
export function rideSeriesMinute(value: string): string {
  const date = serverDate(value);
  if (!date) throw new RangeError("Invalid departure date");
  const stamp = date.toISOString();
  return stamp.slice(0, 16) + ":00Z";
}

export async function newRideSeriesIntent(
  draft: Omit<RideCreateInput, "depart_at">, departures: string[], baseKey = COMMAND_KEY_PREFIX + crypto.randomUUID(),
): Promise<RideSeriesIntent> {
  if (!departures.length || departures.length > MAX_RIDE_SERIES_DATES) {
    throw new RangeError("Invalid number of series dates");
  }
  const dates = [...new Set(departures.map(rideSeriesMinute))].sort();
  const { depart_at: _ignored, ...ride } = { ...draft } as RideCreateInput;
  const rideBody = JSON.stringify(ride);
  const items = await Promise.all(dates.map(async depart_at => {
    const wire = await commandFingerprint("ride.create", null, { ride, depart_at });
    const suffix = depart_at.slice(0, 16).replace(/[-T:]/g, "");
    return { depart_at, commandKey: baseKey + "-" + suffix, hash: wire.payloadHash, outcome: "unknown" as const };
  }));
  return { mode: "series", key: baseKey, createdAt: Date.now(), rideBody, items };
}
