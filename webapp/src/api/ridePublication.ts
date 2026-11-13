import { ApiError, apiCommand } from "./client";
import type { RideCreateInput } from "./driver";
import type { Ride } from "./rides";
import { commandFingerprint } from "../utils/commandFingerprint";
import {
  readPublicationSlot, reserveRidePublication, resolveRidePublication,
  type BookingCommandOwner, type RidePublication,
} from "../utils/bookingCommandStore";

const SCOPE = "ride.create";
const RETRY_WINDOW = 24 * 60 * 60 * 1000;

export type PublicationResult =
  | { state: "applied"; ride: Ride | null }
  | { state: "unseen" | "rejected" | "closed_no_effect" };

export class PublicationProblem extends Error {
  constructor(
    readonly kind: "storage" | "unknown" | "changed" | "expired" | "stale",
    readonly command?: RidePublication,
    readonly original?: ApiError,
  ) { super(kind); }
}

export async function publicationDraftHash(draft: unknown): Promise<string> {
  return (await commandFingerprint("ride.create.draft", null, draft)).payloadHash;
}

function ownedRide(ride: Ride, owner: BookingCommandOwner): Ride {
  if (ride.driver_id !== owner.userId || !Number.isSafeInteger(ride.id) || ride.id <= 0 ||
      !["active", "done", "cancelled", "expired"].includes(ride.status ?? "")) {
    throw new Error("Unexpected publication result");
  }
  return ride;
}

async function settle(owner: BookingCommandOwner, command: RidePublication, state: "applied" | "rejected" | "closed_no_effect") {
  if (!await resolveRidePublication(owner, command, state)) throw new PublicationProblem("stale");
}

export async function checkRidePublication(
  owner: BookingCommandOwner, command: RidePublication, close: boolean, signal: AbortSignal,
): Promise<PublicationResult> {
  const path = "/command-intents/" + encodeURIComponent(command.key);
  const view = await apiCommand<{
    state: string; scope: string | null; ref_kind: string | null; ref_id: number | null; current: Ride | null;
  }>(path + (close ? "/close" : ""), owner, close ? {
    method: "POST", signal, body: JSON.stringify({ scope: SCOPE, target_id: null, payload_hash: command.hash }),
  } : { method: "GET", signal });
  if (view.state === "unseen" && !close && view.scope === null) {
    const { command: current } = await readPublicationSlot(owner);
    if (current?.key !== command.key || current.state !== "unknown") throw new PublicationProblem("stale");
    return { state: "unseen" };
  }
  if (view.scope !== SCOPE) throw new Error("Unexpected publication scope");
  if (view.state === "applied" && view.ref_kind === "ride") {
    const ride = view.current === null ? null : ownedRide(view.current, owner);
    if (ride && view.ref_id !== ride.id) throw new Error("Publication receipt does not match ride");
    await settle(owner, command, "applied");
    return { state: "applied", ride };
  }
  if (view.state === "closed_no_effect" || view.state === "rejected") {
    await settle(owner, command, view.state);
    return { state: view.state };
  }
  throw new Error("Unexpected publication state");
}

export async function publishRide(
  body: RideCreateInput, owner: BookingCommandOwner, signal: AbortSignal,
  options: Pick<RidePublication, "draftHash" | "stopLanguage" | "utcOffsetMinutes"> &
    { revision: string | null; retry?: RidePublication },
): Promise<{ command: RidePublication; result: PublicationResult }> {
  const { revision, retry, ...normalization } = options;
  let command: RidePublication;
  let wireBody: string;
  try {
    const snapshot = await commandFingerprint(SCOPE, null, body);
    if (snapshot.wireBody === undefined) throw new Error("Missing publication body");
    wireBody = snapshot.wireBody;
    if (retry) {
      const { command: current } = await readPublicationSlot(owner);
      if (!current || current.key !== retry.key) throw new PublicationProblem("stale");
      if (current.state !== "unknown") throw new PublicationProblem("unknown", current);
      if (current.hash !== snapshot.payloadHash) throw new PublicationProblem("changed", current);
      const age = Date.now() - current.createdAt;
      if (age < 0 || age >= RETRY_WINDOW) throw new PublicationProblem("expired", current);
      command = current;
    } else {
      const saved = await reserveRidePublication(owner, revision, snapshot.payloadHash, normalization);
      if (saved.status === "stale") throw new PublicationProblem("stale");
      if (saved.status === "existing") throw new PublicationProblem("unknown", saved.command);
      command = saved.command;
    }
  } catch (error) {
    if (error instanceof PublicationProblem) throw error;
    throw new PublicationProblem("storage");
  }

  try {
    const ride = ownedRide(await apiCommand<Ride>("/rides/commands/v1", owner, {
      method: "POST", body: wireBody, key: command.key, signal,
    }), owner);
    await settle(owner, command, "applied");
    return { command, result: { state: "applied", ride } };
  } catch (error) {
    if (error instanceof PublicationProblem) throw error;
    const refusal = error instanceof ApiError && error.status >= 400 && error.status < 500 &&
      ![401, 404, 405, 408, 429].includes(error.status) ? error : undefined;
    if (refusal && !signal.aborted) {
      const result = await checkRidePublication(owner, command, false, signal).catch(() => undefined);
      if (result && result.state !== "unseen") return { command, result };
    }
    throw new PublicationProblem("unknown", command, refusal);
  }
}
