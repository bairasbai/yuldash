import type { TripPassOwner, TripPassRecord } from "./tripPass";

const DATABASE = "yuldash-trip-passes-v1";
const STORE = "sessions";
const CHANNEL = "yuldash-trip-pass-changes";

type TripRecords = ReadonlyMap<number, TripPassRecord>;
type OwnedRow = { ownerId: number; trips: Map<number, TripPassRecord>; closedRides: Set<number> };
type SessionRow = OwnedRow | { revoked: true };
type Edit<Result> = { result: Result; row?: SessionRow };

const listeners = new Set<() => void>();
let channel: BroadcastChannel | null = null;

export function subscribeTripPassChanges(listener: () => void): () => void {
  listeners.add(listener);
  if (!channel && typeof window !== "undefined" && typeof window.BroadcastChannel === "function") {
    channel = new window.BroadcastChannel(CHANNEL);
    channel.onmessage = () => listeners.forEach(notify => notify());
  }
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) {
      channel?.close();
      channel = null;
    }
  };
}

function publishChange(): void {
  listeners.forEach(notify => {
    try { notify(); } catch { /* Rendering cannot change a committed write. */ }
  });
  try {
    if (channel) {
      channel.postMessage(null);
    } else if (typeof window !== "undefined" && typeof window.BroadcastChannel === "function") {
      const sender = new window.BroadcastChannel(CHANNEL);
      try { sender.postMessage(null); } finally { sender.close(); }
    }
  } catch { /* The next focus or navigation reads the durable state directly. */ }
}

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE, 1);
    let blocked = false;
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onerror = () => reject(request.error);
    request.onblocked = () => {
      blocked = true;
      reject(new Error("Trip pass storage blocked"));
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

async function access<Result>(
  generation: string,
  current: () => boolean,
  mode: "readonly" | "readwrite",
  edit: (row: SessionRow | undefined) => Edit<Result>,
): Promise<Result> {
  if (!current()) throw new Error("Trip session changed");
  const db = await openDatabase();
  try {
    return await new Promise<Result>((resolve, reject) => {
      if (!current()) throw new Error("Trip session changed");
      const tx = mode === "readwrite"
        ? db.transaction(STORE, mode, { durability: "strict" })
        : db.transaction(STORE, mode);
      const store = tx.objectStore(STORE);
      let result: Result;
      let changed = false;
      tx.onabort = () => reject(tx.error ?? new Error("Trip pass transaction aborted"));
      tx.oncomplete = () => {
        try {
          if (changed) publishChange();
          if (!current()) throw new Error("Trip session changed");
          resolve(result);
        } catch (error) { reject(error); }
      };
      const request = store.get(generation);
      request.onsuccess = () => {
        try {
          if (!current()) throw new Error("Trip session changed");
          const update = edit(request.result);
          result = update.result;
          if (update.row !== undefined) {
            store.put(update.row, generation);
            changed = true;
          }
        } catch (error) {
          tx.abort();
          reject(error);
        }
      };
    });
  } finally {
    db.close();
  }
}

function ownedRow(row: SessionRow | undefined, owner: TripPassOwner): OwnedRow | null {
  if (row === undefined) return { ownerId: owner.userId, trips: new Map(), closedRides: new Set() };
  if (row && typeof row === "object" && "ownerId" in row && row.ownerId === owner.userId &&
      row.trips instanceof Map && row.closedRides instanceof Set) return row;
  return null;
}

export function readTripRecords(owner: TripPassOwner, current: () => boolean): Promise<TripRecords | null> {
  return access(owner.generation, current, "readonly", row => ({ result: ownedRow(row, owner)?.trips ?? null }));
}

export function editTripRecords<Result>(
  owner: TripPassOwner,
  current: () => boolean,
  edit: (rows: TripRecords, closedRides: ReadonlySet<number>) => {
    result: Result;
    trips?: Map<number, TripPassRecord>;
    closedRides?: Set<number>;
  },
  denied: Result,
): Promise<Result> {
  return access(owner.generation, current, "readwrite", row => {
    const previous = ownedRow(row, owner);
    if (!previous) return { result: denied };
    const update = edit(previous.trips, previous.closedRides);
    if (!update.trips && !update.closedRides) return { result: update.result };
    return { result: update.result, row: { ownerId: owner.userId,
      trips: update.trips ?? previous.trips, closedRides: update.closedRides ?? previous.closedRides } };
  });
}

/** Previous credentials may reappear after a browser crash. The old generation stays closed. */
export function revokeTripPassGeneration(generation: string, current: () => boolean): undefined | Promise<void> {
  if (!generation || typeof indexedDB === "undefined") return;
  return access(generation, current, "readwrite", row => ({
    result: undefined,
    ...(row && "revoked" in row && row.revoked ? {} : { row: { revoked: true } }),
  }));
}
