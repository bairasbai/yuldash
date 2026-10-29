import test from "node:test";
import assert from "node:assert/strict";
import { scrollTargetVisible } from "../rideshare_e2e/lib/native-scroll.mjs";

const height = 2340;
const viewport = [54, 136, 1026, 1996];
const inside = [271, 1800, 636, 1899];
const fixture = (box, clip = viewport) => [
  { parentIndex: null, scrollable: "true", box: clip },
  { parentIndex: 0, scrollable: "false", box },
];
const nested = (outer, inner, target) => [
  { parentIndex: null, scrollable: "true", box: outer },
  { parentIndex: 0, scrollable: "true", box: inner },
  { parentIndex: 1, box: target },
];
const fixed = [
  { parentIndex: null, scrollable: "false", box: [0, 0, 1080, height] },
  { parentIndex: 0, scrollable: "true", box: [54, 352, 1026, 1135] },
  { parentIndex: 0, box: inside },
];
const cycle = fixture(inside);
cycle[0].parentIndex = 1;

// The first case uses the SAME diagnostic prepare-ru-dark XML geometry.
const cases = [
  ["rejects Settings clipped at its actual list bottom", fixture([271, 1909, 636, 1996]), false],
  ["rejects one-pixel text whose rounded center reaches the list bottom", fixture([271, 1995, 636, 1996]), false],
  ["accepts the same label fully inside its actual list", fixture(inside), true],
  ["rejects text above its own nested viewport", fixture([271, 400, 636, 499], [54, 600, 1026, 1996]), false],
  ["rejects text outside the horizontal bounds of its own viewport", fixture([40, 1000, 636, 1099]), false],
  ["checks the inner scrolling ancestor", nested([0, 136, 1080, 2100], [54, 700, 1026, 1300], [271, 1250, 636, 1350]), false],
  ["checks outer clipping even if the inner viewport would accept text", nested([0, 136, 1080, 1200], [54, 700, 1026, 1600], [271, 1250, 636, 1350]), false],
  ["accepts text fully inside both scrolling ancestors", nested([0, 136, 1080, 2100], [54, 700, 1026, 1600], [271, 1000, 636, 1099]), true],
  ["does not apply an unrelated sibling scroll to a fixed action", fixed, true],
  ["keeps the existing explicit 96 percent limit for fixed footer actions", [{ parentIndex: null, box: [200, 2150, 900, 2245] }], true, 0.96],
  ["keeps the existing default 88 percent limit", [{ parentIndex: null, box: [200, 2000, 900, 2100] }], false],
  ["keeps the existing top safety margin", [{ parentIndex: null, box: [200, 180, 900, 250] }], false],
  ["rejects cyclic ancestry instead of assuming visibility", cycle, false],
  ["rejects a missing parent instead of assuming visibility", [{ parentIndex: 99, box: inside }], false],
];
for (const [name, nodes, expected, limit = 0.88] of cases) {
  test(name, () => assert.equal(scrollTargetVisible(nodes, nodes.at(-1), height, limit), expected));
}
test("a missing target remains unavailable", () => {
  assert.equal(scrollTargetVisible([], null, height), false);
});
