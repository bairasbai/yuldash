import assert from "node:assert/strict";
import { IDBFactory } from "fake-indexeddb";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";

const bundle = await build({
  stdin: { contents: "export * from './src/api/client'; export * from './src/api/auth'; export * from './src/utils/sessionRecord';",
    resolveDir: fileURLToPath(new URL("../", import.meta.url)), loader: "ts" },
  bundle: true, write: false, format: "esm", platform: "node",
  define: { "import.meta.env": '{"VITE_API_BASE":"https://local-fixture.invalid"}' },
});
let reload = 0;
function load() {
  const source = bundle.outputFiles[0].text + `\n// reload ${reload++}`;
  return import("data:text/javascript;base64," + Buffer.from(source).toString("base64"));
}

globalThis.indexedDB = new IDBFactory();
globalThis.localStorage = { getItem: () => null, setItem: () => {}, removeItem: () => {} };
const nav = { onLine: false };
Object.defineProperty(globalThis, "navigator", { configurable: true, value: nav });
const calls = [];
const replies = [
  () => { throw new TypeError("connection interrupted"); },
  () => new Response("missing", { status: 404 }),
  () => new Response("<html>wrong route</html>"),
  () => new Response(JSON.stringify({ detail: "gateway denied" }), { status: 401 }),
  () => Response.json({ ok: true }),
  () => Response.json({ detail: { code: "auth_session_unavailable" } }, { status: 401 }),
];
globalThis.fetch = async (url, options) => {
  assert.equal(url, "https://local-fixture.invalid/auth/logout/session");
  assert.equal(options.method, "POST");
  assert.equal(options.keepalive, true);
  calls.push(options.headers.Authorization);
  return replies.shift()();
};

const sid = c => c.repeat(64);
const ticket = (id, key) => sid(id) + "." + key.repeat(64);
const jwt = id => "fixture." + Buffer.from(JSON.stringify({ sub: "42", sid: sid(id) })).toString("base64url") + ".fixture";
let api = await load();
const first = await api.setSession(jwt("a"), "refresh-a", undefined, undefined, ticket("a", "1"));
const guest = await api.setSession(null, null);
await api.flushPendingLogouts();
assert.deepEqual(calls, []);
assert.deepEqual(await api.listPendingSessionLogouts(), [{ generation: first, ticket: ticket("a", "1") }]);
console.log("PASS offline logout queues the old ticket and sends nothing");

api = await load();
await api.initializeSessionStorage();
assert.equal(api.getSessionGeneration(), guest);
const second = await api.setSession(jwt("b"), "refresh-b", undefined, guest, ticket("b", "2"));
nav.onLine = true;
for (let attempt = 0; attempt < 4; attempt++) {
  await api.flushPendingLogouts();
  assert.equal((await api.listPendingSessionLogouts()).length, 1);
}
assert.equal(calls.length, 4);
console.log("PASS restart, network failure, absent route and unconfirmed responses preserve the old ticket");

await Promise.all([api.flushPendingLogouts(), api.flushPendingLogouts()]);
assert.deepEqual(calls, Array(5).fill("Bearer " + ticket("a", "1")));
assert.deepEqual(await api.listPendingSessionLogouts(), []);
assert.equal(api.getSessionGeneration(), second);
assert.equal(api.getToken(), jwt("b"));
console.log("PASS retries use the previous ticket only and keep a fresh login for the same account");

await api.setSession("legacy-access", "legacy-refresh", undefined, second, ticket("c", "3"));
await api.flushPendingLogouts();
assert.equal(calls.at(-1), "Bearer " + ticket("b", "2"));
assert.deepEqual(await api.listPendingSessionLogouts(), []);
await api.setSession(null, null);
await api.flushPendingLogouts();
assert.equal(calls.length, 6);
console.log("PASS confirmed invalid tickets are removed and legacy logout never sends an access key");
console.log("4 passed, 0 failed");
