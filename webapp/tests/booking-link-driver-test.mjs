// ================================================================
//  Водитель по ссылке на бронь попадает туда, где её можно подтвердить.
//
//  Было: пуш «новая бронь» (и карточка в центре уведомлений) вёл водителя на `/booking/:id` —
//  пассажирскую «Бронь» с кнопкой «Отменить бронь»: подтвердить негде, а чужую бронь отменить
//  можно одним нажатием. Теперь экран спрашивает роль у сервера (GET /bookings/{id}/details →
//  role) и уводит водителя: ждёт подтверждения → кабинет водителя (`/driver`, там «Подтвердить»),
//  подтверждена / в пути / завершена → экран поездки (`/trip/:id`). Пассажир — как раньше.
//  Зеркало Android BookingLinkDestination.kt.
//
//  Исполняем настоящий BookingScreen и настоящий utils/bookingLink.ts; сеть и React подменены.
// ================================================================
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const h = (globalThis.__bookingLink = {});
globalThis.document ??= { visibilityState: "visible", addEventListener() {}, removeEventListener() {} };
const mocks = {
  react: `const h=globalThis.__bookingLink;
 export function useState(v){const x=h.inst,i=x.i++;if(!(i in x.values))x.values[i]=typeof v==='function'?v():v;return[x.values[i],v=>{x.values[i]=typeof v==='function'?v(x.values[i]):v}];}
 export const useCallback=fn=>fn;
 export function useRef(v){const x=h.inst,i=x.i++;return x.values[i]??={current:v};}
 export const useEffect=fn=>{h.effects.push(fn)};`,
  "react/jsx-runtime": `export const Fragment='fragment';export const jsx=(type,props)=>({type,props:props??{}});export const jsxs=jsx;`,
  "react-router-dom": `export const Navigate='Navigate'; export const useNavigate=()=>to=>globalThis.__bookingLink.routes.push(to); export const useParams=()=>({id:'42'});`,
  lang: `export const useLang=()=>({lang:'ru',appText:(ru)=>ru});`,
  bookings: `export const fetchBookingDetails=async()=>globalThis.__bookingLink.details; export const cancelBooking=async()=>{globalThis.__bookingLink.cancelled++};`,
  states: `export const LoadingList='loading'; export const ErrorState='error';`,
  map: `export default 'map';`,
  header: `export const SubHeader='header';`,
  pill: `export const StatusPill='pill';`,
  icons: `export const IconArrow='i',IconPhone='i',IconPin='i',IconCheck='i',IconLock='i';`,
  format: `export const formatWhen=()=>'когда',priceLabel=()=>'цена',payMethodLabel=()=>'оплата';`,
  ads: `export const PartnerAdSlot='ad';`,
  client: `export class ApiError extends Error {}`,
  analytics: `export const track=()=>{};`,
  own: `export const useRefreshOwnBookings=()=>()=>{};`,
  interval: `export const useVisibleInterval=()=>{};`,
};
const result = await build({
  entryPoints: [root + "src/screens/BookingScreen.tsx"], bundle: true, write: false, format: "esm", platform: "node", jsx: "automatic",
  plugins: [{ name: "booking-link", setup(b) {
    b.onResolve({ filter: /.*/ }, (a) => {
      const p = a.path;
      const k = Object.hasOwn(mocks, p) ? p : p.endsWith("/utils/useVisibleInterval") ? "interval" : p.endsWith("/i18n/lang") ? "lang" : p.endsWith("/api/bookings") ? "bookings" : p.endsWith("/components/States") ? "states" : p.endsWith("/components/YandexMap") ? "map" : p.endsWith("/ConsentsScreen") ? "header" : p.endsWith("/components/StatusPill") ? "pill" : p.endsWith("/components/Icons") ? "icons" : p.endsWith("/utils/format") ? "format" : p.endsWith("/components/PartnerAd") ? "ads" : p.endsWith("/api/client") ? "client" : p.endsWith("/analytics") ? "analytics" : p.endsWith("/booking/OwnBookingsProvider") ? "own" : null;
      return k ? { path: k, namespace: "mock" } : null;
    });
    b.onLoad({ filter: /.*/, namespace: "mock" }, (a) => ({ contents: mocks[a.path], loader: "js" }));
  } }],
});
const { default: Screen } = await import("data:text/javascript;base64," + Buffer.from(result.outputFiles[0].text).toString("base64"));
const lib = await build({ entryPoints: [root + "src/utils/bookingLink.ts"], bundle: true, write: false, format: "esm", platform: "node" });
const { bookingLinkPath, bookingStatusAllowsActiveTrip } = await import("data:text/javascript;base64," + Buffer.from(lib.outputFiles[0].text).toString("base64"));

function render() {
  h.inst.i = 0;
  h.effects = [];
  return Screen();
}
const text = (tree) => JSON.stringify(tree);

/** Открыть `/booking/42` с таким ответом сервера и вернуть то, что нарисовано. */
async function open(role, status) {
  Object.assign(h, { inst: { values: [], i: 0 }, effects: [], routes: [], cancelled: 0 });
  h.details = {
    booking_id: 42, ride_id: 7, role, status, contact_unlocked: status === "confirmed",
    from_city: "Уфа", to_city: "Сибай", depart_at: "2030-01-01T10:00:00", seats: 1, price: 500, pay_method: "cash",
    driver_name: "Ринат", driver_verified: true, driver_phone: "", driver_car: "Lada", pickup: "",
  };
  render();
  for (const fn of h.effects) fn();
  await new Promise(setImmediate);
  return render();
}

let bad = 0;
async function test(name, run) {
  try { await run(); console.log("✓ " + name); }
  catch (e) { bad++; console.log("✗ " + name + "\n    " + e.message); }
}

// ---- правило ----
const rule = [
  ["driver", "pending", "/driver"],
  ["driver", "cancelled", "/driver"],
  ["driver", "confirmed", "/trip/42"],
  ["driver", "onboard", "/trip/42"],
  ["driver", "done", "/trip/42"],
  ["passenger", "pending", null],
  ["passenger", "confirmed", null],
  ["passenger", "onboard", null],
  ["passenger", "done", null],
  ["passenger", "cancelled", null],
];
for (const [role, status, want] of rule) {
  await test(`правило: ${role} + ${status} → ${want ?? "остаться на «Брони»"}`, () => {
    assert.equal(bookingLinkPath(role, status, 42), want);
  });
}
await test("экран поездки есть у confirmed / onboard / done — как Android bookingStatusAllowsActiveTrip", () => {
  assert.deepEqual(["pending", "confirmed", "onboard", "done", "cancelled"].map(bookingStatusAllowsActiveTrip), [false, true, true, true, false]);
});

// ---- экран ----
for (const [status, want] of [["pending", "/driver"], ["confirmed", "/trip/42"], ["onboard", "/trip/42"], ["done", "/trip/42"], ["cancelled", "/driver"]]) {
  await test(`экран: водитель, бронь «${status}» → уходит на ${want}, пассажирской «Брони» не видит`, async () => {
    const tree = await open("driver", status);
    assert.equal(tree.type, "Navigate", "водителя надо увести, а не рисовать пассажирский экран");
    assert.equal(tree.props.to, want);
    assert.equal(tree.props.replace, true, "replace: «назад» не должен возвращать на ссылку, с которой увели");
    assert.ok(!text(tree).includes("Отменить бронь"));
  });
}
for (const status of ["pending", "confirmed", "onboard", "done", "cancelled"]) {
  await test(`экран: пассажир, бронь «${status}» — остаётся на «Брони»`, async () => {
    const tree = await open("passenger", status);
    assert.notEqual(tree.type, "Navigate");
    assert.ok(text(tree).includes("Детали брони"), "рисуется экран «Бронь»");
    // Паритет с Android (17.10.2026): здесь отменяют только ждущую бронь; подтверждённую —
    // на экране поездки с причиной, «в пути» — никак (booking-cancel-reason-test).
    assert.equal(text(tree).includes("Отменить бронь"), status === "pending", "отмена на «Брони» — только у ждущей");
  });
}
await test("пока ответ не пришёл, водитель не видит ничего пассажирского (загрузка, не вспышка)", () => {
  Object.assign(h, { inst: { values: [], i: 0 }, effects: [], routes: [], details: null });
  const tree = render();
  assert.ok(!text(tree).includes("Отменить бронь"));
  assert.ok(text(tree).includes("loading"));
});

// ---- уведомления ведут на /booking/:id — туда, где роль уже учтена ----
await test("центр уведомлений: «booking» → /booking/:id (роль учитывает сам экран, и веб-пуш с сервера тоже)", () => {
  const src = readFileSync(root + "src/screens/NotificationsScreen.tsx", "utf8");
  assert.match(src, /case "booking":[\s\S]{0,400}`\/booking\/\$\{n\.ref_id\}`/);
});

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
