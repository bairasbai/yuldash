// /root-owned QA proposal. Only an action's own, geometrically bound label can name it.
export function offlineControlGroups(nodes, allowed) {
  const native = n => /(?:^|\.)(?:Image)?Button$/.test(n.class || "");
  const action = n => n.clickable === "true" || native(n);
  const positive = box => Array.isArray(box) && box.length === 4 && box.every(Number.isFinite) && box[2] > box[0] && box[3] > box[1];
  const inside = (inner, outer) => positive(inner) && positive(outer) && inner[0] >= outer[0] && inner[1] >= outer[1] && inner[2] <= outer[2] && inner[3] <= outer[3];
  const nearestClickable = index => {
    const visited = new Set();
    for (let current = index; Number.isInteger(current) && current >= 0 && current < nodes.length;) {
      if (visited.has(current)) return null;
      visited.add(current);
      if (nodes[current].clickable === "true") return current;
      current = nodes[current].parentIndex;
    }
    return null;
  };
  const ownSubtree = root => {
    const indices = [];
    for (let index = root; index < nodes.length; index++) {
      let current = index;
      const visited = new Set();
      while (Number.isInteger(current) && current >= 0 && current < nodes.length && current !== root) {
        if (visited.has(current) || nodes[current].clickable === "true") { current = null; break; }
        visited.add(current);
        current = nodes[current].parentIndex;
      }
      if (current === root) indices.push(index);
    }
    return indices;
  };
  const controls = [];
  for (let index = 0; index < nodes.length; index++) {
    if (!action(nodes[index])) continue; // Include disabled controls as well.
    const root = nearestClickable(index) ?? index;
    const subtree = ownSubtree(root);
    const markers = subtree.filter(i => native(nodes[i]));
    const labels = subtree.flatMap(i => [nodes[i].text, nodes[i]["content-desc"]]
      .filter(v => typeof v === "string" && v.trim()).map(v => ({ index: i, value: v.trim() })));
    const names = [...new Set(labels.map(label => label.value))];
    // A Compose Button marker may share the wrapper with its label, but it must be unique.
    const identityBox = markers.length === 1 ? nodes[markers[0]].box : nodes[root].box;
    const geometry = positive(nodes[root].box) && markers.length <= 1 &&
      (markers.length === 0 || inside(identityBox, nodes[root].box)) &&
      labels.length > 0 && labels.every(label => inside(nodes[label.index].box, identityBox));
    const permitted = geometry && names.length === 1 && allowed.some(expression => {
      expression.lastIndex = 0;
      return expression.test(names[0]);
    });
    controls.push({ index, root, labels: names, marker_count: markers.length, geometry, permitted });
  }
  return { permitted: controls.every(control => control.permitted), controls };
}
