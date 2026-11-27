import { useEffect, useState } from "react";
import { getSessionGeneration } from "../api/client";
import { isSessionStorageSignal } from "./sessionCredentials";
import { subscribeTripPassChanges } from "./tripPassStore";

const hasExpired = (value: { expiresAt?: number }): boolean =>
  value.expiresAt !== undefined && value.expiresAt <= Date.now();

/** Recheck the current generation on every asynchronous read and browser boundary. */
export function useStoredTripData<T extends { generation: string; expiresAt?: number }>(
  enabled: boolean, read: () => Promise<T | null>, clearOnRefresh = true,
): T | null {
  const [value, setValue] = useState<{ read: typeof read; result: T } | null>(null);
  useEffect(() => {
    if (!enabled) return;
    let revision = 0;
    let expiry: ReturnType<typeof setTimeout> | undefined;
    let displayed: T | null = null;
    const publish = (result: T | null) => {
      clearTimeout(expiry);
      displayed = result;
      setValue(result ? { read, result } : null);
    };
    const refresh = () => {
      const expected = ++revision;
      if (clearOnRefresh || (displayed && hasExpired(displayed))) publish(null);
      void read().then(result => {
        if (expected !== revision) return;
        if (!result || hasExpired(result) || result.generation !== getSessionGeneration()) {
          publish(null);
          return;
        }
        publish(result);
        if (result.expiresAt !== undefined) expiry = setTimeout(() => {
          if (displayed !== result) return;
          publish(null);
          refresh();
        }, Math.max(0, result.expiresAt - Date.now()));
      }).catch(() => {
        if (expected === revision) publish(null);
      });
    };
    const storageChanged = (event: StorageEvent) => {
      if (isSessionStorageSignal(event.key)) refresh();
    };
    const unsubscribe = subscribeTripPassChanges(refresh);
    window.addEventListener("focus", refresh);
    window.addEventListener("online", refresh);
    window.addEventListener("offline", refresh);
    window.addEventListener("storage", storageChanged);
    document.addEventListener("visibilitychange", refresh);
    refresh();
    return () => {
      revision++;
      clearTimeout(expiry);
      unsubscribe();
      window.removeEventListener("focus", refresh);
      window.removeEventListener("online", refresh);
      window.removeEventListener("offline", refresh);
      window.removeEventListener("storage", storageChanged);
      document.removeEventListener("visibilitychange", refresh);
    };
  }, [enabled, read, clearOnRefresh]);
  return enabled && value?.read === read && !hasExpired(value.result) &&
    value.result.generation === getSessionGeneration() ? value.result : null;
}
