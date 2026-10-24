// ================================================================
//  Д2 (решение Александра 24.10.2026): водитель везёт нескольких и нажал «Завершить» по одному — точка
//  с сайта идёт дальше, пока у рейса есть живые брони; гаснет на последней. Зеркало Android
//  (TripLocationRideTest, DriverRideGpsTest), условия проверки плана P1-1…P1-7:
//   • выбор несущей — общий с приложением набор случаев (android/…/driver-ride-carrier-cases.json);
//   • без шага водителя на этом устройстве — как раньше: канал закрылся — конец, список не спрашиваем;
//   • режим рейса: закрытая несущая → следующая живая бронь того же рейса; последняя — конец;
//   • устаревшая страница не возвращает точку в закрытый канал; «ended» без причины «trip» — без запросов;
//   • вышел из аккаунта, пока ждали список, — ни новой отправки, ни повторов;
//   • бронь в начале маршрута — только до срока «Не пришёл»; рейс — не дольше 6 часов;
//   • держат отправку только страница поездки и «Едут с тобой»; ушли с обеих — конец через паузу;
//   • после «Завершить» с оставшимися пассажирами — в кабинет к «Едут с тобой»; последний — оценка здесь.
// ================================================================
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { build } from "esbuild";
import { ROOT, bundleComponent, button, checker, langMock, mount, ofType } from "./shallow-harness.mjs";
import { openActiveTrip } from "./active-trip-fixture.mjs";

const t = checker();

async function load(rel) {
  const lib = await build({ entryPoints: [ROOT + rel], bundle: true, write: false, format: "esm", platform: "node", logLevel: "silent" });
  return import("data:text/javascript;base64," + Buffer.from(lib.outputFiles[0].text).toString("base64"));
}
const C = await load("src/utils/rideCarrier.ts");
const R = await load("src/utils/rideLocationShare.ts");

// ---------------------------------------------------------------- общий набор с приложением
const cases = JSON.parse(readFileSync(ROOT + "../android/app/src/test/resources/driver-ride-carrier-cases.json", "utf8")).cases;

await t.test("общий с Android набор случаев на месте", () => {
  assert.ok(cases.length >= 10, "набор пуст — правило выбора не сторожится");
});

for (const c of cases) {
  await t.test(`выбор несущей: ${c.name}`, () => {
    const pick = C.pickRideCarrier(c.rows, c.anchor, c.closed);
    assert.equal(pick.rideId, c.expect.ride_id, "рейс");
    assert.deepEqual(pick.live, c.expect.live, "живые брони для «Едут с тобой»");
    assert.equal(pick.carrier, c.expect.carrier, "несущая");
    assert.equal(pick.carrierTtlSec, c.expect.carrier_ttl_sec, "срок несущей");
  });
}

// ---------------------------------------------------------------- подставной браузер и сервер
function rig() {
  const r = { starts: [], fetches: 0, rows: [], session: "A", token: true, now: 0, timers: [], gate: null, fail: null };
  r.share = R.createRideShare({
    start(id, hooks) {
      const h = { id, hooks, stopped: false, retried: 0, stop() { this.stopped = true; }, retry() { this.retried++; } };
      r.starts.push(h);
      return h;
    },
    async fetchRows() {
      r.fetches++;
      if (r.gate) await r.gate;
      if (r.fail) throw r.fail;
      return JSON.parse(JSON.stringify(r.rows));
    },
    session: () => r.session,
    hasToken: () => r.token,
    now: () => r.now,
    setTimer(fn, ms) { const tm = { fn, ms, done: false }; r.timers.push(tm); return tm; },
    clearTimer(tm) { if (tm) tm.done = true; },
  });
  /** Выполнить таймеры не длиннее `upTo` мс (по умолчанию — все, кроме долгих повторов). */
  r.fire = (upTo = R.HOLD_GRACE_MS) => { for (const tm of [...r.timers]) if (!tm.done && tm.ms <= upTo) { tm.done = true; tm.fn(); } };
  r.last = () => r.starts.at(-1);
  return r;
}
const row = (id, status, o = {}) => ({ booking_id: id, ride_id: 7, status, pickup_stop: "", no_show_wait_sec: 0, ...o });
const settle = async () => { for (let i = 0; i < 12; i++) await new Promise((res) => setImmediate(res)); };

/** Страница поездки 101 открыта, «Сел» по 101 на этом устройстве включил режим рейса вместе со 102. */
async function rideOf101and102() {
  const r = rig();
  r.release101 = r.share.open(101);
  r.rows = [row(101, "onboard"), row(102, "onboard")];
  await r.share.action(101, false);
  assert.equal(r.share.snapshot().ride, true, "режим рейса не включился");
  assert.equal(r.fetches, 1);
  return r;
}

// ---------------------------------------------------------------- без режима рейса — как раньше
await t.test("без шага водителя: канал закрылся — отправка гаснет, список не спрашиваем (планшет дома)", async () => {
  const r = rig();
  r.share.open(101);
  r.last().hooks.onEnd("trip");
  await settle();
  assert.equal(r.fetches, 0);
  assert.equal(r.share.snapshot().bookingId, null);
  assert.equal(r.starts.length, 1);
});

await t.test("без режима рейса ушёл со страницы поездки — после короткой паузы отправка гаснет", async () => {
  const r = rig();
  const release = r.share.open(101);
  release();
  assert.equal(r.last().stopped, false, "пауза на смену маршрута — отправка не мигает");
  r.fire();
  assert.equal(r.last().stopped, true);
  assert.equal(r.share.snapshot().bookingId, null);
  const grace = r.timers.find((tm) => tm.ms === R.HOLD_GRACE_MS);
  assert.ok(grace && grace.ms <= 3000, "пауза не дольше нескольких секунд");
});

await t.test("кабинет сам отправку не начинает и без режима рейса её не держит", () => {
  const r = rig();
  assert.equal(r.share.keep([row(101, "onboard")]), null);
  r.share.open(101);
  assert.equal(r.share.keep([row(101, "onboard")]), null, "без шага водителя кабинет не держит точку");
  assert.equal(r.starts.length, 1);
});

await t.test("шаг по брони другого рейса режим этого рейса не включает", async () => {
  const r = rig();
  r.share.open(101);
  r.rows = [row(101, "onboard"), row(201, "onboard", { ride_id: 9 })];
  await r.share.action(201, false);
  assert.equal(r.share.snapshot().ride, false);
});

// ---------------------------------------------------------------- режим рейса
await t.test("закрылась несущая — точка уходит в канал следующей живой брони рейса", async () => {
  const r = await rideOf101and102();
  r.rows = [row(101, "done"), row(102, "onboard")];
  r.last().hooks.onEnd("trip");
  await settle();
  assert.equal(r.last().id, 102);
  assert.equal(r.starts[0].stopped, true, "старый канал закрыт");
  assert.equal(r.share.snapshot().bookingId, 102);
});

await t.test("«Завершить» на этом устройстве — сразу на следующую, не дожидаясь сервера", async () => {
  const r = await rideOf101and102();
  r.rows = [row(101, "done"), row(102, "onboard")];
  await r.share.action(101, true);
  await settle();
  assert.equal(r.last().id, 102);
});

await t.test("закрыта последняя живая бронь — отправка гаснет", async () => {
  const r = rig();
  r.share.open(101);
  r.rows = [row(101, "onboard")];
  await r.share.action(101, false);
  r.rows = [row(101, "done")];
  await r.share.action(101, true);
  await settle();
  assert.equal(r.share.snapshot().bookingId, null);
  assert.equal(r.share.snapshot().ride, false);
  assert.equal(r.last().stopped, true);
});

await t.test("«Завершить» без режима рейса: канал закрылся раньше ответа — не гаснем, ждём список", async () => {
  const r = rig();
  r.share.open(101);
  let open;
  r.gate = new Promise((res) => (open = res));
  r.rows = [row(101, "done"), row(102, "onboard")];
  const pending = r.share.action(101, true);
  r.last().hooks.onEnd("trip"); // сервер закрыл канал 101 по первому же кадру
  open();
  await pending;
  await settle();
  assert.equal(r.last().id, 102, "остальные ещё едут — точка не должна погаснуть");
});

await t.test("устаревшая страница закрытой брони не возвращает точку в мёртвый канал", async () => {
  const r = await rideOf101and102();
  r.rows = [row(101, "done"), row(102, "onboard")];
  await r.share.action(101, true);
  await settle();
  const before = r.starts.length;
  r.share.open(101);
  assert.equal(r.starts.length, before);
  assert.equal(r.share.snapshot().bookingId, 102);
});

await t.test("«ended» без причины «trip» (вход отозван) — конец без запросов", async () => {
  const r = await rideOf101and102();
  r.last().hooks.onEnd("auth");
  await settle();
  assert.equal(r.fetches, 1, "после отзыва входа список не спрашиваем");
  assert.equal(r.share.snapshot().bookingId, null);
});

await t.test("вышел из аккаунта, пока ждали список, — ни новой отправки, ни повторов", async () => {
  const r = await rideOf101and102();
  let open;
  r.gate = new Promise((res) => (open = res));
  r.rows = [row(101, "done"), row(102, "onboard")];
  r.last().hooks.onEnd("trip");
  await settle();
  r.session = "B";
  r.token = false;
  open();
  await settle();
  assert.equal(r.starts.length, 1, "ответ после выхода открыл канал следующей брони");
  assert.equal(r.share.snapshot().bookingId, null);
  assert.equal(r.timers.filter((tm) => !tm.done && tm.ms > R.HOLD_GRACE_MS).length, 0, "назначен повтор после выхода");
});

await t.test("отказ сервера в списке — конец; нет сети — повтор, а не вечный цикл", async () => {
  const r = await rideOf101and102();
  r.fail = Object.assign(new Error("offline"), { status: 0 });
  r.last().hooks.onEnd("trip");
  await settle();
  const retry = r.timers.find((tm) => !tm.done && tm.ms > R.HOLD_GRACE_MS);
  assert.ok(retry && retry.ms <= 30_000, "нет сети — повтор с паузой");
  r.fail = Object.assign(new Error("forbidden"), { status: 403 });
  retry.done = true;
  retry.fn();
  await settle();
  assert.equal(r.share.snapshot().bookingId, null, "отказ сервера — гаснем");
});

await t.test("начало маршрута — только до срока «Не пришёл»: после срока кадр не уходит, несущей нет — конец", async () => {
  const r = rig();
  r.share.open(101);
  r.rows = [row(101, "onboard"), row(102, "confirmed", { no_show_wait_sec: 120 })];
  await r.share.action(101, false);
  r.rows = [row(101, "done"), row(102, "confirmed", { no_show_wait_sec: 120 })];
  await r.share.action(101, true);
  await settle();
  assert.equal(r.last().id, 102);
  assert.equal(r.last().hooks.allowSend(), true);
  r.now += 121_000;
  r.rows = [row(102, "confirmed", { no_show_wait_sec: 0 })];
  assert.equal(r.last().hooks.allowSend(), false, "после срока точка ушла бы не севшей пассажирке");
  r.fire(0);
  await settle();
  assert.equal(r.share.snapshot().bookingId, null);
});

await t.test("рейс — не дольше 6 часов от начала режима", async () => {
  const r = await rideOf101and102();
  r.now += 3600_000;
  r.share.open(102); // открыл поездку второго пассажира — срок не продлевается
  r.now += 5 * 3600_000 + 1000;
  assert.equal(r.last().hooks.allowSend(), false);
  r.fire(0);
  assert.equal(r.share.snapshot().bookingId, null);
});

await t.test("«Едут с тобой» держит отправку рейса после ухода со страницы поездки, отпустил — конец", async () => {
  const r = await rideOf101and102();
  const releaseCabinet = r.share.keep([row(101, "onboard"), row(102, "onboard")]);
  assert.equal(typeof releaseCabinet, "function");
  r.release101();
  r.fire();
  assert.equal(r.last().stopped, false, "кабинет держит — точка идёт");
  assert.equal(r.share.keep([row(5, "onboard", { ride_id: 3 })]), null, "в списке нет брони этого рейса — держать нечего");
  releaseCabinet();
  r.fire();
  assert.equal(r.last().stopped, true);
  assert.equal(r.share.snapshot().bookingId, null);
});

await t.test("переход «Завершить» → кабинет на медленной сети: точку держим, пока кабинет не возьмёт (приёмка, P1-Б)", async () => {
  const r = await rideOf101and102();
  r.share.handoff(); // «Завершить» ушло
  r.rows = [row(101, "done"), row(102, "onboard")];
  await r.share.action(101, true);
  await settle();
  r.release101(); // страница отпустила точку (статус «done»)
  r.fire(); // прошла пауза на смену маршрута, а кабинет ещё грузится
  assert.equal(r.last().stopped, false, "точка погасла, пока кабинет грузил свои списки");
  assert.equal(r.last().id, 102);
  const releaseCabinet = r.share.keep([row(101, "done"), row(102, "onboard")]);
  assert.equal(typeof releaseCabinet, "function");
  const handoffTimer = r.timers.find((tm) => tm.ms === R.HANDOFF_MS);
  assert.ok(handoffTimer && handoffTimer.done, "кабинет взял точку — переходная держалка снята");
  releaseCabinet();
  r.fire();
  assert.equal(r.share.snapshot().bookingId, null);
});

await t.test("кабинет так и не взял точку — переход держит её не дольше HANDOFF_MS", async () => {
  const r = await rideOf101and102();
  r.share.handoff();
  r.release101();
  r.fire(); // пауза на смену маршрута — держит переход
  assert.equal(r.last().stopped, false);
  assert.ok(R.HANDOFF_MS <= 20_000, "переход держит точку слишком долго");
  r.fire(R.HANDOFF_MS); // срок перехода
  r.fire();
  assert.equal(r.last().stopped, true);
  assert.equal(r.share.snapshot().bookingId, null);
});

await t.test("«Завершить» первым шагом на сайте: ответ ещё в пути, страница отпустила — точка не гаснет", async () => {
  const r = rig();
  const release = r.share.open(101);
  let open;
  r.gate = new Promise((res) => (open = res));
  r.rows = [row(101, "done"), row(102, "onboard")];
  r.share.handoff();
  const pending = r.share.action(101, true);
  release();
  r.fire();
  open();
  await pending;
  await settle();
  assert.equal(r.last().id, 102, "второй пассажир потерял машину");
  assert.equal(r.share.snapshot().ride, true);
});

// Вторая приёмка Д2, P1: сервер записал «done» и закрыл канал брони раньше, чем ответил на «Завершить».
await t.test("канал закрылся раньше ответа на «Завершить» — точка не гаснет и уходит следующей брони", async () => {
  const r = rig();
  r.share.open(101);
  r.share.step(101, true, "start"); // «Завершить» ушло, ответа ещё нет
  r.last().hooks.onEnd("trip"); // сервер уже закрыл канал 101
  assert.equal(r.share.snapshot().bookingId, 101, "отправка погасла до ответа — второй пассажир теряет машину");
  r.rows = [row(101, "done"), row(102, "onboard")];
  await r.share.action(101, true); // ответ пришёл
  await settle();
  assert.equal(r.last().id, 102);
});

await t.test("сервер отверг «Завершить», а канал уже закрыт — ждать нечего, отправка гаснет", async () => {
  const r = rig();
  r.share.open(101);
  r.share.step(101, true, "start");
  r.last().hooks.onEnd("trip");
  r.share.step(101, true, "fail");
  assert.equal(r.share.snapshot().bookingId, null);
  assert.equal(r.fetches, 0);
});

await t.test("ответа на «Завершить» так и нет — ждём не дольше PENDING_STEP_MS", async () => {
  const r = rig();
  r.share.open(101);
  r.share.step(101, true, "start");
  r.last().hooks.onEnd("trip");
  assert.ok(R.PENDING_STEP_MS <= 90_000);
  r.fire(R.PENDING_STEP_MS);
  assert.equal(r.share.snapshot().bookingId, null);
});

await t.test("нет сети для списка после «Завершить» — повтор, а не погасшая точка", async () => {
  const r = rig();
  r.share.open(101);
  r.share.step(101, true, "start");
  r.last().hooks.onEnd("trip");
  r.fail = Object.assign(new Error("offline"), { status: 0 });
  r.rows = [row(101, "done"), row(102, "onboard")];
  await r.share.action(101, true);
  assert.equal(r.share.snapshot().bookingId, 101, "сеть мигнула — и точка погасла при живом рейсе");
  r.fail = null;
  const retry = r.timers.find((tm) => !tm.done && tm.ms > R.HOLD_GRACE_MS && tm.ms < R.PENDING_STEP_MS);
  assert.ok(retry, "повтор не назначен");
  retry.done = true;
  retry.fn();
  await settle();
  assert.equal(r.last().id, 102);
});

await t.test("шаг водителя: «ушёл» до запроса, «принят» после, «отвергнут» — только на отказ сервера", async () => {
  const A = await load("src/utils/driverActions.ts");
  const seen = [];
  const off = A.onDriverAction((id, closed, step) => seen.push([id, closed, step]));
  await A.driverStep(7, true, async () => { seen.push(["request"]); return 1; });
  await A.driverStep(8, true, async () => { throw Object.assign(new Error("409"), { status: 409 }); }).catch(() => {});
  await A.driverStep(9, true, async () => { throw Object.assign(new Error("offline"), { status: 0 }); }).catch(() => {});
  off();
  assert.deepEqual(seen, [
    [7, true, "start"], ["request"], [7, true, "done"],
    [8, true, "start"], [8, true, "fail"],
    [9, true, "start"],
  ]);
});

// Третья приёмка, P2: «Завершить», «Не пришёл» и «Выехал» с сайта правда идут через driverStep — сигнал «ушёл»
// раньше запроса (иначе вернулась бы гонка второго круга: сервер закрывает канал раньше ответа).
await t.test("api/driver и api/bookings шлют шаги водителя через driverStep: «ушёл» → запрос → «принят»", async () => {
  const lib = await build({
    stdin: {
      contents: `export { setDriverStatus } from "./src/api/driver.ts"; export { markNoShow, markBoarded } from "./src/api/bookings.ts";
        export { onDriverAction } from "./src/utils/driverActions.ts";`,
      resolveDir: ROOT, loader: "ts",
    },
    bundle: true, write: false, format: "esm", platform: "node", logLevel: "silent",
    plugins: [{ name: "client", setup(b) {
      b.onResolve({ filter: /\/client$/ }, () => ({ path: "client", namespace: "mock" }));
      b.onLoad({ filter: /.*/, namespace: "mock" }, () => ({ contents:
        `export const apiPost=async(p,b)=>{globalThis.__wire.push(["request",p]);if(globalThis.__wireFail)throw Object.assign(new Error("x"),{status:globalThis.__wireFail});return {ok:true};};
         export const apiGet=async()=>({});export const apiDelete=async()=>({});export const apiUpload=async()=>({});
         export class ApiError extends Error{};`, loader: "js" }));
    } }],
  });
  const W = await import("data:text/javascript;base64," + Buffer.from(lib.outputFiles[0].text).toString("base64"));
  const seen = (globalThis.__wire = []);
  const off = W.onDriverAction((id, closed, step) => seen.push([step, id, closed]));
  await W.setDriverStatus(42, "done");
  await W.markNoShow(43);
  await W.setDriverStatus(44, "departed");
  globalThis.__wireFail = 409;
  await W.setDriverStatus(45, "done").catch(() => {});
  globalThis.__wireFail = 0;
  off();
  assert.deepEqual(seen, [
    ["start", 42, true], ["request", "/bookings/42/driver-status"], ["done", 42, true],
    ["start", 43, true], ["request", "/bookings/43/no-show"], ["done", 43, true],
    ["start", 44, false], ["request", "/bookings/44/driver-status"], ["done", 44, false],
    ["start", 45, true], ["request", "/bookings/45/driver-status"], ["fail", 45, true],
  ]);
});

await t.test("кабинет берёт точку ещё раз, когда режим рейса включился позже (P1-Б)", async () => {
  const View = await bundleComponent("src/components/DriverPassengers.tsx", {
    mocks: {
      "/i18n/lang": langMock("ru"),
      "/api/bookings": `export const fetchDriverBookings=async()=>globalThis.__dpr.rows;export const markNoShow=async()=>({});export const markArriving=async()=>({});export const rateBooking=async()=>({});`,
      "/api/client": `export class ApiError extends Error{constructor(s,m){super(m);this.status=s;}}`,
      "/utils/rideShareStore": `export const rideShareKeep=()=>{const d=globalThis.__dpr;d.keeps++;return d.ride?()=>{d.released++}:null;};
        export const subscribeRideShare=(fn)=>{globalThis.__dpr.fn=fn;fn({bookingId:101,state:null,ride:false});return ()=>{};};
        export const takeRidingFocus=()=>false;`,
    },
  });
  const d = (globalThis.__dpr = { keeps: 0, released: 0, ride: false, fn: null,
    rows: [{ ...row(101, "onboard"), passenger_name: "Айгуль", passenger_rating: null, route: "Уфа → Сибай", my_stars: 0 }] });
  const h = mount(View, { onOpenTrip: () => {} });
  h.render();
  await h.settle();
  const before = d.keeps;
  assert.ok(before >= 1, "кабинет ни разу не попробовал взять точку");
  d.ride = true;
  d.fn({ bookingId: 102, state: "sharing", ride: true }); // ответ на «Завершить» пришёл — режим рейса включился
  await h.settle();
  assert.ok(d.keeps > before, "режим рейса включился, а кабинет точку так и не взял");
});

await t.test("подписка сразу получает текущее состояние", async () => {
  const r = await rideOf101and102();
  let got = null;
  const off = r.share.subscribe((s) => (got = s));
  assert.deepEqual(got, { bookingId: 101, state: null, ride: true });
  r.last().hooks.onState("sharing");
  assert.equal(got.state, "sharing");
  off();
});

// ---------------------------------------------------------------- страница поездки
await t.test("«Завершить», а в рейсе ещё едут — водитель в кабинете у «Едут с тобой»", async () => {
  const { h, at } = await openActiveTrip({ role: "driver", status: "onboard", phase: "departed" });
  at.rideRemains = true;
  button(h.tree, "Завершить поездку").props.onClick();
  await h.settle();
  ofType(h.tree, "AlertDialog").find((d) => d.props.title === "Завершить поездку?").props.confirm.onClick();
  await h.settle();
  assert.deepEqual(at.phases, [[42, "done"]]);
  assert.deepEqual(at.remainsAsked, [42]);
  assert.ok(at.nav.includes("/driver"), "водитель остался на закрытой поездке: " + JSON.stringify(at.nav));
  assert.equal(at.ridingFocus, 1, "кабинет не подведёт «Едут с тобой» к верху");
  assert.equal(at.handoffs, 1, "точку не держат на переход в кабинет — на медленной сети она погаснет");
});

await t.test("«Завершить» последнего — остаёмся: оценка и квитанция здесь", async () => {
  const { h, at } = await openActiveTrip({ role: "driver", status: "onboard", phase: "departed" });
  at.rideRemains = false;
  button(h.tree, "Завершить поездку").props.onClick();
  await h.settle();
  ofType(h.tree, "AlertDialog").find((d) => d.props.title === "Завершить поездку?").props.confirm.onClick();
  await h.settle();
  assert.deepEqual(at.phases, [[42, "done"]]);
  assert.ok(!at.nav.includes("/driver"));
});

// ---------------------------------------------------------------- приватность исходников
await t.test("координаты не уходят ни в журнал, ни в аналитику, ни в хранилище", () => {
  for (const rel of ["src/utils/rideLocationShare.ts", "src/utils/driverRideShare.ts", "src/utils/rideCarrier.ts",
    "src/utils/driverActions.ts", "src/components/RideShareNote.tsx"]) {
    const code = readFileSync(ROOT + rel, "utf8").replace(/\/\*[\s\S]*?\*\//g, "").replace(/\/\/.*$/gm, "");
    assert.ok(!/console\.|track\(|trackOnce\(|localStorage|sessionStorage/.test(code), rel + ": журнал, аналитика или хранилище");
  }
});

t.done();
