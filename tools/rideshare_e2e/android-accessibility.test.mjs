import assert from "node:assert/strict";
import { test } from "node:test";
import { actionNode, checkedNode, find, parse, stopOption } from "./lib/android.mjs";

test("a checked stop remains selected when its row is no longer clickable", () => {
  const nodes = parse(`
    <node bounds="[0,0][120,180]">
      <node checkable="true" checked="true" clickable="false" enabled="true" bounds="[0,0][120,60]">
        <node text="Первая остановка" clickable="false" enabled="true" bounds="[20,5][110,55]" />
      </node>
      <node checkable="true" checked="false" clickable="true" enabled="true" bounds="[0,60][120,120]">
        <node text="Вторая остановка" clickable="false" enabled="true" bounds="[20,65][110,115]" />
      </node>
    </node>`);
  const first = find(nodes, "Первая остановка");
  const second = find(nodes, "Вторая остановка");
  assert.equal(checkedNode(first), nodes[1]);
  assert.equal(actionNode(first), null);
  assert.equal(checkedNode(second), null);
  assert.equal(actionNode(second), nodes[3]);
  assert.equal(Object.keys(first).includes("parent"), false);
});

test("a visible enabled label cannot stand in for a disabled booking button", () => {
  const nodes = parse(`
    <node bounds="[0,0][120,180]">
      <node clickable="true" enabled="false" bounds="[0,80][120,140]">
        <node text="Повторить тот же запрос" clickable="false" enabled="true" bounds="[20,90][100,130]" />
      </node>
    </node>`);
  const caption = find(nodes, "Повторить тот же запрос");
  assert.equal(caption.enabled, "true");
  assert.equal(actionNode(caption), null);
});

test("a matching stop selected in the other section cannot verify this section", () => {
  const nodes = parse(`
    <node bounds="[0,0][120,180]">
      <node bounds="[0,0][120,90]">
        <node text="Где садишься?" bounds="[0,0][120,20]" />
        <node checkable="true" checked="false" clickable="true" enabled="true" bounds="[0,20][120,90]">
          <node text="Остановка" bounds="[20,25][110,85]" />
        </node>
      </node>
      <node bounds="[0,90][120,180]">
        <node text="Где выходишь?" bounds="[0,90][120,110]" />
        <node checkable="true" checked="true" clickable="false" enabled="true" bounds="[0,110][120,180]">
          <node text="Остановка" bounds="[20,115][110,175]" />
        </node>
      </node>
    </node>`);
  const pickup = stopOption(nodes, "Где садишься?", ["Остановка"]);
  assert.equal(pickup.checked, false);
  assert.deepEqual(pickup.action.box, [0, 20, 120, 90]);
  assert.equal(stopOption(nodes, "Где выходишь?", ["Остановка"]).checked, true);
});
