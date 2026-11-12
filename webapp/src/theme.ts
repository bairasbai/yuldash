import { useEffect, useState } from "react";

export type ThemeMode = "system" | "light" | "dark";

const LEGACY_KEY = "yuldash.theme";
const DATABASE = "yuldash-device-theme-v1";
const STORE = "preferences";
const THEME_KEY = "theme";
const STARTUP_READ_LIMIT_MS = 1000;

function isMode(value: unknown): value is ThemeMode {
  return value === "system" || value === "light" || value === "dark";
}

function legacyTheme(): ThemeMode | null {
  try {
    const value = localStorage.getItem(LEGACY_KEY);
    return isMode(value) ? value : null;
  } catch {
    return null;
  }
}

function openThemeDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE, 1);
    let blocked = false;
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onerror = () => reject(request.error);
    request.onblocked = () => {
      blocked = true;
      reject(new Error("Theme storage is blocked"));
    };
    request.onsuccess = () => {
      const db = request.result;
      if (blocked) db.close();
      else {
        db.onversionchange = () => db.close();
        resolve(db);
      }
    };
  });
}

/** The completion event, rather than the success of put(), confirms a preference change. */
async function accessTheme(selected?: ThemeMode): Promise<ThemeMode> {
  if (typeof indexedDB === "undefined") {
    if (selected !== undefined) localStorage.setItem(LEGACY_KEY, selected);
    return selected ?? legacyTheme() ?? "system";
  }
  const db = await openThemeDatabase();
  try {
    const saved = await new Promise<ThemeMode | null>((resolve, reject) => {
      const tx = db.transaction(STORE, "readwrite", { durability: "strict" });
      const store = tx.objectStore(STORE);
      let value: ThemeMode | null = selected ?? null;
      tx.oncomplete = () => resolve(value);
      tx.onabort = () => reject(tx.error ?? new Error("Theme storage write aborted"));
      if (selected !== undefined) {
        store.put(selected, THEME_KEY);
      } else {
        const request = store.get(THEME_KEY);
        request.onsuccess = () => {
          if (isMode(request.result)) {
            value = request.result;
          } else {
            value = legacyTheme();
            if (value !== null) store.put(value, THEME_KEY);
          }
        };
      }
    });
    // The old preference is removed only after the one-time migration or a new choice commits.
    if (saved !== null) {
      try { localStorage.removeItem(LEGACY_KEY); } catch { /* The committed record remains authoritative. */ }
    }
    return saved ?? "system";
  } finally {
    db.close();
  }
}

let currentMode: ThemeMode = "system";
let selectionRevision = 0;
let pendingWrite: Promise<void> = Promise.resolve();
const listeners = new Set<() => void>();

export function getTheme(): ThemeMode {
  return currentMode;
}

function publishTheme(mode: ThemeMode): void {
  currentMode = mode;
  applyTheme();
  listeners.forEach(listener => listener());
}

/** Apply the device preference before mounting the app when the local store is ready. */
export async function restoreTheme(): Promise<void> {
  const fallback = legacyTheme() ?? "system";
  const revision = selectionRevision;
  const reading = accessTheme();
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    const deadline = new Promise<null>(resolve => {
      timer = setTimeout(() => resolve(null), STARTUP_READ_LIMIT_MS);
    });
    const saved = await Promise.race([reading, deadline]);
    if (revision === selectionRevision) publishTheme(saved ?? fallback);
    if (saved === null) {
      void reading.then(mode => {
        if (revision === selectionRevision) publishTheme(mode);
      }).catch(() => { /* Startup is already using the fallback. */ });
    }
  } catch {
    if (revision === selectionRevision) publishTheme(fallback);
  } finally {
    clearTimeout(timer);
  }
}

export function applyTheme(mode: ThemeMode = currentMode): void {
  const el = document.documentElement;
  if (mode === "system") delete el.dataset.theme;
  else el.dataset.theme = mode;
}

export function setTheme(mode: ThemeMode): Promise<void> {
  selectionRevision++;
  const change = pendingWrite.then(async () => {
    publishTheme(await accessTheme(mode));
  });
  pendingWrite = change.catch(() => { /* A failed selection does not prevent the next one. */ });
  return change;
}

export function useTheme(): [ThemeMode, (mode: ThemeMode) => Promise<void>] {
  const [mode, setMode] = useState(getTheme);
  useEffect(() => {
    const update = () => setMode(getTheme());
    listeners.add(update);
    update();
    return () => { listeners.delete(update); };
  }, []);
  return [mode, setTheme];
}
