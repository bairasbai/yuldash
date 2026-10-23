// ================================================================
//  Паритет с Android, шаг 4 (17.10.2026): подтверждение перед необратимым.
//
//  Было на сайте:
//   • «Застряли на трассе» — сигнал дежурному уходил с первого касания (случайный тап в
//     кармане поднимал поддержку и близких);
//   • «Завершить поездку» у водителя — бронь закрывалась с первого касания, вернуть нельзя.
//  Стало как в Android: окно «Позвать помощь?» (RoadsideHelp.kt) и «Завершить поездку?»
//  (BookingActiveTripScreen.kt). Тексты — оттуда; в окне помощи вместо «SMS» — «сообщение»
//  (SMS на проде выключены, сторож sos-no-sms-promise-test).
//
//  Исполняем настоящие RoadsideHelp и ActiveTripScreen (shallow-harness.mjs).
// ================================================================
import assert from "node:assert/strict";
import { bundleComponent, button, checker, langMock, mount, ofType } from "./shallow-harness.mjs";
import { openActiveTrip } from "./active-trip-fixture.mjs";

const t = checker();

async function roadside(lang) {
  const View = await bundleComponent("src/components/RoadsideHelp.tsx", {
    mocks: {
      "/i18n/lang": langMock(lang),
      "/api/safety": `export const roadsideHelpBooking=async(id,p)=>{globalThis.__rh.sent.push([id,p]);return{contacts_total:0,contacts_notified:0};};export const roadsideHelpParcel=async()=>({});`,
      "/analytics": `export const track=(e)=>globalThis.__rh.tracked.push(e);`,
    },
  });
  globalThis.__rh = { sent: [], tracked: [] };
  Object.defineProperty(globalThis, "navigator", { configurable: true, writable: true, value: {} }); // без геолокации — сигнал уходит без места
  const h = mount(View, { target: { kind: "booking", id: 42 } });
  h.render();
  return h;
}

for (const lang of ["ru", "ba"]) {
  const L = (ru, ba) => (lang === "ba" ? ba : ru);
  await t.test(`${lang}: «Застряли на трассе» — сначала «Позвать помощь?», сигнал только после «Позвать помощь»`, async () => {
    const h = await roadside(lang);
    button(h.tree, L("Застряли на трассе", "Юлда ҡалдыҡ")).props.onClick();
    await h.settle();
    assert.deepEqual(globalThis.__rh.sent, [], "с первого касания сигнал не уходит");
    const dlg = ofType(h.tree, "AlertDialog")[0];
    assert.ok(dlg, "окно открылось");
    assert.equal(dlg.props.title, L("Позвать помощь?", "Ярҙам саҡырырғамы?"));
    assert.equal(
      dlg.props.text,
      L(
        "Отправим сигнал в Юлдаш. Если место доступно, приложим его. Если угрожает опасность — звони 112.",
        "Юлдашҡа сигнал ебәрәбеҙ. Урын билдәле булһа, уны ла өҫтәйбеҙ. Хәүеф янаһа — 112-гә шылтырат."
      )
    );
    assert.ok(!/SMS/i.test(dlg.props.text), "окно не обещает SMS");
    assert.equal(dlg.props.confirm.label, L("Позвать помощь", "Ярҙам саҡырыу"));
    assert.equal(dlg.props.dismiss.label, L("Отмена", "Кире алыу"));
    dlg.props.dismiss.onClick();
    await h.settle();
    assert.equal(ofType(h.tree, "AlertDialog").length, 0, "«Отмена» закрывает окно");
    assert.deepEqual(globalThis.__rh.sent, []);
    button(h.tree, L("Застряли на трассе", "Юлда ҡалдыҡ")).props.onClick();
    await h.settle();
    ofType(h.tree, "AlertDialog")[0].props.confirm.onClick();
    await h.settle();
    assert.equal(globalThis.__rh.sent.length, 1, "подтвердил — сигнал ушёл один раз");
    assert.equal(globalThis.__rh.sent[0][0], 42);
    assert.deepEqual(globalThis.__rh.tracked, ["roadside_help"]);
  });

  await t.test(`${lang}: водитель — «Завершить поездку» через окно «Завершить поездку?»`, async () => {
    const { h, at } = await openActiveTrip({ lang, role: "driver", status: "confirmed", phase: "departed" });
    button(h.tree, L("Завершить поездку", "Сәфәрҙе тамамлау")).props.onClick();
    await h.settle();
    assert.deepEqual(at.phases, [], "с первого касания поездка не закрывается");
    const dlg = () => ofType(h.tree, "AlertDialog").find((d) => d.props.title === L("Завершить поездку?", "Сәфәрҙе тамамларғамы?"));
    assert.ok(dlg(), "окно открылось");
    assert.equal(dlg().props.text, L("Подтверди, что поездка действительно закончилась.", "Сәфәр ысынлап та тамамланғанын раҫла."));
    assert.equal(dlg().props.confirm.label, L("Да, завершить", "Эйе, тамамларға"));
    assert.equal(dlg().props.dismiss.label, L("Не завершать", "Тамамламаҫҡа"));
    dlg().props.dismiss.onClick();
    await h.settle();
    assert.equal(dlg(), undefined);
    assert.deepEqual(at.phases, [], "«Не завершать» — ничего не ушло");
    button(h.tree, L("Завершить поездку", "Сәфәрҙе тамамлау")).props.onClick();
    await h.settle();
    dlg().props.confirm.onClick();
    await h.settle();
    assert.deepEqual(at.phases, [[42, "done"]], "подтвердил — поездка завершена");
  });
}

await t.test("«Выехал» и «Подъезжаю» уходят сразу — подтверждение только у необратимого", async () => {
  const { h, at } = await openActiveTrip({ role: "driver", status: "confirmed", phase: "" });
  button(h.tree, "Подъезжаю").props.onClick();
  await h.settle();
  assert.deepEqual(at.phases, [[42, "arriving"]]);
  assert.equal(ofType(h.tree, "AlertDialog").length, 0);
});

t.done();
