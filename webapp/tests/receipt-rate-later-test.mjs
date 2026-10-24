// ================================================================
//  «Оценить водителя» в квитанции попутки (приёмка 18.10, п.7; Android: TripReceiptRateLaterTest).
//
//  Было: пропустил экран «Поездка завершена» — оценить водителя позже негде.
//  Стало: квитанция пассажира с `can_rate` и без своей оценки показывает пять звёзд;
//  касание сразу шлёт оценку, ответ сервера возвращается в квитанцию («Твоя оценка»).
//
//  Исполняем настоящий ReceiptRateDriver (shallow-harness.mjs); квитанцию сверяем по исходнику.
// ================================================================
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { ROOT, all, bundleComponent, checker, langMock, mount } from "./shallow-harness.mjs";

async function open(lang, { fail = false, canAct = () => true } = {}) {
  const Rate = await bundleComponent("src/components/ReceiptRateDriver.tsx", {
    mocks: {
      "/i18n/lang": langMock(lang),
      "/api/client": `export class ApiError extends Error{constructor(s,m){super(m);this.status=s;}}`,
      "/api/bookings": `export const rateBooking=async(id,stars)=>{(globalThis.__rated??=[]).push([id,stars]);if(globalThis.__rateFail)throw new Error("net");return {};};`,
    },
  });
  globalThis.__rated = [];
  globalThis.__rateFail = fail;
  const rated = [];
  const h = mount(Rate, { bookingId: 42, canAct, onRated: (s) => rated.push(s) });
  h.render();
  await h.settle();
  return { h, rated };
}

const stars = (tree) => all(tree).filter((n) => n.type === "button" && String(n.props.className ?? "").includes("rcpt-rate__star"));
const t = checker();

for (const lang of ["ru", "ba"]) {
  const L = (ru, ba) => (lang === "ba" ? ba : ru);
  await t.test(`${lang}: пять звёзд с понятными подписями`, async () => {
    const { h } = await open(lang);
    const s = stars(h.tree);
    assert.equal(s.length, 5);
    assert.deepEqual(s.map((b) => b.props["aria-label"]), [1, 2, 3, 4, 5].map((v) => L(`${v} из 5`, `5-тән ${v}`)));
  });

  await t.test(`${lang}: касание отправляет оценку один раз и отдаёт её квитанции`, async () => {
    const { h, rated } = await open(lang);
    stars(h.tree)[3].props.onClick();
    await h.settle();
    assert.deepEqual(globalThis.__rated, [[42, 4]]);
    assert.deepEqual(rated, [4]);
  });

  await t.test(`${lang}: сеть упала — звёзды на месте и понятный текст`, async () => {
    const { h, rated } = await open(lang, { fail: true });
    stars(h.tree)[4].props.onClick();
    await h.settle();
    assert.deepEqual(rated, []);
    const err = all(h.tree).find((n) => String(n.props?.className ?? "") === "rcpt-rate__err");
    assert.ok(err, "есть текст ошибки");
    assert.equal(stars(h.tree).every((b) => !b.props.disabled), true, "можно попробовать ещё раз");
  });
}

await t.test("ушёл с квитанции — поздний ответ её не трогает", async () => {
  let alive = true;
  const { h, rated } = await open("ru", { canAct: () => alive });
  alive = false;
  stars(h.tree)[0].props.onClick();
  await h.settle();
  assert.deepEqual(globalThis.__rated, [], "чужой экран оценку не шлёт");
  assert.deepEqual(rated, []);
});

await t.test("квитанция пассажира предлагает оценку по can_rate и без своей оценки", () => {
  const src = readFileSync(ROOT + "src/screens/TripReceiptScreen.tsx", "utf8");
  assert.ok(src.includes("<ReceiptRateDriver"), "блок оценки стоит в квитанции");
  assert.ok(/passengerReceipt && r\.can_rate && ownStars === null/.test(src), "только пассажиру, по can_rate, пока нет своей оценки");
});

t.done();
