import assert from "node:assert/strict";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";
import { bundleComponent, button, checker, langMock, mount, text } from "./shallow-harness.mjs";

const t = checker();
const CLIENT = `export class ApiError extends Error{}; export const getSessionGeneration=()=>globalThis.__buc.owner;`;
const API = `export const respondBoardingUndo=async(...args)=>{const h=globalThis.__buc;h.calls.push(args);if(h.error)throw Error("offline");return h.held??h.result;};`;
const deferred = () => { let resolve; const promise = new Promise(done => { resolve = done; }); return { promise, resolve }; };
async function pending(lang = "ru", overrides = {}) {
  const h = globalThis.__buc = { owner: "owner-1", calls: [], result: { status: "confirmed", boarding_undo_confirmed_id: "req-1", boarding_undo_onboard_at: "2026-10-24T12:00:00.123456Z" }, ...overrides };
  const C = await bundleComponent("src/components/PassengerBoardingUndo.tsx", { mocks: { "/i18n/lang": langMock(lang), "/api/client": CLIENT, "/api/bookings": API } });
  const seen = [];
  const view = mount(C, { bookingId: 42, terms: { boarding_undo_pending: true, boarding_undo_request_id: "req-1", boarding_undo_wait_sec: 120 }, onChanged: result => seen.push(result) });
  view.render(); await view.settle(3);
  return { h, view, seen };
}
for (const lang of ["ru", "ba"]) {
  const L = (ru, ba) => lang === "ru" ? ru : ba;
  await t.test(`${lang}: пассажир подтверждает конкретный запрос, отказ сохраняет посадку`, async () => {
    for (const confirm of [true, false]) {
      const h = globalThis.__buc = { owner: "owner-1", calls: [], result: { status: confirm ? "confirmed" : "onboard" } };
      const C = await bundleComponent("src/components/PassengerBoardingUndo.tsx", { mocks: { "/i18n/lang": langMock(lang), "/api/client": CLIENT, "/api/bookings": API } });
      const seen = [];
      const view = mount(C, { bookingId: 42, terms: { boarding_undo_pending: true, boarding_undo_request_id: "req-1", boarding_undo_wait_sec: 120 }, onChanged: r => seen.push(r) });
      view.render(); await view.settle(3);
      await button(view.tree, confirm ? L("Я ещё не сел", "Мин әле ултырманым") : L("Я в машине", "Мин машиналамын")).props.onClick();
      await view.settle(3);
      assert.deepEqual(h.calls, [[42, "req-1", confirm, "owner-1"]]);
      assert.deepEqual(seen, [h.result]);
      view.unmount();
    }
  });
}
await t.test("смена входа не отправляет ответ от нового пользователя", async () => {
  const h = globalThis.__buc = { owner: "owner-1", calls: [] };
  const C = await bundleComponent("src/components/PassengerBoardingUndo.tsx", { mocks: { "/i18n/lang": langMock("ru"), "/api/client": CLIENT, "/api/bookings": API } });
  const view = mount(C, { bookingId: 42, terms: { boarding_undo_pending: true, boarding_undo_request_id: "req-1", boarding_undo_wait_sec: 120 }, onChanged: () => assert.fail("stale update") });
  view.render(); await view.settle(3); h.owner = "owner-2";
  await button(view.tree, "Я ещё не сел").props.onClick(); await view.settle(3);
  assert.deepEqual(h.calls, []); view.unmount();
});
await t.test("запоздавшая квитанция не откатывает новую посадку, микросекунды сохранены", async () => {
  const matches = await bundleComponent("src/utils/boardingUndo.ts", { exportName: "isConfirmedBoardingUndo" });
  const receipt = { boarding_undo_confirmed_id: "receipt", boarding_undo_onboard_at: "2026-10-24T12:00:00.000000" };
  assert.equal(matches({ onboard_at: "2026-10-24T12:00:00Z" }, receipt), true);
  assert.equal(matches({ onboard_at: "2026-10-24T12:00:00.000001Z" }, receipt), false);
  assert.equal(matches({}, receipt), false);
  assert.equal(matches({ onboard_at: "2026-10-24T12:00:00Z" }, {}), false);
  const exact = { boarding_undo_confirmed_id: "receipt", boarding_undo_onboard_at: "2026-10-24T12:00:00.123456+00:00" };
  assert.equal(matches({ onboard_at: "2026-10-24T12:00:00.123456Z" }, exact), true);
  assert.equal(matches({ onboard_at: "2026-10-24T12:00:00.123457Z" }, exact), false);
  assert.equal(matches({ onboard_at: "bad date" }, exact), false);
  const same = await bundleComponent("src/utils/boardingUndo.ts", { exportName: "sameBoardingTimestamp" });
  assert.equal(same(null, null), false); assert.equal(same("bad date", "bad date"), false);
  assert.equal(same("2026-02-30T12:00:00Z", "2026-02-30T12:00:00Z"), false);
  assert.equal(same("2026-10-24T12:00:00.123456Z", "2026-10-24T12:00:00.123456+00:00"), true);
  assert.equal(same("2026-10-24T12:00:00.123456Z", "2026-10-24T12:00:00.123457Z"), false);
});
await t.test('порядок эпох сохраняет микросекунды, а прежняя квитанция не стирает последнюю', async () => {
  const compare = await bundleComponent('src/utils/boardingUndo.ts', { exportName: 'compareBoardingTimestamps' });
  const stale = await bundleComponent('src/utils/boardingUndo.ts', { exportName: 'isStaleBoardingAfterUndo' });
  const a = '2026-10-24T12:00:00.123456Z', b = '2026-10-24T12:00:00.123457Z';
  assert.equal(compare(a, b), -1); assert.equal(compare(b, a), 1); assert.equal(compare(a, a.replace('Z', '+00:00')), 0);
  assert.equal(compare(null, b), null); assert.equal(compare('2026-02-30T12:00:00Z', b), null);
  const current = { status: 'confirmed', boarding_undo_confirmed_id: 'receipt-B', boarding_undo_onboard_at: b };
  assert.equal(stale(current, { status: 'confirmed', boarding_undo_confirmed_id: 'receipt-A', boarding_undo_onboard_at: a }), true);
  assert.equal(stale(current, { status: 'confirmed' }), true); assert.equal(stale(current, current), false);
});
for (const lang of ["ru", "ba"]) await t.test(`${lang}: ошибка ответа видна, ручной повтор отвечает на тот же запрос`, async () => {
  const { h, view, seen } = await pending(lang, { error: true });
  const yes = lang === "ru" ? "Я ещё не сел" : "Мин әле ултырманым";
  button(view.tree, yes).props.onClick(); await view.settle(5);
  assert.deepEqual(seen, []);
  assert.ok(text(view.tree).includes(lang === "ru" ? "Не получилось ответить. Проверь сеть и повтори." : "Яуап биреп булманы. Селтәрҙе тикшер ҙә ҡабатла."));
  assert.equal(button(view.tree, yes).props.disabled, false);
  h.error = false; button(view.tree, yes).props.onClick(); await view.settle(5);
  assert.deepEqual(h.calls, [[42, "req-1", true, "owner-1"], [42, "req-1", true, "owner-1"]]);
  assert.deepEqual(seen, [h.result]); view.unmount();
});
await t.test("истёкший запрос не показывает согласие и не посылает ответ", async () => {
  const { h, view } = await pending();
  view.props = { ...view.props, terms: { ...view.props.terms, boarding_undo_wait_sec: 0 } }; view.render(); await view.settle(3);
  assert.equal(view.tree, null); assert.deepEqual(h.calls, []); view.unmount();
});
for (const boundary of ["owner", "booking", "request", "unmount"]) await t.test(`задержанный ответ после ${boundary} не вызывает прежний обработчик`, async () => {
  const held = deferred(); const { h, view, seen } = await pending("ru", { held: held.promise });
  button(view.tree, "Я ещё не сел").props.onClick(); await view.settle(3);
  assert.deepEqual(h.calls, [[42, "req-1", true, "owner-1"]]); assert.deepEqual(seen, []);
  if (boundary === "owner") h.owner = "owner-2";
  else if (boundary === "booking") { view.props = { ...view.props, bookingId: 43 }; view.render(); await view.settle(3); }
  else if (boundary === "request") { view.props = { ...view.props, terms: { ...view.props.terms, boarding_undo_request_id: "req-2" } }; view.render(); await view.settle(3); }
  else view.unmount();
  held.resolve(h.result); await view.settle(5);
  assert.deepEqual(seen, []); view.unmount();
});

await t.test("два клика до перерисовки отправляют один ответ", async () => {
  const held = deferred(); const { h, view, seen } = await pending("ru", { held: held.promise });
  const click = button(view.tree, "Я ещё не сел").props.onClick;
  click(); click(); await view.settle(3);
  assert.equal(h.calls.length, 1);
  held.resolve(h.result); await view.settle(5);
  assert.deepEqual(seen, [h.result]); view.unmount();
});

async function withPassStore(check) {
  const previous = Object.getOwnPropertyDescriptor(globalThis, "localStorage"), values = new Map();
  const h = { generation: "owner-1", hook: null };
  Object.defineProperty(globalThis, "localStorage", { configurable: true, value: {
    getItem: key => values.get(key) ?? null,
    setItem: (key, value) => { values.set(key, String(value)); h.hook?.(key); },
    removeItem: key => values.delete(key), key: index => [...values.keys()][index] ?? null,
    get length() { return values.size; },
  } });
  try {
    const result = await build({ entryPoints: [fileURLToPath(new URL("../src/utils/tripPass.ts", import.meta.url))], bundle: true, write: false, platform: "node", format: "esm" });
    const moduleUrl = "data:text/javascript;base64," + Buffer.from(result.outputFiles[0].text).toString("base64");
    const store = await import(moduleUrl); let reload = 0;
    h.reload = () => import(moduleUrl + '#reload-' + ++reload);
    const pass = { bookingId: 42, ownerId: 7, sessionGeneration: h.generation, fromCity: "Сибай", toCity: "Акъяр", departAt: "2026-10-24T12:00:00Z", driverName: "QA", driverCar: "QA", driverPlate: "QA", driverPhone: "synthetic", boardingCode: "1234", pickup: "QA", pickupStop: "Ургаза", dropoffStop: "Бурибай", rideHasStops: true, price: 300, seats: 1 };
    store.saveTripPass(pass);
    await check(store, h, pass);
  } finally {
    if (previous) Object.defineProperty(globalThis, "localStorage", previous); else delete globalThis.localStorage;
  }
}
const stamp = "2026-10-24T12:00:00.123456Z";
const currentBoarding = { status: "onboard", onboard_at: stamp };
const consentReceipt = { status: "confirmed", onboard_at: null, boarding_undo_confirmed_id: "receipt-1", boarding_undo_onboard_at: stamp };
await t.test("хранилище снимает только исходный флаг посадки, код приходит заново, обе точки остаются", async () => withPassStore((store, h, pass) => {
  store.clearTripPassBoardingCode(42, h.generation, stamp);
  assert.equal(store.tripPassStage(42, h.generation), "onboard");
  assert.equal(store.restoreConfirmedTripPass(42, h.generation, currentBoarding, consentReceipt, () => h.generation), true);
  assert.equal(store.loadTripPass(42).boardingCode, "", "used code is never restored from the old snapshot");
  store.saveTripPass({ ...pass, boardingCode: "5678" });
  assert.equal(store.loadTripPass(42).boardingCode, "5678");
  assert.equal(store.loadTripPass(42).pickupStop, "Ургаза"); assert.equal(store.loadTripPass(42).dropoffStop, "Бурибай");
}));
await t.test("свежая квитанция после перезагрузки находит исходную посадку в хранилище", async () => withPassStore((store, h) => {
  store.clearTripPassBoardingCode(42, h.generation, stamp);
  assert.equal(store.restoreConfirmedTripPass(42, h.generation, null, consentReceipt, () => h.generation), true);
  assert.equal(store.tripPassStage(42, h.generation), "confirmed");
}));
await t.test("чужая микросекунда, новая посадка и терминал не сбрасываются старой квитанцией", async () => withPassStore((store, h) => {
  store.clearTripPassBoardingCode(42, h.generation, stamp);
  assert.equal(store.restoreConfirmedTripPass(42, h.generation, currentBoarding, { ...consentReceipt, boarding_undo_onboard_at: "2026-10-24T12:00:00.123455Z" }, () => h.generation), false);
  const newer = "2026-10-24T12:00:00.123457Z";
  store.clearTripPassBoardingCode(42, h.generation, newer);
  assert.equal(store.restoreConfirmedTripPass(42, h.generation, currentBoarding, consentReceipt, () => h.generation), false);
  assert.equal(store.tripPassStage(42, h.generation), "onboard");
  store.dropTripPass(42, h.generation);
  assert.equal(store.restoreConfirmedTripPass(42, h.generation, null, consentReceipt, () => h.generation), false);
  assert.equal(store.tripPassStage(42, h.generation), "terminal"); assert.equal(store.loadTripPass(42), null);
}));
await t.test("смена входа перед квитанцией и во время записи не открывает код другой сессии", async () => withPassStore((store, h) => {
  const owner = h.generation; store.clearTripPassBoardingCode(42, owner, stamp); h.generation = "owner-2";
  assert.equal(store.restoreConfirmedTripPass(42, owner, currentBoarding, consentReceipt, () => h.generation), false);
  h.generation = owner; h.hook = () => { h.hook = null; h.generation = "owner-2"; };
  assert.equal(store.restoreConfirmedTripPass(42, owner, currentBoarding, consentReceipt, () => h.generation), false);
  assert.equal(store.loadTripPass(42).boardingCode, "");
}));
for (const reload of [false, true]) await t.test(`посадка B замещает пропущенную квитанцию A; квитанция B возвращает код (reload=${reload})`, async () => withPassStore(async (store, h, pass) => {
  const newer = '2026-10-24T12:00:00.123457Z';
  store.clearTripPassBoardingCode(42, h.generation, stamp); store.clearTripPassBoardingCode(42, h.generation, newer);
  if (reload) store = await h.reload();
  const receiptB = { ...consentReceipt, boarding_undo_confirmed_id: 'receipt-B', boarding_undo_onboard_at: newer };
  assert.equal(store.restoreConfirmedTripPass(42, h.generation, reload ? null : { status: 'onboard', onboard_at: newer }, receiptB, () => h.generation), true);
  assert.equal(store.tripPassStage(42, h.generation), 'confirmed'); store.saveTripPass({ ...pass, boardingCode: '5678' });
  assert.equal(store.loadTripPass(42).boardingCode, '5678'); assert.equal(store.loadTripPass(42).pickupStop, 'Ургаза'); assert.equal(store.loadTripPass(42).dropoffStop, 'Бурибай');
}));
await t.test('дубликат B в другом UTC формате не мешает квитанции B, но новая C остаётся закрытой для B', async () => withPassStore(async (store, h) => {
  const newer = '2026-10-24T12:00:00.123457Z', newest = '2026-10-24T12:00:00.123458Z';
  store.clearTripPassBoardingCode(42, h.generation, stamp); store.clearTripPassBoardingCode(42, h.generation, newer); store.clearTripPassBoardingCode(42, h.generation, newer.replace('Z', '+00:00'));
  const receiptB = { ...consentReceipt, boarding_undo_confirmed_id: 'receipt-B', boarding_undo_onboard_at: newer };
  assert.equal(store.restoreConfirmedTripPass(42, h.generation, null, receiptB, () => h.generation), true);
  store.clearTripPassBoardingCode(42, h.generation, newest); store = await h.reload();
  assert.equal(store.restoreConfirmedTripPass(42, h.generation, null, receiptB, () => h.generation), false); assert.equal(store.tripPassStage(42, h.generation), 'onboard');
  assert.equal(store.restoreConfirmedTripPass(42, h.generation, null, { ...receiptB, boarding_undo_confirmed_id: 'receipt-C', boarding_undo_onboard_at: newest }, () => h.generation), true);
}));
for (const invalid of [undefined, 'invalid-epoch']) await t.test('квитанция без валидной эпохи не открывает последнюю посадку: ' + String(invalid), async () => withPassStore((store, h) => {
  store.clearTripPassBoardingCode(42, h.generation, stamp);
  assert.equal(store.restoreConfirmedTripPass(42, h.generation, currentBoarding, { ...consentReceipt, boarding_undo_onboard_at: invalid }, () => h.generation), false);
  assert.equal(store.tripPassStage(42, h.generation), 'onboard'); assert.equal(store.loadTripPass(42).boardingCode, '');
}));
for (const invalidMarker of ['unknown-epoch', '%ZZ', '']) await t.test('битый сохранённый маркер оставляет код закрытым: ' + invalidMarker, async () => withPassStore(async (store, h) => {
  const newer = '2026-10-24T12:00:00.123457Z'; store.clearTripPassBoardingCode(42, h.generation, newer);
  localStorage.setItem(`yuldash.tripPass.42.onboard.${h.generation}.${invalidMarker}`, '1'); store = await h.reload();
  const receiptB = { ...consentReceipt, boarding_undo_confirmed_id: 'receipt-B', boarding_undo_onboard_at: newer };
  assert.equal(store.restoreConfirmedTripPass(42, h.generation, null, receiptB, () => h.generation), false);
  assert.equal(store.tripPassStage(42, h.generation), 'onboard'); assert.equal(store.loadTripPass(42).boardingCode, '');
}));
t.done();
