// ================================================================
//  «Мои поездки»: отказ водителя — «Отклонена», не «Отменена» (приёмка PR210; Android: MyTripsDeclinedLabelTest).
//
//  Экран брони уже говорил «Водитель отклонил бронь», а карточка той же брони в «Моих поездках» — «Отменена».
//  /bookings/mine теперь отдаёт `declined`. Заодно башкирский: «Отменена» была «Кире ҡағылды» — а это
//  «Отклонена»; теперь «Кире алынды», как в пометке статуса.
//
//  Исполняем настоящий MyTripCard (shallow-harness.mjs).
// ================================================================
import assert from "node:assert/strict";
import { all, bundleComponent, checker, langMock, mount, text } from "./shallow-harness.mjs";

const BOOKING = {
  id: 41, ride_id: 7, seats: 1, price: 500, status: "cancelled", boarding_code: "", pay_method: "negotiate",
  from_city: "Баймаҡ", to_city: "Өфө", depart_at: "2030-01-02T10:00:00+05:00", driver_name: "Ильдар", driver_verified: true,
};

async function statusLabel(lang, booking) {
  const Card = await bundleComponent("src/components/MyTripCard.tsx", { mocks: { "/i18n/lang": langMock(lang) } });
  const h = mount(Card, { booking, index: 0, primaryAction: "a", secondaryAction: "b", onPrimary() {}, onSecondary() {} });
  h.render();
  await h.settle();
  const pill = all(h.tree).find((n) => String(n.props?.className ?? "").includes("my-trip-card__status"));
  assert.ok(pill, "пилюля статуса на месте");
  return text(pill).trim();
}

const t = checker();

for (const lang of ["ru", "ba"]) {
  const L = (ru, ba) => (lang === "ba" ? ba : ru);
  await t.test(`${lang}: отклонённая водителем бронь — «Отклонена»`, async () => {
    assert.equal(await statusLabel(lang, { ...BOOKING, declined: true }), L("Отклонена", "Кире ҡағылған"));
  });
  await t.test(`${lang}: отменённая — «Отменена» (по-башкирски «Кире алынды»)`, async () => {
    assert.equal(await statusLabel(lang, { ...BOOKING, declined: false }), L("Отменена", "Кире алынды"));
    assert.equal(await statusLabel(lang, BOOKING), L("Отменена", "Кире алынды"), "старый сервер без поля — отмена");
  });
}

t.done();
