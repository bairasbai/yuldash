
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { IDBFactory } from "fake-indexeddb";
import { bundleComponent, checker, langMock, mount } from "./shallow-harness.mjs";

const t = checker();
const compiled = await build({
  stdin: {
    contents: "export {initializeSessionStorage,setSession} from './src/api/client';export * from './src/api/driverStages';export * from './src/utils/bookingCommandStore';",
    resolveDir: fileURLToPath(new URL("../", import.meta.url)),
    loader: "ts",
  },
  bundle: true,
  write: false,
  format: "esm",
  platform: "node",
  define: { "import.meta.env": JSON.stringify({ VITE_API_BASE: "https://driver-stage.test", DEV: false }) },
});
let imports = 0;
async function start() {
  const source = compiled.outputFiles[0].text + "\n// " + imports++;
  const api = await import("data:text/javascript;base64," + Buffer.from(source).toString("base64"));
  await api.initializeSessionStorage();
  return api;
}
Object.defineProperty(globalThis, "crypto", { value: webcrypto, configurable: true });
const driver = { id: 7, created_at: "2026-01-01T10:00:00Z" };
const token = id => "header." + Buffer.from(JSON.stringify({ sub: String(id) })).toString("base64url") + ".sig";
const signal = () => new AbortController().signal;
const reply = (value, status = 200) => new Response(JSON.stringify(value), { status });
const unseen = { state: "unseen", scope: null, ref_kind: null, ref_id: null, current: null };
const applied = { state: "applied", scope: "booking.driver_status", ref_kind: "booking", ref_id: 21, current: { id: 21 } };
const role = status => ({ role: "driver", status, driver_phase: "", boarding_undo_sec: status === "onboard" ? 258 : 0 });

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
  const api = await start();
  return { api, owner: api.bookingCommandOwner(driver, "session-a") };
}

for (const [stage, changedStatus] of [["boarded", "onboard"], ["done", "done"]]) {
  await t.test(stage + ": lost request before dispatch keeps the same key and body through restart", async () => {
    const { api, owner } = await fresh();
    const sent = [];
    globalThis.fetch = async (url, init) => {
      const path = new URL(url).pathname;
      if (path.endsWith("/role")) return reply(role(changedStatus));
      if (path.startsWith("/command-intents/")) return reply(unseen);
      assert.equal(path, "/bookings/21/commands/v1/driver-status");
      const [saved] = await api.readDriverStageCommands(owner);
      assert.equal(saved.state, "unknown");
      assert.equal(saved.key, init.headers["Idempotency-Key"]);
      sent.push([saved.key, init.body]);
      if (sent.length === 1) throw TypeError("no response");
      return reply({ ok: true, id: 21, status: changedStatus });
    };
    await assert.rejects(api.advanceDriverStage(owner, 21, stage, signal()), { kind: "unknown" });
    const restarted = await start();
    const [saved] = await restarted.readDriverStageCommands(owner);
    const other = stage === "boarded" ? "done" : "boarded";
    assert.equal((await restarted.advanceDriverStage(owner, 21, other, signal())).state, "unseen");
    assert.equal(sent.length, 1);
    const result = await restarted.repeatDriverStage(owner, saved, signal());
    assert.deepEqual(result, { state: "applied", trip: role(changedStatus) });
    assert.deepEqual(sent, [[saved.key, JSON.stringify({ status: stage })], [saved.key, JSON.stringify({ status: stage })]]);
    assert.equal((await restarted.readDriverStageCommands(owner))[0].state, "applied");
  });
}

await t.test("lost response after save reconciles with GET, and consented undo allows a new boarding key", async () => {
  const { api, owner } = await fresh();
  const keys = [];
  let status = "onboard";
  globalThis.fetch = async (url, init) => {
    const path = new URL(url).pathname;
    if (path.endsWith("/role")) return reply(role(status));
    if (path.startsWith("/command-intents/")) return reply(applied);
    keys.push(init.headers["Idempotency-Key"]);
    if (keys.length === 1) throw TypeError("saved but answer lost");
    status = "onboard";
    return reply({ ok: true, id: 21 });
  };
  await assert.rejects(api.advanceDriverStage(owner, 21, "boarded", signal()), { kind: "unknown" });
  const restarted = await start();
  const [saved] = await restarted.readDriverStageCommands(owner);
  assert.deepEqual(await restarted.checkDriverStage(owner, saved, false, signal()),
    { state: "applied", trip: role("onboard") });
  assert.equal(keys.length, 1);
  status = "confirmed";
  assert.equal((await restarted.advanceDriverStage(owner, 21, "boarded", signal())).trip.status, "onboard");
  assert.notEqual(keys[0], keys[1]);
});

await t.test("an expired command can be checked or closed; its body never gets sent again", async () => {
  const { api, owner } = await fresh();
  const realNow = Date.now;
  try {
    Date.now = () => realNow() - 25 * 60 * 60_000;
    globalThis.fetch = async () => { throw TypeError("lost"); };
    await assert.rejects(api.advanceDriverStage(owner, 21, "done", signal()), { kind: "unknown" });
  } finally {
    Date.now = realNow;
  }
  const [saved] = await api.readDriverStageCommands(owner);
  globalThis.fetch = async (url, init) => {
    const path = new URL(url).pathname;
    assert.ok(path.startsWith("/command-intents/"));
    if (init.method === "GET") return reply(unseen);
    assert.ok(path.endsWith("/close"));
    assert.deepEqual(JSON.parse(init.body), { scope: "booking.driver_status", target_id: 21, payload_hash: saved.hash });
    return reply({ state: "closed_no_effect", scope: "booking.driver_status", ref_kind: null, ref_id: null, current: null });
  };
  await assert.rejects(api.repeatDriverStage(owner, saved, signal()), { kind: "expired" });
  assert.equal((await api.checkDriverStage(owner, saved, false, signal())).state, "unseen");
  assert.equal((await api.checkDriverStage(owner, saved, true, signal())).state, "closed_no_effect");
  assert.equal((await api.readDriverStageCommands(owner))[0].state, "closed_no_effect");
});

await t.test("saved command stays recoverable when the next role read fails", async () => {
  const { api, owner } = await fresh();
  let writes = 0;
  globalThis.fetch = async (url, init) => {
    const path = new URL(url).pathname;
    if (path.endsWith("/role")) throw TypeError("role unavailable");
    if (path.startsWith("/command-intents/")) return reply(applied);
    writes++;
    assert.equal(init.method, "POST");
    return reply({ ok: true, id: 21 });
  };
  await assert.rejects(api.advanceDriverStage(owner, 21, "done", signal()), { kind: "refresh" });
  const restarted = await start();
  const [saved] = await restarted.readDriverStageCommands(owner);
  assert.equal(saved.state, "unknown");
  await assert.rejects(restarted.checkDriverStage(owner, saved, false, signal()), { kind: "refresh" });
  globalThis.fetch = async (url, init) => {
    assert.equal(init.method, "GET");
    return new URL(url).pathname.endsWith("/role") ? reply(role("done")) : reply(applied);
  };
  assert.equal((await restarted.checkDriverStage(owner, saved, false, signal())).trip.status, "done");
  assert.equal(writes, 1);
});

await t.test("session change during the last storage read does not deliver the old result to the caller", async () => {
  const h = globalThis.__stageSession = { generation: "A", reads: 0 };
  const pending = new Promise(resolve => { h.release = resolve; });
  h.read = () => ++h.reads === 1 ? Promise.resolve([]) : pending;
  const useStages = await bundleComponent("src/utils/useDriverStages.ts", {
    exportName: "useDriverStages",
    mocks: {
      "/api/client": 'export const getSessionGeneration=()=>globalThis.__stageSession.generation;',
      "/auth/AuthProvider": 'export const useAuth=()=>({user:{id:7,created_at:"2026-01-01"}});',
      "/i18n/lang": langMock(),
      "/bookingCommandStore": [
        'export const bookingCommandOwner=(user,generation)=>({userId:user.id,generation,namespace:"driver"});',
        'export const isCurrentBookingOwner=owner=>owner.generation===globalThis.__stageSession.generation;',
        'export const readDriverStageCommands=()=>globalThis.__stageSession.read();',
      ].join(" "),
      "/api/driverStages": [
        'export class DriverStageProblem extends Error {}',
        'export const advanceDriverStage=async()=>({state:"applied",trip:{role:"driver",status:"done"}});',
        'export const checkDriverStage=()=>null,repeatDriverStage=()=>null;',
      ].join(" "),
    },
  });
  let stage;
  const screen = mount(() => { stage = useStages(21); return null; });
  try {
    await screen.settle();
    assert.equal(stage.ready, true);
    const inFlight = stage.advance(21, "done");
    await screen.settle();
    assert.equal(h.reads, 2);
    h.generation = "B";
    screen.render();
    h.release([]);
    assert.equal(await inFlight, undefined);
    assert.deepEqual(stage.pending, []);
    assert.equal(stage.notice, null);
  } finally {
    screen.unmount();
    delete globalThis.__stageSession;
  }
});

t.done();
