import { setSession } from "../api/client";
import { sessionForRecovery } from "./sessionCredentials";
import { closeOutboxStore } from "./outboxStore";
import { resetOutboxMemory } from "./outbox";
import { clearSessionCaches } from "./sessionCaches";
import { finishStorageRecovery, canKeepRecoveredSession, probeStorage, sessionEpoch, storageOperation, storageRecoveryMutation } from "./storageHealth";

const DEVICE = new Set(["yuldash.lang", "yuldash.theme", "yuldash.fontScale", "yuldash.introSeen", "yuldash.onboarded", "yuldash.simpleMode", "yuldash.install.dismissed", "yuldash.chatSafetySeen", "yuldash.chunkReload", "yuldash.pref.notifications", "yuldash.pref.sounds", "yuldash.features"]);
let flight: Promise<void> | null = null;
function purge(store: Storage, device: boolean): void {
  const keys: string[] = [];
  for (let i = 0; i < store.length; i++) { const key = store.key(i); if (key?.startsWith("yuldash.") && !(device && DEVICE.has(key))) keys.push(key); }
  keys.forEach(key => store.removeItem(key));
  for (const key of keys) if (store.getItem(key) !== null) throw new Error("Storage cleanup incomplete");
}
function deleteDatabase(name: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve(); request.onerror = () => reject(request.error);
    // onblocked is NOT completion: keep the session lock until the pending delete settles.
    request.onblocked = () => {};
  });
}
/** A UI timeout must not release this lock or cancel its still-running delete. */
export function recoverStorage(): Promise<void> {
  if (flight) return flight;
  flight = Promise.resolve().then(() => storageRecoveryMutation(async permit => {
    if (!probeStorage()) throw new Error("Storage unavailable");
    const attempt = sessionEpoch();
    const record = await sessionForRecovery();
    const active = () => sessionEpoch() === attempt;
    if (!active()) throw new Error("Recovery superseded");
    if (record.access && canKeepRecoveredSession(record.generation)) {
      finishStorageRecovery(record.generation);
      return;
    }
    const guest = await setSession(null, null, permit, record.generation);
    if (!guest || !active()) throw new Error("Recovery superseded");
    resetOutboxMemory();
    storageOperation(() => { purge(localStorage, true); purge(sessionStorage, false); });
    await closeOutboxStore(); if (!active()) throw new Error("Recovery superseded");
    for (const name of ["yuldash-outbox-v1", "yuldash-refresh-intents"]) {
      await deleteDatabase(name); if (!active()) throw new Error("Recovery superseded");
    }
    await clearSessionCaches(active); if (!active()) throw new Error("Recovery superseded");
    finishStorageRecovery(guest);
  })).then(() => {}).finally(() => { flight = null; });
  return flight;
}
