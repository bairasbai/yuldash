// ================================================================
//  «Поделиться поездкой с близким» — честно: ссылка на экране, отправляет человек сам.
//
//  Было: после «Поделиться» экран писал «Ссылка отправлена близкому», а после отметки статуса —
//  «Близкие получили сообщение». SMS на проде выключены, самой ссылки на экране не было:
//  человек считал, что мама всё знает, а она не знала ничего. Теперь (как Android: TripLiveLink.kt,
//  BookingActiveTripScreen.kt): после «Поделиться» показываем ссылку `{сервер}/t/{token}`,
//  кнопки «Скопировать» и «Отправить» (системное окно, если браузер его умеет) и пишем
//  «Ссылка готова — отправь её близкому сам». Слов «отправлена» и «получили» нет.
//
//  Исполняем настоящий ShareTripCard и настоящий api/family.ts; сеть и React подменены.
// ================================================================
import assert from "node:assert/strict";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const h = (globalThis.__shareLink = {});
const mocks = {
  react: `const h=globalThis.__shareLink;
 export function useState(v){const x=h.inst,i=x.i++;if(!(i in x.values))x.values[i]=v;return[x.values[i],v=>{x.values[i]=typeof v==='function'?v(x.values[i]):v}];}
 export const useCallback=fn=>fn;
 export const useEffect=fn=>{h.effects.push(fn)};`,
  "react/jsx-runtime": `export const Fragment='fragment';export const jsx=(type,props)=>({type,props:props??{}});export const jsxs=jsx;`,
  "react-router-dom": `export const Link='Link';`,
  lang: `export const useLang=()=>({lang:'ru',appText:(ru,ba)=>globalThis.__shareLink.ba?ba:ru});`,
  client: `export const API_BASE='https://yulbash.ru'; export class ApiError extends Error{constructor(status,message){super(message);this.status=status;}} globalThis.__shareLink.ApiError=ApiError; export const getSessionGeneration=()=>'A';
 export const apiGet=(p)=>globalThis.__shareLink.api('GET',p); export const apiPost=(p,b)=>globalThis.__shareLink.api('POST',p,b); export const apiDelete=(p)=>globalThis.__shareLink.api('DELETE',p);`,
  outbox: `export const enqueue=async()=>{};`,
  icons: `export const IconCheck='i',IconCopy='i',IconShare='i',IconTrash='i',IconUsers='i';`,
  analytics: `export const track=()=>{};`,
};
const result = await build({
  entryPoints: [root + "src/components/ShareTripCard.tsx"], bundle: true, write: false, format: "esm", platform: "node", jsx: "automatic",
  plugins: [{ name: "share-link", setup(b) {
    b.onResolve({ filter: /.*/ }, (a) => {
      const p = a.path;
      const k = Object.hasOwn(mocks, p) ? p : p.endsWith("/i18n/lang") ? "lang" : p.endsWith("/api/client") || p === "./client" ? "client" : p.endsWith("/utils/outbox") ? "outbox" : p.endsWith("/components/Icons") || p === "./Icons" ? "icons" : p.endsWith("/analytics") ? "analytics" : null;
      return k ? { path: k, namespace: "mock" } : null;
    });
    b.onLoad({ filter: /.*/, namespace: "mock" }, (a) => ({ contents: mocks[a.path], loader: "js" }));
  } }],
});
const { default: Card } = await import("data:text/javascript;base64," + Buffer.from(result.outputFiles[0].text).toString("base64"));

const LINK = "https://yulbash.ru/t/abcDEF123_-xyzQWERT";
const share = { id: 9, booking_id: 42, order_id: null, parcel_id: null, contact_id: 1, token: "abcDEF123_-xyzQWERT", last_status: "shared", created_at: "", expires_at: null };
const mama = { id: 1, user_id: 1, name: "Мама", relation: "мама", phone: "+79990000000", notify_by_default: true };

function render(props = { bookingId: 42 }) {
  h.inst.i = 0; h.effects = [];
  return Card(props);
}
const all = (n) => (n && typeof n === "object" ? [n, ...[n.props?.children].flat(Infinity).flatMap(all)] : []);
const text = (tree) => JSON.stringify(tree);
const byText = (tree, str) => all(tree).find((n) => n.type === "button" && JSON.stringify(n.props.children).includes(str));
const tick = () => new Promise(setImmediate);

/** Открыть карточку: сервер знает контакты и уже выданные ссылки. */
async function open({ shares = [], contacts = [mama], props = { bookingId: 42 }, ba = false, shareFails = null } = {}) {
  Object.assign(h, { inst: { values: [], i: 0 }, effects: [], ba, calls: [], clip: [], sys: [] });
  h.api = async (method, path, body) => {
    h.calls.push([method, path, body]);
    if (method === "GET" && /shares$/.test(path)) return shares;
    if (method === "GET" && path === "/trusted-contacts") return contacts;
    if (method === "POST" && /\/share$/.test(path)) {
      if (shareFails) throw shareFails;
      shares = [...shares, share];
      return share;
    }
    if (method === "DELETE") { shares = shares.filter((s) => !path.endsWith("/" + s.id)); return { ok: true }; }
    if (method === "POST" && /trip-status$/.test(path)) return shares;
    throw new Error("unexpected " + method + " " + path);
  };
  render(props);
  for (const fn of h.effects) fn();
  await tick(); await tick();
  return render(props);
}
function setNavigator({ clipboard = true, systemShare = true, clipboardFails = false, shareError = null } = {}) {
  const nav = {};
  if (clipboard) nav.clipboard = { writeText: async (t) => { if (clipboardFails) throw new Error("denied"); h.clip.push(t); } };
  if (systemShare) nav.share = async (data) => { if (shareError) throw shareError; h.sys.push(data); };
  Object.defineProperty(globalThis, "navigator", { configurable: true, value: nav });
}
/** «Поделиться» → выбрать контакт → ссылка готова. */
async function shareWithMama(opts) {
  let tree = await open(opts);
  byText(tree, opts?.ba ? "Яҡын кешегә ебәреү" : "Поделиться поездкой с близким").props.onClick();
  tree = render(opts?.props);
  const contact = all(tree).find((n) => n.type === "button" && n.props.className?.includes("list-row--link"));
  await contact.props.onClick();
  await tick();
  return render(opts?.props);
}

let bad = 0;
async function test(name, run) {
  try { setNavigator(); await run(); console.log("✓ " + name); }
  catch (e) { bad++; console.log("✗ " + name + "\n    " + e.message); }
}

await test("после «Поделиться»: ссылка видна, сообщение «Ссылка готова — отправь её близкому сам»", async () => {
  const t = text(await shareWithMama());
  assert.ok(t.includes(LINK), "ссылка вида {сервер}/t/{token} должна быть на экране");
  assert.ok(t.includes("Ссылка готова — отправь её близкому сам"));
  assert.ok(t.includes("Ссылка для близкого"), "заголовок как в Android");
  assert.ok(!/отправлена|получили|китте|ебәрелде/.test(t), "никаких «отправлена» и «получили»");
});
await test("на башкирском — тот же порядок, без обещаний", async () => {
  const t = text(await shareWithMama({ ba: true }));
  assert.ok(t.includes(LINK));
  assert.ok(t.includes("Һылтанма әҙер — яҡын кешегә үҙең ебәр"));
  assert.ok(!/ебәрелде|хәбәр китте/.test(t));
});
await test("ссылка уже была выдана раньше — показывается сразу при открытии, без нового «Поделиться»", async () => {
  const tree = await open({ shares: [share] });
  assert.ok(text(tree).includes(LINK));
  assert.ok(byText(tree, "Скопировать"));
});
await test("нет токена у строки — ссылки нет, выдумывать нечего", async () => {
  const tree = await open({ shares: [{ ...share, token: null }] });
  assert.ok(!text(tree).includes("/t/"));
  assert.ok(!byText(tree, "Скопировать"));
});
await test("«Скопировать» кладёт в буфер именно ссылку и говорит «Ссылка скопирована»", async () => {
  const tree = await shareWithMama();
  await byText(tree, "Скопировать").props.onClick();
  assert.deepEqual(h.clip, [LINK]);
  assert.ok(text(render()).includes("Ссылка скопирована"));
});
await test("буфер недоступен — честно просим скопировать руками, а не молчим", async () => {
  setNavigator({ clipboardFails: true });
  const tree = await shareWithMama();
  await byText(tree, "Скопировать").props.onClick();
  assert.ok(text(render()).includes("Не получилось скопировать"));
});
await test("«Отправить» открывает системное окно с текстом «Следи за моей поездкой в Юлдаш: <ссылка>»", async () => {
  const tree = await shareWithMama();
  await byText(tree, "Отправить").props.onClick();
  assert.equal(h.sys.length, 1);
  assert.equal(h.sys[0].text, "Следи за моей поездкой в Юлдаш: " + LINK);
});
await test("браузер не умеет navigator.share — кнопки «Отправить» нет, «Скопировать» есть", async () => {
  setNavigator({ systemShare: false });
  const tree = await shareWithMama();
  assert.ok(byText(tree, "Скопировать"));
  assert.equal(byText(tree, "Отправить"), undefined);
});
await test("закрыл системное окно сам (AbortError) — это не ошибка, ничего не копируем", async () => {
  setNavigator({ shareError: new DOMException("closed", "AbortError") });
  const tree = await shareWithMama();
  await byText(tree, "Отправить").props.onClick();
  assert.deepEqual(h.clip, []);
  assert.ok(!text(render()).includes("Ссылка скопирована"));
});
await test("системное окно не открылось по другой причине — хотя бы копируем ссылку", async () => {
  setNavigator({ shareError: new Error("not allowed") });
  const tree = await shareWithMama();
  await byText(tree, "Отправить").props.onClick();
  assert.deepEqual(h.clip, [LINK]);
});
await test("сервер отказал (429: слишком много сообщений близким) — показываем его слова, ссылки нет", async () => {
  Object.assign(h, { inst: { values: [], i: 0 } });
  const err = (m) => new h.ApiError(429, m);
  let tree = await open({ shareFails: err("Слишком много сообщений близким за сутки. Попробуй завтра.") });
  byText(tree, "Поделиться поездкой с близким").props.onClick();
  tree = render();
  const contact = all(tree).find((n) => n.type === "button" && n.props.className?.includes("list-row--link"));
  await contact.props.onClick();
  await tick();
  const t = text(render());
  assert.ok(t.includes("Слишком много сообщений близким"));
  assert.ok(!t.includes("Ссылка готова") && !t.includes("/t/"));
});
await test("отозвали ссылку — она пропадает с экрана", async () => {
  const tree = await shareWithMama();
  const revoke = all(tree).find((n) => n.type === "button" && n.props["aria-label"] === "Отозвать доступ");
  await revoke.props.onClick();
  await tick();
  assert.ok(!text(render()).includes(LINK));
});
await test("отметка «Доехала»: пишем «Сохранено», а не «Близкие получили сообщение»", async () => {
  const props = { bookingId: 42, showStatuses: true, bookingStatus: "onboard" };
  const tree = await open({ shares: [share], props });
  await byText(tree, "Доехала").props.onClick();
  await tick();
  assert.ok(h.calls.some(([m, p, b]) => m === "POST" && p === "/bookings/42/trip-status" && b?.status === "arrived"));
  const t = text(render(props));
  assert.ok(t.includes("Сохранено"));
  assert.ok(!/получили|китте/.test(t));
});
// Посадку отмечает водитель (решение 17.10.2026): до неё у пассажира нет ни «Села в машину»,
// ни других отметок — нечего отправлять. Старый «sat» сервер и так отклоняет (409).
await test("до посадки «Села в машину» нет — посадку отмечает водитель", async () => {
  const props = { bookingId: 42, showStatuses: true, bookingStatus: "confirmed" };
  const tree = await open({ shares: [share], props });
  assert.equal(byText(tree, "Села в машину"), undefined);
  assert.equal(byText(tree, "Доехала"), undefined);
  assert.ok(!h.calls.some(([, p]) => /trip-status$/.test(p)));
});
await test("такси-заказ: та же ссылка (shareOrder)", async () => {
  const props = { orderId: 5 };
  let tree = await open({ props });
  byText(tree, "Поделиться поездкой с близким").props.onClick();
  tree = render(props);
  await all(tree).find((n) => n.type === "button" && n.props.className?.includes("list-row--link")).props.onClick();
  await tick();
  assert.ok(h.calls.some(([m, p]) => m === "POST" && p === "/instant/orders/5/share"));
  assert.ok(text(render(props)).includes(LINK));
});

console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
process.exit(bad === 0 ? 0 : 1);
