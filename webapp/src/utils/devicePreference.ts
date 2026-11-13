const STORE_NAME = "preferences";
const STARTUP_READ_LIMIT_MS = 1000;

interface Preference<Value extends string> {
  database: string;
  key: string;
  legacyKey: string;
  fallback: Value;
  isValue: (value: unknown) => value is Value;
  onPublish?: (value: Value) => void;
}

export function devicePreference<Value extends string>({
  database, key, legacyKey, fallback, isValue, onPublish,
}: Preference<Value>) {
  function legacyValue(): Value | null {
    try {
      const value = localStorage.getItem(legacyKey);
      return isValue(value) ? value : null;
    } catch {
      return null;
    }
  }

  function openDatabase(): Promise<IDBDatabase> {
    return new Promise((resolve, reject) => {
      const request = indexedDB.open(database, 1);
      let blocked = false;
      request.onupgradeneeded = () => request.result.createObjectStore(STORE_NAME);
      request.onerror = () => reject(request.error);
      request.onblocked = () => {
        blocked = true;
        reject(new Error("Device preference storage is blocked"));
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
  async function access(selected?: Value): Promise<Value> {
    if (typeof indexedDB === "undefined") {
      if (selected !== undefined) localStorage.setItem(legacyKey, selected);
      return selected ?? legacyValue() ?? fallback;
    }
    const db = await openDatabase();
    try {
      const saved = await new Promise<Value | null>((resolve, reject) => {
        const tx = db.transaction(STORE_NAME, "readwrite", { durability: "strict" });
        const store = tx.objectStore(STORE_NAME);
        let value: Value | null = selected ?? null;
        tx.oncomplete = () => resolve(value);
        tx.onabort = () => reject(tx.error ?? new Error("Device preference storage write aborted"));
        if (selected !== undefined) {
          store.put(selected, key);
        } else {
          const request = store.get(key);
          request.onsuccess = () => {
            if (isValue(request.result)) {
              value = request.result;
            } else {
              value = legacyValue();
              if (value !== null) store.put(value, key);
            }
          };
        }
      });
      // The old preference is removed only after the one-time migration or a new choice commits.
      if (saved !== null) {
        try { localStorage.removeItem(legacyKey); } catch { /* The committed record remains authoritative. */ }
      }
      return saved ?? fallback;
    } finally {
      db.close();
    }
  }

  let current: Value | undefined;
  let selectionRevision = 0;
  let pendingWrite: Promise<void> = Promise.resolve();
  const listeners = new Set<() => void>();

  function publish(value: Value): void {
    current = value;
    onPublish?.(value);
    listeners.forEach(listener => listener());
  }

  async function restore(): Promise<void> {
    const legacy = legacyValue() ?? fallback;
    const revision = selectionRevision;
    const reading = access();
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      const deadline = new Promise<null>(resolve => {
        timer = setTimeout(() => resolve(null), STARTUP_READ_LIMIT_MS);
      });
      const saved = await Promise.race([reading, deadline]);
      if (revision === selectionRevision) publish(saved ?? legacy);
      if (saved === null) {
        void reading.then(value => {
          if (revision === selectionRevision) publish(value);
        }).catch(() => { /* Startup is already using the fallback. */ });
      }
    } catch {
      if (revision === selectionRevision) publish(legacy);
    } finally {
      clearTimeout(timer);
    }
  }

  function select(value: Value): Promise<void> {
    const change = pendingWrite.then(async () => {
      const saved = await access(value);
      selectionRevision++;
      publish(saved);
    });
    pendingWrite = change.catch(() => { /* A failed selection does not prevent the next one. */ });
    return change;
  }

  function subscribe(listener: () => void): () => void {
    listeners.add(listener);
    return () => { listeners.delete(listener); };
  }

  return { get: () => current ?? legacyValue() ?? fallback, restore, select, subscribe };
}
