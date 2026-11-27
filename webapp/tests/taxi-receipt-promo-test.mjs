import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { all, bundleComponent, checker, langMock, mount } from "./shallow-harness.mjs";

const money = await bundleComponent("src/utils/taxiReceiptDriverMoney.ts", { exportName: "taxiReceiptDriverMoney" });
const t = checker();
const base = { role: "driver", driver_gross_kop: 60000, driver_net_kop: 58200 };
const promo = { ...base, amount_kop: 57000, promo_discount_kop: 3000, driver_fee_kop: 0, driver_promo_comp_kop: 1200 };
await t.test("driver gets actual passenger payment and separate 12 ₽ promo credit", () => {
  assert.deepEqual(money(promo), { passengerKop: 57000, feeKop: 0, promoCompKop: 1200, netKop: 58200 });
});
await t.test("5 ₽ promo reduces the fee without creating a credit", () => {
  assert.deepEqual(money({ ...base, amount_kop: 59500, promo_discount_kop: 500, driver_fee_kop: 1300, driver_promo_comp_kop: 0 }),
    { passengerKop: 59500, feeKop: 1300, promoCompKop: 0, netKop: 58200 });
});
await t.test("old no-promo receipt renders; missing promo data or inconsistent amounts do not", () => {
  assert.deepEqual(money({ ...base, amount_kop: 60000, promo_discount_kop: 0, driver_fee_kop: 1800 }),
    { passengerKop: 60000, feeKop: 1800, promoCompKop: 0, netKop: 58200 });
  assert.equal(money({ ...promo, driver_promo_comp_kop: undefined }), null);
  assert.equal(money({ ...promo, driver_net_kop: 55200 }), null);
  assert.equal(money({ ...promo, driver_promo_comp_kop: -1, driver_net_kop: 56999 }), null);
  assert.equal(money({ ...promo, role: "passenger" }), null);
});
await t.test("driver section uses validated amounts and localized labels", () => {
  const s = readFileSync(new URL("../src/screens/TaxiReceiptScreen.tsx", import.meta.url), "utf8");
  assert.match(s, /const driverMoney = r \? taxiReceiptDriverMoney\(r\)/);
  assert.match(s, /kopExactLabel\(driverMoney.passengerKop\)/);
  assert.match(s, /driverMoney\.promoCompKop > 0/);
  assert.match(s, /appText\("Доплата Юлдаша", "Юлдаш өҫтәп түләне"\)/);
  assert.match(s, /appText\("Уточняется", "Асыҡлана"\)/);
});
for (const lang of ["ru", "ba"]) {
  const local = (ru, ba) => lang === "ba" ? ba : ru;
  const Screen = await bundleComponent("src/screens/TaxiReceiptScreen.tsx", {
    real: ["/utils/taxiReceiptDriverMoney"],
    mocks: {
      "/i18n/lang": langMock(lang),
      "react-router-dom": 'export const useNavigate=()=>()=>{},useParams=()=>({orderId:"42"});',
      "/api/client": "export class ApiError extends Error {}",
      "/api/instant": "export const fetchTaxiReceipt=async()=>globalThis.__promoReceipt; export const markCashReceived=()=>{},rateInstantOrder=()=>{},reportLostItem=()=>{};",
      "/api/family": "export const fetchOrderTip=async()=>({}),thankOrder=async()=>({});",
      "/utils/format": "export const kopExactLabel=k=>String(k/100)+' ₽',formatWhen=()=>'',payMethodLabel=()=>'';",
    },
  });
  const labels = {
    passenger: local("От пассажира", "Пассажирҙан"),
    credit: local("Доплата Юлдаша", "Юлдаш өҫтәп түләне"),
    net: local("Чистыми тебе", "Һиңә таҙа килем"),
    pending: local("Расчёт для водителя", "Йөрөтөүсе өсөн иҫәп"),
  };
  async function rows(extra = {}) {
    globalThis.__promoReceipt = { ...promo, order_id: 42, ride_price: 600, paid: true, ...extra };
    const screen = mount(Screen, {});
    screen.render();
    await screen.settle();
    return new Map(all(screen.tree).filter(n => "label" in n.props && "value" in n.props)
      .map(n => [n.props.label, n.props.value]));
  }
  await t.test(lang + " renders reconciled driver breakdown and keeps it off passenger receipt", async () => {
    const r = await rows();
    assert.equal(r.get(labels.passenger), "570 ₽");
    assert.equal(r.get(labels.credit), "+12 ₽");
    assert.equal(r.get(labels.net), "582 ₽");
    const small = await rows({ amount_kop: 59500, promo_discount_kop: 500, driver_fee_kop: 1300, driver_promo_comp_kop: 0 });
    assert.equal(small.get(labels.passenger), "595 ₽");
    assert.ok(!small.has(labels.credit));
    assert.equal(small.get(labels.net), "582 ₽");
    const older = await rows({ driver_promo_comp_kop: undefined });
    assert.ok(older.has(labels.pending) && !older.has(labels.net));
    const passenger = await rows({ role: "passenger" });
    assert.ok(!passenger.has(labels.credit) && !passenger.has(labels.net) && !passenger.has(labels.pending));
  });
}
t.done();
