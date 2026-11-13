// Real client and IndexedDB transactions, with a controlled transport at the fetch boundary.
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { IDBFactory } from "fake-indexeddb";

const root = fileURLToPath(new URL("../", import.meta.url));
const compiled = await build({
  stdin: {
    contents: "export {initializeSessionStorage,setSession,reconcileSession,rotateSession} from './src/api/client';export * from './src/api/bookingCommands';export * from './src/utils/bookingCommandStore';export * from './src/utils/commandFingerprint';export {deleteAccount} from './src/api/auth';",
    resolveDir: root, loader: "ts",
  },
  bundle: true, write: false, format: "esm", platform: "node",
  define: { "import.meta.env": JSON.stringify({ VITE_API_BASE: "https://booking.test" }) },
});
let imports = 0;
const freshModule = () => import("data:text/javascript;base64," +
  Buffer.from(compiled.outputFiles[0].text + "\n// " + imports++).toString("base64"));
let api, restarted;
Object.defineProperty(globalThis, "crypto", { value: webcrypto, configurable: true });
const user = { id: 7, created_at: "2026-01-01T10:00:00Z" };
const other = { id: 9, created_at: "2026-01-01T10:01:00Z" };
const token = id => "header." + Buffer.from(JSON.stringify({ sub: String(id) })).toString("base64url") + ".sig";
const signal = () => new AbortController().signal;
const reply = (body, status = 200) => new Response(JSON.stringify(body), { status });
const body = { ride_id: 12, seats: 2, meeting: { note: "Ишек янында", lat: 54, lng: 56 } };
const booking = { id: 21, ride_id: 12, passenger_id: user.id, status: "pending" };
const receipt = (state, current = null) => ({
  state, scope: state === "unseen" ? null : "booking.create", ref_id: current?.id ?? null, current,
});
const restore = async () => {
  globalThis.indexedDB = new IDBFactory();
  const values = new Map([["yuldash.session", "generation-a"], ["yuldash.token", token(user.id)], ["yuldash.refresh", "fixture-refresh"]]);
  globalThis.localStorage = { getItem: key => values.get(key) ?? null };
  api = await freshModule(); await api.initializeSessionStorage();
  restarted = await freshModule(); await restarted.initializeSessionStorage();
};
const ownerA = () => api.bookingCommandOwner(user, "generation-a");
const problem = kind => error => error instanceof api.BookingCommandProblem && error.kind === kind;

await restore();
const keys = [];
globalThis.fetch = async (url, options) => {
  assert.equal(new URL(url).origin, "https://booking.test");
  assert.equal(options.redirect, "error");
  assert.equal(options.headers.Authorization, `Bearer ${token(user.id)}`);
  if (options.method === "POST") {
    assert.equal(new URL(url).pathname, "/bookings/commands/v1");
    assert.deepEqual(JSON.parse(options.body), body);
    const saved = await api.readBookingCommand(ownerA(), body.ride_id);
    assert.equal(saved.key, options.headers["Idempotency-Key"], "record must already be committed");
    keys.push(saved.key);
    throw new TypeError("network closed after server commit");
  }
  assert.equal(new URL(url).pathname, `/command-intents/${keys[0]}`);
  return reply(receipt("applied", { ...booking, status: "cancelled" }));
};
await assert.rejects(api.createBooking(body, signal(), ownerA()), problem("unknown"));
restarted = await freshModule(); await restarted.initializeSessionStorage();
const saved = await restarted.readBookingCommand(ownerA(), body.ride_id);
assert.equal(saved.state, "unknown");
assert.equal(Object.hasOwn(saved, "body"), false);
assert.doesNotMatch(JSON.stringify(saved), /Ишек янында/);
await assert.rejects(restarted.createBooking(body, signal(), ownerA()), error => error.kind === "unknown");
const applied = await restarted.reconcileBookingCommand(ownerA(), saved, false, signal());
assert.equal(applied.booking.status, "cancelled");
assert.deepEqual(keys, [saved.key]);
assert.equal((await api.readBookingCommand(ownerA(), body.ride_id)).state, "applied");
console.log("PASS saved before fetch, lost reply, restart and current server result");

await restore();
let posts = 0;
let firstKey;
globalThis.fetch = async (url, options) => {
  assert.equal(new URL(url).pathname, "/bookings/commands/v1");
  posts++;
  firstKey ??= options.headers["Idempotency-Key"];
  assert.equal(options.headers["Idempotency-Key"], firstKey);
  if (posts === 1) return reply({ detail: "Old instance" }, 404);
  return reply(booking);
};
const prepared = [];
const onPrepared = (key, input) => prepared.push({ key, input });
const tabs = await Promise.allSettled([
  api.createBooking(body, signal(), ownerA(), undefined, false, onPrepared),
  restarted.createBooking(body, signal(), ownerA(), undefined, false, onPrepared),
]);
assert.ok(tabs.every(result => result.status === "rejected" && result.reason.kind === "unknown"));
assert.equal(posts, 1, "only the reserving tab sends the first POST");
assert.deepEqual(prepared, [{ key: firstKey, input: body }], "the other tab did not prepare a send for its form");
const after404 = await api.readBookingCommand(ownerA(), body.ride_id);
assert.equal(after404.key, firstKey);
await assert.rejects(api.createBooking({ ...body, seats: 3 }, signal(), ownerA(), after404, true), problem("changed"));
assert.equal(posts, 1);
assert.equal((await api.createBooking(body, signal(), ownerA(), after404, true)).id, booking.id);
assert.equal(posts, 2);
console.log("PASS two tabs, old server 404 and manual retry with the same key");

const forAnother = { ...body, traveler_name: "Ҡунаҡ Һынау" };
await restore();
const travelerRequests = [];
globalThis.fetch = async (url, opts) => {
  const path = new URL(url).pathname;
  travelerRequests.push(path);
  assert.equal(opts.cache, "no-store");
  return reply({ detail: "old server" }, 404);
};
await assert.rejects(api.createBooking(forAnother, signal(), ownerA()), problem("unsupported"));
assert.deepEqual(travelerRequests, ["/bookings/capabilities"]);
assert.equal(await api.readBookingCommand(ownerA(), body.ride_id), undefined);
console.log("PASS old server receives no booking or name");

await restore();
let travelerKey;
let originalTravelerBody;
let oldInstance = true;
globalThis.fetch = async (url, opts) => {
  const path = new URL(url).pathname;
  if (path === "/bookings/capabilities") return reply({ traveler_name: true });
  if (path === "/bookings/for-another/commands/v1") {
    travelerKey ??= opts.headers["Idempotency-Key"];
    originalTravelerBody ??= opts.body;
    assert.equal(opts.headers["Idempotency-Key"], travelerKey);
    assert.equal(opts.body, originalTravelerBody);
    assert.deepEqual(JSON.parse(opts.body), forAnother);
    return oldInstance ? reply({ detail: "old instance" }, 404) : reply(booking);
  }
  assert.fail("unexpected path for another passenger: " + path);
};
await assert.rejects(api.createBooking(forAnother, signal(), ownerA()), problem("unsupported"));
const travelerSaved = await restarted.readBookingCommand(ownerA(), body.ride_id);
assert.equal(travelerSaved.key, travelerKey);
assert.equal(travelerSaved.forAnother, true);
assert.doesNotMatch(JSON.stringify(travelerSaved), /Ҡунаҡ Һынау|traveler_name/);
await assert.rejects(restarted.createBooking(body, signal(), ownerA(), travelerSaved, true), error => error.kind === "changed");
oldInstance = false;
assert.equal((await api.createBooking(forAnother, signal(), ownerA(), travelerSaved, true)).id, booking.id);
console.log("PASS mixed deployment keeps the same key and dedicated address");

await restore();
let conflictKey;
globalThis.fetch = async (url, opts) => {
  const path = new URL(url).pathname;
  if (path === "/bookings/capabilities") return reply({ traveler_name: true });
  if (path === "/bookings/for-another/commands/v1") {
    conflictKey = opts.headers["Idempotency-Key"];
    throw new TypeError("response lost");
  }
  assert.equal(path, `/command-intents/${conflictKey}`);
  assert.equal(opts.method, "GET");
  return reply({ ...receipt("rejected"), result_code: "booking_traveler_conflict", booking_id: booking.id });
};
await assert.rejects(api.createBooking(forAnother, signal(), ownerA()), problem("unknown"));
const lostConflict = await restarted.readBookingCommand(ownerA(), body.ride_id);
assert.equal((await restarted.reconcileBookingCommand(ownerA(), lostConflict, false, signal())).conflictBookingId, booking.id);
console.log("PASS lost conflict response recovers the existing booking without resending the name");

for (const originalVersion of [0, undefined]) {
  await restore();
  const requests = [];
  globalThis.fetch = async (url, options) => {
    assert.equal(new URL(url).pathname, "/bookings/commands/v1");
    requests.push({ body: options.body, key: options.headers["Idempotency-Key"] });
    if (requests.length === 1) throw new TypeError("network closed after server commit");
    return reply({ ...booking, status: "cancelled" });
  };
  await assert.rejects(api.createBooking({ ...body, expected_terms_version: originalVersion }, signal(), ownerA()), problem("unknown"));
  const savedTerms = await restarted.readBookingCommand(ownerA(), body.ride_id);
  assert.equal(savedTerms.expectedTermsVersion, originalVersion ?? null);
  const refreshed = { ...body, expected_terms_version: 2 };
  const snapshots = [];
  const capture = (key, input) => snapshots.push({ key, input });
  await assert.rejects(restarted.createBooking({ ...refreshed, seats: 3 }, signal(), ownerA(), savedTerms, true, capture),
    error => error.kind === "changed");
  assert.equal(requests.length, 1);
  assert.deepEqual(snapshots, []);
  assert.equal((await restarted.createBooking(refreshed, signal(), ownerA(), savedTerms, true, capture)).status, "cancelled");
  assert.deepEqual(requests[0], requests[1]);
  assert.deepEqual(snapshots, [{ key: savedTerms.key, input: { ...body, expected_terms_version: originalVersion } }]);
  assert.equal(refreshed.expected_terms_version, 2);
}
await restore();
const legacyBody = { ...body, expected_terms_version: 0 };
const legacyHash = await api.commandFingerprint("booking.create", body.ride_id, legacyBody);
const legacy = (await api.reserveBookingCommand(ownerA(), body.ride_id, legacyHash.payloadHash)).command;
assert.equal(legacy.expectedTermsVersion, undefined);
globalThis.fetch = async () => { assert.fail("a legacy record cannot guess an older version"); };
await assert.rejects(api.createBooking({ ...legacyBody, expected_terms_version: 2 }, signal(), ownerA(), legacy, true),
  problem("changed"));
console.log("PASS replay keeps the exact original terms after seat changes and rejects edited bookings");

await restore();
let releasePost;
let began;
const postStarted = new Promise(resolve => { began = resolve; });
const delayed = new Promise(resolve => { releasePost = resolve; });
let closed = false;
globalThis.fetch = async (url, options) => {
  if (new URL(url).pathname.endsWith("/close")) {
    const current = await api.readBookingCommand(ownerA(), body.ride_id);
    assert.deepEqual(JSON.parse(options.body), { scope: "booking.create", target_id: 12, payload_hash: current.hash });
    closed = true;
    return reply(receipt("closed_no_effect"));
  }
  if (options.method === "GET") return reply(receipt(closed ? "closed_no_effect" : "unseen"));
  began();
  await delayed;
  return reply({ detail: { code: "idempotency_closed_no_effect" } }, 409);
};
const late = api.createBooking(body, signal(), ownerA());
const capturedLate = assert.rejects(late, problem("closed"));
await postStarted;
const pending = await api.readBookingCommand(ownerA(), body.ride_id);
assert.equal((await api.reconcileBookingCommand(ownerA(), pending, true, signal())).state, "closed_no_effect");
releasePost();
await capturedLate;
const generationB = await api.setSession(token(other.id), "fixture-refresh-B");
globalThis.fetch = () => assert.fail("must not send an old action with new account credentials");
await assert.rejects(api.createBooking(body, signal(), ownerA(), { ...pending, state: "closed_no_effect" }), problem("storage"));
assert.equal(await api.readBookingCommand(api.bookingCommandOwner(other, generationB), body.ride_id), undefined);
await api.purgeBookingCommands(ownerA());
const generationA2 = await api.setSession(token(user.id), "fixture-refresh-A2");
const nextOwner = api.bookingCommandOwner(user, generationA2);
assert.equal(await api.readBookingCommand(nextOwner, body.ride_id), undefined);
assert.equal(await api.resolveBookingCommand(nextOwner, pending, "applied"), false);
console.log("PASS close wins race, captured account token, purge and stale response");

await restore();
const originalNow = Date.now;
try {
  Date.now = () => 1000;
  for (let rideId = 1; rideId <= 64; rideId++) {
    const { payloadHash } = await api.commandFingerprint("booking.create", rideId, { ride_id: rideId });
    assert.equal((await api.reserveBookingCommand(ownerA(), rideId, payloadHash)).status, "reserved");
  }
  assert.equal((await api.reserveBookingCommand(ownerA(), 65, "test-hash")).status, "full");
  assert.equal((await api.readPendingBookingCommands(ownerA())).length, 64);
  const oldest = await api.readBookingCommand(ownerA(), 1);
  Date.now = () => 1000 + api.BOOKING_RETRY_WINDOW;
  await assert.rejects(api.createBooking({ ride_id: 1 }, signal(), ownerA(), oldest, true), problem("expired"));
  assert.equal((await api.readBookingCommand(ownerA(), 1)).key, oldest.key);
  globalThis.indexedDB = { open: () => { throw new Error("No storage"); } };
  await assert.rejects(api.createBooking(body, signal(), ownerA()), problem("storage"));
} finally {
  Date.now = originalNow;
}
console.log("PASS capacity keeps unknown commands and storage failure prevents fetch");

await restore();
await api.setSession(token(other.id), "fixture-refresh-B");
globalThis.fetch = () => assert.fail("cannot delete under a new account token");
await assert.rejects(api.deleteAccount(ownerA()), error => error.status === 409);
await restore();
globalThis.fetch = async (url, options) => {
  assert.equal(new URL(url).pathname, "/me/delete");
  assert.equal(options.headers.Authorization, `Bearer ${token(user.id)}`);
  await api.setSession(token(other.id), "fixture-refresh-B");
  return reply({ ok: true });
};
assert.deepEqual(await api.deleteAccount(ownerA()), { ok: true });
console.log("PASS deletion uses original credentials and returns only its own confirmation after a session switch");
