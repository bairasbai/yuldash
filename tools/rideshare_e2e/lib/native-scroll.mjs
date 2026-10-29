// Pure gesture geometry; never select an unrelated scrolling container by screen fraction.
export function scrollGesture(nodes, [width, height], direction, targetIndex = null, fraction = 0.35) {
  if (![width, height, fraction].every(Number.isFinite) || width <= 0 || height <= 0 || fraction <= 0 || !["up", "down"].includes(direction)) throw new Error("Invalid native scroll geometry");
  const candidates = new Map();
  nodes.forEach((node, index) => {
    const box = node.box;
    if (node.scrollable !== "true" || node.enabled === "false" || !Array.isArray(box) || box.length !== 4 || !box.every(Number.isFinite)) return;
    const clipped = [Math.max(0, box[0]), Math.max(height * 0.08, box[1]), Math.min(width, box[2]), Math.min(height * 0.96, box[3])];
    // Short horizontal chips are not the vertical content of these journey screens.
    if (clipped[2] - clipped[0] >= width * 0.35 && clipped[3] - clipped[1] >= height * 0.12) candidates.set(index, clipped);
  });
  let selected = null;
  const visited = new Set();
  for (let index = targetIndex; Number.isInteger(index) && index >= 0 && index < nodes.length;) {
    if (visited.has(index)) break;
    visited.add(index);
    if (candidates.has(index)) { selected = index; break; }
    index = nodes[index].parentIndex;
  }
  if (selected === null && targetIndex === null && candidates.size === 1) selected = candidates.keys().next().value;
  if (selected === null) throw new Error(`Native scroll needs one current content container or the target's own ancestor; candidates=${JSON.stringify([...candidates])}`);
  const box = candidates.get(selected), span = box[3] - box[1];
  const margin = Math.max(1, Math.round(span * 0.12)), distance = Math.min(height * fraction, span * 0.7);
  const x = Math.round((box[0] + box[2]) / 2);
  const start = direction === "up" ? Math.floor(box[3] - margin) : Math.ceil(box[1] + margin);
  const end = direction === "up" ? Math.ceil(Math.max(box[1] + margin, start - distance)) : Math.floor(Math.min(box[3] - margin, start + distance));
  if (start === end) throw new Error("Native scroll container has no gesture space");
  return { x, start, end, box, nodeIndex: selected };
}

/** Shared visibility check for positioning and the fresh, single tap. */
export function scrollTargetVisible(nodes, target, height, maxBottom = 0.88) {
  if (!target || !Array.isArray(target.box) || target.box.length !== 4 || !target.box.every(Number.isFinite)) return false;
  if (!Number.isFinite(height) || !Number.isFinite(maxBottom) || height <= 0 || nodes.indexOf(target) < 0) return false;
  if (target.box[1] <= height * 0.08 || target.box[3] >= height * maxBottom) return false;
  const visited = new Set();
  for (let index = target.parentIndex; index !== null;) {
    if (!Number.isInteger(index) || index < 0 || index >= nodes.length || visited.has(index)) return false;
    visited.add(index);
    const ancestor = nodes[index];
    if (ancestor.scrollable === "true") {
      const viewport = ancestor.box;
      if (!Array.isArray(viewport) || viewport.length !== 4 || !viewport.every(Number.isFinite) || viewport[2] <= viewport[0] || viewport[3] <= viewport[1]) return false;
      if (target.box[0] < viewport[0] || target.box[2] > viewport[2] || target.box[1] < viewport[1] || target.box[3] >= viewport[3]) return false;
    }
    index = ancestor.parentIndex;
  }
  return true;
}
