import assert from "node:assert/strict";
import { all, bundleComponent, button, checker, langMock, mount, text } from "./shallow-harness.mjs";

const t = checker();
const h = globalThis.__receiptOwner = {};
const receipt = (id = 42, extra = {}) => ({ booking_id: id, ride_id: 7, role: "passenger", from_city: "Сибай", to_city: "Акъяр",
  depart_at: "2030-01-02T10:00:00Z", seats: 1, amount: 450, pay_method: "cash", paid: false, driver_name: `Driver-${id}`, driver_verified: true,
  pickup_stop: "Ургаза", dropoff_stop: "Бурибай", my_stars: 4, ...extra });
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
function reset() {
  Object.assign(h, { generation: "A", bookingId: 42, calls: [], routes: [], clips: [], shares: [], thanks: [], pays: [], remembered: [],
    receipt: async id => receipt(id), tip: async () => ({ already_thanked: false, money: { sbp: "+70000000001", name: "PRIVATE-42" } }),
    thank: async () => ({}), pay: async () => ({ ok: true }), share: undefined, clip: async () => {} });
}
Object.defineProperty(globalThis, "navigator", { configurable: true, value: {
  get share() { return h.share ? async data => { h.shares.push(data); return h.share(data); } : undefined; },
  clipboard: { writeText: async value => { h.clips.push(value); return h.clip(value); } },
} });
const timers = new Map(); let timerId = 0;
globalThis.window = { location: { pathname: "/trips/42/receipt", href: "" }, setTimeout: fn => { timers.set(++timerId, fn); return timerId; }, clearTimeout: id => timers.delete(id) };
const mocks = lang => ({
  "/i18n/lang": langMock(lang),
  "react-router-dom": `export const useNavigate=()=> (...a)=>globalThis.__receiptOwner.routes.push(a),useParams=()=>({id:String(globalThis.__receiptOwner.bookingId)});`,
  "/api/client": `export class ApiError extends Error{constructor(status){super('fixture');this.status=status;}}globalThis.__receiptOwner.ApiError=ApiError;export const getSessionGeneration=()=>globalThis.__receiptOwner.generation;`,
  "/api/bookings": `export const fetchReceipt=(id,signal)=>{const h=globalThis.__receiptOwner;h.calls.push({kind:'receipt',id,generation:h.generation,signal});return h.receipt(id,signal);};export const reportBookingLostItem=async()=>({ok:true,chat_open_until:'2030-01-01T00:00:00Z'});`,
  "/api/family": `export const fetchBookingTip=(id,signal)=>{const h=globalThis.__receiptOwner;h.calls.push({kind:'tip',id,generation:h.generation,signal});return h.tip(id,signal);};export const thankBooking=id=>{const h=globalThis.__receiptOwner;h.thanks.push({id,generation:h.generation});return h.thank(id);};`,
  "/api/wallet": `export const payBooking=(id,method)=>{const h=globalThis.__receiptOwner;h.pays.push({id,method,generation:h.generation});return h.pay(id,method);};export const payInstantOrder=()=>{throw Error('unexpected order payment');};`,
  "/utils/pendingPayment": `export const rememberPayment=(...a)=>globalThis.__receiptOwner.remembered.push(a);`,
  "/utils/format": `export const formatWhen=v=>v,priceLabel=v=>String(v)+' ₽',payMethodLabel=v=>v;`,
});
function open(Component, props) {
  const s = mount(Component, props), render = s.render;
  s.render = () => { globalThis.__sh = s.state; return render(); };
  return s;
}
const findLeaf = (s, name) => all(s.tree).find(n => n.type === name);
const shareButton = s => all(s.tree).find(n => n.type === "button" && (text(n).includes("Поделиться квитанцией") || text(n).includes("Квитанция менән бүлешеү")));
const thanksButton = s => all(s.tree).find(n => n.type === "button" && (text(n).includes("Сказать рәхмәт") || text(n).includes("Рәхмәт әйтеү")));

for (const lang of ["ru", "ba"]) {
  const Screen = await bundleComponent("src/screens/TripReceiptScreen.tsx", { mocks: mocks(lang) });
  const Pay = await bundleComponent("src/components/PayTripCard.tsx", { mocks: mocks(lang) });
  const Sbp = await bundleComponent("src/components/SbpPay.tsx", { mocks: mocks(lang) });
  await t.test(`${lang}: receipt distinguishes own segment and parent ride, displays only my rating 1..5`, async () => {
    reset(); const s = open(Screen); await s.settle();
    assert.ok(text(s.tree).includes("Ургаза")); assert.ok(text(s.tree).includes("Бурибай"));
    assert.ok(text(s.tree).includes(lang === "ru" ? "Рейс водителя" : "Йөрөтөүсенең сәфәре"));
    assert.ok(text(s.tree).includes(lang === "ru" ? "Участок пассажира" : "Юлсының юл өлөшө"));
    assert.ok(text(s.tree).includes(lang === "ru" ? "Твоя оценка" : "Һинең баһаң")); assert.ok(text(s.tree).includes("4")); s.unmount();
  });
  await t.test(`${lang}: own completed rating is shown for each integer 1..5`, async () => {
    for (const stars of [1, 2, 3, 4, 5]) {
      reset(); h.receipt = async id => receipt(id, { my_stars: stars }); const s = open(Screen); await s.settle();
      const row = all(s.tree).find(n => n.props.className === "info-row" && text(n).includes(lang === "ru" ? "Твоя оценка" : "Һинең баһаң"));
      assert.ok(row, `Missing own rating ${stars}`); assert.ok(text(row).includes(String(stars))); s.unmount();
    }
  });
  await t.test(`${lang}: raw empty strings mean endpoints; NULL/absent never guesses a private segment; zero/bad rating is hidden`, async () => {
    for (const extra of [{ pickup_stop: "", dropoff_stop: "", my_stars: 5 }, { pickup_stop: null, dropoff_stop: "Бурибай", my_stars: 0 },
      { pickup_stop: "Ургаза", dropoff_stop: null, my_stars: null }, { pickup_stop: undefined, dropoff_stop: undefined, my_stars: 6 },
      { pickup_stop: null, dropoff_stop: null, my_stars: -1 }, { pickup_stop: null, dropoff_stop: null, my_stars: 2.5 }]) {
      reset(); h.receipt = async id => receipt(id, extra); const s = open(Screen); await s.settle();
      const out = text(s.tree), segmentLabel = lang === "ru" ? "Участок пассажира" : "Юлсының юл өлөшө", ratingLabel = lang === "ru" ? "Твоя оценка" : "Һинең баһаң";
      assert.equal(out.includes(segmentLabel), extra.pickup_stop === "" && extra.dropoff_stop === "");
      assert.equal(out.includes(ratingLabel), extra.my_stars === 5); s.unmount();
    }
  });
  await t.test(`${lang}: share and clipboard keep full parent route without stops, rating or tip phone`, async () => {
    reset(); const s = open(Screen); await s.settle(); shareButton(s).props.onClick(); await s.settle();
    assert.equal(h.clips.length, 1); assert.ok(h.clips[0].includes("Сибай → Акъяр"));
    for (const privatePart of ["Ургаза", "Бурибай", "+70000000001", "PRIVATE-42", "Твоя оценка", "Һинең баһаң"]) assert.ok(!h.clips[0].includes(privatePart)); s.unmount();
  });
  for (const boundary of ["booking", "account", "same-user-login"]) for (const outcome of ["success", "error"]) {
    await t.test(`${lang}: held receipt ${outcome} after ${boundary} cannot replace current owner`, async () => {
      reset(); const held = deferred(); h.receipt = id => id === 42 && h.generation === "A" ? held.promise : Promise.resolve(receipt(id, { driver_name: "CURRENT" }));
      const s = open(Screen); s.render();
      if (boundary === "booking") h.bookingId = 43; else h.generation = boundary === "account" ? "B" : "A-new-login";
      s.render(); await s.settle(); assert.ok(text(s.tree).includes("CURRENT"));
      if (outcome === "success") held.resolve(receipt(42, { driver_name: "STALE-PRIVATE" })); else held.reject(new h.ApiError(500));
      await s.settle(); assert.ok(text(s.tree).includes("CURRENT")); assert.ok(!text(s.tree).includes("STALE-PRIVATE")); s.unmount();
    });
  }
  await t.test(`${lang}: stale callbacks never start share/thanks/payment/reload under new session or booking`, async () => {
    for (const boundary of ["booking", "account", "same-user-login", "account-no-render"]) {
      reset(); const s = open(Screen); await s.settle();
      const share = shareButton(s).props.onClick, thanks = thanksButton(s).props.onClick;
      const payProps = findLeaf(s, "PayTripCard").props;
      if (boundary === "booking") h.bookingId = 43; else h.generation = boundary === "account" ? "B" : "A-new-login";
      if (boundary !== "account-no-render") { s.render(); await s.settle(); }
      const before = h.calls.length;
      share(); thanks(); payProps.onPaid();
      const pay = open(Pay, payProps); pay.render(); button(pay.tree, lang === "ru" ? "Наличными" : "Наличный менән").props.onClick(); await pay.settle(); await s.settle();
      assert.equal(h.calls.length, before); assert.equal(h.thanks.length, 0); assert.equal(h.clips.length, 0); assert.equal(h.shares.length, 0); assert.equal(h.pays.length, 0);
      pay.unmount(); s.unmount();
    }
  });
  await t.test(`${lang}: retained manual retry and delayed tip are owned, and failed refresh removes old private card`, async () => {
    reset(); h.receipt = async () => { throw new h.ApiError(500); }; const s = open(Screen); await s.settle();
    const retry = findLeaf(s, "ErrorState").props.onRetry;
    h.bookingId = 43; h.receipt = async id => receipt(id, { driver_name: "CURRENT" }); s.render(); await s.settle();
    const before = h.calls.length; retry(); await s.settle(); assert.equal(h.calls.length, before); assert.ok(text(s.tree).includes("CURRENT"));
    const refresh = findLeaf(s, "PayTripCard").props.onPaid; h.receipt = async () => { throw new h.ApiError(500); }; refresh(); await s.settle();
    assert.ok(findLeaf(s, "ErrorState")); assert.ok(!text(s.tree).includes("CURRENT")); assert.ok(!findLeaf(s, "SbpPay")); s.unmount();
    reset(); const tipHeld = deferred(); h.tip = () => tipHeld.promise; const a = open(Screen); await a.settle();
    h.bookingId = 43; h.tip = async () => ({ already_thanked: false, money: null }); a.render(); await a.settle();
    tipHeld.resolve({ already_thanked: true, money: { sbp: "+70000000001", name: "STALE-TIP" } }); await a.settle();
    assert.ok(!findLeaf(a, "SbpPay")); assert.ok(!text(a.tree).includes("STALE-TIP")); a.unmount();
  });
  await t.test(`${lang}: share rejection after owner change must not fall back to clipboard or show copied`, async () => {
    reset(); const held = deferred(); h.share = () => held.promise; const s = open(Screen); await s.settle();
    shareButton(s).props.onClick(); h.generation = "B"; s.render(); await s.settle();
    held.reject(Error("cancelled")); await s.settle(); assert.equal(h.clips.length, 0);
    assert.ok(!text(s.tree).includes(lang === "ru" ? "Скопировано" : "Күсерелде")); s.unmount();
  });
  await t.test(`${lang}: completed child payment after owner change does not redirect, remember or reload`, async () => {
    reset(); const s = open(Screen); await s.settle(); const props = findLeaf(s, "PayTripCard").props;
    const held = deferred(); h.pay = () => held.promise; window.location.href = "";
    const pay = open(Pay, props); pay.render(); button(pay.tree, lang === "ru" ? "Картой" : "Карта менән").props.onClick();
    h.generation = "B"; s.render(); await s.settle(); const before = h.calls.length;
    held.resolve({ confirmation_url: "https://example.invalid/fixture", payment_id: "synthetic" }); await pay.settle(); await s.settle();
    assert.equal(h.calls.length, before); assert.equal(window.location.href, ""); assert.equal(h.remembered.length, 0); pay.unmount(); s.unmount();
  });
  await t.test(`${lang}: manual reload success/error is cancelled across booking, account and same-user new login`, async () => {
    for (const boundary of ["booking", "account", "same-user-login"]) for (const outcome of ["success", "error"]) {
      reset(); h.receipt = async () => { throw new h.ApiError(500); }; const s = open(Screen); await s.settle();
      const held = deferred(); h.receipt = () => held.promise; findLeaf(s, "ErrorState").props.onRetry(); await s.settle();
      const reload = h.calls.filter(c => c.kind === "receipt").at(-1);
      if (boundary === "booking") h.bookingId = 43; else h.generation = boundary === "account" ? "B" : "A-new-login";
      h.receipt = async id => receipt(id, { driver_name: "CURRENT-RELOAD" }); s.render(); await s.settle();
      assert.equal(reload.signal.aborted, true);
      if (outcome === "success") held.resolve(receipt(42, { driver_name: "STALE-RELOAD" })); else held.reject(new h.ApiError(500));
      await s.settle(); assert.ok(text(s.tree).includes("CURRENT-RELOAD")); assert.ok(!text(s.tree).includes("STALE-RELOAD")); s.unmount();
    }
  });
  await t.test(`${lang}: late tip success/error cannot replace or clear current owner's phone`, async () => {
    for (const outcome of ["success", "error"]) {
      reset(); const held = deferred(); h.tip = () => held.promise; const s = open(Screen); await s.settle();
      h.generation = "B"; h.tip = async () => ({ already_thanked: false, money: { sbp: "+70000000002", name: "CURRENT-TIP" } }); s.render(); await s.settle();
      assert.equal(findLeaf(s, "SbpPay").props.phone, "+70000000002");
      if (outcome === "success") held.resolve({ already_thanked: true, money: { sbp: "+70000000001", name: "STALE-TIP" } }); else held.reject(new h.ApiError(500));
      await s.settle(); assert.equal(findLeaf(s, "SbpPay").props.phone, "+70000000002"); s.unmount();
    }
  });
  await t.test(`${lang}: held thanks and clipboard completion cannot update a new owner; unmounted buttons cannot act`, async () => {
    reset(); const thanksHeld = deferred(), clipboardHeld = deferred(); h.thank = () => thanksHeld.promise; h.clip = () => clipboardHeld.promise;
    const s = open(Screen); await s.settle(); const oldShare = shareButton(s).props.onClick, oldThanks = thanksButton(s).props.onClick, oldPay = findLeaf(s, "PayTripCard").props;
    oldThanks(); oldShare(); h.generation = "B"; s.render(); await s.settle();
    thanksHeld.resolve({}); clipboardHeld.resolve(); await s.settle();
    assert.ok(!text(s.tree).includes(lang === "ru" ? "Рәхмәт сказан" : "Рәхмәт әйтелде"));
    assert.ok(!text(s.tree).includes(lang === "ru" ? "Скопировано" : "Күсерелде"));
    s.unmount(); const before = h.calls.length; oldShare(); oldThanks(); oldPay.onPaid(); await new Promise(setImmediate);
    assert.equal(h.calls.length, before); assert.equal(h.thanks.length, 1); assert.equal(h.clips.length, 1);
  });
  await t.test(`${lang}: retained tip-phone copy and bank link cannot act for an old receipt owner`, async () => {
    reset(); const s = open(Screen); await s.settle(); const props = findLeaf(s, "SbpPay").props;
    const tip = open(Sbp, props); tip.render();
    const copy = button(tip.tree, lang === "ru" ? "Копировать" : "Күсереү").props.onClick;
    const bankLink = all(tip.tree).find(n => n.type === "a");
    h.generation = "B"; s.render(); await s.settle();
    await copy(); assert.equal(h.clips.length, 0);
    let prevented = false; bankLink.props.onClick?.({ preventDefault() { prevented = true; } }); assert.equal(prevented, true);
    tip.unmount(); s.unmount();
  });
  await t.test(`${lang}: current tip copy works; delayed clipboard result cannot show old-owner copied state`, async () => {
    reset(); const s = open(Screen); await s.settle(); const props = findLeaf(s, "SbpPay").props;
    const tip = open(Sbp, props); tip.render();
    await button(tip.tree, lang === "ru" ? "Копировать" : "Күсереү").props.onClick(); await tip.settle();
    assert.deepEqual(h.clips, ["+70000000001"]); assert.ok(text(tip.tree).includes(lang === "ru" ? "Скопировано" : "Күсерелде"));
    tip.unmount(); const held = deferred(); h.clip = () => held.promise; const late = open(Sbp, props); late.render();
    const pending = button(late.tree, lang === "ru" ? "Копировать" : "Күсереү").props.onClick();
    h.generation = "B"; s.render(); await s.settle(); held.resolve(); await pending; await late.settle();
    assert.ok(!text(late.tree).includes(lang === "ru" ? "Скопировано" : "Күсерелде")); late.unmount(); s.unmount();
  });
}
t.done();
