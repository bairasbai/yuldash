import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { driverOfferMoney } from "./.bundles/driver-offer-money.mjs";

const ru = (ru, _ba) => ru;
const ba = (_ru, ba) => ba;
const gross = { driver_gross_kop: 70000 };
const complete = { ...gross, driver_net_kop: 57575, driver_fee_kop: 12425, driver_fee_percent: 17.75 };
function check(label, run) {
  run();
  console.log(`✓ ${label}`);
}
check("complete quote keeps the server net and displays kopecks exactly on RU/BA", () => {
  assert.deepEqual(driverOfferMoney(complete, ru), {
    income: "575,75 ₽",
    incomeKnown: true,
    breakdown: "Пассажир платит 700 ₽ · комиссия 124,25 ₽ (17,75%)",
  });
  assert.equal(driverOfferMoney({ driver_gross_kop: 200000, driver_net_kop: 123456 }, ru).income, "1\u00A0234,56 ₽");
  assert.deepEqual(driverOfferMoney(complete, ba), {
    income: "575,75 ₽",
    incomeKnown: true,
    breakdown: "Пассажир 700 ₽ түләй · комиссия 124,25 ₽ (17,75%)",
  });
});
check("legacy fare does not masquerade as driver net", () => {
  assert.deepEqual(driverOfferMoney({ price_estimate: 700 }, ru), { income: "Уточняется", breakdown: null, incomeKnown: false });
  assert.equal(driverOfferMoney({ driver_gross_kop: 0, driver_net_kop: 0 }, ba).income, "Асыҡлана");
});
check("missing net does not masquerade as zero; known zero remains visible", () => {
  assert.equal(driverOfferMoney(gross, ru).income, "Уточняется");
  assert.equal(driverOfferMoney({ ...gross, driver_net_kop: 0 }, ru).income, "0 ₽");
});
check("unknown fee or percentage is not presented as zero", () => {
  assert.equal(driverOfferMoney({ ...gross, driver_net_kop: 62000 }, ru).breakdown, null);
  assert.equal(driverOfferMoney({ ...gross, driver_fee_kop: 0 }, ru).breakdown, "Пассажир платит 700 ₽ · комиссия 0 ₽");
});
check("invalid money and percentage do not show as genuine amounts", () => {
  for (const net of [null, NaN, Infinity, "0", -1, 1.5, 70001, 2_147_483_648]) {
    assert.equal(driverOfferMoney({ ...gross, driver_net_kop: net }, ba).income, "Асыҡлана");
  }
  assert.equal(driverOfferMoney({ ...complete, driver_fee_kop: -1 }, ru).breakdown, null);
  assert.equal(driverOfferMoney({ ...complete, driver_fee_percent: 100.01 }, ru).breakdown,
    "Пассажир платит 700 ₽ · комиссия 124,25 ₽");
});
check("the real incoming offer reads the same helper and renders its result", () => {
  const screen = readFileSync(new URL("../src/screens/InstantDriverTripScreen.tsx", import.meta.url), "utf8");
  assert.match(screen, /const \{ income, breakdown, incomeKnown \} = driverOfferMoney\(order, appText\)/);
  assert.match(screen, /<b className=\{incomeKnown \? undefined : "toffer-income--pending"\}>\{income\}<\/b>/);
  assert.match(screen, /if \(breakdown\) notes.push\(breakdown\)/);
});

check("offer never presents the old straight-line estimate as road ETA", () => {
  const screen = readFileSync(new URL("../src/screens/InstantDriverTripScreen.tsx", import.meta.url), "utf8");
  assert.match(screen, /const pickupLine = appText\("Время подачи уточняется", "Килеп етеү ваҡыты асыҡлана"\)/);
  assert.match(screen, /<strong>\{pickupLine\}<\/strong>/);
  assert.doesNotMatch(screen, /order\.offer_pickup_(?:km|eta_min)/);
});
