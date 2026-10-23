// «Выехал» без сети: экран пишет «Нет сети — отправим позже». Сервер потом отказал («Рано…») —
// ложное обещание должно смениться словами отказа (а сверху висит плашка «— не принято»).
import assert from "node:assert/strict";
import { button, checker, text } from "./shallow-harness.mjs";
import { openActiveTrip } from "./active-trip-fixture.mjs";

const t = checker();
for (const lang of ["ru", "ba"]) {
  const L = (ru, ba) => (lang === "ba" ? ba : ru);
  const queued = L("Нет сети — отправим позже", "Селтәр юҡ — һуңыраҡ ебәрербеҙ");
  const departed = L("Выехал", "Сыҡтым");
  await t.test(`${lang}: отказ очереди заменяет «отправим позже» на экране водителя`, async () => {
    const { h, at } = await openActiveTrip({ lang, role: "driver", status: "confirmed" });
    try {
      at.offline = true;
      await button(h.tree, departed).props.onClick(); await h.settle();
      assert.ok(text(h.tree).includes(queued), "без связи отметка ждёт в очереди — так и пишем");
      assert.equal(at.refusal.id, 42, "экран слушает отказы своей брони");
      const refusal = L("Рано: «Я выехал» можно нажать за час до выезда.", "Иртә: «Сыҡтым»ға сығыуға бер сәғәт ҡалғас ҡына баҫып була.");
      at.refusal.fn({ kind: "driver_status", payload: "departed", bookingId: 42, key: "A:1", message: refusal }); await h.settle();
      assert.ok(text(h.tree).includes(refusal), "слова сервера на экране");
      assert.ok(!text(h.tree).includes(queued), "«отправим позже» ушло — отметка не отправится");
    } finally { h.unmount(); }
  });
  await t.test(`${lang}: отказ пассажирской отметки (trip_status) на карточке водителя ничего не меняет`, async () => {
    const { h, at } = await openActiveTrip({ lang, role: "driver", status: "confirmed" });
    try {
      at.offline = true;
      await button(h.tree, departed).props.onClick(); await h.settle();
      at.refusal.fn({ kind: "trip_status", payload: "arrived", bookingId: 42, key: "A:2", message: "Чужая отметка" }); await h.settle();
      assert.ok(text(h.tree).includes(queued)); assert.ok(!text(h.tree).includes("Чужая отметка"));
    } finally { h.unmount(); }
  });
}
t.done();
