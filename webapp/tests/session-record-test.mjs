import assert from "node:assert/strict";
import { IDBFactory, IDBObjectStore } from "fake-indexeddb";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const result = await build({
  entryPoints: [root + "src/utils/sessionRecord.ts"], bundle: true, write: false, format: "esm", platform: "node",
});
const api = await import("data:text/javascript;base64," + Buffer.from(result.outputFiles[0].text).toString("base64"));
const A = { generation: "A", access: "fixture-access-A", refresh: "fixture-refresh-A" };
const B = { generation: "B", access: "fixture-access-B", refresh: "fixture-refresh-B" };
const guest = { generation: "guest", access: null, refresh: null };
const row = (credentials, revision) => ({ ...credentials, revision });
const allow = () => {};
const boot = (legacy = () => A) => api.initializeSessionRecord(guest.generation, legacy);

function request(operation) {
  return new Promise((resolve, reject) => {
    operation.onsuccess = () => resolve(operation.result);
    operation.onerror = () => reject(operation.error);
  });
}

async function oldDatabase(trip) {
  const open = indexedDB.open("yuldash-trip-passes-v1", 1);
  open.onupgradeneeded = () => open.result.createObjectStore("sessions").put(trip, A.generation);
  const db = await request(open);
  db.close();
}

async function readTrip(generation) {
  const db = await request(indexedDB.open("yuldash-trip-passes-v1"));
  try { return await request(db.transaction("sessions").objectStore("sessions").get(generation)); }
  finally { db.close(); }
}

const cases = [
  ["upgrade imports one valid legacy pair and preserves an existing trip", async () => {
    const trip = { ownerId: 12, trips: new Map([[42, { status: "confirmed" }]]), closedRides: new Set() };
    await oldDatabase(trip);
    assert.deepEqual(await boot(), row(A, 1));
    assert.deepEqual(await readTrip(A.generation), trip);
    assert.deepEqual(await boot(() => { throw new Error("legacy was read again"); }), row(A, 1));
  }],
  ["revoked legacy generation boots as guest and never imports again", async () => {
    await oldDatabase({ revoked: true });
    assert.deepEqual(await boot(), row(guest, 1));
    assert.deepEqual(await boot(() => B), row(guest, 1));
  }],
  ["recovery seals a damaged current record without importing localStorage", async () => {
    await boot();
    await api.replaceSessionRecord(A.generation, A, allow);
    const db = await request(indexedDB.open("yuldash-trip-passes-v1"));
    const tx = db.transaction("credentials", "readwrite");
    tx.objectStore("credentials").put({ generation: A.generation, access: A.access }, "current");
    await new Promise((resolve, reject) => { tx.oncomplete = resolve; tx.onabort = reject; });
    db.close();
    await assert.rejects(boot(), /invalid saved session/i);
    assert.deepEqual(await api.initializeSessionRecord(guest.generation, () => undefined, true), row(guest, 3));
    assert.deepEqual(await readTrip(A.generation), { revoked: true });
  }],
  ["logout commits an explicit guest and closes prior trips", async () => {
    await boot();
    assert.deepEqual(await api.replaceSessionRecord(A.generation, guest, allow), { current: row(guest, 2), applied: true });
    assert.deepEqual(await readTrip(A.generation), { revoked: true });
    assert.deepEqual(await boot(() => A), row(guest, 2));
  }],
  ["an aborted new login preserves both old credentials and old trip", async () => {
    const trip = { ownerId: 12, trips: new Map(), closedRides: new Set() };
    await oldDatabase(trip);
    await boot();
    const put = IDBObjectStore.prototype.put;
    IDBObjectStore.prototype.put = function (...args) {
      if (this.name === "credentials") throw new Error("fixture write denied");
      return put.apply(this, args);
    };
    try { await assert.rejects(api.replaceSessionRecord(A.generation, B, allow), /write denied|aborted/i); }
    finally { IDBObjectStore.prototype.put = put; }
    assert.deepEqual(await api.readSessionRecord(), row(A, 1));
    assert.deepEqual(await readTrip(A.generation), trip);
  }],
  ["the losing tab can neither publish its login nor clear the winner", async () => {
    await boot();
    const changed = await Promise.all([
      api.replaceSessionRecord(A.generation, B, allow),
      api.replaceSessionRecord(A.generation, guest, allow),
    ]);
    assert.equal(changed.filter(item => item.applied).length, 1);
    assert.deepEqual(changed[0].current, changed[1].current);
    assert.deepEqual(await api.readSessionRecord(), changed[0].current);
  }],
  ["same-generation rotation requires the exact preceding pair and leaves the trip available", async () => {
    const trip = { ownerId: 12, trips: new Map(), closedRides: new Set() };
    await oldDatabase(trip);
    await boot();
    const next = { ...A, access: "fixture-rotated-access", refresh: "fixture-rotated-refresh" };
    assert.deepEqual(await api.replaceSessionRecord(A.generation, next, allow, A), { current: row(next, 2), applied: true });
    assert.deepEqual(await api.replaceSessionRecord(A.generation, guest, allow, A), { current: row(next, 2), applied: false });
    assert.deepEqual(await readTrip(A.generation), trip);
  }],
];

for (const [label, run] of cases) {
  globalThis.indexedDB = new IDBFactory();
  await run();
  console.log("PASS " + label);
}
console.log(cases.length + " passed, 0 failed");
