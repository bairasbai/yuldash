import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { checker } from "./shallow-harness.mjs";

const checks = checker();
const source = readFileSync(new URL("../src/screens/InstantDriverTripScreen.tsx", import.meta.url), "utf8");
const effect = source.split("// ---------------- Presence-heartbeat пока на линии ----------------")[1]
  ?.split("// ---------------- Опрос оффера")[0];
assert.ok(effect?.includes("useEffect("));

function fixture() {
  const stops = [];
  let failures = 0;
  let online = true;
  let tick;
  let timersCleared = 0;
  let next = { ok: true, shift_seconds_online: 0, shift_remaining_sec: 60 };
  let workday = { seconds_online: 0, remaining_sec: 60 };
  class ApiError extends Error {
    constructor(status) { super(); this.status = status; }
  }
  new Function("online", "useEffect", "posRef", "sendPresence", "setPresenceFails",
    "setWorkday", "setDriver", "ApiError", "window", "PRESENCE_MS", effect)(
    online, (run) => stops.push(run()), { current: { lat: 52, lng: 58 } },
    () => next instanceof Error ? Promise.reject(next) : Promise.resolve(next),
    (value) => { failures = typeof value === "function" ? value(failures) : value; },
    (update) => { workday = update(workday); },
    (update) => { online = update({ online }).online; },
    ApiError, { setInterval: (cb) => { tick = cb; return 1; }, clearInterval: () => { timersCleared++; } }, 15_000,
  );
  async function send(ok, seconds = 0) {
    next = { ok, shift_seconds_online: seconds, shift_remaining_sec: 60 - seconds };
    tick();
    await Promise.resolve();
  }
  async function reject(status) {
    next = new ApiError(status);
    tick();
    await Promise.resolve();
    await Promise.resolve();
  }
  return { send, reject, misses: () => failures, online: () => online, workday: () => workday,
    stop: () => stops[0](), timersCleared: () => timersCleared };
}

await checks.test("HTTP 200 с ok:false накапливает пропуски; ok:true восстанавливает линию", async () => {
  const f = fixture();
  await Promise.resolve();
  await f.send(false, 10);
  assert.equal(f.misses(), 1);
  assert.equal(f.online(), true);
  await f.send(false, 20);
  assert.equal(f.misses(), 2);
  assert.deepEqual(f.workday(), { seconds_online: 20, remaining_sec: 40 });
  await f.send(true, 30);
  assert.equal(f.misses(), 0);
  assert.equal(f.online(), true);
  f.stop();
  assert.equal(f.timersCleared(), 1);
  await f.send(false);
  assert.equal(f.misses(), 0);
});

await checks.test("Серверный 409 снимает с линии, а 503 остаётся временным сбоем", async () => {
  const f = fixture();
  await Promise.resolve();
  await f.reject(503);
  assert.equal(f.misses(), 1);
  assert.equal(f.online(), true);
  await f.reject(409);
  assert.equal(f.misses(), 0);
  assert.equal(f.online(), false);
  f.stop();
});
checks.done();
