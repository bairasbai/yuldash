// ================================================================
//  SOS из поездки передаёт бронь, а после отправки говорит то, что ответил сервер.
//
//  Было: «SOS» в поездке и в чате вёл на голый `/sos`, экран не клал `booking_id` в сигнал —
//  дежурный не знал, в какой машине человек. Теперь `/sos?booking=<id>` → POST /sos с
//  booking_id (как Android: bookingId → ApiClient.sos). И показывается hint_ru / hint_ba из
//  ответа: «SMS сейчас не работает — позвони им сама».
//
//  Исполняем настоящий SosScreen и настоящий utils/sosLink.ts; сеть, GPS и React подменены.
// ================================================================
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const h = (globalThis.__sosLink = {});
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const mocks = {
  react: `const h=globalThis.__sosLink;
 export function useState(v){const x=h.current,i=x.i++;if(!(i in x.values))x.values[i]=typeof v==='function'?v():v;return[x.values[i],v=>{if(x.live)x.values[i]=typeof v==='function'?v(x.values[i]):v}];}
 export function useRef(v){const x=h.current,i=x.i++;return x.values[i]??=( {current:v} );}
 export function useCallback(fn,deps){const x=h.current,i=x.i++,p=x.values[i];if(!p||deps.some((v,j)=>v!==p.deps[j]))x.values[i]={fn,deps};return x.values[i].fn;}
 export function useEffect(fn,deps){const x=h.current,i=x.i++,p=x.values[i];if(!p||!deps||deps.some((v,j)=>v!==p.deps[j])){x.values[i]={deps};h.effects.push(()=>{x.cleanups[i]?.();x.cleanups[i]=fn()});}}`,
  "react/jsx-runtime": `export const Fragment='fragment';export const jsx=(type,props,key)=>({type,props,key}),jsxs=jsx;`,
  "react-router-dom": `export const useNavigate=()=> (...args)=>globalThis.__sosLink.nav.push(args); export const useSearchParams=()=>[new URLSearchParams(globalThis.__sosLink.search??'')];`,
  lang: `export const useLang=()=>({appText:(ru,ba)=>globalThis.__sosLink.ba?ba:ru,lang:globalThis.__sosLink.ba?'ba':'ru'});`,
  auth: `export const useAuth=()=>({isAuthed:globalThis.__sosLink.authed!==false});`,
  client: `export class ApiError extends Error {constructor(status){super('fixture');this.status=status;}} globalThis.__sosLink.ApiError=ApiError; export const getSessionGeneration=()=>'A';`,
  safety: `export const sendSos=body=>{const h=globalThis.__sosLink,r=h.deferred();h.sends.push({body,...r});return r.promise;};`,
  header: `export const SubHeader=()=>null;`,
  icons: `export const IconPhone=()=>null,IconShield=()=>null,IconCheck=()=>null,IconWarn=()=>null,IconHospital=()=>null,IconHeart=()=>null,IconCar=()=>null,IconPin=()=>null,IconCopy=()=>null;`,
  analytics: `export const track=()=>{};`,
};
const result = await build({
  entryPoints: [root + "src/screens/SosScreen.tsx"], bundle: true, write: false, format: "esm", platform: "node", jsx: "automatic",
  plugins: [{ name: "sos-link", setup(b) {
    b.onResolve({ filter: /.*/ }, (a) => {
      const p = a.path;
      const k = Object.hasOwn(mocks, p) ? p : p.endsWith("/i18n/lang") ? "lang" : p.endsWith("/auth/AuthProvider") ? "auth" : p.endsWith("/api/client") ? "client" : p.endsWith("/api/safety") ? "safety" : p.endsWith("/ConsentsScreen") ? "header" : p.endsWith("/components/Icons") ? "icons" : p.endsWith("/analytics") ? "analytics" : null;
      return k ? { path: k, namespace: "mock" } : null;
    });
    b.onLoad({ filter: /.*/, namespace: "mock" }, (a) => ({ contents: mocks[a.path], loader: "js" }));
  } }],
});
const { default: Screen } = await import("data:text/javascript;base64," + Buffer.from(result.outputFiles[0].text).toString("base64"));

// Настоящий помощник со ссылкой на SOS — отдельной сборкой.
const link = await build({ entryPoints: [root + "src/utils/sosLink.ts"], bundle: true, write: false, format: "esm", platform: "node" });
const { sosPath, bookingIdFromSearch } = await import("data:text/javascript;base64," + Buffer.from(link.outputFiles[0].text).toString("base64"));

// Мини-рантайм хуков: один экземпляр, состояние живёт между рендерами.
function render() {
  const x = (h.inst ??= { values: [], cleanups: [], live: true });
  x.i = 0; h.current = x; h.effects = [];
  const form = Screen({}); // SosScreen → <SosForm/>
  const tree = form.type(form.props);
  h.effects.forEach((fn) => fn());
  return tree;
}
function nodes(tree, pred) {
  if (Array.isArray(tree)) return tree.flatMap((t) => nodes(t, pred));
  if (!tree || typeof tree !== "object") return [];
  return [...(pred(tree) ? [tree] : []), ...nodes(tree.props?.children, pred)];
}
const text = (tree) => JSON.stringify(tree);
const danger = (tree) => nodes(tree, (n) => n.props?.className?.split(" ").includes("btn-danger"))[0];
function reset(search = "", opts = {}) {
  Object.assign(h, { inst: null, effects: [], nav: [], sends: [], search, authed: true, ba: false, deferred, ...opts });
  Object.defineProperty(globalThis, "navigator", { configurable: true, value: { geolocation: { getCurrentPosition() {} } } });
}

let bad = 0;
async function test(name, run) {
  try { reset(); await run(); console.log("✓ " + name); }
  catch (e) { bad++; console.log("✗ " + name + "\n    " + e.message); }
}

// ---- ссылка ----
await test("sosPath: с бронью — /sos?booking=<id>, без — /sos", () => {
  assert.equal(sosPath(42), "/sos?booking=42");
  assert.equal(sosPath(null), "/sos");
  assert.equal(sosPath(undefined), "/sos");
  assert.equal(sosPath(0), "/sos");
  assert.equal(sosPath(-3), "/sos");
  assert.equal(sosPath(1.5), "/sos");
});
await test("bookingIdFromSearch: число — бронь, мусор — «брони нет»", () => {
  assert.equal(bookingIdFromSearch("42"), 42);
  for (const bad of [null, undefined, "", "0", "-1", "abc", "4x", "1e3", "1.5", "99999999999", " 5"]) assert.equal(bookingIdFromSearch(bad), null, String(bad));
});

// ---- экран ----
await test("/sos?booking=42: POST /sos уходит с booking_id = 42", async () => {
  reset("booking=42");
  const pending = danger(render()).props.onClick();
  assert.equal(h.sends.length, 1);
  assert.equal(h.sends[0].body.booking_id, 42, "дежурный должен знать бронь");
  assert.equal(h.sends[0].body.category, "other");
  h.sends[0].resolve({});
  await pending;
});
await test("/sos без брони: booking_id не отправляется", async () => {
  reset("");
  const pending = danger(render()).props.onClick();
  assert.equal(h.sends[0].body.booking_id ?? null, null);
  h.sends[0].resolve({});
  await pending;
});
await test("/sos?booking=мусор: брони нет, сигнал всё равно уходит", async () => {
  reset("booking=abc");
  const pending = danger(render()).props.onClick();
  assert.equal(h.sends[0].body.booking_id ?? null, null);
  h.sends[0].resolve({});
  await pending;
});
await test("401 на отправке: после входа человека вернут на /sos?booking=42", async () => {
  reset("booking=42");
  const pending = danger(render()).props.onClick();
  h.sends[0].reject(new h.ApiError(401));
  await pending;
  assert.deepEqual(h.nav, [["/login", { state: { from: "/sos?booking=42" } }]]);
});
await test("гость: «Войти» возвращает на тот же адрес с бронью", () => {
  reset("booking=42", { authed: false });
  const tree = render();
  const login = nodes(tree, (n) => n.type === "button" && n.props?.className?.includes("btn-primary"))[0];
  login.props.onClick();
  assert.deepEqual(h.nav, [["/login", { state: { from: "/sos?booking=42" } }]]);
});

// ---- что видит человек ----
await test("после отправки показывается подсказка сервера (hint_ru), если она есть", async () => {
  reset("booking=42");
  const pending = danger(render()).props.onClick();
  const hint = "Сигнал сохранён в Юлдаш. Отправка SMS сейчас не работает — родным сообщение не уйдёт. Позвони им, если можешь.";
  h.sends[0].resolve({ hint_ru: hint, hint_ba: "ба-текст" });
  await pending;
  const t = text(render());
  assert.ok(t.includes("Сигнал отправлен"));
  assert.ok(t.includes(hint), "подсказка сервера должна быть на экране");
});
await test("на башкирском — hint_ba; нет hint_ba — берём русский, а не пустоту", async () => {
  reset("booking=42", { ba: true });
  let pending = danger(render()).props.onClick();
  h.sends[0].resolve({ hint_ru: "по-русски", hint_ba: "башҡортса" });
  await pending;
  assert.ok(text(render()).includes("башҡортса"));
  reset("booking=42", { ba: true });
  pending = danger(render()).props.onClick();
  h.sends[0].resolve({ hint_ru: "по-русски", hint_ba: "" });
  await pending;
  assert.ok(text(render()).includes("по-русски"));
});
await test("сервер ничего не добавил (hint пустой) — экран не выдумывает свой", async () => {
  reset("booking=42");
  const pending = danger(render()).props.onClick();
  h.sends[0].resolve({ hint_ru: "", hint_ba: "" });
  await pending;
  const t = text(render());
  assert.ok(t.includes("Сигнал отправлен"));
  assert.ok(!/SMS/.test(t), "на экране не должно быть слова SMS без слов сервера");
});
await test("на экране SOS нет обещаний SMS (ни у вошедшего, ни у гостя)", () => {
  for (const authed of [true, false]) {
    for (const ba of [false, true]) {
      reset("", { authed, ba });
      const t = text(render());
      assert.ok(!/SMS/.test(t), `authed=${authed} ba=${ba}: слово SMS на экране`);
      assert.ok(!t.includes("Сообщить близким") && !t.includes("Яҡындарға һәм ярҙамға"));
    }
  }
  reset("");
  assert.ok(text(render()).includes("Сообщить поддержке"));
});

// ---- входы в SOS из поездки ----
for (const [file, re] of [
  ["src/screens/ActiveTripScreen.tsx", /navigate\(sosPath\(bookingId\)\)/],
  ["src/screens/TripChatScreen.tsx", /onSos=\{\(\) => navigate\(sosPath\(bookingId\)\)\}/],
]) {
  await test(`${file}: SOS ведёт на /sos?booking=<id>`, () => {
    const src = readFileSync(root + file, "utf8");
    assert.match(src, re);
    assert.ok(!/navigate\("\/sos"\)/.test(src), "остался голый /sos без брони");
  });
}

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
