import assert from "node:assert/strict";
import { IDBFactory } from "fake-indexeddb";
import { all, bundleComponent, button, checker, langMock, mount, text } from "./shallow-harness.mjs";

const t = checker();
const h = globalThis.__receiptOwner = {};
const receipt = (id = 42, extra = {}) => ({ booking_id: id, ride_id: 7, role: "passenger", from_city: "Сибай", to_city: "Акъяр",
  depart_at: "2030-01-02T10:00:00Z", seats: 1, amount: 450, pay_method: "cash", paid: false, driver_name: `Driver-${id}`, driver_verified: true,
  pickup_stop: "Ургаза", dropoff_stop: "Бурибай", my_stars: 4, ...extra });
const bankReceipt = (status = "unavailable") => ({ currency: "RUB", payment_status: status, amount_kop: 45000,
  paid_kop: status === "succeeded" ? 45000 : 0, refund_status: "not_started", refunded_kop: 0,
  refund_coverage: "none", payout_status: null, paid_out_kop: null, payout_remaining_kop: null });
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const clientMock = `export class ApiError extends Error{constructor(status){super('fixture');this.status=status;}}
const h=globalThis.__receiptOwner;
h.ApiError=ApiError;
export const API_BASE='http://127.0.0.1:8000';
export const getSessionGeneration=()=>h.generation;
export const apiGet=(path, options)=>h.bank(path,options);
export const isAccountSession=(owner)=>owner.userId===h.user?.id&&owner.generation===h.generation;
export const apiCommand=(...args)=>h.command(...args);`;
function reset() {
  globalThis.indexedDB = new IDBFactory();
  Object.assign(h, { generation: "A", bookingId: 42, user: null, calls: [], routes: [], clips: [], shares: [], thanks: [], pays: [], remembered: [], bankLinks: [],
    receipt: async id => receipt(id), tip: async () => ({ already_thanked: false, money: { sbp: "+70000000001", name: "PRIVATE-42" } }),
    thank: async () => ({}), pay: async () => ({ ok: true }), bank: async () => bankReceipt(), share: undefined, clip: async () => {} });
}
Object.defineProperty(globalThis, "navigator", { configurable: true, value: {
  get share() { return h.share ? async data => { h.shares.push(data); return h.share(data); } : undefined; },
  clipboard: { writeText: async value => { h.clips.push(value); return h.clip(value); } },
} });
const timers = new Map(); const focusListeners = new Set(); let timerId = 0;
globalThis.window = { location: { pathname: "/trips/42/receipt", href: "", assign: url => h.bankLinks.push(url) }, setTimeout: fn => { timers.set(++timerId, fn); return timerId; }, clearTimeout: id => timers.delete(id),
  addEventListener: (event, fn) => { if (event === "focus") focusListeners.add(fn); },
  removeEventListener: (event, fn) => { if (event === "focus") focusListeners.delete(fn); },
};
const mocks = lang => ({
  "/i18n/lang": langMock(lang),
  "/auth/AuthProvider": `export const useAuth=()=>({user:globalThis.__receiptOwner.user});`,
  "react-router-dom": `export const useNavigate=()=> (...a)=>globalThis.__receiptOwner.routes.push(a),useParams=()=>({id:String(globalThis.__receiptOwner.bookingId)});`,
  "/api/client": clientMock,
  "./client": clientMock,
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

function checkoutFixture(action) {
  const priorId = "a".repeat(32);
  const nextId = "b".repeat(32);
  const terms = { amount_kop: 45000, paid_revision: 1, card_revision: 1, recipient_version: "c".repeat(32),
    driver_consent_at: "2026-11-21T04:00:00Z", retry_after_payment_id: action === "retry" ? priorId : null };
  const fixture = { outcome: "unseen", status: action === "retry" ? "canceled" : "unavailable",
    attemptId: action === "retry" ? priorId : null, posts: [], offer: { action, confirmation: terms } };
  h.user = { id: 12, created_at: "2025-01-01T00:00:00Z" };
  h.bank = async () => ({ ...bankReceipt(fixture.status), payment_attempt_id: fixture.attemptId });
  h.command = async (path, owner, options) => {
    assert.equal(owner.userId, h.user.id);
    assert.equal(owner.generation, h.generation);
    assert.equal(options.signal.aborted, false);
    if (path === "/bookings/42/card-payment/terms") return fixture.offer ?? {};
    if (path === "/bookings/42/commands/v1/card-confirm") {
      assert.equal(options.method, "POST");
      assert.equal(options.oneShot, true);
      assert.match(options.key, /^cmd1_/);
      assert.deepEqual(JSON.parse(options.body), terms);
      fixture.posts.push(options.key);
      if (action === "retry") {
        fixture.outcome = "applied";
        return { accepted: true };
      }
      throw new Error("Simulated loss before any client acknowledgement");
    }
    if (path.startsWith("/command-intents/")) {
      assert.equal(options.method, "GET");
      assert.equal(path, "/command-intents/" + fixture.posts[0]);
      return fixture.outcome === "unseen" ? { state: "unseen", scope: null } :
        { state: "applied", scope: "booking.card_confirm", ref_id: 42, current: { accepted: true } };
    }
    assert.equal(path, "/bookings/42/card-payment/checkout-link?expected_payment_id=" + nextId);
    return { checkout_url: "https://yoomoney.ru/checkout/synthetic" };
  };
  return { fixture, nextId };
}

async function checkoutUntil(s, condition) {
  for (let i = 0; i < 30; i++) {
    await new Promise(resolve => setImmediate(resolve));
    await s.settle();
    const controls = findLeaf(s, "RideshareCardCheckout")?.props.checkout;
    if (controls && condition(controls)) return controls;
  }
  throw new Error("Checkout control did not reach the expected state");
}

await t.test("the bank redirect accepts only exact trusted HTTPS authorities", async () => {
  const trusted = await bundleComponent("src/api/rideshareCardCheckout.ts", { mocks: mocks("ru"), exportName: "trustedCardCheckoutUrl" });
  assert.equal(trusted("https://yoomoney.ru/checkout/synthetic"), true);
  assert.equal(trusted("https://yookassa.ru:443/checkout/synthetic"), true);
  for (const url of ["http://yoomoney.ru", "https://yoomoney.ru.invalid", "https://user@yoomoney.ru",
    "https://yoomoney.ru@invalid.example", "https://yoomoney.ru:444", "https://yoomoney.ru:",
    "https://%79oomoney.ru/checkout", " https://yoomoney.ru", ["https://yoomoney.ru"]]) {
    assert.equal(trusted(url), false);
  }
});

for (const lang of ["ru", "ba"]) {
  const Screen = await bundleComponent("src/screens/TripReceiptScreen.tsx", { mocks: mocks(lang),
    real: ["/utils/completedBookingPrivacy", "/utils/useRideshareCardPayment", "/api/rideshareCardPayment",
      "/utils/useRideshareCardCheckout", "/utils/bookingCommandStore"] });
  const Pay = await bundleComponent("src/components/PayTripCard.tsx", { mocks: mocks(lang) });
  const Sbp = await bundleComponent("src/components/SbpPay.tsx", { mocks: mocks(lang) });
  await t.test(`${lang}: a lost card confirmation survives reopening and no automatic POST is sent`, async () => {
    reset();
    const { fixture, nextId } = checkoutFixture("start");
    const starting = open(Screen);
    await starting.settle();
    const initial = await checkoutUntil(starting, read => read.offer?.action === "start");
    initial.choose();
    const confirmation = await checkoutUntil(starting, read => read.view.selected !== null);
    assert.equal(confirmation.view.selected.confirmation.amount_kop, 45000);
    confirmation.confirm();
    confirmation.confirm();
    await checkoutUntil(starting, read => read.canRepeat && !read.view.busy);
    assert.equal(fixture.posts.length, 1);
    assert.equal(findLeaf(starting, "PayTripCard"), undefined);
    starting.unmount();
    const restored = open(Screen);
    await restored.settle();
    const pending = await checkoutUntil(restored, read => read.canRepeat && !read.view.busy);
    assert.equal(fixture.posts.length, 1);
    assert.equal(findLeaf(restored, "PayTripCard"), undefined);
    fixture.outcome = "applied";
    fixture.status = "waiting_bank";
    fixture.attemptId = nextId;
    fixture.offer = null;
    pending.refresh();
    const waiting = await checkoutUntil(restored, read => read.canOpenBank && !read.view.pending && !read.view.busy);
    waiting.openBank();
    await checkoutUntil(restored, read => !read.view.busy && h.bankLinks.length === 1);
    assert.deepEqual(h.bankLinks, ["https://yoomoney.ru/checkout/synthetic"]);
    assert.equal(fixture.posts.length, 1);
    restored.unmount();
  });

  await t.test(`${lang}: old canceled status cannot release direct payment during a new card attempt`, async () => {
    reset();
    const { fixture, nextId } = checkoutFixture("retry");
    const s = open(Screen);
    await s.settle();
    const retry = await checkoutUntil(s, read => read.offer?.action === "retry");
    retry.choose();
    const confirmation = await checkoutUntil(s, read => read.view.selected !== null);
    confirmation.confirm();
    const stillPending = await checkoutUntil(s, read => read.view.confirmationState === "applied" && !read.view.busy);
    assert.equal(stillPending.canRepeat, false);
    assert.equal(findLeaf(s, "PayTripCard"), undefined);
    let reads = 0;
    const old = { ...bankReceipt("canceled"), payment_attempt_id: fixture.attemptId };
    const next = { ...bankReceipt("waiting_bank"), payment_attempt_id: nextId };
    h.bank = async () => reads++ === 0 ? old : next;
    fixture.offer = null;
    stillPending.refresh();
    await checkoutUntil(s, read => !read.view.pending && read.canOpenBank && !read.view.busy);
    assert.equal(findLeaf(s, "PayTripCard"), undefined);
    assert.equal(findLeaf(s, "RideshareCardPaymentSummary").props.read.payment.payment_attempt_id, nextId);
    assert.equal(fixture.posts.length, 1);
    s.unmount();
  });

  await t.test(`${lang}: a retained card confirmation never starts the next booking with identical terms`, async () => {
    reset();
    const { fixture } = checkoutFixture("start");
    const original = h.command;
    const posts = [];
    h.command = async (path, owner, options) => {
      if (path === "/bookings/43/card-payment/terms") return fixture.offer;
      if (options.method === "POST") posts.push(path);
      if (path === "/bookings/43/commands/v1/card-confirm") return { accepted: true };
      return original(path, owner, options);
    };
    const s = open(Screen);
    await s.settle();
    const available = await checkoutUntil(s, read => read.offer?.action === "start");
    available.choose();
    const retained = await checkoutUntil(s, read => read.view.selected !== null);
    h.bookingId = 43;
    s.render();
    await checkoutUntil(s, read => read.offer?.action === "start" && !read.view.selected && !read.view.busy);
    retained.confirm();
    for (let i = 0; i < 5; i++) {
      await new Promise(resolve => setImmediate(resolve));
      await s.settle();
    }
    assert.deepEqual(posts, []);
    s.unmount();
  });
  await t.test(`${lang}: an admitted card payment takes precedence and a canceled charge releases direct payment`, async () => {
    reset();
    h.receipt = async id => receipt(id, { card_payment_obligation: true });
    let status = "first_read_failed";
    h.bank = async (path, options) => {
      assert.equal(path, "/bookings/42/card-payment");
      assert.equal(options._generation, "A");
      assert.equal(options.cache, "no-store");
      if (status === "first_read_failed") throw new Error("offline");
      return bankReceipt(status);
    };
    const s = open(Screen); await s.settle();
    assert.equal(findLeaf(s, "PayTripCard"), undefined);
    const card = findLeaf(s, "RideshareCardPaymentSummary");
    assert.equal(card.props.read.phase, "error");
    status = "waiting_bank";
    card.props.read.refresh(); await s.settle();
    assert.equal(findLeaf(s, "RideshareCardPaymentSummary").props.read.payment.payment_status, "waiting_bank");
    assert.equal(findLeaf(s, "PayTripCard"), undefined);
    status = "canceled";
    card.props.read.refresh(); await s.settle();
    assert.ok(findLeaf(s, "PayTripCard"));
    assert.equal(findLeaf(s, "RideshareCardPaymentSummary").props.read.payment.payment_status, "canceled");
    assert.deepEqual(h.pays, []);
    s.unmount();
  });
  await t.test(`${lang}: a late private bank receipt cannot follow the old account to another booking`, async () => {
    reset();
    const held = deferred();
    h.bank = (path, options) => options._generation === "A" ? held.promise : Promise.resolve(bankReceipt());
    const s = open(Screen); await s.settle();
    h.generation = "B"; h.bookingId = 43;
    s.render(); await s.settle();
    held.resolve(bankReceipt("succeeded")); await s.settle();
    assert.equal(findLeaf(s, "RideshareCardPaymentSummary"), undefined);
    assert.equal(findLeaf(s, "PayTripCard").props.id, 43);
    assert.deepEqual(h.pays, []);
    s.unmount();
  });
  await t.test(`${lang}: receipt distinguishes own segment and parent ride, displays only my rating 1..5`, async () => {
    reset(); const s = open(Screen); await s.settle();
    assert.ok(text(s.tree).includes("Ургаза")); assert.ok(text(s.tree).includes("Бурибай"));
    assert.ok(text(s.tree).includes(lang === "ru" ? "Рейс водителя" : "Йөрөтөүсенең сәфәре"));
    assert.ok(text(s.tree).includes(lang === "ru" ? "Участок пассажира" : "Юлсының юл өлөшө"));
    assert.ok(text(s.tree).includes(lang === "ru" ? "Твоя оценка" : "Һинең баһаң")); assert.ok(text(s.tree).includes("4")); s.unmount();
  });
  await t.test(`${lang}: open driver receipt hides both stops when the server window expires`, async () => {
    reset();
    const deadline = { monotonic: performance.now() + 60_000, wall: Date.now() + 60_000 };
    h.receipt = async id => receipt(id, { role: "driver", completedContactDeadline: deadline });
    const s = open(Screen);
    await s.settle();
    assert.ok(text(s.tree).includes("Ургаза"));
    const realNow = performance.now;
    try {
      performance.now = () => deadline.monotonic;
      for (const expire of [...timers.values()]) expire();
      await s.settle();
      assert.match(text(s.tree), /Сибай.*Акъяр/);
      assert.doesNotMatch(text(s.tree), /Ургаза|Бурибай/);
      assert.equal(h.calls.filter(call => call.kind === "receipt").length, 1);
    } finally {
      performance.now = realNow;
      s.unmount();
    }
  });
  await t.test(`${lang}: driver receipt hides stale stops after sleep even with a stopped monotonic clock`, async () => {
    reset();
    const deadline = { monotonic: performance.now() + 3_600_000, wall: Date.now() + 60_000 };
    h.receipt = async id => receipt(id, { role: "driver", completedContactDeadline: deadline });
    const s = open(Screen);
    await s.settle();
    assert.match(text(s.tree), /Ургаза.*Бурибай/);
    const wallNow = Date.now;
    try {
      Date.now = () => deadline.wall;
      for (const resume of focusListeners) resume();
      await s.settle();
      assert.doesNotMatch(text(s.tree), /Ургаза|Бурибай/);
      Date.now = wallNow;
      s.render();
      assert.doesNotMatch(text(s.tree), /Ургаза|Бурибай/, "перевод часов не открывает тот же снимок");
      assert.equal(h.calls.filter(call => call.kind === "receipt").length, 1);
    } finally {
      Date.now = wallNow;
      s.unmount();
    }
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
    const pay = open(Pay, props); pay.render(); button(pay.tree, lang === "ru" ? "Наличными" : "Наличный менән").props.onClick();
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
