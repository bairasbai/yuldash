import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { IDBDatabase, IDBFactory } from "fake-indexeddb";
import { storedActions } from "./outbox-helpers.mjs";
import { checker } from "./shallow-harness.mjs";

const t = checker();
const compiled = await build({
  stdin: {
    contents: "export {initializeSessionStorage,setSession,getSessionGeneration} from './src/api/client';export * from './src/api/passengerTripStatus';export * from './src/utils/bookingCommandStore';export * from './src/utils/offlineTripPass';export * from './src/utils/outbox';",
    resolveDir: fileURLToPath(new URL("../", import.meta.url)), loader: "ts",
  },
  bundle: true, write: false, format: "esm", platform: "node",
  define: { "import.meta.env": JSON.stringify({ VITE_API_BASE: "http://127.0.0.1:9", DEV: false }) },
});

Object.defineProperty(globalThis, "crypto", { value: webcrypto, configurable: true });
const profile = { id: 7, created_at: "2026-01-01T10:00:00Z" };
const token = id => "header." + Buffer.from(JSON.stringify({ sub: String(id) })).toString("base64url") + ".sig";
const signal = () => new AbortController().signal;
const reply = (value, status = 200) => new Response(JSON.stringify(value), { status });
const endpoint = "/bookings/21/commands/v1/trip-status";
const scope = "booking.trip_status";
const unseen = { state: "unseen", scope: null, ref_kind: null, ref_id: null, result_code: null, current: null };
const booking = status => ({ id: 21, passenger_id: 7, status });
const receipt = status => ({ state: "applied", scope, ref_kind: "booking", ref_id: 21, result_code: null, current: booking(status) });
let imports = 0;

async function load() {
  const source = compiled.outputFiles[0].text + "\n// " + imports++;
  const api = await import("data:text/javascript;base64," + Buffer.from(source).toString("base64"));
  await api.initializeSessionStorage();
  return api;
}

async function fresh() {
  globalThis.indexedDB = new IDBFactory();
  const values = new Map([
    ["yuldash.session", "session-a"], ["yuldash.token", token(7)], ["yuldash.refresh", "fixture-refresh"],
  ]);
  globalThis.localStorage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key),
  };
  const api = await load();
  return { api, owner: api.bookingCommandOwner(profile, "session-a") };
}

for (const [stage, status] of [["arrived", "onboard"], ["done", "done"]]) {
  await t.test(stage + ": persists before first POST and replays exactly the same key and body after restart", async () => {
    const { api, owner } = await fresh();
    const writes = [];
    globalThis.fetch = async (url, init) => {
      const path = new URL(url).pathname;
      if (path.startsWith("/command-intents/")) return reply(unseen);
      assert.equal(path, endpoint);
      const [saved] = await api.passengerTripStatuses.read(owner);
      assert.equal(saved.state, "unknown");
      assert.equal(saved.key, init.headers["Idempotency-Key"]);
      writes.push([saved.key, init.body]);
      if (writes.length === 1) throw new TypeError("disconnected before send");
      return reply(booking(status));
    };
    await assert.rejects(api.submitPassengerTripStatus(owner, 21, stage, signal()), { reason: "unknown" });
    const restarted = await load();
    const [saved] = await restarted.passengerTripStatuses.read(owner);
    assert.equal((await restarted.submitPassengerTripStatus(owner, 21, stage === "done" ? "arrived" : "done", signal())).state, "unseen");
    assert.equal(writes.length, 1);
    assert.deepEqual(await restarted.repeatPassengerTripStatus(owner, saved, signal()), { state: "applied", bookingStatus: status });
    const body = JSON.stringify({ status: stage });
    assert.deepEqual(writes, [[saved.key, body], [saved.key, body]]);
    assert.equal((await restarted.passengerTripStatuses.read(owner))[0].state, "applied");
  });
}

await t.test("lost response after commit is recovered after restart using only the original key and GET", async () => {
  const { api, owner } = await fresh();
  const calls = [];
  globalThis.fetch = async (url, init) => {
    const path = new URL(url).pathname;
    calls.push([path, init.method, init.headers["Idempotency-Key"] ?? null]);
    if (path === endpoint) throw new TypeError("committed, response lost");
    return reply(receipt("done"));
  };
  await assert.rejects(api.submitPassengerTripStatus(owner, 21, "done", signal()), { reason: "unknown" });
  const restarted = await load();
  const [saved] = await restarted.passengerTripStatuses.read(owner);
  assert.deepEqual(await restarted.checkPassengerTripStatus(owner, saved, false, signal()), { state: "applied", bookingStatus: "done" });
  assert.deepEqual(calls, [[endpoint, "POST", saved.key], ["/command-intents/" + saved.key, "GET", null]]);
  const offline = await restarted.readOfflineTrip(21);
  assert.equal(offline.status, "terminal");
  assert.equal(offline.pass, null);
});

await t.test("concurrent preparation writes one key and waits for terminal outcome", async () => {
  const { api, owner } = await fresh();
  const writes = [];
  globalThis.fetch = async (url, init) => {
    const path = new URL(url).pathname;
    if (path.startsWith("/command-intents/")) return reply(unseen);
    writes.push(init.headers["Idempotency-Key"]);
    throw new TypeError("pending wire");
  };
  await Promise.allSettled([
    api.submitPassengerTripStatus(owner, 21, "arrived", signal()),
    api.submitPassengerTripStatus(owner, 21, "done", signal()),
  ]);
  assert.equal(writes.length, 1);
  assert.equal((await api.passengerTripStatuses.read(owner))[0].key, writes[0]);
});

await t.test("after the retry window and for a future clock, check/close remain available without a POST replay", async () => {
  const { api, owner } = await fresh();
  let calls = 0;
  globalThis.fetch = async (url, init) => {
    calls++;
    if (calls === 1) throw new TypeError("unknown");
    assert.equal(new URL(url).pathname, "/command-intents/" + saved.key + "/close");
    assert.deepEqual(JSON.parse(init.body), { scope, target_id: 21, payload_hash: saved.hash });
    return reply({ ...unseen, scope, state: "closed_no_effect", result_code: "idempotency_closed_no_effect" });
  };
  await assert.rejects(api.submitPassengerTripStatus(owner, 21, "done", signal()), { reason: "unknown" });
  const [saved] = await api.passengerTripStatuses.read(owner);
  const now = Date.now;
  try {
    for (const value of [saved.createdAt - 1, saved.createdAt + 86400000]) {
      Date.now = () => value;
      await assert.rejects(api.repeatPassengerTripStatus(owner, saved, signal()), { reason: "expired" });
    }
  } finally { Date.now = now; }
  assert.equal(calls, 1);
  assert.equal((await api.checkPassengerTripStatus(owner, saved, true, signal())).state, "closed_no_effect");
  await assert.rejects(api.repeatPassengerTripStatus(owner, saved, signal()), { reason: "stale" });
  assert.equal(calls, 2);
});

await t.test("forbidden and missing reads keep an unknown key; gone is a distinct terminal result", async () => {
  const { api, owner } = await fresh();
  globalThis.fetch = async () => { throw new TypeError("unknown"); };
  await assert.rejects(api.submitPassengerTripStatus(owner, 21, "done", signal()), { reason: "unknown" });
  const [saved] = await api.passengerTripStatuses.read(owner);
  for (const status of [403, 404]) {
    globalThis.fetch = async () => reply({ detail: "denied" }, status);
    await assert.rejects(api.checkPassengerTripStatus(owner, saved, false, signal()));
    assert.equal((await api.passengerTripStatuses.read(owner))[0].state, "unknown");
  }
  globalThis.fetch = async () => reply({ ...receipt("done"), current: null, result_code: "idempotency_result_gone" });
  assert.deepEqual(await api.checkPassengerTripStatus(owner, saved, false, signal()), { state: "gone" });
  assert.equal((await api.passengerTripStatuses.read(owner))[0].state, "applied");
});

await t.test("late response cannot settle a command after account switch; original owner can read it after sign-in", async () => {
  const { api, owner } = await fresh();
  let received, release;
  const entered = new Promise(resolve => received = resolve);
  globalThis.fetch = async () => {
    received();
    await new Promise(resolve => release = resolve);
    return reply(booking("done"));
  };
  const request = api.submitPassengerTripStatus(owner, 21, "done", signal());
  await entered;
  await api.setSession(token(8), "fixture-refresh-b");
  release();
  await assert.rejects(request);
  const other = api.bookingCommandOwner({ ...profile, id: 8 }, api.getSessionGeneration());
  assert.deepEqual(await api.passengerTripStatuses.read(other), []);
  await api.setSession(token(7), "fixture-refresh-returned");
  const returned = api.bookingCommandOwner(profile, api.getSessionGeneration());
  assert.equal((await api.passengerTripStatuses.read(returned))[0].state, "unknown");
});

await t.test("unavailable or full durable storage prevents all writes", async () => {
  const { api, owner } = await fresh();
  let requests = 0;
  globalThis.fetch = async () => { requests++; throw new TypeError("unexpected"); };
  for (let id = 101; id <= 164; id++) assert(await api.passengerTripStatuses.reserve(owner, id, "arrived", "x", undefined));
  await assert.rejects(api.submitPassengerTripStatus(owner, 21, "done", signal()), { reason: "unknown" });
  globalThis.indexedDB = { open() { throw new Error("storage unavailable"); } };
  await assert.rejects(api.submitPassengerTripStatus(owner, 21, "done", signal()), { reason: "storage" });
  assert.equal(requests, 0);
});

await t.test("legacy statuses migrate before logout, remain visible to the old account, and are checked via GET only", async () => {
  const { api, owner } = await fresh();
  await api.enqueue(21, "trip_status", "arrived");
  await api.preserveLegacyTripStatuses(owner);
  await api.clearOutbox();
  await api.setSession(token(8), "fixture-refresh-b");
  const other = api.bookingCommandOwner({ ...profile, id: 8 }, api.getSessionGeneration());
  assert.deepEqual(await api.legacyTripStatuses(other, 21, signal()), []);
  await api.setSession(token(7), "fixture-refresh-returned");
  const restored = api.bookingCommandOwner(profile, api.getSessionGeneration());
  assert.equal((await api.legacyTripStatuses(restored, 21, signal())).length, 1);

  const calls = [];
  let arrived = false;
  globalThis.fetch = async (url, init) => {
    const path = new URL(url).pathname;
    calls.push([path, init.method]);
    if (path.endsWith("/role")) return reply({ role: "passenger", status: "onboard", onboard_at: null });
    if (path.endsWith("/shares")) return reply(arrived ? [{ last_status: "arrived" }] : []);
    throw new Error("unexpected legacy write");
  };
  assert.equal((await api.reconcileLegacyTripStatuses(restored, 21, signal())).length, 1);
  arrived = true;
  assert.deepEqual(await api.reconcileLegacyTripStatuses(restored, 21, signal()), []);
  assert.deepEqual(calls, [
    ["/bookings/21/role", "GET"], ["/bookings/21/shares", "GET"],
    ["/bookings/21/role", "GET"], ["/bookings/21/shares", "GET"],
  ]);
});

await t.test("a definitive refusal is displayed only with a matching rejected receipt", async () => {
  const { api, owner } = await fresh();
  globalThis.fetch = async url => new URL(url).pathname === endpoint
    ? reply({ detail: { ru: "Посадку ещё не отметили", ba: "Ултырыу әлегә билдәләнмәгән", code: "arrived_requires_onboard" } }, 409)
    : reply({ ...unseen, scope, state: "rejected", result_code: "arrived_requires_onboard" });
  const result = await api.submitPassengerTripStatus(owner, 21, "arrived", signal());
  assert.equal(result.state, "rejected");
  assert.equal(result.refusal.message, "Посадку ещё не отметили");
  assert.equal((await api.passengerTripStatuses.read(owner))[0].state, "rejected");
});

await t.test("closing while original POST is held leaves the original slot terminal and never sends a new key", async () => {
  const { api, owner } = await fresh();
  let send, release;
  let writes = 0;
  const entered = new Promise(resolve => send = resolve);
  globalThis.fetch = async (url, init) => {
    const path = new URL(url).pathname;
    if (path === endpoint) {
      writes++;
      send();
      await new Promise(resolve => release = resolve);
      return reply({ detail: { code: "idempotency_closed_no_effect" } }, 409);
    }
    assert.ok(path.startsWith("/command-intents/"));
    assert([ "POST", "GET" ].includes(init.method));
    return reply({ ...unseen, scope, state: "closed_no_effect", result_code: "idempotency_closed_no_effect" });
  };
  const pending = api.submitPassengerTripStatus(owner, 21, "done", signal());
  await entered;
  const [saved] = await api.passengerTripStatuses.read(owner);
  assert.equal((await api.checkPassengerTripStatus(owner, saved, true, signal())).state, "closed_no_effect");
  release();
  assert.equal((await pending).state, "closed_no_effect");
  assert.equal((await api.passengerTripStatuses.read(owner))[0].state, "closed_no_effect");
  assert.equal(writes, 1);
});

await t.test("legacy binding and logout survive a failure opening the separate command database", async () => {
  const { api, owner } = await fresh();
  await api.enqueue(21, "trip_status", "done");
  const original = indexedDB.open.bind(indexedDB);
  indexedDB.open = (name, version) => {
    if (name === "yuldash-booking-commands-v1") throw new DOMException("storage limit", "QuotaExceededError");
    return original(name, version);
  };
  await api.preserveLegacyTripStatuses(owner);
  await api.clearOutbox();
  await api.setSession(token(7), "fixture-refresh-returned");
  const returned = api.bookingCommandOwner(profile, api.getSessionGeneration());
  await assert.rejects(api.passengerTripStatuses.read(returned), { name: "QuotaExceededError" });
  indexedDB.open = original;
  assert.equal((await api.legacyTripStatuses(returned, 21, signal()))[0].payload, "done");
  await api.purgeBookingCommands(returned);
  assert.deepEqual(await api.legacyTripStatuses(returned, 21, signal()), []);
});

await t.test("a failed binding of IndexedDB and legacy localStorage survives logout and is owned by the original account", async () => {
  const { api, owner } = await fresh();
  await api.enqueue(21, "trip_status", "arrived");
  localStorage.setItem("yuldash.outbox", JSON.stringify([
    { id: "old-done", session: "session-a", bookingId: 21, kind: "trip_status", payload: "done", createdAt: 100 },
    { id: "old-chat", session: "session-a", bookingId: 21, kind: "message", payload: "private", createdAt: 101 },
  ]));
  const original = IDBDatabase.prototype.transaction;
  IDBDatabase.prototype.transaction = function (...args) {
    const tx = original.apply(this, args);
    if (this.name === "yuldash-outbox-v1" && Array.isArray(args[0])) queueMicrotask(() => tx.abort());
    return tx;
  };
  try {
    await assert.rejects(api.preserveLegacyTripStatuses(owner));
  } finally {
    IDBDatabase.prototype.transaction = original;
  }
  await api.clearOutbox();
  const rows = await storedActions();
  assert.deepEqual(rows.map(action => [action.session, action.payload]), [["session-a", "arrived"]]);
  assert.deepEqual(JSON.parse(localStorage.getItem("yuldash.outbox")).map(row => row.id), ["old-done"]);
  await api.setSession(token(8), "fixture-refresh-b");
  const other = api.bookingCommandOwner({ ...profile, id: 8 }, api.getSessionGeneration());
  assert.deepEqual(await api.legacyTripStatuses(other, 21, signal()), []);
  await api.purgeBookingCommands(other);
  await api.setSession(token(7), "fixture-refresh-returned");
  const returned = api.bookingCommandOwner(profile, api.getSessionGeneration());
  assert.deepEqual((await api.legacyTripStatuses(returned, 21, signal())).map(row => row.payload), ["arrived", "done"]);
  assert.equal(localStorage.getItem("yuldash.outbox"), null);
  await api.reconcileLegacyTripStatuses(returned, 21, signal(), true);
  assert.deepEqual(await api.legacyTripStatuses(returned, 21, signal()), []);
});

await t.test("account deletion purges failed bindings from previous login generations", async () => {
  const { api, owner } = await fresh();
  await api.enqueue(21, "trip_status", "done");
  localStorage.setItem("yuldash.outbox", JSON.stringify([
    { id: "old-arrival", session: "session-a", bookingId: 21, kind: "trip_status", payload: "arrived", createdAt: 100 },
  ]));
  const original = IDBDatabase.prototype.transaction;
  IDBDatabase.prototype.transaction = function (...args) {
    const tx = original.apply(this, args);
    if (this.name === "yuldash-outbox-v1" && Array.isArray(args[0])) queueMicrotask(() => tx.abort());
    return tx;
  };
  try {
    await assert.rejects(api.preserveLegacyTripStatuses(owner));
  } finally {
    IDBDatabase.prototype.transaction = original;
  }
  await api.clearOutbox();
  await api.setSession(token(7), "fixture-refresh-returned");
  const returned = api.bookingCommandOwner(profile, api.getSessionGeneration());
  await api.setSession(null);
  await api.purgeBookingCommands(returned);
  assert.deepEqual(await storedActions(), []);
  assert.equal(localStorage.getItem("yuldash.outbox"), null);
});

t.done();
