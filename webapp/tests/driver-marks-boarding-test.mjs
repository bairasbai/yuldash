// ================================================================
//  Посадку отмечает ВОДИТЕЛЬ (решение Александра 17.10.2026). Зеркало Android RideshareBoarding.kt.
//
//  Было: пассажир сам жал «Я сел» (на сайте — «Села в машину») — можно было нажать из дома,
//  чтобы водитель уже не отметил «Не пришёл», а нажатое по ошибке было не вернуть.
//  Стало:
//   • водитель: «Сел» у подтверждённого пассажира → «Пассажир в машине» и «Отменить · 4:59»
//     в первые 5 минут; дальше — то, что передал кабинет (разбор);
//   • пассажир: «Водитель отметит посадку» и «Сообщить о проблеме» (разбор по брони с водителем).
//
//  Исполняем настоящие DriverBoarding и BoardingWait; сеть и язык подменены (shallow-harness.mjs).
// ================================================================
import assert from "node:assert/strict";
import { all, bundleComponent, button, checker, langMock, mount, ofType, text } from "./shallow-harness.mjs";

// Один класс ошибки на все сборки: одинаковый исходник импортируется из кеша, и класс из другой
// сборки не прошёл бы `instanceof` внутри компонента.
const ApiError = (globalThis.__dmbApiError = class ApiError extends Error {
  constructor(s, m) { super(m); this.status = s; }
});
const CLIENT = `export const ApiError=globalThis.__dmbApiError;`;
const BOOKINGS = `const h=()=>globalThis.__dmb;
export const markBoarded=async(id)=>{h().calls.push(["mark",id]);return h().reply("mark");};
export const undoBoarded=async(id)=>{h().calls.push(["undo",id]);return h().reply("undo");};`;

async function boarding(lang, props) {
  const Component = await bundleComponent("src/components/DriverBoarding.tsx", {
    mocks: { "/i18n/lang": langMock(lang), "/api/client": CLIENT, "/api/bookings": BOOKINGS },
  });
  const h = (globalThis.__dmb = {
    calls: [], changed: [], errors: [],
    reply: (what) => ({ ok: true, status: what === "mark" ? "onboard" : "confirmed", boarding_undo_sec: what === "mark" ? 300 : 0 }),
  });
  const view = mount(Component, {
    bookingId: 42,
    onChanged: (r) => { h.changed.push(r ?? null); },
    onError: (t) => { h.errors.push(t); },
    ...props,
  });
  view.render();
  await view.settle(3);
  return { view, h };
}

const t = checker();

for (const lang of ["ru", "ba"]) {
  const L = (ru, ba) => (lang === "ba" ? ba : ru);

  await t.test(`${lang}: подтверждённый пассажир — у водителя «Сел», отметка уходит на сервер`, async () => {
    const { view, h } = await boarding(lang, { status: "confirmed" });
    const sat = button(view.tree, L("Сел", "Ултырҙы"));
    assert.ok(sat, "нет кнопки «Сел»");
    await sat.props.onClick();
    await view.settle(3);
    assert.deepEqual(h.calls, [["mark", 42]]);
    assert.deepEqual(h.changed, [{ ok: true, status: "onboard", boarding_undo_sec: 300 }], "ответ отметки ушёл родителю");
    view.unmount();
  });

  await t.test(`${lang}: на экране поездки та же отметка — «Пассажир сел» в ряду статусов`, async () => {
    const { view } = await boarding(lang, { status: "confirmed", variant: "chip" });
    const chip = button(view.tree, L("Пассажир сел", "Пассажир ултырҙы"));
    assert.ok(chip && chip.props.className === "chip", "чип «Пассажир сел»");
    view.unmount();
  });

  await t.test(`${lang}: «в пути» в первые минуты — «Пассажир в машине» и «Отменить · 2:00»`, async () => {
    const { view, h } = await boarding(lang, { status: "onboard", undoSec: 120, children: { type: "div", props: { children: "РАЗБОР" } } });
    assert.ok(text(view.tree).includes(L("Пассажир в машине", "Пассажир машинала")));
    const undo = button(view.tree, L("Отменить · 2:00", "Кире алырға · 2:00"));
    assert.ok(undo, "нет «Отменить» с обратным отсчётом: " + text(view.tree));
    assert.ok(!text(view.tree).includes("РАЗБОР"), "пока можно отменить, разбор не нужен");
    await undo.props.onClick();
    await view.settle(3);
    assert.deepEqual(h.calls, [["undo", 42]]);
    assert.deepEqual(h.changed, [{ ok: true, status: "confirmed", boarding_undo_sec: 0 }]);
    view.unmount();
  });

  await t.test(`${lang}: окно отмены прошло — «Отменить» нет, вместо него разбор от кабинета`, async () => {
    const { view } = await boarding(lang, { status: "onboard", undoSec: 0, children: { type: "div", props: { children: "РАЗБОР" } } });
    assert.ok(text(view.tree).includes(L("Пассажир в машине", "Пассажир машинала")));
    assert.equal(button(view.tree, L("Отменить", "Кире алырға")), undefined);
    assert.ok(text(view.tree).includes("РАЗБОР"));
    view.unmount();
  });
}

await t.test("отказ сервера: его слова родителю и перечитать (409 — бронь ушла вперёд)", async () => {
  const { view, h } = await boarding("ru", { status: "onboard", undoSec: 30 });
  h.reply = () => { throw new ApiError(409, "Отменить посадку можно в первые 5 минут."); };
  await button(view.tree, "Отменить").props.onClick();
  await view.settle(3);
  assert.deepEqual(h.errors, ["Отменить посадку можно в первые 5 минут."]);
  assert.deepEqual(h.changed, [null], "после 409 родитель перечитывает правду");
  view.unmount();
});

await t.test("нет сети: понятная фраза на двух языках, без перечитывания", async () => {
  for (const [lang, expected] of [["ru", "Не получилось отметить. Проверь сеть."], ["ba", "Билдәләп булманы. Селтәрҙе тикшер."]]) {
    const { view, h } = await boarding(lang, { status: "confirmed" });
    h.reply = () => { throw new ApiError(0, ""); };
    await button(view.tree, lang === "ba" ? "Ултырҙы" : "Сел").props.onClick();
    await view.settle(3);
    assert.deepEqual(h.errors, [expected]);
    assert.deepEqual(h.changed, []);
    view.unmount();
  }
});

await t.test("обратный отсчёт: секунды с сервера → «м:сс», не уходит в минус", async () => {
  const undoClock = await bundleComponent("src/components/DriverBoarding.tsx", {
    mocks: { "/i18n/lang": langMock("ru"), "/api/client": CLIENT, "/api/bookings": BOOKINGS },
    exportName: "undoClock",
  });
  assert.equal(undoClock(300), "5:00");
  assert.equal(undoClock(299), "4:59");
  assert.equal(undoClock(61), "1:01");
  assert.equal(undoClock(0), "0:00");
  assert.equal(undoClock(-4), "0:00");
});

await t.test("завершённая или отменённая бронь — отметки нет вовсе", async () => {
  for (const status of ["done", "cancelled", "pending"]) {
    const { view } = await boarding("ru", { status });
    assert.equal(view.tree, null, status);
    view.unmount();
  }
});

// ---------------- Пассажир: «Водитель отметит посадку» ----------------
async function wait(lang, props) {
  const Component = await bundleComponent("src/components/BoardingWait.tsx", { mocks: { "/i18n/lang": langMock(lang) } });
  const view = mount(Component, { bookingId: 42, driverName: "Ринат", ...props });
  view.render();
  await view.settle(3);
  return view;
}

for (const lang of ["ru", "ba"]) {
  const L = (ru, ba) => (lang === "ba" ? ba : ru);
  await t.test(`${lang}: пассажир видит «Водитель отметит посадку» и «Сообщить о проблеме», «Я сел» нет`, async () => {
    const view = await wait(lang, { driverId: 7 });
    assert.ok(text(view.tree).includes(L("Водитель отметит посадку", "Йөрөтөүсе ултырыуҙы билдәләр")));
    assert.equal(button(view.tree, L("Я сел", "Ултырҙым")), undefined);
    assert.equal(button(view.tree, L("Села в машину", "Машинаға ултырҙым")), undefined);
    const report = button(view.tree, L("Сообщить о проблеме", "Проблема тураһында хәбәр итергә"));
    assert.ok(report);
    report.props.onClick();
    await view.settle(3);
    const card = ofType(view.tree, "FileIncidentCard")[0];
    assert.ok(card, "разбор не открылся");
    assert.equal(card.props.respondentId, 7);
    assert.equal(card.props.bookingId, 42);
    assert.equal(card.props.respondentName, "Ринат");
    card.props.onFiled();
    await view.settle(3);
    assert.ok(text(view.tree).includes(L("Разбор открыт", "Ҡарау асылды")));
    assert.equal(ofType(view.tree, "FileIncidentCard").length, 0);
  });
}

await t.test("старый сервер без peer_id — подпись есть, разбора нет (не с кем)", async () => {
  const view = await wait("ru", { driverId: undefined });
  assert.ok(text(view.tree).includes("Водитель отметит посадку"));
  assert.equal(button(view.tree, "Сообщить о проблеме"), undefined);
  assert.equal(all(view.tree).filter((n) => n.type === "FileIncidentCard").length, 0);
});

t.done();
