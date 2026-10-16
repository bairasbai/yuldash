import { setSession } from "../api/client";
import { closeOutboxStore } from "./outboxStore";
import { resetOutboxMemory } from "./outbox";
import { clearSessionCaches } from "./sessionCaches";
import { finishStorageRecovery, isNewSessionCommit, probeStorage, sessionEpoch, storageOperation, storageRecoveryMutation } from "./storageHealth";

const DEVICE = new Set(["yuldash.lang", "yuldash.theme", "yuldash.fontScale", "yuldash.introSeen", "yuldash.onboarded", "yuldash.simpleMode", "yuldash.install.dismissed", "yuldash.chatSafetySeen", "yuldash.chunkReload", "yuldash.pref.notifications", "yuldash.pref.sounds", "yuldash.features"]);
let flight: Promise<void> | null = null;
const rawGeneration = () => storageOperation(() => localStorage.getItem("yuldash.session") ?? "");
function purge(store: Storage, device: boolean): void {
  const keys: string[] = [];
  for (let i = 0; i < store.length; i++) { const key = store.key(i); if (key?.startsWith("yuldash.") && key !== "yuldash.session" && !(device && DEVICE.has(key))) keys.push(key); }
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
export function recoverStorage(language?: "ru" | "ba"): Promise<void> {
  if (flight) return flight;
  flight = Promise.resolve().then(() => storageRecoveryMutation(async permit => {
    if (!probeStorage()) throw new Error("Storage unavailable");
    const owner = rawGeneration(), attempt = sessionEpoch();
    const active = () => sessionEpoch() === attempt && rawGeneration() === owner;
    if (storageOperation(() => !!localStorage.getItem("yuldash.token")) && isNewSessionCommit(owner)) {
      finishStorageRecovery(owner); return; // A cannot purge an already committed B.
    }
    resetOutboxMemory();
    storageOperation(() => { purge(localStorage, true); purge(sessionStorage, false); });
    await closeOutboxStore(); if (!active()) throw new Error("Recovery superseded");
    for (const name of ["yuldash-outbox-v1", "yuldash-refresh-intents"]) {
      await deleteDatabase(name); if (!active()) throw new Error("Recovery superseded");
    }
    await clearSessionCaches(active); if (!active()) throw new Error("Recovery superseded");
    await setSession(null, null, permit);
    if (language) storageOperation(() => localStorage.setItem("yuldash.lang", language));
    finishStorageRecovery(rawGeneration());
  })).then(() => {}).finally(() => { flight = null; });
  return flight;
}
