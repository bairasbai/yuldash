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
const tabs = await Promise.allSettled([
  api.createBooking(body, signal(), ownerA()),
  restarted.createBooking(body, signal(), ownerA()),
]);
assert.ok(tabs.every(result => result.status === "rejected" && result.reason.kind === "unknown"));
assert.equal(posts, 1, "only the reserving tab sends the first POST");
const after404 = await api.readBookingCommand(ownerA(), body.ride_id);
assert.equal(after404.key, firstKey);
await assert.rejects(api.createBooking({ ...body, seats: 3 }, signal(), ownerA(), after404, true), problem("changed"));
assert.equal(posts, 1);
assert.equal((await api.createBooking(body, signal(), ownerA(), after404, true)).id, booking.id);
assert.equal(posts, 2);
console.log("PASS two tabs, old server 404 and manual retry with the same key");

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
  await assert.rejects(restarted.createBooking({ ...refreshed, seats: 3 }, signal(), ownerA(), savedTerms, true),
    error => error.kind === "changed");
  assert.equal(requests.length, 1);
  assert.equal((await restarted.createBooking(refreshed, signal(), ownerA(), savedTerms, true)).status, "cancelled");
  assert.deepEqual(requests[0], requests[1]);
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
