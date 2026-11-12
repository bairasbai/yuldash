import { useEffect, useState } from "react";
import { getSessionGeneration } from "../api/client";
import { subscribeTripPassChanges } from "./tripPassStore";

/** Recheck the current generation on every asynchronous read and browser boundary. */
export function useStoredTripData<T extends { generation: string }>(
  enabled: boolean, read: () => Promise<T | null>, clearOnRefresh = true,
): T | null {
  const [value, setValue] = useState<{ read: typeof read; result: T } | null>(null);
  useEffect(() => {
    if (!enabled) return;
    let revision = 0;
    const refresh = () => {
      const expected = ++revision;
      if (clearOnRefresh) setValue(null);
      void read().then(result => {
        if (expected !== revision) return;
        setValue(result?.generation === getSessionGeneration() ? { read, result } : null);
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
