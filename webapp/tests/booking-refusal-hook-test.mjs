// Хук useBookingRefusal: экран своей брони узнаёт об отказах очереди — только своей брони, каждый один раз,
// включая тот, что пришёл, пока человек был на другом экране; подписка снимается при уходе.
import assert from "node:assert/strict";
import { bundleComponent, checker, mount } from "./shallow-harness.mjs";

const t = checker();
const h = globalThis.__refusalHook = { list: [], listeners: new Set() };
const outbox = `const h=()=>globalThis.__refusalHook;
  export const getOutboxRefusals=()=>h().list.slice();
  export const subscribeOutboxRefusals=fn=>{h().listeners.add(fn);return()=>h().listeners.delete(fn);};`;
const hook = await bundleComponent("src/utils/useBookingRefusal.ts", { mocks: { "./outbox": outbox }, exportName: "useBookingRefusal" });
const Probe = props => { hook(props.id, props.fn); return null; };
const refusal = (key, bookingId, kind = "driver_status") => ({ key, bookingId, kind, payload: "departed", session: "A", message: "Отказ " + key });
const push = (...items) => { h.list.push(...items); h.listeners.forEach(fn => fn()); };

await t.test("отказ уже лежал, пока человек был на другом экране — экран получает его при открытии, один раз", () => {
  h.list = [refusal("A:1", 42)]; h.listeners.clear();
  const got = []; const s = mount(Probe, { id: 42, fn: r => got.push(r.key) }); s.render();
  assert.deepEqual(got, ["A:1"]);
  push(); // повторное уведомление без нового отказа
  assert.deepEqual(got, ["A:1"], "тот же отказ второй раз не отдаём");
  s.unmount();
});
await t.test("отказы ЧУЖОЙ брони экран не получает; новый отказ своей — сразу, без повторов", () => {
  h.list = []; h.listeners.clear();
  const got = []; const s = mount(Probe, { id: 42, fn: r => got.push(r.key) }); s.render();
  push(refusal("A:2", 77)); assert.deepEqual(got, [], "бронь 77 — не наша");
  push(refusal("A:3", 42)); assert.deepEqual(got, ["A:3"]);
  push(refusal("A:4", 42, "trip_status")); assert.deepEqual(got, ["A:3", "A:4"], "вид отметки решает сам экран, хук отдаёт обе");
  s.unmount();
});
await t.test("подписка снимается при уходе с экрана; без брони (0 / нет) подписки нет", () => {
  h.list = []; h.listeners.clear();
  const got = []; const s = mount(Probe, { id: 42, fn: r => got.push(r.key) }); s.render();
  assert.equal(h.listeners.size, 1);
  s.unmount(); assert.equal(h.listeners.size, 0, "подписка снята");
  push(refusal("A:5", 42)); assert.deepEqual(got, []);
  for (const id of [0, undefined]) { const z = mount(Probe, { id, fn: () => got.push("zero") }); z.render(); assert.equal(h.listeners.size, 0, "id=" + id); z.unmount(); }
});
await t.test("обработчик берётся последний (экран перерисовался с новым fn)", () => {
  h.list = []; h.listeners.clear();
  const a = [], b = []; const s = mount(Probe, { id: 42, fn: r => a.push(r.key) }); s.render();
  s.props = { id: 42, fn: r => b.push(r.key) }; s.render();
  push(refusal("A:6", 42)); assert.deepEqual(a, []); assert.deepEqual(b, ["A:6"]);
  s.unmount();
});
t.done();
