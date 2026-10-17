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
import { browser, finish, flat, JSX, LANG, load, mount, REACT, root, test } from "./fake-react.mjs";

const h = (globalThis.__rideExit = { booking: null, routes: [], calls: [], authed: true, own: null });

const Sheet = (
  await load("src/components/RideSheet.tsx", [
    [/^react$/, REACT],
    [/^react\/jsx-runtime$/, JSX],
    [/^react-router-dom$/, `export const useNavigate = () => (...args) => globalThis.__rideExit.routes.push(args);`],
    [/\/i18n\/lang$/, LANG],
    [/\/auth\/AuthProvider$/, `export const useAuth = () => ({ isAuthed: globalThis.__rideExit.authed });`],
    [/\/features$/, `const f = { rides: true, parcels: true, ready: true }; export const useFeatures = () => f, getFeatures = () => f;`],
    [/\/ComingSoon$/, `export default "ComingSoon";`],
    [/\/api\/client$/, `export class ApiError extends Error {} export const getSessionGeneration = () => "same";`],
    [/\/booking\/OwnBookingsProvider$/, `export const useOwnBooking = () => globalThis.__rideExit.own; export const useRefreshOwnBookings = () => () => {};`],
    [/\/api\/bookings$/, `export const createBooking = (body) => (globalThis.__rideExit.calls.push(body), globalThis.__rideExit.booking(body));`],
    [/\/analytics$/, `export const track = () => {};`],
    [/\/utils\/format$/, `export const formatWhen = () => "завтра, 10:00"; export const priceLabel = () => "500 ₽";`],
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

finish();
