// ================================================================
//  Шторка поездки (RideSheet) уходит вниз, как шторки ленты (useSheetExit, решение лида 17.10.2026):
//  «Закрыть», тап по затемнению и Esc — шторка уезжает, затемнение гаснет, и только потом её
//  убирают; при «Уменьшить движение» — сразу. Бронь и «Открыть поездку» не ждут движения:
//  шторка закрывается в тот же миг, и человек сразу попадает на экран поездки.
//
//  Исполняем настоящие RideSheet.tsx и useSheetExit.ts; React — крошечная заглушка (fake-react.mjs),
//  сеть брони, вход, навигация и соседние компоненты подменены.
// ================================================================
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { browser, FakeEl, finish, flat, JSX, LANG, load, mount, PORTAL, portals, REACT, root, test } from "./fake-react.mjs";

const h = (globalThis.__rideExit = { booking: null, routes: [], calls: [], authed: true, own: null });

const Sheet = (
  await load("src/components/RideSheet.tsx", [
    [/^react$/, REACT],
    [/^react\/jsx-runtime$/, JSX],
    [/^react-dom$/, PORTAL],
    [/^react-router-dom$/, `export const useNavigate = () => (...args) => globalThis.__rideExit.routes.push(args);`],
    [/\/i18n\/lang$/, LANG],
    [/\/auth\/AuthProvider$/, `export const useAuth = () => ({ isAuthed: globalThis.__rideExit.authed });`],
    [/\/features$/, `const f = { rides: true, parcels: true, ready: true }; export const useFeatures = () => f, getFeatures = () => f;`],
    [/\/ComingSoon$/, `export default "ComingSoon";`],
    [/\/api\/client$/, `export class ApiError extends Error {} export const getSessionGeneration = () => "same";`],
    [/\/booking\/OwnBookingsProvider$/, `export const useOwnBooking = () => globalThis.__rideExit.own; export const useRefreshOwnBookings = () => () => {};`],
    [/\/api\/bookings$/, `export const createBooking = (body) => (globalThis.__rideExit.calls.push(body), globalThis.__rideExit.booking(body));`],
    [/\/analytics$/, `export const track = () => {};`],
    [/\/utils\/format$/, `export const formatWhen = () => "завтра, 10:00"; export const priceLabel = () => "500 ₽"; export const pluralRu = (n, one) => one;`],
    [/\/Icons$/, `export const IconArrow = "Icon", IconCheck = "Icon";`],
    [/\/BrandIcons$/, `export const YuStar = "Icon", YuWomenOnly = "Icon", YuLuggage = "Icon", YuChildSeat = "Icon";`],
    [/\/SeatOptions$/, `export const FrontSeatChoice = "FrontSeatChoice", SeatBadges = "SeatBadges";`],
  ])
).default;

const ride = { id: 42, driver_id: 7, from_city: "Уфа", to_city: "Сибай", depart_at: "2030-01-01T10:00:00", seats_left: 2, seats_total: 3, price: 500, driver_name: "Ринат", driver_rating: 5, driver_car: "Lada", category: "regular" };

/** Открытая шторка поездки; закрытия считаем. */
function open() {
  h.routes = [];
  h.calls = [];
  const closes = [];
  const s = mount(Sheet, { ride, onClose: () => closes.push(1) });
  const backdrop = () => s.tree;
  const dialog = () => flat(s.tree).find((n) => n.props?.role === "dialog");
  const button = (cls) => flat(s.tree).find((n) => n.type === "button" && (n.props.className ?? "").split(" ").includes(cls));
  const leaving = () => /\bis-leaving\b/.test(backdrop().props.className);
  const ended = () => {
    const el = dialog();
    el.props.onAnimationEnd({ target: el, currentTarget: el, animationName: "sheetDown" });
  };
  return { s, closes, backdrop, dialog, button, leaving, ended };
}
const ESC = { key: "Escape", stopPropagation() {}, preventDefault() {} };

for (const [how, act] of [
  ["«Закрыть»", (x) => x.button("btn-ghost").props.onClick()],
  ["тап по затемнению", (x) => x.backdrop().props.onClick()],
  ["Esc", (x) => x.dialog().props.onKeyDown(ESC)],
]) {
  await test(`шторка поездки: ${how} — уезжает вниз, затемнение гаснет, потом пропадает`, async () => {
    browser.reduced = false;
    const x = open();
    assert.equal(x.backdrop().props.className, "sheet-backdrop", "открытая шторка — без ухода");
    act(x);
    x.s.render();
    assert.equal(x.closes.length, 0, "не пропала в тот же кадр");
    assert.ok(x.leaving(), "включилось движение ухода (is-leaving)");
    x.ended();
    assert.equal(x.closes.length, 1, "доехала — убрана");
    act(x);
    x.ended();
    assert.equal(x.closes.length, 1, "повторное нажатие во время ухода не закрывает второй раз");
  });
}

await test("шторка поездки: «Уменьшить движение» — закрывается сразу", async () => {
  browser.reduced = true;
  try {
    const x = open();
    x.button("btn-ghost").props.onClick();
    x.s.render();
    assert.equal(x.closes.length, 1);
    assert.ok(!x.leaving());
  } finally {
    browser.reduced = false;
  }
});

await test("шторка поездки рисуется в <body>, а не в карточке, откуда её открыли: затемнение — на весь экран", async () => {
  browser.reduced = false;
  const before = portals().length;
  const x = open();
  const call = portals().at(-1);
  assert.ok(portals().length > before, "через портал");
  assert.equal(call.container, globalThis.document.body, "в <body>");
  assert.equal(call.node, x.backdrop(), "вынесено затемнение целиком, вместе со шторкой");
});

await test("бронь прошла — шторка закрыта в тот же миг и человек на экране поездки, без ожидания движения", async () => {
  browser.reduced = false;
  h.booking = async () => ({ id: 73 });
  const x = open();
  await x.button("sheet__cta").props.onClick();
  assert.equal(h.calls.length, 1, "бронь ушла на сервер");
  assert.equal(x.closes.length, 1, "закрыта сразу, а не после ухода вниз");
  assert.deepEqual(h.routes, [["/trip/73"]], "переход на поездку — сразу");
  x.s.render();
  assert.ok(!x.leaving(), "движения ухода нет — его роль играет переход");
});

await test("пока бронь в пути, затемнение и Esc шторку не трогают", async () => {
  browser.reduced = false;
  let release;
  h.booking = () => new Promise((r) => (release = () => r({ id: 74 })));
  const x = open();
  const pending = x.button("sheet__cta").props.onClick();
  x.backdrop().props.onClick();
  x.dialog().props.onKeyDown(ESC);
  x.s.render();
  assert.ok(!x.leaving(), "бронь не обрывается уходом шторки");
  assert.equal(x.closes.length, 0);
  release();
  await pending;
  assert.equal(x.closes.length, 1);
  assert.deepEqual(h.routes, [["/trip/74"]]);
});

await test("у человека уже есть бронь — «Открыть поездку» ведёт на неё сразу", async () => {
  browser.reduced = false;
  h.own = { id: 89, ride_id: 42, status: "confirmed" };
  try {
    const x = open();
    await x.button("sheet__cta").props.onClick();
    assert.equal(x.closes.length, 1);
    assert.deepEqual(h.routes, [["/trip/89"]]);
    assert.equal(h.calls.length, 0, "новой брони нет");
  } finally {
    h.own = null;
  }
});

await test("стили: уходящая шторка нажатий не принимает — «Забронировать» не сработает на полпути вниз", async () => {
  const css = readFileSync(root + "src/ui.css", "utf8");
  const rule = css.match(/\.sheet-backdrop\.is-leaving \.sheet\s*\{([^}]*)\}/);
  assert.ok(rule, "правило ухода шторки есть");
  assert.match(rule[1], /pointer-events:\s*none/);
  assert.match(rule[1], /animation:\s*sheetDown\s+var\(--motion-normal\)/, "уход — за --motion-normal, как появление");
});

// ---------------------------------------------------------------- приёмка PR154

await test("Esc, а за ним Enter на «Забронировать» — брони нет, шторка закрыта (клавиатура и читалка)", async () => {
  browser.reduced = false;
  let reject;
  h.booking = () => new Promise((_, r) => (reject = () => r(new Error("409"))));
  const x = open();
  const node = x.dialog().props.ref.current;
  const cta = node.querySelectorAll().at(-1);
  cta.focus(); // фокус на «Забронировать»
  x.dialog().props.onKeyDown(ESC);
  x.s.render();
  assert.ok(x.leaving(), "уходит");
  assert.equal(node.inert, true, "уходящая шторка inert: ни нажать, ни Tab");
  assert.equal(globalThis.document.activeElement, globalThis.document.body, "фокус снят с «Забронировать» сразу, а не через кадр");
  const pending = x.button("sheet__cta").props.onClick(); // даже если нажатие всё же дошло
  x.ended();
  assert.equal(x.closes.length, 1, "шторка убрана");
  assert.equal(h.calls.length, 0, "бронь из уходящей шторки не уходит на сервер");
  reject?.();
  await pending;
});

await test("«Закрыть» и затемнение тоже делают уходящую шторку inert", async () => {
  browser.reduced = false;
  for (const act of [(x) => x.button("btn-ghost").props.onClick(), (x) => x.backdrop().props.onClick()]) {
    const x = open();
    act(x);
    x.s.render();
    assert.equal(x.dialog().props.ref.current.inert, true);
  }
});

await test("открыли поездку — фокус на самой шторке (читалка слышит «Поездка»), а не на «Закрыть»", async () => {
  browser.reduced = false;
  const card = new FakeEl("карточка");
  card.focus();
  const x = open();
  assert.equal(x.dialog().props.tabIndex, -1, "шторку можно сфокусировать целиком");
  assert.equal(globalThis.document.activeElement, x.dialog().props.ref.current, "фокус в шторке");
  x.dialog().props.onKeyDown(ESC);
  x.s.render();
  x.ended();
  x.s.unmount();
  assert.equal(globalThis.document.activeElement, card, "после закрытия фокус — снова на карточке");
});

await test("Tab не уходит на страницу под шторкой: Shift+Tab с самой шторки — на последнюю кнопку, Tab с последней — на первую", async () => {
  browser.reduced = false;
  const x = open();
  const node = x.dialog().props.ref.current;
  node.focus(); // фокус на самой шторке — так она открывается
  const controls = node.querySelectorAll();
  let prevented = 0;
  const key = (shiftKey) => ({ key: "Tab", shiftKey, preventDefault: () => prevented++, stopPropagation() {} });
  x.dialog().props.onKeyDown(key(true));
  assert.equal(prevented, 1, "Shift+Tab с шторки не выпускает фокус наружу");
  assert.equal(globalThis.document.activeElement, controls.at(-1));
  x.dialog().props.onKeyDown(key(false));
  assert.equal(prevented, 2);
  assert.equal(globalThis.document.activeElement, controls[0]);
});

const Card = (
  await load("src/components/NearbyRideCard.tsx", [
    [/^react\/jsx-runtime$/, JSX],
    [/\/i18n\/lang$/, LANG],
    [/\/adminUi$/, `export const SmallAvatar = "SmallAvatar";`],
    [/\/InviteDriverCallout$/, `export default "InviteDriverCallout";`],
    [/\/Icons$/, `export const IconBolt = "Icon", IconBox = "Icon", IconCar = "Icon", IconClock = "Icon", IconHospital = "Icon", IconIdCard = "Icon", IconShield = "Icon", IconStar = "Icon";`],
    [/\/BrandIcons$/, `export const YuRoute = "Icon", YuWomenOnly = "Icon";`],
    [/\/utils\/format$/, `export const pluralRu = (n, one) => one;`],
    [/\/utils\/serverTime$/, `export const serverDate = () => new Date("2030-01-01T08:00:00");`],
  ])
).default;

await test("карточка ленты: Enter и пробел открывают поездку и не достаются кнопке в шторке (и не листают страницу)", async () => {
  for (const k of ["Enter", " "]) {
    let opened = 0;
    let prevented = 0;
    const card = Card({ ride: { ...ride, driver_trips: 3, driver_since: null, distance_km: 2 }, soonest: false, onOpen: () => opened++ });
    card.props.onKeyDown({ key: k, preventDefault: () => prevented++ });
    assert.equal(opened, 1, `«${k}» открывает`);
    assert.equal(prevented, 1, `«${k}» — нажатие карточки, дальше не идёт`);
  }
  let opened = 0;
  Card({ ride: { ...ride, driver_trips: 3, driver_since: null, distance_km: 2 }, soonest: false, onOpen: () => opened++ }).props.onKeyDown({ key: "Tab", preventDefault() {} });
  assert.equal(opened, 0, "Tab — просто идёт дальше");
});

// ---------------------------------------------------------------- уход с текущего места

const X = await load(`export { useSheetExit } from "./src/components/useSheetExit"; export { useRef } from "react";`, [
  [/^react$/, REACT],
  [/^react\/jsx-runtime$/, JSX],
  [/^react-dom$/, PORTAL],
]);

/** Поддельное CSS-движение: идёт, можно развернуть, `end()` — доиграло. */
function anim(name, currentTime, playState = "running") {
  let done;
  const a = { animationName: name, playState, currentTime, reversed: 0, finished: new Promise((r) => (done = r)) };
  a.reverse = () => a.reversed++;
  a.end = () => ((a.playState = "finished"), done(a));
  return a;
}
function sheetNode(sheetAnims, backdropAnims = []) {
  const backdrop = { animations: backdropAnims, dims: [], getAnimations: () => backdrop.animations, animate: (kf, opts) => backdrop.dims.push({ kf, opts }) };
  return { animations: sheetAnims, getAnimations: () => sheetAnims, parentElement: backdrop, inert: false, contains: () => false, focus() {} };
}
function exitWith(sheet) {
  const closes = [];
  const host = mount(() => X.useSheetExit(() => closes.push(1), X.useRef(sheet)), {});
  return { host, closes, sheet, exit: () => host.tree };
}

await test("закрыли, пока шторка ещё поднимается, — её появление разворачивается с того же места, без рывка", async () => {
  browser.reduced = false;
  const rising = anim("sheetUp", 90);
  const fading = anim("fadeIn", 90);
  const x = exitWith(sheetNode([rising], [fading]));
  x.exit().close();
  x.host.render();
  assert.equal(rising.reversed, 1, "подъём шторки пошёл обратно");
  assert.equal(fading.reversed, 1, "проявление затемнения пошло обратно");
  assert.ok(!/is-leaving/.test(x.exit().backdropClass), "не перескакивает на уход «с самого верха» (sheetDown)");
  assert.equal(x.exit().leaving, true, "но уже уходит: бронь и кнопки заперты");
  assert.equal(x.sheet.inert, true);
  x.exit().close();
  assert.equal(rising.reversed, 1, "второе нажатие ничего не разворачивает заново");
  assert.equal(x.closes.length, 0, "ещё не доехала");
  rising.end();
  await new Promise((r) => setImmediate(r));
  assert.equal(x.closes.length, 1, "вернулась вниз — убрана");
});

await test("…затемнение уже проявилось — гаснет за столько же, сколько шторка едет обратно", async () => {
  browser.reduced = false;
  const rising = anim("sheetUp", 200);
  const x = exitWith(sheetNode([rising], [anim("fadeIn", 180, "finished")]));
  x.exit().close();
  const dims = x.sheet.parentElement.dims;
  assert.equal(dims.length, 1);
  assert.deepEqual(dims[0].kf, [{ backgroundColor: "transparent" }]);
  assert.equal(dims[0].opts.duration, 200, "ровно столько, сколько шторка уже ехала вверх");
  assert.equal(dims[0].opts.fill, "forwards");
});

await test("шторка уже поднялась — обычный уход вниз (sheetDown), страховочный таймер на месте", async () => {
  browser.reduced = false;
  browser.timers.length = 0;
  const x = exitWith(sheetNode([anim("sheetUp", 260, "finished")]));
  x.exit().close();
  x.host.render();
  assert.match(x.exit().backdropClass, /is-leaving/);
  assert.equal(browser.timers.filter((t) => t.on).length, 1, "страховка, если конец движения не придёт");
});

await test("закрыли в первый же кадр, подъём ещё не начался, — убрана сразу: не мелькает во весь рост", async () => {
  browser.reduced = false;
  for (const rising of [anim("sheetUp", 0), Object.assign(anim("sheetUp", null), { pending: true })]) {
    const fading = anim("fadeIn", 0);
    const x = exitWith(sheetNode([rising], [fading]));
    x.exit().close();
    assert.equal(rising.reversed, 0, "разворот с нулевой точки прыгнул бы в конец появления");
    assert.equal(fading.reversed, 0);
    assert.equal(x.closes.length, 1, "закрыта в тот же миг");
    assert.equal(x.sheet.inert, true);
  }
});

await test("«Уменьшить движение» во время подъёма — закрывается сразу, ничего не разворачивая", async () => {
  browser.reduced = true;
  try {
    const rising = anim("sheetUp", 90);
    const x = exitWith(sheetNode([rising]));
    x.exit().close();
    assert.equal(x.closes.length, 1);
    assert.equal(rising.reversed, 0);
  } finally {
    browser.reduced = false;
  }
});

finish();
