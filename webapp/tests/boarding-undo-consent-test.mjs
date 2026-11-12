import assert from "node:assert/strict";
import { build } from "esbuild";
import { IDBFactory } from "fake-indexeddb";
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

const storeBundle = await build({
  stdin: {
    contents: "export * from './src/utils/offlineTripPass'; export * from './src/utils/tripPassStore';",
    resolveDir: fileURLToPath(new URL("../", import.meta.url)), loader: "ts",
  },
  bundle: true, write: false, platform: "node", format: "esm",
  define: { "import.meta.env": "{}" },
});
const moduleUrl = "data:text/javascript;base64," + Buffer.from(storeBundle.outputFiles[0].text).toString("base64");
let reloads = 0;
const reloadStore = () => import(moduleUrl + "#reload-" + ++reloads);
const owner = { userId: 7, generation: "owner-1" };
const pass = {
  bookingId: 42, rideId: 4, fromCity: "Сибай", toCity: "Акъяр", departAt: "2026-10-24T12:00:00Z",
  driverName: "QA", driverCar: "QA", driverPlate: "QA", driverPhone: "synthetic", pickup: "QA",
  pickupStop: "Ургаза", dropoffStop: "Бурибай", rideHasStops: true, price: 300, seats: 1,
};
const stamp = "2026-10-24T12:00:00.123456Z";
const consentReceipt = { status: "confirmed", boarding_undo_confirmed_id: "receipt-1", boarding_undo_onboard_at: stamp };
const view = store => store.readOfflineTrip(42);
const board = (store, at = stamp) => store.observeTripPass(owner, 42, { status: "onboard", onboard_at: at }, undefined);
async function undo(store, receipt = consentReceipt) {
  const revision = await store.readTripPassRevision(owner, 42);
  await store.observeTripPass(owner, 42, receipt, revision);
}
async function setCode(store, code) {
  await store.saveTripPassCode(owner, 42, await store.readTripPassRevision(owner, 42), code);
}
async function withPassStore(check) {
  const previous = Object.getOwnPropertyDescriptor(globalThis, "localStorage");
  const oldFactory = globalThis.indexedDB;
  const h = { generation: owner.generation, changeDuringWrite: false };
  const token = "test." + Buffer.from(JSON.stringify({ sub: "7" })).toString("base64url") + ".test";
  globalThis.localStorage = { getItem: key => key === "yuldash.session" ? h.generation : key === "yuldash.token" ? token : null };
  const factory = new IDBFactory();
  globalThis.indexedDB = {
    open(...args) {
      const request = factory.open(...args);
      request.addEventListener("success", () => {
        const db = request.result, start = db.transaction.bind(db);
        db.transaction = (...options) => {
          const tx = start(...options);
          if (h.changeDuringWrite && options[1] === "readwrite") queueMicrotask(() => { h.generation = "owner-2"; });
          return tx;
        };
      });
      return request;
    },
  };
  try {
    const store = await reloadStore();
    await store.observeTripPass(owner, 42, { status: "confirmed" }, null, pass);
    await setCode(store, "1234");
    await check(store, h);
  } finally {
    if (previous) Object.defineProperty(globalThis, "localStorage", previous); else delete globalThis.localStorage;
    if (oldFactory) globalThis.indexedDB = oldFactory; else delete globalThis.indexedDB;
  }
}

await t.test("хранилище снимает только исходный флаг посадки, код приходит заново, обе точки остаются", async () => withPassStore(async store => {
  await board(store);
  assert.equal((await view(store)).status, "onboard");
  await undo(store);
  assert.equal((await view(store)).pass.boardingCode, "", "used code is never restored from the old snapshot");
  await setCode(store, "5678");
  const { pass } = await view(store);
  assert.equal(pass.boardingCode, "5678");
  assert.equal(pass.pickupStop, "Ургаза");
  assert.equal(pass.dropoffStop, "Бурибай");
}));
await t.test("свежая квитанция после перезагрузки находит исходную посадку в хранилище", async () => withPassStore(async store => {
  await board(store);
  const restarted = await reloadStore();
  await undo(restarted);
  assert.equal((await view(restarted)).status, "confirmed");
}));
await t.test("чужая микросекунда, новая посадка и терминал не сбрасываются старой квитанцией", async () => withPassStore(async store => {
  await board(store);
  await undo(store, { ...consentReceipt, boarding_undo_onboard_at: "2026-10-24T12:00:00.123455Z" });
  assert.equal((await view(store)).status, "onboard");
  await board(store, "2026-10-24T12:00:00.123457Z");
  await undo(store);
  assert.equal((await view(store)).status, "onboard");
  await store.finishTripPass(owner, 42);
  await undo(store);
  assert.equal((await view(store)).status, "terminal");
  assert.equal((await view(store)).pass, null);
}));
await t.test("смена входа перед квитанцией и во время записи не открывает код другой сессии", async () => withPassStore(async (store, h) => {
  await board(store);
  const revision = await store.readTripPassRevision(owner, 42);
  h.generation = "owner-2";
  await store.observeTripPass(owner, 42, consentReceipt, revision);
  assert.equal((await view(store)).pass, null);
  h.generation = owner.generation;
  h.changeDuringWrite = true;
  await store.observeTripPass(owner, 42, consentReceipt, revision);
  h.generation = owner.generation;
  assert.equal((await view(store)).status, "onboard");
  assert.equal((await view(store)).pass.boardingCode, "");
}));
for (const reload of [false, true]) await t.test("посадка B замещает A, свежий код после квитанции B (reload=" + reload + ")", async () => withPassStore(async store => {
  const newer = "2026-10-24T12:00:00.123457Z";
  await board(store);
  await board(store, newer);
  if (reload) store = await reloadStore();
  await undo(store, { ...consentReceipt, boarding_undo_confirmed_id: "receipt-B", boarding_undo_onboard_at: newer });
  assert.equal((await view(store)).status, "confirmed");
  await setCode(store, "5678");
  const { pass } = await view(store);
  assert.equal(pass.boardingCode, "5678");
  assert.equal(pass.pickupStop, "Ургаза");
  assert.equal(pass.dropoffStop, "Бурибай");
}));
await t.test("дубликат B в другом UTC формате не мешает квитанции B, новая C остаётся закрытой для B", async () => withPassStore(async store => {
  const newer = "2026-10-24T12:00:00.123457Z", newest = "2026-10-24T12:00:00.123458Z";
  await board(store);
  await board(store, newer);
  await board(store, newer.replace("Z", "+00:00"));
  const receiptB = { ...consentReceipt, boarding_undo_confirmed_id: "receipt-B", boarding_undo_onboard_at: newer };
  await undo(store, receiptB);
  assert.equal((await view(store)).status, "confirmed");
  await board(store, newest);
  store = await reloadStore();
  await undo(store, receiptB);
  assert.equal((await view(store)).status, "onboard");
  await undo(store, { ...receiptB, boarding_undo_confirmed_id: "receipt-C", boarding_undo_onboard_at: newest });
  assert.equal((await view(store)).status, "confirmed");
}));
for (const invalid of [undefined, "invalid-epoch"]) await t.test("квитанция без валидной эпохи не открывает последнюю посадку: " + String(invalid), async () => withPassStore(async store => {
  await board(store);
  await undo(store, { ...consentReceipt, boarding_undo_onboard_at: invalid });
  assert.equal((await view(store)).status, "onboard");
  assert.equal((await view(store)).pass.boardingCode, "");
}));
for (const malformed of ["unknown-epoch", "%ZZ", ""]) await t.test("битая сохранённая эпоха оставляет код закрытым: " + malformed, async () => withPassStore(async store => {
  await board(store);
  await store.editTripRecords(owner, () => true, rows => {
    const trips = new Map(rows);
    trips.set(42, { ...trips.get(42), onboardAt: malformed });
    return { result: true, trips };
  }, false);
  const restarted = await reloadStore();
  await undo(restarted);
  assert.equal((await view(restarted)).status, "onboard");
  assert.equal((await view(restarted)).pass.boardingCode, "");
}));

t.done();
