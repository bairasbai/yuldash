import assert from "node:assert/strict";
import { cubicBezier, introMotionFrame, INTRO_EASE, MOTES, moteFrame, springDurationMs, springValue } from "../src/utils/introMotion.js";

const near = (actual, expected, tolerance = 0.000002) => assert.ok(Math.abs(actual - expected) <= tolerance, `${actual} differs from ${expected}`);

// Golden FloatSpringSpec samples from local Compose 1.11.3 classes.jar, executed by IntroSpringProbe.java.
assert.equal(springDurationMs(0.96, 1, 0.72), 172);
assert.equal(springDurationMs(0.92, 1, 0.82), 227);
near(springValue(0.96, 1, 40, 0.72), 0.964841902);
near(springValue(0.96, 1, 80, 0.72), 0.974480569);
near(springValue(0.96, 1, 160, 0.72), 0.991858184);
near(springValue(0.92, 1, 80, 0.82), 0.947427154);
near(springValue(0.92, 1, 160, 0.82), 0.979347944);
console.log("✓ Compose spring durations and sampled values agree with installed runtime");

assert.deepEqual(INTRO_EASE.outExpo, [0.16, 1, 0.3, 1]);
assert.deepEqual(INTRO_EASE.inOutSine, [0.37, 0, 0.63, 1]);
assert.deepEqual(INTRO_EASE.inCubic, [0.32, 0, 0.67, 0]);
for (const curve of Object.values(INTRO_EASE)) {
  near(cubicBezier(0, curve), 0);
  near(cubicBezier(1, curve), 1);
}
near(cubicBezier(0.5, INTRO_EASE.inOutSine), 0.5);
console.log("✓ Compose easing curves use verified cubic-bezier control points");

const opening = introMotionFrame(0);
near(opening.sceneAlpha, 0);
near(opening.sceneScale, 1.08);
near(opening.columnScale, 1);
near(opening.logoScale, 0.96);
const settled = introMotionFrame(4700);
near(settled.sceneAlpha, 1);
near(settled.sceneScale, 1);
near(settled.columnScale, 1.05);
near(introMotionFrame(4700, 500).columnScale, 1.05 * 1.06);
console.log("✓ scene and column follow shared drift and exit scale");

const first = moteFrame(MOTES[0], 0, 1);
near(first.x, 0.14);
near(first.y, 0.06 + 0.10 * 0.46);
near(first.alpha, 0.32);
near(moteFrame(MOTES[0], 0, 0).alpha, 0);
near(moteFrame(MOTES[0], 1, 1).y, 0.06 + 0.5 * 0.46);
console.log("✓ sky motes use Android phase, vertical wrap and scene alpha");
