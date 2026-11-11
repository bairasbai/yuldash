import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { IDBFactory } from "fake-indexeddb";

const compiled = await build({
  stdin: {
    contents: "export * from './src/api/ridePublication';export * from './src/utils/bookingCommandStore';",
    resolveDir: fileURLToPath(new URL("../", import.meta.url)),
    loader: "ts",
  },
  bundle: true, write: false, format: "esm", platform: "node",
  define: { "import.meta.env": JSON.stringify({ VITE_API_BASE: "https://publication.test" }) },
});
const load = tab => import("data:text/javascript;base64," +
  Buffer.from(compiled.outputFiles[0].text + "\n// " + tab).toString("base64"));
const [first, second] = await Promise.all([load("first tab"), load("second tab")]);
Object.defineProperty(globalThis, "crypto", { value: webcrypto, configurable: true });
globalThis.indexedDB = new IDBFactory();
const token = id => "header." + Buffer.from(JSON.stringify({ sub: String(id) })).toString("base64url") + ".sig";
const values = new Map([["yuldash.session", "session-a"], ["yuldash.token", token(7)]]);
globalThis.localStorage = { getItem: key => values.get(key) ?? null };
const owner = first.bookingCommandOwner({ id: 7, created_at: "2026-01-01T10:00:00Z" }, "session-a");
const signal = () => new AbortController().signal;
const form = {
  from_city: "Уфа", to_city: "Сибай", depart_at: "2030-01-02T10:00:00Z",
  recurrence: "weekly", comment: "Только для проверки",
};
const draftHash = await first.publicationDraftHash(form);
const normal = { draftHash, stopLanguage: "ru", utcOffsetMinutes: -300 };
const send = (api, options = {}, body = form) => api.publishRide(body, owner, signal(), {
  ...normal, revision: null, ...options,
});
const pendingIn = async (api, account = owner) => (await api.readPublicationSlot(account)).command;
const ride = { id: 42, driver_id: 7, status: "cancelled" };
const receipt = (state, current = null) => ({
  state, scope: state === "unseen" ? null : "ride.create",
  ref_kind: state === "applied" ? "ride" : null, ref_id: current?.id ?? null, current,
});
const reply = body => new Response(JSON.stringify(body), { status: 200 });
const posts = [];
let mode = "lost";
globalThis.fetch = async (url, request) => {
  const path = new URL(url).pathname;
  assert.equal(new URL(url).origin, "https://publication.test");
  assert.equal(request.headers.Authorization, "Bearer " + token(7));
  if (path === "/rides/commands/v1") {
    const saved = await pendingIn(first);
    assert.equal(request.headers["Idempotency-Key"], saved.key);
    assert.deepEqual(Object.keys(saved).sort(), ["createdAt", "draftHash", "hash", "key", "state", "stopLanguage", "utcOffsetMinutes"]);
    assert.equal(saved.draftHash, draftHash);
    assert.equal(saved.stopLanguage, normal.stopLanguage);
    assert.equal(saved.utcOffsetMinutes, normal.utcOffsetMinutes);
    assert.deepEqual(JSON.parse(request.body), form);
    posts.push([saved.key, request.body]);
    if (mode === "lost") throw new TypeError("lost after write");
    return reply(ride);
  }
  assert.ok(path.startsWith("/command-intents/cmd1_"));
  if (path.endsWith("/close")) {
    const saved = await pendingIn(first);
    assert.deepEqual(JSON.parse(request.body), { scope: "ride.create", target_id: null, payload_hash: saved.hash });
    return reply(receipt("closed_no_effect"));
  }
  return reply(receipt("applied", mode === "expired" ? { ...ride, status: "expired" } : ride));
};

const unresolved = error => error.kind === "unknown" && error.command?.state === "unknown";
await Promise.all([
  assert.rejects(send(first), unresolved),
  assert.rejects(send(second), unresolved),
]);
assert.equal(posts.length, 1, "two tabs share one pending publication");
console.log("PASS one durable publication for two tabs");
const pending = await pendingIn(second);
assert.equal(await second.releaseRidePublication(owner, pending), false);
await assert.rejects(send(second, { revision: pending.key, retry: pending }, { ...form, recurrence: "daily" }),
  error => error.kind === "changed");
assert.equal(posts.length, 1);
mode = "replay";
assert.deepEqual((await send(second, { revision: pending.key, retry: pending })).result, { state: "applied", ride });
assert.deepEqual(posts[1], posts[0], "restart sends the same wire body and key");
assert.deepEqual(await first.checkRidePublication(owner, pending, false, signal()), { state: "applied", ride });
mode = "expired";
assert.deepEqual(await first.checkRidePublication(owner, pending, false, signal()), {
  state: "applied", ride: { ...ride, status: "expired" },
});
console.log("PASS same request after reload and safe expired result");
assert.equal((await pendingIn(first)).state, "applied");
await assert.rejects(send(first), error => error.command?.state === "applied");
assert.equal(posts.length, 2, "an acknowledged success still blocks a stale tab");

assert.equal(await second.releaseRidePublication(owner, pending), true);
await assert.rejects(send(first), error => error.kind === "stale");
await assert.rejects(first.checkRidePublication(owner, pending, false, signal()), error => error.kind === "stale");
assert.equal(posts.length, 2, "release retains a version that still blocks a stale tab");
console.log("PASS stale tab stays blocked after another tab releases the result");
mode = "lost";
const { revision } = await first.readPublicationSlot(owner);
assert.equal(revision, pending.key);
await assert.rejects(send(first, { revision }), unresolved);
const next = await pendingIn(first);
assert.notEqual(next.key, pending.key);
assert.deepEqual(await first.checkRidePublication(owner, next, true, signal()), { state: "closed_no_effect" });
await assert.rejects(send(second, { revision: next.key, retry: next }), error => error.kind === "unknown");
assert.equal(posts.length, 3);
console.log("PASS closed request cannot be sent again");

values.set("yuldash.session", "session-b");
values.set("yuldash.token", token(9));
const other = second.bookingCommandOwner({ id: 9, created_at: "2026-01-02T10:00:00Z" }, "session-b");
assert.equal(await pendingIn(second, other), undefined);
await assert.rejects(first.checkRidePublication(owner, next, false, signal()));
await first.purgeBookingCommands(owner);
values.set("yuldash.session", "session-a-again");
values.set("yuldash.token", token(7));
const signedBack = first.bookingCommandOwner({ id: 7, created_at: "2026-01-01T10:00:00Z" }, "session-a-again");
assert.deepEqual(await first.readPublicationSlot(signedBack), { revision: null, command: undefined });
console.log("PASS isolation and deletion of account publication data");
