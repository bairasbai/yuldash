import { useEffect, useRef, useState } from "react";
import { ApiError, getAccountSession, isAccountSession } from "../api/client";
import { fetchInstantOrder, type InstantOrder, type StopControl, type StopRequestScope } from "../api/instant";

const UUID = /^[\da-f]{8}-(?:[\da-f]{4}-){3}[\da-f]{12}$/;
export const stopId = (x: unknown): x is string => typeof x === "string" && UUID.test(x);
const optionalId = (x: unknown) => x === null || stopId(x);

/** undefined is legacy; null indicates a present but unusable capability. */
export function taxiStopControl(order: InstantOrder): StopControl | null | undefined {
  const c = order.stop_control;
  if (!Object.hasOwn(order, "stop_control")) return undefined;
  if (order.status !== "onboard") return null;
  if (!c || typeof c.admission_enabled !== "boolean" || !optionalId(c.route_revision) ||
      !optionalId(c.wait_revision) || typeof order.standing !== "boolean" ||
      (Object.hasOwn(c, "passenger_edit_v1") && typeof c.passenger_edit_v1 !== "boolean")) return null;
  const w = c.active_wait;
  if (w === null) return order.standing ? null : c;
  if (!w || !order.standing) return null;
  if (w.kind === "legacy_unknown") return w.wait_id === null && w.waypoint_id === null ? c : null;
  if (!stopId(w.wait_id) || w.wait_id !== c.wait_revision) return null;
  if (w.kind === "unplanned" && w.waypoint_id === null) return c;
  if (w.kind === "planned" && stopId(c.route_revision) && stopId(w.waypoint_id)) return c;
  return null;
}

export function activeWaypointIndex(order: InstantOrder, c: StopControl | null | undefined): number | null {
  const w = c?.active_wait;
  if (w?.kind !== "planned") return null;
  const same = (order.stops ?? []).filter(s => s.waypoint_id === w.waypoint_id);
  if (same.length !== 1 || same[0].done) return null;
  return (order.stops ?? []).filter(s => !s.done).indexOf(same[0]);
}

export function verifiedIdleAfterResume(control: StopControl | null | undefined, previousWaitId: string): boolean {
  return !!control && control.active_wait === null && stopId(control.wait_revision) &&
    control.wait_revision !== previousWaitId;
}

function stopSnapshot(order: InstantOrder) {
  const c = taxiStopControl(order), w = c?.active_wait;
  return JSON.stringify([order.id, order.status, order.role, order.driver_id, order.passenger_id, order.standing,
    c === undefined ? "legacy" : c === null ? "invalid" : c.admission_enabled,
    c?.route_revision, c?.wait_revision, c?.passenger_edit_v1, w?.kind, w?.wait_id, w?.waypoint_id,
    order.stops?.map(s => [s.waypoint_id, s.done, s.lat, s.lng, s.text])]);
}

// A parent's authoritative fare refresh also supersedes the local response, without
// treating the fare or other ancillary metadata as waypoint command identity.
export function stopFareSnapshot(order: InstantOrder): string {
  return JSON.stringify([stopSnapshot(order), order.price_estimate, order.ride_price, order.passenger_price_kop]);
}

type Problem = "stale" | "protected" | "cooldown" | "unknown" | null;
const classify = (e: unknown, typedEdit: boolean): Problem => {
  if (!(e instanceof ApiError)) return "unknown";
  // Only the typed waypoint endpoint's explicit pre-write refusal is definitive.
  if (typedEdit && e.status === 429 && e.code === "taxi_waypoint_edit_cooldown") return "cooldown";
  if (e.status !== 409) return "unknown";
  if (e.code === "taxi_active_waypoint_protected") return "protected";
  if (["taxi_wait_stale", "taxi_waypoints_stale", "taxi_explicit_wait_required"].includes(e.code)) return "stale";
  return "unknown";
};

type Write = (scope: StopRequestScope) => Promise<InstantOrder>;
type Intent = "driver-explicit" | "passenger-edit";
type StopBarrier = {
  key: string; problem: Problem; intent?: Intent; driverAction?: Write; resumeWaitId?: string; reads: number;
};
type RetriableStop = StopBarrier & { driverAction: Write };

function isRetriableStop(waiting: StopBarrier | null, key: string): waiting is RetriableStop {
  return !!waiting?.driverAction && waiting.reads >= 2 && waiting.key === key;
}

/** Read after uncertain writes; only a manual retry can repeat an exact driver command or its idempotent ID preparation. */
export function useTaxiStopRequest(input: InstantOrder) {
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem>(null);
  const [hold, setHold] = useState(false);
  const [local, setLocal] = useState<{ before: string; order: InstantOrder } | null>(null);
  const inputKey = stopFareSnapshot(input);
  const order = local?.before === inputKey ? local.order : input;
  const key = stopSnapshot(order);
  const view = useRef({ input, inputKey, order, key });
  view.current = { input, inputKey, order, key };
  const currentRef = useRef(true);
  const mountedOwner = useRef(getAccountSession());
  const flight = useRef(false);
  const barrier = useRef<StopBarrier | null>(null);

  useEffect(() => {
    currentRef.current = true;
    return () => { currentRef.current = false; };
  }, []);

  const owns = (o: InstantOrder, owner: StopRequestScope["owner"]) =>
    o.role === order.role && (o.role === "driver" ? o.driver_id === owner.userId : o.role === "passenger");
  // The passenger's own DTO deliberately has no passenger_id. Same-session
  // authenticated GET + exact order ID and server-projected role are the proof.
  async function attempt(send?: Write, intent?: Intent, resumeWaitId?: string, retry?: RetriableStop) {
    const owner = mountedOwner.current;
    if (!owner || !isAccountSession(owner) || flight.current || view.current.key !== key ||
        !currentRef.current || !owns(order, owner) || order.status !== "onboard" ||
        (send && barrier.current && !retry) ||
        (retry && (barrier.current !== retry || !isRetriableStop(retry, key) || order.role !== "driver"))) return false;
    flight.current = true;
    setBusy(true);
    const current = () => currentRef.current && view.current.input.id === order.id &&
      view.current.input.role === order.role && isAccountSession(owner);
    const read: StopRequestScope = { owner, allowAttempt: async () => current() };
    const valid = (r: InstantOrder) => current() && r?.id === order.id && owns(r, owner);
    const publish = (r: InstantOrder) => setLocal({ before: view.current.inputKey, order: r });
    try {
      if (send) {
        try {
          const next = await send({ owner, allowAttempt: async () => current() && view.current.key === key });
          if (!current()) return false;
          if (!valid(next) || taxiStopControl(next) === null ||
              (view.current.key !== key && stopSnapshot(next) !== view.current.key)) throw Error("Unverified stop response");
          publish(next);
          barrier.current = null;
          setHold(false);
          setProblem(null);
          return true;
        } catch (e) {
          if (!current()) return false;
          const outcome = classify(e, intent === "passenger-edit");
          const driverAction = intent === "driver-explicit" && order.role === "driver" && outcome === "unknown" ? send : undefined;
          barrier.current = { key, problem: outcome, intent, driverAction, resumeWaitId, reads: 0 };
          setHold(true);
          setProblem(barrier.current.problem);
        }
      }
      const readKey = view.current.key;
      const fresh = await fetchInstantOrder(order.id, undefined, read);
      const waiting = barrier.current;
      if (!valid(fresh) || (view.current.key !== readKey && stopSnapshot(fresh) !== view.current.key)) {
        if (waiting) waiting.reads = 0;
        return false;
      }
      const incoming = taxiStopControl(fresh);
      const badDriverRead = waiting?.intent === "driver-explicit" &&
        (incoming?.active_wait?.kind === "legacy_unknown" ||
          (waiting.resumeWaitId && fresh.standing === false && !verifiedIdleAfterResume(incoming, waiting.resumeWaitId)));
      // Absence/legacy shaping after a protected command cannot authorize an older writer.
      if (fresh.status === "onboard" &&
          (!incoming || (waiting?.intent === "passenger-edit" && !incoming.passenger_edit_v1) || badDriverRead)) {
        if (waiting) waiting.reads = 0;
        return false;
      }
      publish(fresh);
      const same = stopSnapshot(fresh) === waiting?.key;
      if (!waiting || (waiting.problem === "cooldown" ? same : waiting.problem !== "unknown" || !same)) {
        barrier.current = null;
        setHold(false);
      } else if (same && waiting.driverAction) {
        waiting.reads = Math.min(2, waiting.reads + 1);
      }
      return false;
    } catch {
      if (barrier.current) barrier.current.reads = 0;
      if (current()) setProblem(barrier.current?.problem ?? "unknown");
      return false;
    } finally {
      flight.current = false;
      if (current()) setBusy(false);
    }
  }

  const waiting = barrier.current;
  const retrySameDriverAction = isRetriableStop(waiting, key)
    ? () => attempt(waiting.driverAction, "driver-explicit", waiting.resumeWaitId, waiting) : null;
  return {
    order, busy, problem, hold, retrySameDriverAction,
    execute: (send?: Write, intent?: Intent, resumeWaitId?: string) => attempt(send, intent, resumeWaitId),
  };
}
