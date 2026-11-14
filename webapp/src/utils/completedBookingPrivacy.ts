import { useEffect, useRef, useState } from "react";

interface Clock {
  monotonic: number;
  wall: number;
}

const expiredSnapshots = new WeakSet<Clock>();
const MAX_RECHECK_MS = 30_000;

export interface CompletedContactWindow {
  completed_contact_remaining_ms?: number | null;
  completedContactDeadline?: Clock;
}

export function readContactClock(): Clock {
  return { monotonic: performance.now(), wall: Date.now() };
}

export function setCompletedContactDeadline(value: CompletedContactWindow, started: Clock): void {
  const remaining = value.completed_contact_remaining_ms;
  if (typeof remaining === "number" && Number.isSafeInteger(remaining) && remaining > 0) {
    value.completedContactDeadline = { monotonic: started.monotonic + remaining, wall: started.wall + remaining };
  } else {
    delete value.completedContactDeadline;
  }
}

function remainingTime(deadline: Clock | undefined, now: Clock): number {
  if (!deadline || expiredSnapshots.has(deadline)) return 0;
  // On some systems the monotonic clock pauses during sleep. A wall clock adjustment
  // backwards cannot extend the deadline beyond the monotonic timer.
  return Math.min(deadline.monotonic - now.monotonic, deadline.wall - now.wall);
}

export function completedContactsVisible(deadline: Clock | undefined): boolean {
  return remainingTime(deadline, readContactClock()) > 0;
}

function expireSnapshots(deadlines: readonly (Clock | undefined)[], now: Clock): void {
  for (const deadline of deadlines) {
    if (deadline && remainingTime(deadline, now) <= 0) expiredSnapshots.add(deadline);
  }
}

/** Use one timer for the next visible deadline, including lists with multiple passengers. */
export function useCompletedContactExpiry(deadlines: readonly (Clock | undefined)[]): void {
  const [tick, setTick] = useState(0);
  const latest = useRef(deadlines);
  const now = readContactClock();
  const next = deadlines.reduce<Clock | undefined>((soonest, deadline) => {
    const remaining = remainingTime(deadline, now);
    return remaining > 0 && (!soonest || remaining < remainingTime(soonest, now)) ? deadline : soonest;
  }, undefined);
  useEffect(() => {
    latest.current = deadlines;
    expireSnapshots(deadlines, readContactClock());
  }, [deadlines]);
  useEffect(() => {
    if (!next) return;
    const update = () => {
      expireSnapshots(latest.current, readContactClock());
      setTick(n => n + 1);
    };
    const timer = window.setTimeout(update, Math.min(MAX_RECHECK_MS, Math.max(0, Math.ceil(remainingTime(next, readContactClock())))));
    globalThis.document?.addEventListener?.("visibilitychange", update);
    window.addEventListener?.("pageshow", update);
    window.addEventListener?.("focus", update);
    return () => {
      window.clearTimeout(timer);
      globalThis.document?.removeEventListener?.("visibilitychange", update);
      window.removeEventListener?.("pageshow", update);
      window.removeEventListener?.("focus", update);
    };
  }, [next, tick]);
}
