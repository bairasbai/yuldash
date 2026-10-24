// ================================================================
//  Пометка брони при отказе водителя — «Отклонена», а не «Отменена» (приёмка PR210; Android: BookingCancelledCard).
//
//  Было: на экране брони пассажира под текстом «Водитель отклонил бронь» стояла пометка «Отменена» —
//  будто сорвалось что-то общее или пассажир отменил сам. Стало: пометка говорит то же, что текст.
//
//  Исполняем настоящий StatusPill (shallow-harness.mjs); экран брони сверяем по исходнику.
// ================================================================
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { ROOT, bundleComponent, checker, langMock, mount, text } from "./shallow-harness.mjs";

async function pill(lang, props) {
  const StatusPill = await bundleComponent("src/components/StatusPill.tsx", {
    mocks: { "/i18n/lang": langMock(lang) },
    exportName: "StatusPill",
  });
  const h = mount(StatusPill, props);
  h.render();
  await h.settle();
  return text(h.tree);
}

const t = checker();

for (const lang of ["ru", "ba"]) {
  const L = (ru, ba) => (lang === "ba" ? ba : ru);
  await t.test(`${lang}: отказ водителя — «Отклонена»`, async () => {
    assert.equal(await pill(lang, { status: "cancelled", declined: true }), L("Отклонена", "Кире ҡағылды"));
  });
  await t.test(`${lang}: обычная отмена — по-прежнему «Отменена»`, async () => {
    assert.equal(await pill(lang, { status: "cancelled" }), L("Отменена", "Кире алынды"));
    assert.equal(await pill(lang, { status: "cancelled", declined: false }), L("Отменена", "Кире алынды"));
  });
  await t.test(`${lang}: «declined» без отмены статус не подменяет`, async () => {
    assert.equal(await pill(lang, { status: "confirmed", declined: true }), L("Подтверждена", "Раҫланды"));
  });
}

await t.test("экран брони передаёт отказ в пометку", () => {
  const src = readFileSync(ROOT + "src/screens/BookingScreen.tsx", "utf8");
  assert.ok(/<StatusPill status=\{d\.status\} declined=\{d\.declined === true\} \/>/.test(src), "пометка знает об отказе");
});

t.done();
