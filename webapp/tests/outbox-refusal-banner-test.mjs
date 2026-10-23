// Плашка «Отметка не принята»: что видит водитель, когда «Выехал» из очереди сервер отклонил.
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { all, bundleComponent, button, checker, langMock, mount, text } from "./shallow-harness.mjs";

const t = checker();
const h = globalThis.__refusalBanner = { items: [], routes: [], dismissed: [], listeners: new Set(), generation: "A", reads: 0, win: new Map() };
// Окно: слушатели storage / focus, чтобы показать, как плашка узнаёт о смене входа в соседней вкладке.
globalThis.window = {
  addEventListener: (name, fn) => { if (!h.win.has(name)) h.win.set(name, new Set()); h.win.get(name).add(fn); },
  removeEventListener: (name, fn) => h.win.get(name)?.delete(fn),
};
const fire = (name, event = {}) => [...(h.win.get(name) ?? [])].forEach(fn => fn(event));
const mocks = lang => ({
  "/i18n/lang": langMock(lang),
  "/api/client": `export const getSessionGeneration=()=>{if(globalThis.__refusalBanner.throwGeneration)throw new Error("storage unavailable");return globalThis.__refusalBanner.generation;};`,
  "react-router-dom": `export const useNavigate=()=>(...a)=>globalThis.__refusalBanner.routes.push(a);`,
  "/utils/outbox": `const h=()=>globalThis.__refusalBanner;
    export const getOutboxRefusals=()=>{h().reads++;return h().items.filter(r=>r.session===h().generation);};
    export const subscribeOutboxRefusals=fn=>{h().listeners.add(fn);return()=>h().listeners.delete(fn);};
    export const dismissOutboxRefusal=key=>{h().dismissed.push(key);h().items=h().items.filter(r=>r.key!==key);h().listeners.forEach(f=>f());};`,
});
const refusal = (extra = {}) => ({ key: "A:1", session: "A", bookingId: 42, kind: "driver_status", payload: "departed", message: "Рано: «Я выехал» можно нажать за час до выезда.", ...extra });
const MARK = {
  "driver_status/departed": { ru: "«Выехал»", ba: "«Сыҡтым»" },
  "driver_status/arriving": { ru: "«Подъезжаю»", ba: "«Яҡынлашам»" },
  "driver_status/done": { ru: "«Завершить поездку»", ba: "«Сәфәрҙе тамамлау»" },
  "trip_status/arrived": { ru: "«Доехала»", ba: "«Барып еттем»" },
  "trip_status/done": { ru: "«Поездка завершена»", ba: "«Сәфәр тамамланды»" },
};

for (const lang of ["ru", "ba"]) {
  const Banner = await bundleComponent("src/components/OutboxRefusalBanner.tsx", { mocks: mocks(lang) });
  const Mark = await bundleComponent("src/components/OutboxRefusalBanner.tsx", { mocks: mocks(lang), exportName: "refusalMark" });
  const appText = (ru, ba) => (lang === "ba" ? ba : ru);

  await t.test(`${lang}: нет отказов — плашки нет`, () => {
    Object.assign(h, { items: [], routes: [], dismissed: [], generation: "A" });
    const s = mount(Banner); s.render(); assert.equal(s.tree, null); s.unmount();
  });

  await t.test(`${lang}: отказ показан словами сервера, с названием отметки, role=alert`, () => {
    Object.assign(h, { items: [refusal()], routes: [], dismissed: [], generation: "A" });
    const s = mount(Banner); s.render();
    assert.equal(s.tree.props.role, "alert");
    assert.ok(text(s.tree).includes("Рано: «Я выехал» можно нажать за час до выезда."), "слова сервера");
    assert.ok(text(s.tree).includes(appText("«Выехал» — не принято", "«Сыҡтым» — ҡабул ителмәне")), text(s.tree));
    assert.ok(button(s.tree, appText("К поездке", "Сәфәргә"))); assert.ok(button(s.tree, appText("Понятно", "Аңлашылды")));
    s.unmount();
  });

  await t.test(`${lang}: у каждой отметки своё название на кнопке; неизвестная — общее слово`, () => {
    for (const [id, names] of Object.entries(MARK)) {
      const [kind, payload] = id.split("/");
      assert.equal(Mark({ kind, payload }, appText), names[lang], id);
    }
    assert.equal(Mark({ kind: "driver_status", payload: "???" }, appText), appText("Отметка", "Билдәләү"));
  });

  await t.test(`${lang}: «Понятно» убирает отказ; «К поездке» убирает и ведёт на поездку; следующий отказ — по очереди`, async () => {
    Object.assign(h, { items: [refusal(), refusal({ key: "A:2", bookingId: 43, payload: "arriving", message: "Второй отказ" })], routes: [], dismissed: [], generation: "A" });
    const s = mount(Banner); await s.settle();
    assert.ok(text(s.tree).includes("Рано")); assert.ok(!text(s.tree).includes("Второй отказ"), "показываем по одному, первый старейший");
    button(s.tree, appText("Понятно", "Аңлашылды")).props.onClick(); await s.settle();
    assert.deepEqual(h.dismissed, ["A:1"]); assert.deepEqual(h.routes, []);
    assert.ok(text(s.tree).includes("Второй отказ"));
    button(s.tree, appText("К поездке", "Сәфәргә")).props.onClick(); await s.settle();
    assert.deepEqual(h.dismissed, ["A:1", "A:2"]); assert.deepEqual(h.routes, [["/trip/43"]]);
    assert.equal(s.tree, null, "все отказы прочитаны — плашка ушла");
    s.unmount();
  });

  // Вход сменили в соседней вкладке (мама вышла, зашёл сын): событий очереди тут нет, плашка не должна остаться чужой.
  await t.test(`${lang}: поколение входа сменилось без события очереди — плашки нет, «К поездке» не ведёт на чужую бронь`, async () => {
    Object.assign(h, { items: [refusal({ bookingId: 42 })], routes: [], dismissed: [], generation: "A" }); h.listeners.clear(); h.win.clear();
    const s = mount(Banner); await s.settle();
    assert.ok(text(s.tree).includes("Рано"), "у своего входа плашка есть");
    h.generation = "B"; // новый вход в другой вкладке: ни subscribeOutboxRefusals, ни setItems не сработали
    s.render();
    assert.equal(s.tree, null, "отказ чужого входа не показываем при отрисовке");
    assert.deepEqual(h.routes, []);
    s.unmount();
  });
  await t.test(`${lang}: запись поколения в localStorage другой вкладкой перечитывает список; чужие ключи и нашу вкладку не трогают`, async () => {
    Object.assign(h, { items: [refusal(), refusal({ key: "B:1", session: "B", bookingId: 77, message: "Отказ нового входа" })], routes: [], dismissed: [], generation: "A", reads: 0 }); h.listeners.clear(); h.win.clear();
    const s = mount(Banner); await s.settle();
    const base = h.reads;
    fire("storage", { key: "yuldash.token" }); assert.equal(h.reads, base, "посторонний ключ — не перечитываем");
    h.generation = "B"; fire("storage", { key: "yuldash.session", newValue: "B" }); await s.settle();
    assert.ok(h.reads > base, "запись поколения — перечитываем");
    assert.ok(text(s.tree).includes("Отказ нового входа") && !text(s.tree).includes("Рано"), "теперь видно только отказ своего (нового) входа");
    button(s.tree, appText("К поездке", "Сәфәргә")).props.onClick(); assert.deepEqual(h.routes, [["/trip/77"]]);
    fire("storage", { key: null }); // очистка хранилища целиком — тоже повод перечитать
    s.unmount(); assert.equal(h.win.get("storage")?.size ?? 0, 0, "слушатели сняты при уходе"); assert.equal(h.win.get("focus")?.size ?? 0, 0);
  });
  await t.test(`${lang}: хранилище недоступно (поколение не прочитать) — плашку не показываем`, async () => {
    Object.assign(h, { items: [refusal()], routes: [], dismissed: [], generation: "A" }); h.listeners.clear(); h.win.clear();
    const s = mount(Banner); await s.settle();
    assert.ok(text(s.tree).includes("Рано"));
    h.throwGeneration = true;
    try { s.render(); assert.equal(s.tree, null, "если поколение входа не прочитать — чужого не показываем и не падаем"); }
    finally { h.throwGeneration = false; s.unmount(); }
  });
  await t.test(`${lang}: отказ, пришедший пока плашка открыта, появляется сам; после ухода подписки нет`, async () => {
    Object.assign(h, { items: [], routes: [], dismissed: [], generation: "A" }); h.listeners.clear();
    const s = mount(Banner); await s.settle(); assert.equal(s.tree, null);
    h.items = [refusal()]; h.listeners.forEach(f => f()); await s.settle();
    assert.ok(text(s.tree).includes("Рано"));
    s.unmount(); assert.equal(h.listeners.size, 0, "подписка снята при уходе");
  });
}
await t.test("оболочка рисует плашку (иначе отказ запоминается, но человек его не увидит)", () => {
  const app = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
  assert.match(app, /import OutboxRefusalBanner from "\.\/components\/OutboxRefusalBanner"/);
  assert.match(app, /<UpdateBanner \/>[\s\S]*<OutboxRefusalBanner \/>[\s\S]*<main className="app-main">/);
});
t.done();
