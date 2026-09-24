import { springDurationMs } from "./introMotion.js";

// Compose's analytical spring duration is 172 ms for 0.96→1; the actual coroutine
// resumes on a display frame, so browser and Android can differ by one frame.
export const INTRO_LOGO_SETTLE_MS = springDurationMs(0.96, 1, 0.72);
export const INTRO_MEANING_MS = INTRO_LOGO_SETTLE_MS + 160;

export function introInitialStage(reduceMotion) {
  return {
    meaningPhase: "before",
    brand: reduceMotion,
    sheen: false,
    underline: reduceMotion,
    slogan: reduceMotion,
    sloganBa: reduceMotion,
    exiting: false,
    skip: false,
  };
}

/** Owns all intro timers and guarantees one completion, including repeated skips. */
export function createIntroTimeline(reduceMotion, onStage, onComplete, setTimer = setTimeout, clearTimer = clearTimeout) {
  const timers = [];
  let active = true;
  let done = false;
  const at = (ms, patch) => timers.push(setTimer(() => {
    if (active && !done) onStage(patch);
  }, ms));
  const finish = () => {
    if (!active || done) return;
    done = true;
    timers.forEach(clearTimer);
    onComplete();
  };

  if (reduceMotion) {
    timers.push(setTimer(finish, 1000));
  } else {
    at(1400, { skip: true });
    let t = INTRO_MEANING_MS;
    at(t, { meaningPhase: "in" });
    t += 1200; at(t, { meaningPhase: "out" });
    t += 420; at(t, { brand: true });
    at(t + 360, { sheen: true });
    t += 500; at(t, { underline: true });
    t += 780; at(t, { slogan: true });
    t += 1400; at(t, { sloganBa: true });
    t += 1200; at(t, { exiting: true });
    t += 500; timers.push(setTimer(finish, t));
  }

  return {
    skip: finish,
    dispose() {
      active = false;
      timers.forEach(clearTimer);
    },
  };
}
