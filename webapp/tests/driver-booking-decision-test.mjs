import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { IDBFactory } from "fake-indexeddb";

const compiled = await build({
  stdin: {
    contents: "export * from './src/api/bookingDecisions';export * from './src/utils/bookingCommandStore';export { readOfflineTrip } from './src/utils/offlineTripPass';",
    resolveDir: fileURLToPath(new URL("../", import.meta.url)), loader: "ts",
  },
  bundle: true, write: false, format: "esm", platform: "node",
  define: { "import.meta.env": JSON.stringify({ VITE_API_BASE: "https://booking.test" }) },
});
let imports = 0;
const fresh = () => import("data:text/javascript;base64," +
  Buffer.from(compiled.outputFiles[0].text + "\n// " + imports++).toString("base64"));
const api = await fresh();
Object.defineProperty(globalThis, "crypto", { value: webcrypto, configurable: true });
const user = { id: 7, created_at: "2026-01-01T10:00:00Z" };
const token = id => "header." + Buffer.from(JSON.stringify({ sub: String(id) })).toString("base64url") + ".sig";
const signal = () => new AbortController().signal;
const reply = (body, status = 200) => new Response(JSON.stringify(body), { status });
const owner = api.bookingCommandOwner(user, "generation-a");
const row = { id: 21, ride_id: 4, passenger_id: 8, status: "cancelled", declined: false };
const unseen = { state: "unseen", scope: null, ref_kind: null, ref_id: null, current: null };
const terminal = (scope, state) => ({ scope, state, ref_id: null, ref_kind: null, current: null });
function reset() {
  globalThis.indexedDB = new IDBFactory();
  const values = new Map([["yuldash.session", "generation-a"], ["yuldash.token", token(7)]]);
  globalThis.localStorage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key),
  };
  return values;
}

reset();
let key;
let writes = 0;
globalThis.fetch = async (url, init) => {
  const path = new URL(url).pathname;
  if (init.method === "GET") {
    assert.equal(path, "/command-intents/" + key);
    return reply(unseen);
  }
  assert.equal(path, "/bookings/21/commands/v1/confirm");
  assert.equal(init.body, undefined);
  const [saved] = await api.readDriverDecisions(owner);
  assert.equal(init.headers["Idempotency-Key"], saved.key);
  assert.equal(Object.hasOwn(saved, "body"), false);
  writes++;
  if (writes === 1) { key = saved.key; throw new TypeError("response lost"); }
  assert.equal(key, saved.key);
  return reply(row);
};
await assert.rejects(api.decideDriverBooking(owner, 21, "confirm", signal()), { kind: "pending" });
const restarted = await fresh();
const [decision] = await restarted.readDriverDecisions(owner);
assert.equal((await restarted.decideDriverBooking(owner, 21, "decline", signal())).state, "unseen");
assert.equal(writes, 1);
assert.deepEqual(await restarted.repeatDriverDecision(owner, decision, signal()), { state: "applied", booking: { id: 21, status: "cancelled", declined: false } });
assert.equal(writes, 2);
console.log("PASS saved before POST, confirm without a body, conflicting action only reads, exact repeat after reload");

reset();
const terms = "a".repeat(64);
const attempts = [];
globalThis.fetch = async (url, init) => {
  const path = new URL(url).pathname;
  attempts.push([init.method, path]);
  if (path.endsWith("/role")) return reply({ role: "driver" });
  if (path.endsWith("/cancel-preview")) return reply({ booking_status: "pending", terms_key: terms });
  if (path.endsWith("/cancel")) {
    assert.equal(path, "/bookings/21/commands/v1/cancel");
    assert.deepEqual(JSON.parse(init.body), { reason: "", expected_terms_key: terms });
    const [saved] = await api.readDriverDecisions(owner);
    assert.equal(saved.termsKey, terms);
    return reply({ detail: { code: "cancel_terms_changed", ru: "Условия отмены изменились." } }, 409);
  }
  return reply(terminal("booking.cancel", "rejected"));
};
assert.equal((await api.decideDriverBooking(owner, 21, "decline", signal())).state, "rejected");
assert.equal(attempts.filter(([method]) => method === "POST").length, 1);
assert.equal(attempts.filter(([, path]) => path.endsWith("/cancel-preview")).length, 1);
globalThis.fetch = async (url, init) => {
  assert.equal(init.method, "GET");
  const path = new URL(url).pathname;
  if (path === "/bookings/21/role") return reply({ role: "driver" });
  assert.equal(path, "/bookings/21/cancel-preview");
  return reply({ booking_status: "confirmed", terms_key: "b".repeat(64) });
};
await assert.rejects(api.decideDriverBooking(owner, 21, "decline", signal()), { kind: "changed" });
globalThis.fetch = async (url, init) => {
  assert.equal(init.method, "GET");
  assert.equal(new URL(url).pathname, "/bookings/21/role");
  return reply({ role: "passenger" });
};
await assert.rejects(api.decideDriverBooking(owner, 21, "decline", signal()), { kind: "changed" });
console.log("PASS decline is bound to pending terms, change refuses and never posts with refreshed terms");

reset();
let declineWire;
globalThis.fetch = async (url, init) => {
  const path = new URL(url).pathname;
  if (path.endsWith("/role")) return reply({ role: "driver" });
  if (path.endsWith("/cancel-preview")) return reply({ booking_status: "pending", terms_key: terms });
  assert.equal(path, "/bookings/21/commands/v1/cancel");
  declineWire = [init.headers["Idempotency-Key"], init.body];
  throw new TypeError("response lost");
};
await assert.rejects(api.decideDriverBooking(owner, 21, "decline", signal()), { kind: "pending" });
const declineRestarted = await fresh();
const [lostDecline] = await declineRestarted.readDriverDecisions(owner);
globalThis.fetch = async (url, init) => {
  assert.equal(new URL(url).pathname, "/bookings/21/commands/v1/cancel");
  assert.equal(init.method, "POST");
  assert.deepEqual([init.headers["Idempotency-Key"], init.body], declineWire);
  return reply({ ...row, declined: true });
};
assert.equal((await declineRestarted.repeatDriverDecision(owner, lostDecline, signal())).booking?.declined, true);
console.log("PASS decline repeat after reload uses identical key and body without reading new terms");

const values = reset();
let closeBody;
globalThis.fetch = async (url, init) => {
  if (new URL(url).pathname.endsWith("/close")) {
    closeBody = JSON.parse(init.body);
    return reply(terminal("booking.confirm", "closed_no_effect"));
  }
  return reply({}, 404);
};
await assert.rejects(api.decideDriverBooking(owner, 21, "confirm", signal()), { kind: "pending" });
const [beforeClose] = await api.readDriverDecisions(owner);
assert.equal((await api.checkDriverDecision(owner, beforeClose, true, signal())).state, "closed_no_effect");
assert.deepEqual(closeBody, { scope: "booking.confirm", target_id: 21, payload_hash: beforeClose.hash });
await assert.rejects(api.repeatDriverDecision(owner, beforeClose, signal()), { kind: "stale" });
values.set("yuldash.session", "generation-b");
values.set("yuldash.token", token(9));
await assert.rejects(api.decideDriverBooking(owner, 21, "confirm", signal()));
await api.purgeBookingCommands(owner);
values.set("yuldash.session", "generation-a");
values.set("yuldash.token", token(7));
assert.deepEqual(await api.readDriverDecisions(owner), []);
console.log("PASS closed intent cannot replay, session switch blocks old owner, deletion purges decision");

reset();
const passengerPosts = [];
const passengerRow = { ...row, passenger_id: owner.userId, contact_then_cancel: true };
const receipt = { state: "applied", scope: "booking.cancel", ref_kind: "booking", ref_id: 21, current: passengerRow };
globalThis.fetch = async (url, init) => {
  const path = new URL(url).pathname;
  if (path.endsWith("/role")) return reply({ role: "passenger" });
  if (path.endsWith("/cancel-preview")) return reply({ booking_status: "confirmed", consequence: "late", terms_key: terms });
  assert.equal(path, "/bookings/21/commands/v1/cancel");
  assert.equal(init.method, "POST");
  passengerPosts.push([init.headers["Idempotency-Key"], init.body]);
  assert.deepEqual(JSON.parse(init.body), { reason: "found_other", expected_terms_key: terms });
  const [saved] = await api.readDriverDecisions(owner);
  assert.equal(saved.reason, "found_other");
  if (passengerPosts.length === 1) throw new TypeError("response lost");
  const open = indexedDB.open.bind(indexedDB);
  indexedDB.open = (name, ...args) => {
    if (name === "yuldash-trip-passes-v1") throw new Error("trip storage full");
    return open(name, ...args);
  };
  return reply(passengerRow);
};
const shown = await api.preparePassengerCancellation(owner, 21, "confirmed", signal());
assert.equal(shown.consequence, "late");
assert.equal(passengerPosts.length, 0);
await assert.rejects(api.cancelPassengerBooking(owner, shown, "found_other", signal()), { kind: "pending" });
const passengerRestarted = await fresh();
const [savedPassenger] = await passengerRestarted.readDriverDecisions(owner);
const persist = indexedDB.open.bind(indexedDB);
assert.equal((await passengerRestarted.readOfflineTrip(21)).status, "held");
await assert.rejects(passengerRestarted.repeatDriverDecision(owner, savedPassenger, signal()), { kind: "storage" });
assert.deepEqual(passengerPosts[0], passengerPosts[1]);
assert.equal((await passengerRestarted.readDriverDecisions(owner))[0].state, "unknown");
indexedDB.open = persist;
globalThis.fetch = async (url, init) => {
  assert.equal(new URL(url).pathname, "/command-intents/" + savedPassenger.key);
  assert.equal(init.method, "GET");
  return reply(receipt);
};
const recovered = await passengerRestarted.checkDriverDecision(owner, savedPassenger, false, signal());
assert.equal(recovered.booking?.contact_then_cancel, true);
assert.equal((await passengerRestarted.readOfflineTrip(21)).status, "terminal");
assert.equal((await passengerRestarted.readDriverDecisions(owner))[0].state, "applied");
console.log("PASS passenger consents to preview before POST, repeat preserves reason and terms, terminal passport fence precedes settlement");

const switched = reset();
let forbiddenWrites = 0;
globalThis.fetch = async (url, init) => {
  const path = new URL(url).pathname;
  if (path.endsWith("/role")) return reply({ role: "passenger" });
  if (path.endsWith("/cancel-preview")) return reply({ booking_status: "confirmed", consequence: "early", terms_key: terms });
  forbiddenWrites++;
  throw new Error("unexpected write");
};
await assert.rejects(api.preparePassengerCancellation(owner, 21, "pending", signal()), { kind: "changed" });
const approved = await api.preparePassengerCancellation(owner, 21, "confirmed", signal());
switched.set("yuldash.session", "generation-b");
const laterOwner = api.bookingCommandOwner(user, "generation-b");
await assert.rejects(api.cancelPassengerBooking(laterOwner, approved, "", signal()), { kind: "stale" });
assert.equal(forbiddenWrites, 0);
console.log("PASS changed booking stage and session invalidate the passenger consent without a write");

reset();
globalThis.fetch = async (url, init) => {
  const path = new URL(url).pathname;
  if (path.endsWith("/role")) return reply({ role: "passenger" });
  if (path.endsWith("/cancel-preview")) return reply({ booking_status: "pending", consequence: "early", terms_key: terms });
  if (init.method === "POST") throw new TypeError("response lost");
  return reply({ ...receipt, current: null, result_code: "idempotency_result_gone" });
};
const beforeDeletion = await api.preparePassengerCancellation(owner, 21, "pending", signal());
await assert.rejects(api.cancelPassengerBooking(owner, beforeDeletion, "", signal()), { kind: "pending" });
const [lost] = await api.readDriverDecisions(owner);
assert.deepEqual(await api.checkDriverDecision(owner, lost, false, signal()), { state: "applied", booking: null });
assert.equal((await api.readOfflineTrip(21)).status, "terminal");
assert.equal((await api.readDriverDecisions(owner))[0].state, "applied");
console.log("PASS disappeared passenger booking closes the offline passport without inventing a booking status");
