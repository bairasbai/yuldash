// «Не вышел» нельзя отметить пассажиру «в пути» (решение 14.10.2026, docs/decisions.md).
// С 17.10.2026 посадку отмечает водитель: у подтверждённого пассажира «Сел» рядом с «Не вышел»
// (DriverBoarding), «в пути» — «Пассажир в машине» и «Отменить» в первые 5 минут, а когда окно
// прошло — объяснение и «Сообщить о проблеме» (двусторонний разбор по брони). Сервер неявку
// «в пути» не принимает (409). Исполняем настоящий компонент; заменены только хуки React, язык,
// сеть и сам DriverBoarding (его проверяет driver-marks-boarding-test.mjs).
import assert from "node:assert/strict";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const h = (globalThis.__drvPax = { states: [], si: 0, effects: [], fetches: 0, noShows: [] });
const modules = {
  react: `const h=globalThis.__drvPax; export function useState(v){const i=h.si++; if(!(i in h.states))h.states[i]=typeof v==='function'?v():v; return [h.states[i],x=>h.states[i]=typeof x==='function'?x(h.states[i]):x]}; export const useEffect=fn=>{h.effects.push(fn)}; export const useCallback=fn=>fn;`,
  "react/jsx-runtime": `export const Fragment='fragment'; export const jsx=(type,props,key)=>({type,props:props??{},key}); export const jsxs=jsx;`,
  lang: `export const useLang=()=>({lang:'ru',appText:(ru,ba)=>{if(!ba||!ba.trim())throw new Error('одноязычная строка: '+ru);return ru},t:k=>k});`,
  client: `export class ApiError extends Error { constructor(status,message){super(message);this.status=status;} }; globalThis.__drvPax.ApiError=ApiError;`,
  bookings: `const h=globalThis.__drvPax; export const fetchDriverBookings=()=>{h.fetches++;return Promise.resolve(h.rows)}; export const markNoShow=id=>{h.noShows.push(id);return h.noShowResult()}; export const rateBooking=()=>Promise.resolve();`,
  states: `export const LoadingList='loading';`,
  icons: `export const IconProfile='icon'; export const IconStar='icon';`,
  card: `export default 'FileIncidentCard';`,
  boarding: `export default 'DriverBoarding';`,
  cabinet: `export const SectionHeader='SectionHeader';`,
};

const result = await build({
  entryPoints: [root + "src/components/DriverPassengers.tsx"], bundle: true, write: false, format: "esm",
  platform: "node", jsx: "automatic",
  plugins: [{
    name: "mock-ui",
    setup(b) {
      b.onResolve({ filter: /.*/ }, (args) => {
        const p = args.path;
        const key = Object.hasOwn(modules, p) ? p
          : p.endsWith("/i18n/lang") ? "lang"
          : p.endsWith("/api/client") ? "client"
          : p.endsWith("/api/bookings") ? "bookings"
          : p.endsWith("/States") ? "states"
          : p.endsWith("/Icons") ? "icons"
          : p.endsWith("/FileIncidentCard") ? "card"
          : p.endsWith("/DriverBoarding") ? "boarding"
          : p.endsWith("/cabinetUi") ? "cabinet"
          : null;
        return key ? { path: key, namespace: "mock" } : null;
      });
      b.onLoad({ filter: /.*/, namespace: "mock" }, (args) => ({ contents: modules[args.path], loader: "js" }));
    },
  }],
});
const DriverPassengers = (await import("data:text/javascript;base64," +
  Buffer.from(result.outputFiles[0].text).toString("base64"))).default;
const ApiError = h.ApiError;

const render = () => { h.si = 0; h.effects = []; return DriverPassengers({ onOpenTrip: () => {} }); };
const all = (n) => (!n || typeof n !== "object") ? [] : [n, ...[n.props?.children].flat(Infinity).flatMap(all)];
const text = (n) => (n == null || n === false) ? "" : typeof n !== "object" ? String(n)
  : [n.props?.children].flat(Infinity).map(text).join("");
const rowOf = (tree, id) => all(tree).find((n) => n.key === id && String(n.props.className).includes("list-row"));
const buttonIn = (node, label) => all(node).find((n) => n.type === "button" && text(n).includes(label));
const tick = () => new Promise((r) => setTimeout(r, 0));

const rows = [
  { booking_id: 1, passenger_id: 11, passenger_name: "Айгуль", passenger_rating: null, route: "Уфа → Сибай", status: "confirmed", my_stars: 0 },
  { booking_id: 2, passenger_id: 12, passenger_name: "Ринат", passenger_rating: 4.8, route: "Уфа → Сибай", status: "onboard", my_stars: 0, boarding_undo_sec: 0 },
];
const boardingOf = (row) => all(row).find((n) => n.type === "DriverBoarding");
const DISPUTE = /Если пассажир на самом деле не садился — сообщи о проблеме/;
h.rows = rows;
// Состояния компонента по порядку useState: boot, rows, busyId, note, disputeId, filedIds.
h.states = ["ready", rows, null, "", null, []];

let tree = render();
const confirmedRow = rowOf(tree, 1);
const onboardRow = rowOf(tree, 2);
assert.ok(confirmedRow && onboardRow, "обе брони видны в «Едут с тобой»");
assert.ok(buttonIn(confirmedRow, "Не вышел"), "по подтверждённой брони неявку отметить можно");
// «Сел» рядом с «Не вышел»: посадку отмечает водитель (решение 17.10.2026).
const sat = boardingOf(confirmedRow);
assert.ok(sat, "у подтверждённого пассажира нет «Сел»");
assert.equal(sat.props.bookingId, 1);
assert.equal(sat.props.status, "confirmed");
const order = all(confirmedRow).filter((n) => n.type === "DriverBoarding" || (n.type === "button" && text(n).includes("Не вышел")));
assert.equal(order[0].type, "DriverBoarding", "«Сел» стоит перед «Не вышел»");
assert.equal(buttonIn(onboardRow, "Не вышел"), undefined, "пассажир «в пути» — «Не вышел» нет");
// «В пути»: отметка с её отменой, а разбор — внутри, на то время, когда отменить уже нельзя.
const onboard = boardingOf(onboardRow);
assert.ok(onboard && onboard.props.status === "onboard" && onboard.props.undoSec === 0, "«в пути» — отметка водителя с окном отмены");
assert.match(text(onboard), DISPUTE, "водителю объясняют, почему «Не вышел» нет");
const report = buttonIn(onboard, "Сообщить о проблеме");
assert.ok(report, "вместо неявки — «Сообщить о проблеме»");

// «Сообщить о проблеме» открывает существующий разбор по брони с выбранным типом.
report.props.onClick();
tree = render();
const card = all(rowOf(tree, 2)).find((n) => n.type === "FileIncidentCard");
assert.ok(card, "открылся разбор");
assert.equal(card.props.respondentId, 12);
assert.equal(card.props.bookingId, 2);
assert.equal(card.props.initialType, "passenger_no_show");
card.props.onFiled();
tree = render();
assert.match(text(rowOf(tree, 2)), /Разбор открыт/, "после отправки видно, что разбор открыт");
assert.equal(buttonIn(rowOf(tree, 2), "Сообщить о проблеме"), undefined);

// Гонка: список старый, а посадку уже отметили (второй телефон) — сервер отвечает 409, список перечитываем.
h.noShowResult = () => Promise.reject(new ApiError(409, "Пассажир уже отмечен в машине — неявку не отметить."));
const before = h.fetches;
buttonIn(rowOf(tree, 1), "Не вышел").props.onClick();
await tick(); await tick();
assert.deepEqual(h.noShows, [1]);
assert.equal(h.states[3], "Пассажир уже отмечен в машине — неявку не отметить.", "показан ответ сервера");
assert.equal(h.fetches, before + 1, "после 409 список перечитан — кнопка сменится на разбор");

// «Сел» / «Отменить» прошли — DriverBoarding просит перечитать: без аргументов (ответ действия —
// не сигнал отмены запроса) и с показом ошибок в общем месте экрана.
const beforeReload = h.fetches;
boardingOf(rowOf(tree, 1)).props.onChanged({ ok: true, status: "onboard", boarding_undo_sec: 300 });
assert.equal(h.fetches, beforeReload + 1, "после «Сел» список перечитан");
boardingOf(rowOf(tree, 1)).props.onError("Посадку можно отметить за 30 минут до выезда — не раньше");
assert.equal(h.states[3], "Посадку можно отметить за 30 минут до выезда — не раньше");

// Старый сервер без passenger_id и без boarding_undo_sec: объяснение есть, «Не вышел» по-прежнему нет.
h.states = ["ready", [{ ...rows[1], booking_id: 3, passenger_id: undefined, boarding_undo_sec: undefined }], null, "", null, []];
tree = render();
assert.equal(buttonIn(rowOf(tree, 3), "Не вышел"), undefined);
assert.equal(boardingOf(rowOf(tree, 3)).props.undoSec, 0, "старый сервер — отменять нечего");
assert.match(text(rowOf(tree, 3)), DISPUTE);

console.log("✓ подтверждённый пассажир: «Сел» перед «Не вышел»");
console.log("✓ «в пути»: «Не вышел» спрятан, отметка с окном отмены, разбор внутри неё");
console.log("✓ разбор открывается по брони с типом passenger_no_show");
console.log("✓ 409 от сервера перечитывает список; «Сел»/«Отменить» перечитывают без аргументов");
console.log("✓ старый сервер без passenger_id — неявки нет, объяснение есть");
