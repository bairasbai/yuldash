import assert from "node:assert/strict";
import { build } from "esbuild";
import { IDBFactory } from "fake-indexeddb";
import { fileURLToPath } from "node:url";

const output = await build({
  entryPoints: [fileURLToPath(new URL("../src/theme.ts", import.meta.url))],
  bundle: true, write: false, platform: "node", format: "esm",
  plugins: [{
    name: "react-hooks-boundary",
    setup(build) {
      build.onResolve({ filter: /^react$/ }, () => ({ path: "react", namespace: "stub" }));
      build.onLoad({ filter: /.*/, namespace: "stub" }, () => ({
        contents: "export const useEffect=()=>{},useState=initial=>[initial(),()=>{}];"
      }));
    },
  }],
});
const url = "data:text/javascript;base64," + Buffer.from(output.outputFiles[0].text).toString("base64");
let serial = 0;
const fresh = () => import(url + "#" + serial++);
const deferred = () => {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { resolve, promise };
};

function setup(legacy) {
  const values = new Map();
  if (legacy !== undefined) values.set("yuldash.theme", legacy);
  globalThis.localStorage = {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => values.set(key, value),
    removeItem: key => values.delete(key),
  };
  globalThis.document = { documentElement: { dataset: {} } };
  const factory = new IDBFactory();
  const fixture = { values, durability: [], completeGate: null, completionArrived: null, abortNext: false };
  globalThis.indexedDB = {
    open(...args) {
      const request = factory.open(...args);
      request.addEventListener("success", () => {
        const db = request.result;
        const start = db.transaction.bind(db);
        db.transaction = (...args) => {
          const tx = start(...args);
          fixture.durability.push(args[2]?.durability);
          if (fixture.completeGate) {
            const gate = fixture.completeGate;
            const arrived = fixture.completionArrived;
            fixture.completeGate = null;
            let handler;
            Object.defineProperty(tx, "oncomplete", {
              get: () => () => { arrived.resolve(); void gate.promise.then(() => handler()); },
              set: next => { handler = next; },
            });
          }
          if (fixture.abortNext) {
            fixture.abortNext = false;
            queueMicrotask(() => tx.abort());
          }
          return tx;
        };
      });
      return request;
    },
  };
  return fixture;
}

const mode = () => document.documentElement.dataset.theme;
let passed = 0;
async function test(name, run) {
  await run();
  passed++;
  console.log("PASS " + name);
}

await test("valid legacy preference migrates once and a new module reads it", async () => {
  const fixture = setup("dark");
  const first = await fresh();
  await first.restoreTheme();
  assert.equal(first.getTheme(), "dark");
  assert.equal(mode(), "dark");
  assert(!fixture.values.has("yuldash.theme"));
  fixture.values.set("yuldash.theme", "light");
  const next = await fresh();
  await next.restoreTheme();
  assert.equal(next.getTheme(), "dark");
  assert(!fixture.values.has("yuldash.theme"));
  assert(fixture.durability.every(value => value === "strict"));
});

await test("a choice becomes visible only after the strict complete event", async () => {
  const fixture = setup("light");
  const app = await fresh();
  await app.restoreTheme();
  const gate = fixture.completeGate = deferred();
  const arrived = fixture.completionArrived = deferred();
  const write = app.setTheme("dark");
  await arrived.promise;
  assert.equal(mode(), "light");
  assert.equal(app.getTheme(), "light");
  gate.resolve();
  await write;
  assert.equal(mode(), "dark");
  const next = await fresh();
  await next.restoreTheme();
  assert.equal(next.getTheme(), "dark");
});

await test("an aborted write leaves the previous choice visible and durable", async () => {
  const fixture = setup("light");
  const app = await fresh();
  await app.restoreTheme();
  fixture.abortNext = true;
  await assert.rejects(app.setTheme("dark"));
  assert.equal(mode(), "light");
  const next = await fresh();
  await next.restoreTheme();
  assert.equal(next.getTheme(), "light");
  await app.setTheme("system");
  assert.equal(mode(), undefined);
});

await test("rapid choices commit in invocation order", async () => {
  setup();
  const app = await fresh();
  await app.restoreTheme();
  await Promise.all([app.setTheme("dark"), app.setTheme("light"), app.setTheme("system")]);
  const next = await fresh();
  await next.restoreTheme();
  assert.equal(next.getTheme(), "system");
  assert.equal(mode(), undefined);
});

await test("a blocked startup keeps the legacy preference and a failed save keeps the current theme", async () => {
  const fixture = setup("dark");
  globalThis.indexedDB = {
    open() {
      const request = {};
      queueMicrotask(() => request.onblocked());
      return request;
    },
  };
  const app = await fresh();
  await app.restoreTheme();
  assert.equal(mode(), "dark");
  assert.equal(fixture.values.get("yuldash.theme"), "dark");
  await assert.rejects(app.setTheme("light"));
  assert.equal(mode(), "dark");
});

await test("a browser without IndexedDB keeps the legacy preference path", async () => {
  const fixture = setup("light");
  delete globalThis.indexedDB;
  const app = await fresh();
  await app.restoreTheme();
  await app.setTheme("dark");
  assert.equal(mode(), "dark");
  assert.equal(fixture.values.get("yuldash.theme"), "dark");
});
await test("an empty startup does not prevent later migration of a readable legacy preference", async () => {
  const fixture = setup();
  const initial = await fresh();
  await initial.restoreTheme();
  assert.equal(initial.getTheme(), "system");
  fixture.values.set("yuldash.theme", "dark");
  const next = await fresh();
  await next.restoreTheme();
  assert.equal(next.getTheme(), "dark");
  assert(!fixture.values.has("yuldash.theme"));
});
console.log(passed + " theme durability checks passed");
