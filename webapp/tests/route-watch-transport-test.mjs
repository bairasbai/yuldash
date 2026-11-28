import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { IDBFactory } from "fake-indexeddb";
import { checker } from "./shallow-harness.mjs";

const t = checker();
const bundle = await build({
  stdin: {
    contents: "export { initializeSessionStorage, getAccountSession } from './src/api/client'; export * from './src/api/routeWatch';",
    resolveDir: fileURLToPath(new URL("../", import.meta.url)),
    loader: "ts",
  },
  bundle: true,
  write: false,
  format: "esm",
  platform: "node",
  define: { "import.meta.env": JSON.stringify({ VITE_API_BASE: "http://127.0.0.1:9", DEV: false }) },
});

const token = "fixture." + Buffer.from(JSON.stringify({ sub: "7" })).toString("base64url") + ".fixture";
const storage = new Map([
  ["yuldash.session", "route-watch-fixture"], ["yuldash.token", token], ["yuldash.refresh", "fixture-refresh"],
]);
globalThis.indexedDB = new IDBFactory();
globalThis.localStorage = {
  getItem: (key) => storage.get(key) ?? null,
  setItem: (key, value) => storage.set(key, value),
  removeItem: (key) => storage.delete(key),
};
const api = await import("data:text/javascript;base64," + Buffer.from(bundle.outputFiles[0].text).toString("base64"));
await api.initializeSessionStorage();
const owner = api.getAccountSession();
assert.equal(owner?.userId, 7);

const legacy = { id: 7, from_city: "Ургаза", to_city: "Акъяр" };
const current = { ...legacy, direction: "both", watch_kind: "requests", watch_date: "2030-05-01T19:00:00" };
function returnResponse(value, method = "GET") {
  globalThis.fetch = async (url, init) => {
    assert.equal(String(url), "http://127.0.0.1:9/route-watch");
    assert.equal(init.method ?? "GET", method);
    return new Response(JSON.stringify(value), { status: 200 });
  };
}

await t.test("a valid empty array proves absence", async () => {
  returnResponse([]);
  assert.deepEqual(await api.fetchRouteWatches(owner), []);
});

await t.test("an unreadable list cannot confirm absence or an unrelated row", async () => {
  const malformed = [
    {}, [{}], [{ ...legacy, id: 0 }], [{ ...legacy, id: 7.9 }], [{ ...legacy, id: "7" }],
    [{ ...legacy, from_city: 42 }], [{ ...legacy, from_city: "" }], [{ ...legacy, direction: "unknown" }],
  ];
  for (const body of malformed) {
    returnResponse(body);
    await assert.rejects(api.fetchRouteWatches(owner), TypeError);
  }
});

await t.test("an older row and current fields both remain usable", async () => {
  returnResponse([legacy, { ...current, id: 8 }]);
  const rows = await api.fetchRouteWatches(owner);
  assert.equal(rows[0].direction, "forward");
  assert.equal(rows[0].watch_kind, "rides");
  assert.equal(api.matchingRouteWatch(rows, { from: "Ургаза", to: "Акъяр" })?.id, 7);
  assert.equal(rows[1].direction, "both");
  assert.equal(rows[1].watch_kind, "requests");
  assert.equal(rows[1].watch_date, current.watch_date);
});

await t.test("a malformed successful POST is still an unknown write outcome", async () => {
  returnResponse({ ...legacy, id: 0 }, "POST");
  await assert.rejects(api.createRouteWatch(owner, legacy), (error) =>
    error instanceof TypeError && api.routeWatchOutcomeUnknown(error));
});

t.done();
