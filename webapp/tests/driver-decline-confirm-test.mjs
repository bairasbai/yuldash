// ================================================================
//  «Отклонить» бронь у водителя — сначала вопрос (приёмка 18.10, п.6; Android: DriverDeclineBookingTest).
//
//  Было: в кабинете «Отклонить» сразу звал отмену брони — одно касание, и пассажир получил отказ.
//  Стало: кнопка — DeclineBookingButton: «Отклонить бронь?» с именем, отмена только после «Да».
//
//  Исполняем настоящий DeclineBookingButton (shallow-harness.mjs); кабинет сверяем по исходнику:
//  «Отклонить» в нём больше не шлёт отмену напрямую.
// ================================================================
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { ROOT, bundleComponent, button, checker, langMock, mount, ofType } from "./shallow-harness.mjs";

async function open(lang, name = "Гульнара") {
  const Button = await bundleComponent("src/components/DeclineBookingButton.tsx", {
    mocks: { "/i18n/lang": langMock(lang) },
  });
  const declined = [];
  const h = mount(Button, { passengerName: name, onDecline: () => declined.push(1) });
  h.render();
  await h.settle();
  return { h, declined };
}

const t = checker();

for (const lang of ["ru", "ba"]) {
  const L = (ru, ba) => (lang === "ba" ? ba : ru);
  await t.test(`${lang}: касание «Отклонить» только спрашивает`, async () => {
    const { h, declined } = await open(lang);
    assert.equal(ofType(h.tree, "AlertDialog").length, 0, "до касания окна нет");
    button(h.tree, L("Отклонить", "Кире ҡағыу")).props.onClick();
    await h.settle();
    const dlg = ofType(h.tree, "AlertDialog")[0];
    assert.ok(dlg, "появился вопрос");
    assert.equal(dlg.props.title, L("Отклонить бронь?", "Бронде кире ҡағырғамы?"));
    assert.ok(dlg.props.text.startsWith("Гульнара "), "в вопросе имя пассажира: " + dlg.props.text);
    assert.deepEqual(declined, [], "бронь не тронута, пока водитель не ответил «Да»");
  });

  await t.test(`${lang}: «Да, отклонить» снимает бронь один раз и закрывает окно`, async () => {
    const { h, declined } = await open(lang);
    button(h.tree, L("Отклонить", "Кире ҡағыу")).props.onClick();
    await h.settle();
    const dlg = ofType(h.tree, "AlertDialog")[0];
    assert.equal(dlg.props.confirm.label, L("Да, отклонить", "Эйе, кире ҡағырға"));
    assert.equal(dlg.props.confirm.tone, "danger");
    dlg.props.confirm.onClick();
    await h.settle();
    assert.deepEqual(declined, [1]);
    assert.equal(ofType(h.tree, "AlertDialog").length, 0);
  });

  await t.test(`${lang}: «Не отклонять» и тап мимо окна бронь не трогают`, async () => {
    const { h, declined } = await open(lang);
    for (const close of [(d) => d.props.dismiss.onClick(), (d) => d.props.onClose()]) {
      button(h.tree, L("Отклонить", "Кире ҡағыу")).props.onClick();
      await h.settle();
      close(ofType(h.tree, "AlertDialog")[0]);
      await h.settle();
      assert.equal(ofType(h.tree, "AlertDialog").length, 0);
    }
    assert.deepEqual(declined, []);
  });
}

await t.test("без имени — «Пассажир», а не пустое место", async () => {
  const { h } = await open("ru", "");
  button(h.tree, "Отклонить").props.onClick();
  await h.settle();
  assert.ok(ofType(h.tree, "AlertDialog")[0].props.text.startsWith("Пассажир получит"));
});

await t.test("кабинет водителя: «Отклонить» идёт через вопрос, а не шлёт отмену сразу", () => {
  const src = readFileSync(ROOT + "src/screens/DriverCabinetScreen.tsx", "utf8");
  assert.ok(src.includes("<DeclineBookingButton"), "кнопка с вопросом стоит в кабинете");
  assert.ok(!/onClick=\{\(\) => void answerBooking\(b\.booking_id, false\)\}/.test(src), "прямой отмены по касанию нет");
});

t.done();
