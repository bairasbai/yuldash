import { apiCommand, reconcileSession } from "./client";
import type { RideCreateInput } from "./driver";
import {
  readPublicationSlot, replaceRejectedSeriesDates, reserveRideSeries, updateRideSeries, type BookingCommandOwner,
} from "../utils/bookingCommandStore";
import {
  newRideSeriesIntent, rideSeriesMinute, type RideSeriesDate, type RideSeriesIntent, type RideSeriesResult,
} from "../utils/rideSeriesIntent";

const RETRY_WINDOW = 24 * 60 * 60 * 1000;

export function canRepeatRideSeries(intent: RideSeriesIntent, now = Date.now()): boolean {
  const age = now - intent.createdAt;
  return age >= 0 && age < RETRY_WINDOW && intent.items.some(item => item.outcome === "unknown");
}

async function currentSeries(owner: BookingCommandOwner, intent: RideSeriesIntent): Promise<RideSeriesIntent> {
  await reconcileSession();
  const { series } = await readPublicationSlot(owner);
  if (series?.key !== intent.key) throw new Error("Ride series changed");
  return series;
}

async function persist(owner: BookingCommandOwner, intent: RideSeriesIntent, updates: RideSeriesResult[]) {
  const saved = await updateRideSeries(owner, intent, updates);
  if (!saved) throw new Error("Ride series changed");
  return saved;
}

export async function sendRideSeries(owner: BookingCommandOwner, intent: RideSeriesIntent, signal: AbortSignal) {
  const active = await currentSeries(owner, intent);
  if (!canRepeatRideSeries(active)) return checkRideSeries(owner, active, signal);
  const departures = active.items.filter(item => item.outcome === "unknown").map(item => item.depart_at);
  const data = await apiCommand<{ items: RideSeriesResult[] }>("/rides/series", owner, {
    method: "POST", key: active.key, signal, oneShot: true,
    allowAttempt: async () => (await readPublicationSlot(owner)).series?.key === active.key,
    body: JSON.stringify({ ride: JSON.parse(active.rideBody), departures }),
  });
  const expected = new Set(departures);
  if (!Array.isArray(data.items) || data.items.length !== expected.size) throw new Error("Unexpected series response");
  const updates = data.items.map(item => {
    const depart_at = rideSeriesMinute(item.depart_at);
    if (!expected.delete(depart_at) || !["applied", "rejected", "unknown"].includes(item.outcome) ||
        (item.outcome === "applied" && !positiveId(item.ride_id))) {
      throw new Error("Unexpected departure status");
    }
    const { outcome, ride_id, already, code, ru, ba } = item;
    return { depart_at, outcome, ride_id, already, code, ru, ba };
  });
  return persist(owner, active, updates);
}

export async function publishRideSeries(
  body: Omit<RideCreateInput, "depart_at">, departures: string[], owner: BookingCommandOwner,
  revision: string | null, draftHash: string, signal: AbortSignal,
) {
  const intent = { ...await newRideSeriesIntent(body, departures), draftHash };
  await reconcileSession();
  if (signal.aborted || !await reserveRideSeries(owner, revision, intent)) throw new Error("Ride series storage changed");
  return sendRideSeries(owner, intent, signal);
}

export async function publishRejectedRideSeriesDates(
  owner: BookingCommandOwner, intent: RideSeriesIntent, departures: string[], replacedDates: string[], signal: AbortSignal,
) {
  const retry = { ...await newRideSeriesIntent(JSON.parse(intent.rideBody), departures), draftHash: intent.draftHash };
  await reconcileSession();
  if (signal.aborted) throw new Error("Ride series request stopped");
  const saved = await replaceRejectedSeriesDates(owner, intent, retry, replacedDates);
  if (!saved) throw new Error("Ride series changed");
  return sendRideSeries(owner, saved, signal);
}

function positiveId(value: number | null | undefined): boolean {
  return value != null && Number.isSafeInteger(value) && value > 0;
}

async function readDate(
  owner: BookingCommandOwner, intent: RideSeriesIntent, date: RideSeriesDate, signal: AbortSignal, close = false,
): Promise<RideSeriesResult | null> {
  const path = "/command-intents/" + encodeURIComponent(date.commandKey);
  const view = await apiCommand<{
    state: string; scope: string | null; ref_kind: string | null; ref_id: number | null; result_code: string | null;
    current: { id: number; driver_id: number } | null;
  }>(path + (close ? "/close" : ""), owner, {
    method: close ? "POST" : "GET", signal, oneShot: close,
    allowAttempt: async () => (await readPublicationSlot(owner)).series?.key === intent.key,
    ...(close ? { body: JSON.stringify({ scope: "ride.create", target_id: null, payload_hash: date.hash }) } : {}),
  });
  const depart_at = date.depart_at;
  if (!close && view.state === "unseen" && view.scope === null) return null;
  if (view.scope !== "ride.create") throw new Error("Unexpected series scope");
  if (view.state === "applied" && view.ref_kind === "ride" && positiveId(view.ref_id) &&
      (!view.current || (view.current.id === view.ref_id && view.current.driver_id === owner.userId))) {
    return { depart_at, outcome: "applied", ride_id: view.ref_id, already: true, code: view.result_code };
  }
  if (view.state === "rejected" || view.state === "closed_no_effect") {
    return { depart_at, outcome: "rejected", code: view.result_code };
  }
  throw new Error("Unexpected series state");
}

export async function checkRideSeries(owner: BookingCommandOwner, intent: RideSeriesIntent, signal: AbortSignal) {
  const active = await currentSeries(owner, intent);
  const requests = active.items.filter(item => item.outcome === "unknown").map(item => readDate(owner, active, item, signal));
  const results = await Promise.allSettled(requests);
  const updates = results.flatMap(result => result.status === "fulfilled" && result.value ? [result.value] : []);
  const updated = await persist(owner, active, updates);
  if (results.some(result => result.status === "rejected")) throw new Error("Cannot check all departures");
  return updated;
}

export async function closeRideSeriesDate(
  owner: BookingCommandOwner, intent: RideSeriesIntent, departAt: string, signal: AbortSignal,
) {
  const active = await currentSeries(owner, intent);
  const date = active.items.find(item => item.depart_at === departAt && item.outcome === "unknown");
  if (!date) return active;
  const result = await readDate(owner, active, date, signal, true);
  if (!result) throw new Error("Series date outcome is missing");
  return persist(owner, active, [result]);
}
