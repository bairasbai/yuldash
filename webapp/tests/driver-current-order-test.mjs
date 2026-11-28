import assert from "node:assert/strict";
import { bundleComponent } from "./shallow-harness.mjs";

const owner = { generation: "original", userId: 17 };
const path = "/instant/driver/current-order";
const detail = "/instant/orders/81";
const order = { id: 81, driver_id: 17, role: "driver", status: "accepted" };
const fixture = globalThis.__currentDriverFixture = { current: owner, calls: [], respond: null };

const read = await bundleComponent("src/api/driverCurrentOrder.ts", {
  exportName: "readDriverCurrentOrder",
  mocks: {
    "./client": `const f=globalThis.__currentDriverFixture;
      export class ApiError extends Error {
        constructor(status, message, code) { super(message); this.status = status; this.code = code; }
      }
      f.conflict = () => new ApiError(409, "fixture", "taxi_multiple_active_orders");
      f.otherError = () => new ApiError(409, "fixture", "different_code");
      export const isAccountSession = owner => owner === f.current;
      export const apiCommand = async (path, owner, opts) => {
        f.calls.push({path, owner, opts}); return f.respond(path);
      };`,
  },
});

async function observe(respond) {
  fixture.current = owner;
  fixture.calls = [];
  fixture.respond = respond;
  const signal = new AbortController().signal;
  const result = await read(owner, signal);
  for (const call of fixture.calls) {
    assert.equal(call.owner, owner);
    assert.deepEqual(call.opts, { method: "GET", oneShot: true, signal });
  }
  return result;
}

for (const status of ["accepted", "arriving", "onboard"]) {
  const active = { ...order, status };
  assert.deepEqual(await observe(p => p === path ? { order_id: 81 } : active), { state: "active", order: active });
  assert.deepEqual(fixture.calls.map(c => c.path), [path, detail]);
}
console.log("✓ a fresh detail must confirm the owned active order");

assert.deepEqual(await observe(() => ({ order_id: null })), { state: "none" });
assert.deepEqual(fixture.calls.map(c => c.path), [path]);
for (const input of [0, -1, 1.2, "81", 2_147_483_648, 9007199254740992, undefined]) {
  assert.deepEqual(await observe(() => ({ order_id: input })), { state: "unavailable" });
  assert.equal(fixture.calls.length, 1);
}
console.log("✓ only explicit null means absent; invalid IDs never trigger a detail read");

for (const bad of [{ ...order, status: "done" }, { ...order, role: "passenger" },
  { ...order, driver_id: 99 }, { ...order, id: 82 }]) {
  assert.deepEqual(await observe(p => p === path ? { order_id: 81 } : bad), { state: "unavailable" });
}
assert.deepEqual(await observe(() => { throw new Error("offline"); }), { state: "unavailable" });
console.log("✓ terminal, foreign, wrong-order and failed responses do not open a trip");

assert.deepEqual(await observe(p => {
  if (p === path) return { order_id: 81 };
  fixture.current = { generation: "new login", userId: owner.userId };
  return order;
}), { state: "session-changed" });
assert.deepEqual(await observe(() => {
  fixture.current = { generation: "different account", userId: 19 };
  return { order_id: 81 };
}), { state: "session-changed" });
assert.equal(fixture.calls.length, 1);
const aborted = new AbortController();
aborted.abort();
fixture.current = owner;
fixture.calls = [];
assert.deepEqual(await read(owner, aborted.signal), { state: "session-changed" });
assert.equal(fixture.calls.length, 0);
console.log("✓ sign-out/re-login and cancellation discard in-flight responses");

assert.deepEqual(await observe(() => { throw fixture.conflict(); }), { state: "conflict" });
assert.deepEqual(fixture.calls.map(c => c.path), [path]);
assert.deepEqual(await observe(() => { throw fixture.otherError(); }), { state: "unavailable" });
assert.deepEqual(await observe(p => {
  if (p === path) return { order_id: 81 };
  throw fixture.conflict();
}), { state: "unavailable" });
console.log("✓ only an explicit ambiguous assignment prevents choosing a trip");

delete globalThis.__currentDriverFixture;
