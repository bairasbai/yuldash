// Выполняет production-компоненты старых кодов и погашения, без DOM и без сети.
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { build } from "esbuild";
const paths = ["CouponsScreen", "PartnerCabinetScreen", "BenefitHistoryScreen"];
const entries = Object.fromEntries(paths.map((name) => [name, fileURLToPath(new URL(`../src/screens/${name}.tsx`, import.meta.url))]));
entries.BenefitOwner = fileURLToPath(new URL("../src/benefitOwner.ts", import.meta.url));
const imports = new Map();
for (const entry of Object.values(entries)) {
  const source = await readFile(entry, "utf8");
  for (const [, clause, path] of source.matchAll(/import\s+([\s\S]*?)\s+from\s+"([^"]+)";/g)) {
    if (path === "./CouponsScreen" || path === "./PartnerCabinetScreen" || path === "../benefitOwner") continue;
    const names = (clause.match(/\{([\s\S]*?)\}/)?.[1] ?? "").split(",").map((n) => n.trim()).filter((n) => n && !n.startsWith("type "));
    const known = imports.get(path) ?? new Set();
    names.forEach((name) => known.add(name)); imports.set(path, known);
  }
}
const mocks = Object.fromEntries([...imports].map(([path, names]) => [path,
  "export default function Stub(){return null;}\n" + [...names].map((name) => `export const ${name}=()=>null;`).join("\n")]));
mocks.react = `const h=()=>globalThis.__benefits;
export const useState=initial=>{const s=h(),i=s.cursor++;if(!(i in s.slots))s.slots[i]=typeof initial==='function'?initial():initial;return[s.slots[i],v=>{s.slots[i]=typeof v==='function'?v(s.slots[i]):v;s.dirty=true;}];};
export const useRef=value=>{const s=h(),i=s.cursor++;return s.slots[i]??={current:value};};
const same=(a,b)=>a&&b&&a.length===b.length&&a.every((v,i)=>Object.is(v,b[i]));
export const useCallback=(fn,deps)=>{const s=h(),i=s.cursor++,old=s.slots[i];if(!old||!same(old.deps,deps))s.slots[i]={fn,deps};return s.slots[i].fn;};
export const useMemo=(fn,deps)=>{const s=h(),i=s.cursor++,old=s.slots[i];if(!old||!same(old.deps,deps))s.slots[i]={value:fn(),deps};return s.slots[i].value;};
export const useEffect=(fn,deps)=>{const s=h(),i=s.cursor++,old=s.slots[i];if(!old||!same(old.deps,deps)){s.effects.push(()=>{old?.cleanup?.();s.slots[i]={deps,cleanup:fn()};});}};`;
mocks["react/jsx-runtime"] = "export const Fragment='fragment';export const jsx=(type,props)=>({type,props});export const jsxs=jsx;";
mocks["../i18n/lang"] = "export const useLang=()=>({lang:globalThis.__benefits.lang,appText:(ru,ba)=>globalThis.__benefits.lang==='ba'?ba:ru});";
mocks["../auth/AuthProvider"] = "export const useAuth=()=>({isAuthed:!!globalThis.__benefits.token,status:globalThis.__benefits.status,user:{id:globalThis.__benefits.userId,city:'Уфа'}});";
mocks["./auth/AuthProvider"] = mocks["../auth/AuthProvider"];
mocks["react-router-dom"] = "export const useNavigate=()=>()=>{};";
mocks["../api/client"] = "export class ApiError extends Error{constructor(status,message){super(message);this.status=status;}} export const getToken=()=>globalThis.__benefits.token; export const getSessionGeneration=()=>globalThis.__benefits.generation;";
mocks["./api/client"] = mocks["../api/client"];
mocks["../components/ComingSoon"] = "export default function ComingSoon(){return null;}";
const apiNames = [...imports.get("../api/coupons")];
mocks["../api/coupons"] = apiNames.map((name) => `export const ${name}=(...args)=>{
const h=globalThis.__benefits;h.calls.push({name:'${name}',args});
if('${name}'==='fetchMyCoupons')return h.pendingMine??Promise.resolve(h.mine);
if('${name}'==='fetchPartnerMe')return h.pendingPartner??Promise.resolve({partner:h.partner});
if('${name}'==='redeemCoupon')return h.pendingRedeem??Promise.resolve({coupon_title:'Старый купон',discount_text:'10%',customer_name:'Тестовый клиент'});
if(h.allowNew&&'${name}'==='fetchCoupons')return Promise.resolve([h.coupon]);
if(h.allowNew&&'${name}'==='activateCoupon')return Promise.resolve({code:'NEW123',coupon:h.coupon});
throw new Error('Forbidden new business operation ${name}');};`).join("\n");
async function compile(entry) {
  const output = await build({ entryPoints: [entry], bundle: true, write: false, platform: "node", format: "esm", jsx: "automatic",
    plugins: [{ name: "benefit-fixtures", setup(b) {
      b.onResolve({ filter: /.*/ }, (a) => a.path in mocks ? { path: a.path, namespace: "fixture" } : undefined);
      b.onLoad({ filter: /.*/, namespace: "fixture" }, (a) => ({ contents: mocks[a.path], loader: "js" }));
    } }] });
  return import("data:text/javascript;base64," + Buffer.from(output.outputFiles[0].text).toString("base64"));
}
const coupons = await compile(entries.CouponsScreen), benefits = await compile(entries.BenefitHistoryScreen);
function nodes(node) { return !node || typeof node !== "object" ? [] : [node, ...[node.props?.children].flat(Infinity).flatMap(nodes)]; }
function text(node) { return typeof node === "string" ? node : !node || typeof node !== "object" ? "" : [node.props?.children].flat(Infinity).map(text).join(" "); }
function button(h, label) { return nodes(h.tree).find((n) => n.type === "button" && text(n) === label); }
function component(h, name) { return nodes(h.tree).find((n) => typeof n.type === "function" && n.type.name === name); }
async function settle(h, render) {
  for (let turn = 0; turn < 30; turn++) {
    if (h.dirty) { h.dirty = false; h.cursor = 0; h.effects = []; h.tree = render(); for (const effect of h.effects) effect(); }
    await Promise.resolve();
  }
  assert.equal(h.dirty, false);
}
function fresh(lang, extras = {}) {
  const coupon = { id: 7, title: "Старый купон", discount_text: "10%", city: "Уфа", partner: { id: 4, name: "Местное кафе", category: "cafe" } };
  return globalThis.__benefits = { lang, generation: "A", token: "local-token", status: "authed", userId: 1, slots: [], cursor: 0, effects: [], dirty: true, calls: [], coupon,
    mine: [{ code: "A1B2C3", status: "reserved", coupon }], partner: { id: 4, name: "Местное кафе", status: "active" }, ...extras };
}
let total = 0;
for (const lang of ["ru", "ba"]) {
  {
    const h = fresh(lang), render = () => coupons.default({ historyOnly: true });
    await settle(h, render);
    assert.match(text(h.tree), /A1B2C3/);
    assert.equal(button(h, lang === "ru" ? "Скидки рядом" : "Яҡындағы ташламалар"), undefined);
    assert.doesNotMatch(text(h.tree), /Разместить свою скидку|Юлдашта үҙ ташламаңды урынлаштыр/);
    assert.deepEqual(h.calls.map((c) => c.name), ["fetchMyCoupons"]);
    total++;
  }
  {
    const h = fresh(lang, { mine: [] }), render = () => coupons.default({ historyOnly: true });
    await settle(h, render);
    assert.equal(component(h, "ComingSoon")?.props.kind, "business");
    assert.deepEqual(h.calls.map((c) => c.name), ["fetchMyCoupons"]);
    total++;
  }
  {
    const h = fresh(lang), render = () => benefits.ExistingPartnerCode();
    await settle(h, render);
    const label = lang === "ru" ? "Погасить код клиента" : "Клиент кодын һүндереү";
    assert.ok(button(h, label));
    button(h, label).props.onClick(); await settle(h, render);
    const card = component(h, "RedeemCard"); assert.ok(card);
    assert.deepEqual(h.calls.map((c) => c.name), ["fetchPartnerMe"]);
    h.slots = []; h.dirty = true;
    const renderCard = () => card.type(card.props);
    await settle(h, renderCard);
    nodes(h.tree).find((n) => n.type === "input").props.onChange({ target: { value: "a1b2c3" } });
    await settle(h, renderCard);
    button(h, lang === "ru" ? "Погасить" : "Һүндереү").props.onClick();
    await settle(h, renderCard);
    assert.match(text(h.tree), lang === "ru" ? /Скидка подтверждена/ : /Ташлама раҫланды/);
    assert.deepEqual(h.calls.map((c) => c.name), ["fetchPartnerMe", "redeemCoupon"]);
    assert.equal(h.calls[1].args[0], "A1B2C3");
    total++;
  }
  {
    const h = fresh(lang, { partner: null }), render = () => benefits.ExistingPartnerCode();
    await settle(h, render);
    assert.equal(component(h, "ComingSoon")?.props.kind, "business");
    assert.deepEqual(h.calls.map((c) => c.name), ["fetchPartnerMe"]);
    total++;
  }
}
// Положительный контроль: обычная включённая витрина по-прежнему может выдать новый код.
{
  const h = fresh("ru", { allowNew: true }), render = () => coupons.default();
  await settle(h, render);
  const activate = nodes(h.tree).find((n) => n.type === "button" && /Активировать/.test(text(n)));
  assert.ok(activate); activate.props.onClick(); await settle(h, render);
  assert.ok(h.calls.some((c) => c.name === "activateCoupon"));
  assert.match(text(h.tree), /NEW123/);
  total++;
}

function deferred() { let resolve; const promise = new Promise((done) => { resolve = done; }); return { promise, resolve }; }
function retire(h) { for (const slot of h.slots) slot?.cleanup?.(); h.tree = null; h.dirty = false; }
function guardedHistory(h) {
  h.cursor = 0; h.effects = []; const child = benefits.default({ partner: false });
  for (const effect of h.effects) effect();
  const cleanups = h.slots.filter((slot) => slot?.cleanup).map((slot) => slot.cleanup);
  h.slots = []; h.cursor = 0; h.effects = []; h.dirty = true;
  return { render: () => child.type(child.props), cleanups };
}
for (const transition of ["logout", "owner", "unmount"]) {
  {
    const late = deferred(), h = fresh("ru", { pendingMine: late.promise }), stand = guardedHistory(h);
    await settle(h, stand.render);
    if (transition === "unmount") { stand.cleanups.forEach((fn) => fn()); retire(h); }
    else { h.generation = "B"; h.userId = 2; if (transition === "logout") { h.token = null; h.status = "guest"; } h.dirty = true; }
    late.resolve(h.mine); await settle(h, stand.render);
    assert.doesNotMatch(text(h.tree), /A1B2C3/);
    assert.deepEqual(h.calls.map((call) => call.name), ["fetchMyCoupons"]);
    total++;
  }
  {
    const late = deferred(), h = fresh("ru", { pendingPartner: late.promise }), render = () => benefits.ExistingPartnerCode();
    await settle(h, render);
    if (transition === "unmount") retire(h);
    else { h.generation = "B"; h.userId = 2; if (transition === "logout") { h.token = null; h.status = "guest"; } h.dirty = true; }
    late.resolve({ partner: h.partner }); await settle(h, render);
    assert.doesNotMatch(text(h.tree), /Местное кафе/);
    assert.equal(button(h, "Погасить код клиента"), undefined);
    total++;
  }
}
for (const started of [false, true]) {
  const h = fresh("ru"), render = () => benefits.ExistingPartnerCode();
  await settle(h, render); button(h, "Погасить код клиента").props.onClick(); await settle(h, render);
  const card = component(h, "RedeemCard"); assert.ok(card);
  const ownerCleanup = h.slots.filter((slot) => slot?.cleanup).map((slot) => slot.cleanup);
  h.slots = []; h.dirty = true;
  const renderCard = () => card.type(card.props); await settle(h, renderCard);
  nodes(h.tree).find((node) => node.type === "input").props.onChange({ target: { value: "A1B2C3" } }); await settle(h, renderCard);
  const action = button(h, "Погасить").props.onClick;
  const late = deferred();
  if (started) { h.pendingRedeem = late.promise; action(); await settle(h, renderCard); }
  h.generation = "B"; h.userId = 2;
  if (!started) action();
  else late.resolve({ coupon_title: "Старый купон", discount_text: "10%", customer_name: "Тестовый клиент" });
  await settle(h, renderCard);
  assert.equal(h.calls.filter((call) => call.name === "redeemCoupon").length, started ? 1 : 0);
  assert.doesNotMatch(text(h.tree), /Скидка подтверждена/);
  ownerCleanup.forEach((fn) => fn());
  total++;
}
console.log(`Existing benefit UI: ${total}/${total} passed`);
