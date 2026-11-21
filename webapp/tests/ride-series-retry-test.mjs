import assert from "node:assert/strict";
import { createHash, webcrypto } from "node:crypto";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { IDBFactory } from "fake-indexeddb";

const bundle = await build({
  stdin: {
    contents: "export {initializeSessionStorage,setSession,reconcileSession} from './src/api/client';" +
      "export * from './src/api/rideSeries';export * from './src/utils/rideSeriesIntent';" +
      "export * from './src/utils/bookingCommandStore';",
    resolveDir: fileURLToPath(new URL("../", import.meta.url)), loader: "ts",
  },
  bundle: true, write: false, format: "esm", platform: "node",
  define: { "import.meta.env": JSON.stringify({ VITE_API_BASE: "https://series.test" }) },
});
const load = name => import("data:text/javascript;base64," +
  Buffer.from(bundle.outputFiles[0].text + "\n// " + name).toString("base64"));
const [first, second] = await Promise.all([load("tab one"), load("tab two")]);
Object.defineProperty(globalThis, "crypto", { value: webcrypto, configurable: true });
globalThis.indexedDB = new IDBFactory();
const token = id => "header." + Buffer.from(JSON.stringify({ sub: String(id) })).toString("base64url") + ".sig";
const values = new Map([["yuldash.session", "test-session"], ["yuldash.token", token(7)], ["yuldash.refresh", "test-refresh"]]);
globalThis.localStorage = { getItem: key => values.get(key) ?? null };
await Promise.all([first.initializeSessionStorage(), second.initializeSessionStorage()]);
const owner = first.bookingCommandOwner({ id: 7, created_at: "2026-06-01T00:00:00Z" }, "test-session");
const request = () => new AbortController().signal;
const dateA = "2030-11-01T05:00:00Z";
const dateB = "2030-11-03T05:00:00Z";
const dateC = "2030-11-04T05:00:00Z";
const dateD = "2030-11-05T05:00:00Z";
const draft = { from_city: "Уфа", to_city: "Сибай", price: 0 };
const expectedHash = createHash("sha256").update(
  '{"body":{"depart_at":"2030-11-01T05:00:00Z","ride":{"from_city":"Уфа","price":0,"to_city":"Сибай"}},"scope":"ride.create","target_id":null}',
).digest("hex");
const intent = await first.newRideSeriesIntent({ ...draft, depart_at: "ignore" },
  [dateB, "2030-11-01T10:00:55+05:00", dateA], "cmd1_test-series-key");
assert.deepEqual(intent.items.map(item => item.depart_at), [dateA, dateB]);
assert.equal(intent.items[0].commandKey, "cmd1_test-series-key-203011010500");
assert.equal(intent.items[0].hash, expectedHash);
console.log("PASS one canonical fingerprint per UTC minute and no unneeded draft departure");

const listPosts = [];
const reads = [];
const closePosts = [];
const pending = async (tab = first) => (await tab.readPublicationSlot(owner)).series;
const response = data => new Response(JSON.stringify(data), { status: 200 });
const applied = (depart_at, ride_id) => ({ depart_at, outcome: "applied", ride_id });
const unknown = depart_at => ({ depart_at, outcome: "unknown" });
const rejected = depart_at => ({ depart_at, outcome: "rejected", code: "time_passed" });
let phase = "first";
globalThis.fetch = async (url, options) => {
  assert.equal(new URL(url).origin, "https://series.test");
  assert.equal(options.headers.Authorization, "Bearer " + token(7));
  const path = new URL(url).pathname;
  if (path === "/rides/series") {
    const saved = await pending();
    assert.equal(options.headers["Idempotency-Key"], saved.key);
    const body = JSON.parse(options.body);
    assert.deepEqual(body.ride, draft);
    listPosts.push({ key: saved.key, dates: body.departures });
    if (phase === "first") return response({ items: [applied(dateA, 51), unknown(dateB)] });
    if (phase === "lost") throw new TypeError("Synthetic loss after commit");
    if (phase === "rejected") return response({ items: [applied(dateA, 61), rejected(dateB), rejected(dateD)] });
    if (phase === "retry") return response({ items: [applied(dateC, 62)] });
    if (phase === "unknown") return response({ items: [unknown(dateB)] });
    throw new Error("unexpected fixture mode");
  }
  if (path.endsWith("/close")) {
    const saved = await pending();
    const item = saved.items.find(row => row.depart_at === dateB);
    assert.ok(path.endsWith(item.commandKey + "/close"));
    const body = JSON.parse(options.body);
    assert.deepEqual(body, { scope: "ride.create", target_id: null, payload_hash: item.hash });
    closePosts.push(item.commandKey);
    return response({ state: "closed_no_effect", scope: "ride.create", result_code: "idempotency_closed_no_effect" });
  }
  assert.ok(path.endsWith((await pending()).items.find(row => row.depart_at === dateB).commandKey));
  reads.push(path);
  if (phase === "unknown") return response({ state: "unseen", scope: null });
  return response({ state: "applied", scope: "ride.create", ref_kind: "ride", ref_id: 52, current: {id: 52, driver_id: 7} });
};

const submit = (tab, revision = null, dates = [dateA, dateB]) =>
  tab.publishRideSeries(draft, dates, owner, revision, "saved-draft", request());
const competing = await Promise.allSettled([submit(first), submit(second)]);
assert.equal(competing.filter(result => result.status === "fulfilled").length, 1);
assert.equal(listPosts.length, 1);
const firstSaved = await pending(second);
assert.deepEqual(firstSaved.items.map(item => item.outcome), ["applied", "unknown"]);
assert.equal(await first.releaseRideSeries(owner, firstSaved), false);
assert.equal((await second.reserveRidePublication(owner, firstSaved.key, "hash", {
  draftHash: "draft", stopLanguage: "ru", utcOffsetMinutes: 0,
})).status, "stale");
console.log("PASS series is persisted before POST and reserves the publication slot across tabs");

phase = "lost";
await assert.rejects(second.sendRideSeries(owner, firstSaved, request()), error => error.status === 0);
assert.deepEqual(listPosts[1], { key: firstSaved.key, dates: [dateB] });
const checked = await first.checkRideSeries(owner, firstSaved, request());
assert.equal(reads.length, 1);
assert.deepEqual(checked.items.map(item => item.ride_id), [51, 52]);
assert.equal(await first.releaseRideSeries(owner, checked), true);
console.log("PASS repeat only one unresolved date with the same key and recover a lost response");

phase = "rejected";
const withFailure = await submit(first, checked.key, [dateA, dateB, dateD]);
phase = "retry";
const republished = await second.publishRejectedRideSeriesDates(owner, withFailure, [dateC], [dateB], request());
assert.notEqual(republished.key, withFailure.key);
assert.deepEqual(listPosts.at(-1), { key: republished.key, dates: [dateC] });
assert.deepEqual(republished.items.map(item => [item.depart_at, item.outcome, item.ride_id]), [
  [dateA, "applied", 61], [dateC, "applied", 62], [dateD, "rejected", undefined],
]);
assert.equal(await second.releaseRideSeries(owner, republished), true);
console.log("PASS explicit new attempt keeps applied rides and unselected refusals");

phase = "unknown";
const unresolved = await submit(second, republished.key, [dateB]);
const expired = { ...unresolved, createdAt: Date.now() - 25 * 60 * 60 * 1000 };
assert.equal(first.canRepeatRideSeries(expired), false);
assert.equal((await first.checkRideSeries(owner, unresolved, request())).items[0].outcome, "unknown");
const closed = await second.closeRideSeriesDate(owner, unresolved, dateB, request());
assert.equal(closed.items[0].code, "idempotency_closed_no_effect");
assert.equal(closePosts.length, 1);
const oldCount = listPosts.length;
await first.sendRideSeries(owner, closed, request());
assert.equal(listPosts.length, oldCount);
console.log("PASS expired checking and confirmed closure obey the original date fingerprint");

assert.equal(await first.releaseRideSeries(owner, closed), true);
const pendingBeforeSwitch = await submit(first, closed.key, [dateB]);
const beforeSwitch = listPosts.length;
const nextGeneration = await second.setSession(token(9), "test-refresh-other");
const other = second.bookingCommandOwner({ id: 9, created_at: "2026-06-02T00:00:00Z" }, nextGeneration);
assert.equal((await second.readPublicationSlot(other)).series, undefined);
await assert.rejects(first.sendRideSeries(owner, pendingBeforeSwitch, request()));
assert.equal(listPosts.length, beforeSwitch);
await first.purgeBookingCommands(owner);
console.log("PASS account changes stop replay and deleted-account storage is purged");
