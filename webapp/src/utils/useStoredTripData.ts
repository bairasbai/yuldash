import { useEffect, useState } from "react";
import { getSessionGeneration } from "../api/client";
import { subscribeTripPassChanges } from "./tripPassStore";

/** Recheck the current generation on every asynchronous read and browser boundary. */
export function useStoredTripData<T extends { generation: string; expiresAt?: number }>(
  enabled: boolean, read: () => Promise<T | null>, clearOnRefresh = true,
): T | null {
  const [value, setValue] = useState<{ read: typeof read; result: T } | null>(null);
  useEffect(() => {
    if (!enabled) return;
    let revision = 0;
    let expiry: ReturnType<typeof setTimeout> | undefined;
    const refresh = () => {
      clearTimeout(expiry);
      const expected = ++revision;
      if (clearOnRefresh) setValue(null);
      void read().then(result => {
        if (expected !== revision) return;
        if (!result || result.generation !== getSessionGeneration()) {
          setValue(null);
          return;
        }
        setValue({ read, result });
        if (result.expiresAt !== undefined) expiry = setTimeout(refresh, Math.max(0, result.expiresAt - Date.now()));
      }).catch(() => {
        if (expected === revision) setValue(null);
      });
    };
    const storageChanged = (event: StorageEvent) => {
      if (event.key === null || event.key === "yuldash.session" || event.key === "yuldash.token") refresh();
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
  return enabled && value?.read === read && value.result.generation === getSessionGeneration() ? value.result : null;
}
