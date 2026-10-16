/** Document-lifetime fence. No credentials or personal data are kept here. */
let enabled = false, blocked = false, epoch = 0, known = "", failedOwner = "", committed = "";
let offlineTrusted = true;
const listeners = new Set<() => void>();
const permits = new WeakSet<object>();
export interface SessionWritePermit { readonly epoch: number }
export const sessionEpoch = () => epoch;
export const storageFenced = () => enabled && blocked;
export const storageFenceEnabled = () => enabled;
export const offlineStorageTrusted = () => !enabled || (!blocked && offlineTrusted);
export const quarantineGeneration = () => `storage-unavailable:${epoch}`;
export function rememberSession(generation: string): void { if (!blocked) known = generation; }
export function activateStorageFence(): void {
  if (enabled) return;
  enabled = true;
  window.addEventListener("storage", event => {
    if (event.key === "yuldash.session" && event.newValue) {
      if (blocked) committed = event.newValue;
      else known = event.newValue;
    }
  });
}
export function reportStorageFailure(): void {
  if (!enabled || blocked) return;
  failedOwner = known; committed = ""; blocked = true; offlineTrusted = false; epoch++;
  // A failing read may happen during a child render; never set parent state there.
  queueMicrotask(() => listeners.forEach(fn => fn()));
}
export function storageOperation<T>(work: () => T): T {
  try { return work(); } catch (error) { reportStorageFailure(); throw error; }
}
export function subscribeStorageFailure(fn: () => void): () => void {
  listeners.add(fn); return () => { listeners.delete(fn); };
}
export function probeStorage(write = true): boolean {
  try {
    for (const store of [localStorage, sessionStorage]) {
      store.getItem("yuldash.tracked.storageProbe"); void store.length; store.key(0);
      if (write) { const key = `yuldash.tracked.storageProbe.${crypto.randomUUID()}`; store.setItem(key, "1"); store.removeItem(key); }
    }
    return true;
  } catch { reportStorageFailure(); return false; }
}
export function assertSessionPermit(permit: SessionWritePermit): void {
  if (!permits.has(permit) || permit.epoch !== epoch) throw new Error("Session operation superseded");
}
function mutation<T>(work: (permit: SessionWritePermit) => T, recovery: boolean): T | Promise<Awaited<T>> {
  if (!enabled) return work({ epoch });
  const expected = epoch;
  const run = () => {
    if (expected !== epoch || (blocked && !recovery)) throw new Error("Session operation superseded");
    const permit = { epoch }; permits.add(permit);
    try {
      const result = work(permit);
      if (result && typeof (result as unknown as Promise<unknown>).then === "function") {
        return Promise.resolve(result).finally(() => permits.delete(permit));
      }
      permits.delete(permit); return result;
    } catch (error) { permits.delete(permit); throw error; }
  };
  if (typeof navigator !== "undefined" && navigator.locks) return navigator.locks.request("yuldash-session-write", run) as Promise<Awaited<T>>;
  if (recovery) throw new Error("Safe storage recovery requires Web Locks");
  return run() as T;
}
export function sessionMutation<T>(work: (permit: SessionWritePermit) => T): T | Promise<Awaited<T>> { return mutation(work, false); }
export function storageRecoveryMutation<T>(work: (permit: SessionWritePermit) => T): T | Promise<Awaited<T>> { return mutation(work, true); }
export function isNewSessionCommit(generation: string): boolean {
  return !!generation && ((!!failedOwner && generation !== failedOwner) || generation === committed);
}
export function finishStorageRecovery(generation: string): void {
  known = generation; failedOwner = ""; committed = ""; blocked = false; epoch++;
  // Until /me confirms this owner, even an otherwise matching offline pass stays closed.
  offlineTrusted = false;
}
export function confirmStorageSession(generation: string): void {
  if (!blocked && generation === known) offlineTrusted = true;
}
