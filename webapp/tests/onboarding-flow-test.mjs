import assert from "node:assert/strict";
import { createOnboardingPagerController, createPagerFrameGate, finishOnboarding, enableSimpleOnboarding, pagerPosition } from "../src/utils/onboardingFlow.js";

function harness() {
  const writes = [];
  const events = [];
  const history = ["/intro", "/onboarding"];
  return {
    writes, events, history,
    store: {
      setRole: (role) => writes.push(["role", role]),
      setSimpleMode: (enabled) => writes.push(["simple", enabled]),
      setOnboarded: () => writes.push(["completed", true]),
    },
    track: (event, props) => events.push([event, props]),
    navigate: (to, options) => {
      assert.deepEqual(options, { replace: true });
      history[history.length - 1] = to;
    },
  };
}

for (const role of ["passenger", "driver"]) {
  const h = harness();
  finishOnboarding(role, h.store, h.track, h.navigate);
  assert.deepEqual(h.writes, [["role", role], ["completed", true]]);
  assert.deepEqual(h.events, [["onboarding_complete", { role }]]);
  assert.deepEqual(h.history, ["/intro", "/login"]);
  console.log(`✓ normal finish/skip saves ${role}, logs completion, replaces with login`);
}

{
  const h = harness();
  enableSimpleOnboarding(h.store, h.track, h.navigate);
  assert.deepEqual(h.writes, [["simple", true], ["completed", true]]);
  assert.deepEqual(h.events, [["onboarding_simple_mode", undefined]]);
  assert.deepEqual(h.history, ["/intro", "/simple"]);
  console.log("✓ simple finish preserves role and does not log normal completion");
}

{
  const pages = [];
  const positions = [];
  const verticalScrollTop = [340, 0, 128, 0];
  const pager = createOnboardingPagerController(4, (next, old) => pages.push([old, next]), (position) => positions.push(position));
  assert.equal(pagerPosition(196.5, 393, 4), 0.5);
  assert.equal(pager.onScroll(0, 393), 0);
  assert.deepEqual(pages, []);
  assert.equal(verticalScrollTop[0], 340);
  assert.equal(pager.requestDelta(1), 1);
  assert.equal(pager.requestDelta(1), 2); // fast consecutive button presses target two panels ahead
  pager.onScroll(393, 393);
  pager.onScroll(786, 393);
  assert.deepEqual(pages, [[0, 1], [1, 2]]);
  assert.equal(pager.align(320), 640); // width changed: same logical panel
  assert.equal(verticalScrollTop[0], 340); // leaving the panel does not reset independent scroll
  pager.settle(786, 393);
  assert.equal(pager.requestDelta(-1), 1);
  pager.onScroll(393, 393);
  assert.equal(pager.requestDelta(-1), 0);
  pager.onScroll(0, 393);
  assert.equal(verticalScrollTop[0], 340);
  assert.deepEqual(pages, [[0, 1], [1, 2], [2, 1], [1, 0]]);
  assert.equal(positions.at(-1), 0);
  assert.equal(pager.requestDelta(-1), 0);
  assert.equal(pagerPosition(2000, 393, 4), 3);
  console.log("✓ pager observes snap position, queues rapid buttons, aligns resize and retains panel scroll");
}

{
  let id = 0;
  let runs = 0;
  const pending = new Map();
  const gate = createPagerFrameGate(
    (callback) => { pending.set(++id, callback); return id; },
    (frame) => { pending.delete(frame); },
    () => { runs++; }
  );
  gate.schedule();
  gate.schedule();
  assert.equal(pending.size, 1);
  gate.cancel(); // zoom effect cleanup cancels a pending scroll RAF
  assert.equal(pending.size, 0);
  gate.schedule(); // the next scroll must still be observed
  assert.equal(pending.size, 1);
  const callback = pending.values().next().value;
  pending.clear();
  callback();
  assert.equal(runs, 1);
  gate.schedule();
  assert.equal(pending.size, 1);
  console.log("✓ cancelled scroll frame is reschedulable after zoom lifecycle cleanup");
}
