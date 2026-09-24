import assert from "node:assert/strict";
import { createIntroTimeline, introInitialStage, INTRO_LOGO_SETTLE_MS, INTRO_MEANING_MS } from "../src/utils/introTimeline.js";

function clock() {
  let now = 0;
  let next = 0;
  const timers = new Map();
  return {
    timers,
    set(fn, delay) { const id = ++next; timers.set(id, { at: now + delay, fn }); return id; },
    clear(id) { timers.delete(id); },
    advance(to) {
      while (true) {
        const due = [...timers].filter(([, task]) => task.at <= to).sort((a, b) => a[1].at - b[1].at)[0];
        if (!due) break;
        timers.delete(due[0]);
        now = due[1].at;
        due[1].fn();
      }
      now = to;
    },
  };
}

{
  const reduced = introInitialStage(true);
  assert.equal(reduced.brand, true);
  assert.equal(reduced.underline, true);
  assert.equal(reduced.sloganBa, true);
  assert.equal(reduced.meaningPhase, "before");
  const c = clock();
  let completions = 0;
  const timeline = createIntroTimeline(true, () => assert.fail("reduced motion must not stage animations"), () => completions++, c.set, c.clear);
  c.advance(999);
  assert.equal(completions, 0);
  c.advance(1000);
  timeline.skip();
  assert.equal(completions, 1);
  console.log("✓ reduced motion starts on the final frame and completes once after one second");
}

{
  const c = clock();
  let stage = introInitialStage(false);
  let completions = 0;
  const timeline = createIntroTimeline(false, (patch) => { stage = { ...stage, ...patch }; }, () => completions++, c.set, c.clear);
  assert.equal(INTRO_LOGO_SETTLE_MS, 172);
  assert.equal(INTRO_MEANING_MS, 332);
  c.advance(INTRO_MEANING_MS - 1);
  assert.equal(stage.meaningPhase, "before");
  c.advance(INTRO_MEANING_MS);
  assert.equal(stage.meaningPhase, "in");
  c.advance(1400);
  assert.equal(stage.skip, true);
  c.advance(INTRO_MEANING_MS + 1200);
  assert.equal(stage.meaningPhase, "out");
  c.advance(INTRO_MEANING_MS + 1620);
  assert.equal(stage.brand, true);
  c.advance(INTRO_MEANING_MS + 4300);
  assert.equal(stage.sloganBa, true);
  c.advance(INTRO_MEANING_MS + 6000);
  assert.equal(completions, 1);
  assert.equal(c.timers.size, 0);
  timeline.skip();
  assert.equal(completions, 1);
  console.log("✓ staged meaning, brand, language swap and one automatic completion");
}

{
  const c = clock();
  let completions = 0;
  const timeline = createIntroTimeline(false, () => {}, () => completions++, c.set, c.clear);
  timeline.skip();
  timeline.skip();
  c.advance(10000);
  assert.equal(completions, 1);
  assert.equal(c.timers.size, 0);
  console.log("✓ repeated tap cancels timers and transitions once");
}

{
  const c = clock();
  let patches = 0;
  let completions = 0;
  const timeline = createIntroTimeline(false, () => patches++, () => completions++, c.set, c.clear);
  timeline.dispose();
  c.advance(10000);
  timeline.skip();
  assert.equal(patches, 0);
  assert.equal(completions, 0);
  assert.equal(c.timers.size, 0);
  console.log("✓ unmount clears pending timers without late state updates or navigation");
}
