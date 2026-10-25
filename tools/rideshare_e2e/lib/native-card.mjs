// Preserve real accessibility ancestry; never combine labels from adjacent cards.
export function nativeCardContaining(nodes, anchor, accepts) {
  let index = nodes.indexOf(anchor);
  while (Number.isInteger(index) && index >= 0) {
    const parent = nodes[index];
    if (parent.scrollable === "true") return null;
    const inside = nodes.filter((_, i) => {
      for (let cursor = i; Number.isInteger(cursor) && cursor >= 0; cursor = nodes[cursor].parentIndex) if (cursor === index) return true;
      return false;
    });
    if (accepts(inside)) return { node: parent, nodes: inside };
    index = parent.parentIndex;
  }
  return null;
}
export const firstCardRoute = nodes => nodes.find(n => /^[^\n]+ → [^\n]+$/.test(n.text || "") && !/^(Участок|Посадка|Высадка):/.test(n.text || ""))?.text;
