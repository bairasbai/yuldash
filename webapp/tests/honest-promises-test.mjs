import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
import { all, bundleComponent, button, checker, langMock, mount, text } from "./shallow-harness.mjs";
import { LOGIN_METHODS_CACHE_KEY, cachedLoginMethods, cacheLoginMethods, normalizeLoginMethods } from "../src/utils/loginMethods.js";
import { createLoginFlow, nextNeedPhone } from "../src/utils/loginFlow.js";

const t = checker();
const h = globalThis.__honestPromises = {};
const read = path => readFileSync(new URL("../" + path, import.meta.url), "utf8");
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; };
const storage = new Map();
globalThis.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) };
globalThis.document = { visibilityState: "visible", addEventListener: (name, fn) => { h.visible = fn; }, removeEventListener: () => { h.visible = null; } };
globalThis.window = { open: () => {}, location: { pathname: "/login", href: "" } };

await t.test("cache: unknown methods and duplicates cannot create extra login buttons", () => {
  assert.deepEqual(normalizeLoginMethods(["unknown", "telegram", "sms", "sms"]), ["sms", "telegram"]);
  assert.equal(normalizeLoginMethods({ methods: [] }), null);
  assert.deepEqual(cachedLoginMethods(localStorage), ["telegram"]);
  cacheLoginMethods(localStorage, []);
  assert.deepEqual(cachedLoginMethods(localStorage), [], "empty server list is a real cached state");
  storage.set(LOGIN_METHODS_CACHE_KEY, "{broken");
  assert.deepEqual(cachedLoginMethods(localStorage), ["telegram"]);
  assert.deepEqual(cachedLoginMethods({ getItem() { throw Error("blocked"); } }), ["telegram"]);
  cacheLoginMethods({ setItem() { throw Error("blocked"); } }, ["sms"]);
});

const clientMock = `export class ApiError extends Error {constructor(status,message='fixture',code=''){super(message);this.status=status;this.code=code;}}globalThis.__honestPromises.ApiError=ApiError;
export const getSessionGeneration=()=>'guest',apiPost=async()=>({});`;
const authMock = `export const TELEGRAM_BOT='fixture',lastLoginMethods=()=>globalThis.__honestPromises.cached??['telegram'];
export const fetchLoginMethods=async()=>{const h=globalThis.__honestPromises;h.loads++;if(h.offline)throw Error('offline');return h.held?h.held.promise:h.methods;};
export const tgStart=async()=>({request_id:'synthetic'}),tgVerify=async()=>{const h=globalThis.__honestPromises;if(h.disabled)throw new h.ApiError(403,h.message,'login_method_disabled');return {};};
export const requestSmsCode=async()=>({sent:true}),verifySmsCode=async()=>({}),telegramChatUrl=()=>'',telegramStartUrl=()=>'';`;
const loginMocks = lang => ({
  "/i18n/lang": langMock(lang), "/api/client": clientMock, "/api/auth": authMock,
  "/auth/AuthProvider": "export const useAuth=()=>({login(){}});",
  "react-router-dom": "export const useNavigate=()=>()=>{},useLocation=()=>({});",
});
function reset() { Object.assign(h, { methods: ["telegram"], cached: ["telegram"], loads: 0, offline: false, held: null, disabled: false, message: "" }); }

for (const lang of ["ru", "ba"]) {
  const Login = await bundleComponent("src/screens/LoginScreen.tsx", { mocks: loginMocks(lang), real: ["/utils/loginFlow"] });
  for (const methods of [["telegram"], ["sms", "telegram"], [], ["vk"], ["unknown"]]) {
    await t.test(`${lang}: actual login renders only server methods ${JSON.stringify(methods)}`, async () => {
      reset(); h.methods = methods;
      const s = mount(Login); await s.settle();
      assert.equal(!!button(s.tree, lang === "ru" ? "Войти через Telegram" : "Telegram аша инеү"), methods.includes("telegram"));
      assert.equal(!!button(s.tree, lang === "ru" ? "Войти по номеру" : "Телефон номеры аша"), methods.includes("sms"));
      const empty = !methods.some(method => ["sms", "telegram"].includes(method));
      assert.equal(text(s.tree).includes(lang === "ru" ? "Вход временно недоступен" : "Инеү ваҡытлыса мөмкин түгел"), empty);
      if (empty) assert.ok(button(s.tree, lang === "ru" ? "Повторить" : "Ҡабатлау"));
      s.unmount();
    });
  }
  await t.test(`${lang}: initial loading, network fallback and visible-page refresh keep an entrance`, async () => {
    reset(); h.held = deferred();
    const s = mount(Login); s.render();
    assert.ok(all(s.tree).some(n => n.props.className === "skeleton-card"));
    assert.ok(!button(s.tree, lang === "ru" ? "Войти через Telegram" : "Telegram аша инеү"));
    h.held.reject(Error("offline")); await s.settle();
    assert.ok(button(s.tree, lang === "ru" ? "Войти через Telegram" : "Telegram аша инеү"));
    assert.ok(text(s.tree).includes(lang === "ru" ? "Не получилось обновить" : "яңыртып булманы"));
    h.held = null; h.methods = ["sms", "telegram"]; h.visible(); await s.settle();
    assert.ok(button(s.tree, lang === "ru" ? "Войти по номеру" : "Телефон номеры аша"));
    assert.equal(h.loads, 2); s.unmount();
  });
  await t.test(`${lang}: disabled Telegram shows server message and refreshes methods, without phone-required story`, async () => {
    reset(); const s = mount(Login); await s.settle();
    await button(s.tree, lang === "ru" ? "Войти через Telegram" : "Telegram аша инеү").props.onClick(); await s.settle();
    const input = all(s.tree).find(n => n.type === "input" && n.props.autoComplete === "one-time-code");
    input.props.onChange({ target: { value: "123456" } }); await s.settle();
    h.disabled = true; h.methods = []; h.message = lang === "ru" ? "Этот способ входа сейчас выключен." : "Был инеү ысулы хәҙер һүндерелгән.";
    await button(s.tree, lang === "ru" ? "Войти" : "Инеү").props.onClick(); await s.settle();
    assert.equal(h.loads, 2);
    assert.ok(text(s.tree).includes(h.message));
    assert.ok(!text(s.tree).includes(lang === "ru" ? "Для безопасности нужен номер" : "Хәүефһеҙлек өсөн номер кәрәк"));
    s.unmount();
  });
  const Pay = await bundleComponent("src/components/PayTripCard.tsx", { mocks: {
    "/i18n/lang": langMock(lang), "/api/client": clientMock,
    "/api/wallet": `export const payBooking=async(id,method)=>{globalThis.__honestPromises.pays.push({id,method});return {ok:true};};export const payInstantOrder=async()=>{throw new globalThis.__honestPromises.ApiError(503);};`,
  } });
  await t.test(`${lang}: booking never displays a bank button and cash still records the agreement`, async () => {
    h.pays = [];
    const s = mount(Pay, { kind: "booking", id: 42, amountLabel: "450 ₽" }); await s.settle();
    assert.ok(!button(s.tree, lang === "ru" ? "Картой" : "Карта менән"));
    assert.ok(!button(s.tree, "СБП"));
    assert.ok(text(s.tree).includes(lang === "ru" ? "Это отметка, а не платёж" : "Был түләү түгел"));
    await button(s.tree, lang === "ru" ? "Наличными" : "Наличный менән").props.onClick(); await s.settle();
    assert.deepEqual(h.pays, [{ id: 42, method: "cash" }]); s.unmount();
  });
  await t.test(`${lang}: order keeps bank buttons but 503 explains the current refusal`, async () => {
    const s = mount(Pay, { kind: "order", id: 42, amountLabel: "450 ₽" }); await s.settle();
    await button(s.tree, lang === "ru" ? "Картой" : "Карта менән").props.onClick(); await s.settle();
    assert.ok(text(s.tree).includes(lang === "ru" ? "Оплата картой сейчас недоступна" : "Карта менән түләү хәҙер мөмкин түгел"));
    assert.ok(!button(s.tree, lang === "ru" ? "Картой" : "Карта менән")); s.unmount();
  });
  const Feed = await bundleComponent("src/screens/RequestsFeedScreen.tsx", { real: ["/components/PartialFeedNotice"], mocks: {
    "/i18n/lang": langMock(lang), "/api/client": clientMock,
    "/api/requests": "export const fetchRequestsFeed=async()=>({items:[],partial:globalThis.__honestPromises.partial}),respondToRequest=async()=>({});",
  } });
  await t.test(`${lang}: empty request feed still reports a partial scan`, async () => {
    // Shallow tree retains the actual notice component; render it separately to verify both texts.
    h.partial = true; const s = mount(Feed); await s.settle();
    const notice = all(s.tree).find(n => typeof n.type === "function" && n.type.name === "PartialFeedNotice");
    assert.ok(notice); s.unmount();
    const Notice = await bundleComponent("src/components/PartialFeedNotice.tsx", { mocks: { "/i18n/lang": langMock(lang) } });
    const n = mount(Notice); n.render();
    assert.ok(text(n.tree).includes(lang === "ru" ? "Показаны не все — уточни село или дату" : "Бөтәһе лә күрһәтелмәгән")); n.unmount();
    h.partial = false; const full = mount(Feed); await full.settle();
    assert.ok(!all(full.tree).some(n => typeof n.type === "function" && n.type.name === "PartialFeedNotice")); full.unmount();
  });
  const matchingMocks = {
    "/i18n/lang": langMock(lang), "/api/client": clientMock,
    "react-router-dom": "export const useNavigate=()=>()=>{},useParams=()=>({id:'22'});",
    "/api/requests": "export const fetchMatchRides=async()=>({items:[],partial:globalThis.__honestPromises.partial}),fetchMyRequests=async()=>[{id:22,status:'active',from_city:'Сибай',to_city:'Акъяр',seats:1}],fetchRequestResponses=async()=>[],cancelRequest=async()=>({}),acceptResponse=async()=>({}),counterOffer=async()=>({}),declineResponse=async()=>({}),parseBargainHistory=()=>[];",
  };
  const MyRequests = await bundleComponent("src/screens/MyRequestsScreen.tsx", { mocks: matchingMocks });
  const Responses = await bundleComponent("src/screens/RequestResponsesScreen.tsx", { mocks: matchingMocks });
  await t.test(`${lang}: both matching surfaces preserve the warning even with zero matching rides`, async () => {
    for (const partial of [false, true]) {
      h.partial = partial;
      const outer = mount(MyRequests); await outer.settle();
      const section = all(outer.tree).find(n => typeof n.type === "function" && n.type.name === "MatchingRidesSection");
      assert.ok(section); outer.unmount();
      const matches = mount(section.type, section.props); await matches.settle();
      assert.equal(all(matches.tree).some(n => n.type === "PartialFeedNotice"), partial); matches.unmount();
      const responses = mount(Responses); await responses.settle();
      assert.equal(all(responses.tree).some(n => n.type === "PartialFeedNotice"), partial); responses.unmount();
    }
  });
}

await t.test("no configured bot: Telegram button is absent even if cached/server list includes it", async () => {
  reset();
  const Login = await bundleComponent("src/screens/LoginScreen.tsx", { mocks: { ...loginMocks("ru"), "/api/auth": authMock.replace("TELEGRAM_BOT='fixture'", "TELEGRAM_BOT=''") }, real: ["/utils/loginFlow"] });
  const s = mount(Login); await s.settle();
  assert.ok(!button(s.tree, "Войти через Telegram")); assert.ok(text(s.tree).includes("Вход временно недоступен")); s.unmount();
});

const apiBuild = await build({ stdin: { contents: "export * from './src/api/client';export {fetchLoginMethods,lastLoginMethods} from './src/api/auth';export {fetchRequestsFeed,fetchMatchRides} from './src/api/requests';", resolveDir: fileURLToPath(new URL("../", import.meta.url)), loader: "ts" }, bundle: true, write: false, format: "esm", platform: "node", define: { "import.meta.env": "{}" } });
const api = await import("data:text/javascript;base64," + Buffer.from(apiBuild.outputFiles[0].text).toString("base64"));
await t.test("public login methods omit Authorization, cache empty/known list and surface transport errors", async () => {
  api.setSession("synthetic-access", "synthetic-refresh");
  globalThis.fetch = async (url, init) => {
    assert.ok(String(url).endsWith("/config/login-methods")); assert.ok(!init.headers.Authorization);
    return new Response(JSON.stringify({ methods: ["unknown", "sms", "telegram", "sms"] }), { status: 200 });
  };
  assert.deepEqual(await api.fetchLoginMethods(), ["sms", "telegram"]);
  assert.deepEqual(api.lastLoginMethods(), ["sms", "telegram"]);
  globalThis.fetch = async () => new Response(JSON.stringify({ methods: [] }), { status: 200 });
  assert.deepEqual(await api.fetchLoginMethods(), []); assert.deepEqual(api.lastLoginMethods(), []);
  globalThis.fetch = async () => { throw Error("offline"); };
  await assert.rejects(api.fetchLoginMethods(), error => error.status === 0);
  assert.deepEqual(api.lastLoginMethods(), []);
});
await t.test("ApiError preserves the code and rejects unrelated/legacy codes distinctly", async () => {
  globalThis.fetch = async () => new Response(JSON.stringify({ detail: { ru: "Способ выключен", ba: "Ысул һүндерелгән", code: "login_method_disabled" } }), { status: 403 });
  await assert.rejects(api.apiGet("/synthetic", { auth: false }), error => error instanceof api.ApiError && error.status === 403 && error.code === "login_method_disabled" && error.message === "Способ выключен");
  globalThis.fetch = async () => new Response(JSON.stringify({ detail: "phone_required" }), { status: 403 });
  await assert.rejects(api.apiGet("/synthetic", { auth: false }), error => error.code === "");
});
await t.test("HTTP header reaches typed feed and matching results without changing JSON list", async () => {
  for (const partial of [false, true]) {
    globalThis.fetch = async () => new Response(JSON.stringify([{ id: 42 }]), { status: 200, headers: partial ? { "X-Yuldash-Partial": "1" } : {} });
    assert.deepEqual(await api.fetchRequestsFeed(undefined, true), { items: [{ id: 42 }], partial });
    assert.deepEqual(await api.fetchMatchRides(7), { items: [{ id: 42 }], partial });
  }
});

await t.test("all login operations handle disabled code; legacy 403 alone keeps phone-required behavior", async () => {
  for (const operation of ["start", "verify", "smsRequest", "smsVerify"]) {
    const errors = [], refusals = [];
    const refused = async () => { throw { status: 403, code: "login_method_disabled" }; };
    const flow = createLoginFlow({ getGeneration: () => "guest", botAvailable: () => true, getStatus: e => e.status, getCode: e => e.code || "", onBusy() {}, onError: e => errors.push(e), onMethodDisabled: e => refusals.push(e), track() {}, tgStart: refused, tgVerify: refused, smsRequest: refused, smsVerify: refused });
    if (operation === "start") await flow.start();
    if (operation === "verify") await flow.verify("id", "123456", "");
    if (operation === "smsRequest") await flow.smsRequest("synthetic");
    if (operation === "smsVerify") await flow.smsVerify("synthetic", "123456", "");
    assert.equal(errors.at(-1), "methodDisabled"); assert.equal(refusals.length, 1); assert.equal(nextNeedPhone(true, errors.at(-1)), false);
  }
  for (const [code, expected] of [["phone_required", "phoneRequired"], ["", "phoneRequired"], ["unrelated_forbidden", "verify"]]) {
    const errors = [];
    const flow = createLoginFlow({ getGeneration: () => "guest", getStatus: e => e.status, getCode: e => e.code, onBusy() {}, onError: e => errors.push(e), tgVerify: async () => { throw { status: 403, code }; } });
    await flow.verify("id", "123456", ""); assert.equal(errors.at(-1), expected);
  }
});

await t.test("guard: source has no false promises or build-time SMS switch", () => {
  const root = new URL("../src/", import.meta.url);
  const source = readdirSync(root, { recursive: true, withFileTypes: true })
    .filter(entry => entry.isFile() && /\.(tsx?|js)$/.test(entry.name))
    .map(entry => readFileSync(`${entry.parentPath}/${entry.name}`, "utf8")).join("\n");
  for (const old of ["Сошлись — сажай", "Это та самая машина", "Telegram скоро", "Онлайн-оплата скоро", "Скоро: оплата картой", "Не звоним и не шлём SMS", "SMS_LOGIN_ENABLED", "VK_APP_ID", "Шифруем и не передаём третьим"]) assert.ok(!source.includes(old), `obsolete claim: ${old}`);
  assert.ok(read("src/screens/LoginScreen.tsx").includes("fetchLoginMethods"));
  for (const file of [new URL("../src/legal.ts", import.meta.url), new URL("../../web/components/legal-content.ts", import.meta.url)]) {
    const legal = readFileSync(file, "utf8");
    assert.ok(legal.includes("Вход в сервис — по одноразовому коду. Доступные способы входа"));
    assert.ok(!legal.includes("Вход в сервис — по номеру телефона и одноразовому коду из SMS"));
  }
});

t.done();
