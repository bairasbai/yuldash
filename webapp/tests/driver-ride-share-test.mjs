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
import { ROOT, button, checker, ofType } from "./shallow-harness.mjs";
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
