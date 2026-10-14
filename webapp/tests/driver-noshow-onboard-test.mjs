// «Не вышел» нельзя отметить пассажиру «в пути» (решение 14.10.2026, docs/decisions.md).
// Пассажир сам нажал «Я сел» → бронь onboard. Раньше в «Мои пассажиры» у водителя и тут висела
// «Не вышел»: тап отменял бронь человека, который уже в машине. Теперь — объяснение и
// «Сообщить о проблеме» (двусторонний разбор по брони), а сервер такую неявку не принимает (409).
// Исполняем настоящий компонент; заменены только хуки React, язык и сеть.
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

const render = () => { h.si = 0; h.effects = []; return DriverPassengers(); };
const all = (n) => (!n || typeof n !== "object") ? [] : [n, ...[n.props?.children].flat(Infinity).flatMap(all)];
const text = (n) => (n == null || n === false) ? "" : typeof n !== "object" ? String(n)
  : [n.props?.children].flat(Infinity).map(text).join("");
const rowOf = (tree, id) => all(tree).find((n) => n.key === id && String(n.props.className).includes("list-row"));
const buttonIn = (node, label) => all(node).find((n) => n.type === "button" && text(n).includes(label));
const tick = () => new Promise((r) => setTimeout(r, 0));

const rows = [
  { booking_id: 1, passenger_id: 11, passenger_name: "Айгуль", passenger_rating: null, route: "Уфа → Сибай", status: "confirmed", my_stars: 0 },
  { booking_id: 2, passenger_id: 12, passenger_name: "Ринат", passenger_rating: 4.8, route: "Уфа → Сибай", status: "onboard", my_stars: 0 },
];
h.rows = rows;
// Состояния компонента по порядку useState: boot, rows, busyId, note, disputeId, filedIds.
h.states = ["ready", rows, null, "", null, []];

let tree = render();
const confirmedRow = rowOf(tree, 1);
const onboardRow = rowOf(tree, 2);
assert.ok(confirmedRow && onboardRow, "обе брони видны в «Мои пассажиры»");
assert.ok(buttonIn(confirmedRow, "Не вышел"), "по подтверждённой брони неявку отметить можно");
assert.equal(buttonIn(onboardRow, "Не вышел"), undefined, "пассажир «в пути» — «Не вышел» нет");
assert.match(text(onboardRow), /Пассажир отметил «Я сел»/, "водителю объясняют, почему кнопки нет");
const report = buttonIn(onboardRow, "Сообщить о проблеме");
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

// Гонка: список старый, а пассажир уже нажал «Я сел» — сервер отвечает 409, список перечитываем.
h.noShowResult = () => Promise.reject(new ApiError(409, "Пассажир уже отметил «Я сел» — неявку не отметить."));
const before = h.fetches;
buttonIn(rowOf(tree, 1), "Не вышел").props.onClick();
await tick(); await tick();
assert.deepEqual(h.noShows, [1]);
assert.equal(h.states[3], "Пассажир уже отметил «Я сел» — неявку не отметить.", "показан ответ сервера");
assert.equal(h.fetches, before + 1, "после 409 список перечитан — кнопка сменится на разбор");

// Старый сервер без passenger_id: объяснение есть, «Не вышел» по-прежнему нет.
h.states = ["ready", [{ ...rows[1], booking_id: 3, passenger_id: undefined }], null, "", null, []];
tree = render();
assert.equal(buttonIn(rowOf(tree, 3), "Не вышел"), undefined);
assert.match(text(rowOf(tree, 3)), /Пассажир отметил «Я сел»/);

console.log("✓ «в пути»: «Не вышел» спрятан, есть «Сообщить о проблеме»");
console.log("✓ разбор открывается по брони с типом passenger_no_show");
console.log("✓ 409 от сервера перечитывает список");
console.log("✓ старый сервер без passenger_id — неявки нет, объяснение есть");
