import assert from "node:assert/strict";
import { splashTarget, scheduleSplashNavigation } from "../src/utils/splashTarget.js";

function makeClock() {
  const timers = new Map();
  let next = 0;
  return {
    timers,
    set(fn, ms) { const id = ++next; timers.set(id, { fn, ms }); return id; },
    clear(id) { timers.delete(id); },
    tick(ms) {
      for (const [id, task] of [...timers]) {
        if (task.ms <= ms) { timers.delete(id); task.fn(); }
      }
    },
  };
}

for (const [status, onboarded, expected, delay] of [
  ["guest", false, "/intro", 60],
  ["authed", false, "/intro", 60],
  ["guest", true, "/login", 140],
  ["authed", true, "/map", 140],
]) {
  assert.equal(splashTarget(status, onboarded), expected);
  const clock = makeClock();
  const history = ["/previous", "/splash"];
  const navigate = (path, options) => {
    assert.deepEqual(options, { replace: true });
    history[history.length - 1] = path;
  };
  scheduleSplashNavigation(status, onboarded, navigate, clock.set, clock.clear);
  assert.deepEqual([...clock.timers.values()].map((t) => t.ms), [delay]);
  clock.tick(delay - 1);
  assert.equal(history.at(-1), "/splash");
  clock.tick(delay);
  assert.deepEqual(history, ["/previous", expected]);
  clock.tick(delay);
  assert.equal(history.length, 2);
  console.log(`✓ ${status}, onboarded=${onboarded}: delayed replace → ${expected}`);
}

for (const status of ["loading", "unavailable"]) {
  for (const onboarded of [false, true]) {
    const clock = makeClock();
    let calls = 0;
    assert.equal(splashTarget(status, onboarded), null);
    scheduleSplashNavigation(status, onboarded, () => calls++, clock.set, clock.clear);
    clock.tick(1000);
    assert.equal(calls, 0);
    assert.equal(clock.timers.size, 0);
  }
  console.log(`✓ ${status}: remains at splash, never treats uncertain session as guest`);
}

{
  const clock = makeClock();
  const history = ["/splash"];
  const navigate = (path, { replace }) => { if (replace) history[0] = path; };
  const cancelGuest = scheduleSplashNavigation("guest", true, navigate, clock.set, clock.clear);
  cancelGuest(); // AuthProvider changed status before the guest timer fired.
  scheduleSplashNavigation("authed", true, navigate, clock.set, clock.clear);
  clock.tick(140);
  assert.deepEqual(history, ["/map"]);
  assert.equal(clock.timers.size, 0);
  console.log("✓ auth change cancels stale guest redirect and keeps one history entry");
}
