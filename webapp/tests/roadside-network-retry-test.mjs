// Actual adapters/helper/RoadsideHelp; simulated committed server Map, not live API/DB.
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { IDBFactory } from 'fake-indexeddb';
import { fileURLToPath } from 'node:url';
import { webcrypto } from 'node:crypto';

const h = globalThis.__roadsideNetwork = {};
const root = fileURLToPath(new URL('../', import.meta.url));
const mocks = {
  react: `const h=globalThis.__roadsideNetwork;const same=(a,b)=>a&&b&&a.length===b.length&&a.every((v,i)=>Object.is(v,b[i]));
    export function useState(v){const i=h.i++;if(!(i in h.values))h.values[i]=typeof v==='function'?v():v;return[h.values[i],v=>h.values[i]=typeof v==='function'?v(h.values[i]):v];}
    export function useRef(v){const i=h.i++;return h.values[i]??={current:v};}
    export function useCallback(fn,deps){const i=h.i++,p=h.values[i];if(!p||!same(p.deps,deps))h.values[i]={fn,deps};return h.values[i].fn;}
    export function useEffect(fn,deps){const i=h.i++,p=h.values[i];if(!p||!same(p.deps,deps)){h.values[i]={deps};h.effects.push(()=>{h.cleanups[i]?.();h.cleanups[i]=fn()});}}`,
  'react/jsx-runtime': `export const Fragment='fragment';export const jsx=(type,props,key)=>({type,props:props??{},key}),jsxs=jsx;`,
  lang: `export const useLang=()=>({appText:(ru,ba)=>globalThis.__roadsideNetwork.ba?ba:ru});`,
  icons: `export const IconWarn=()=>null;`,
  analytics: `export const track=()=>{};`,
  dialog: `export default 'AlertDialog';`,
};
const compiled = await build({
  stdin: { contents: "export * from './src/api/client';export * from './src/api/safety';export * from './src/utils/sosIntent';export * from './src/utils/storageHealth';export {default as Roadside} from './src/components/RoadsideHelp';", resolveDir: root, loader: 'ts' },
  bundle: true, write: false, format: 'esm', platform: 'node', jsx: 'automatic', define: { 'import.meta.env': '{}' },
  plugins: [{ name: 'roadside-network', setup(b) {
    b.onResolve({ filter: /.*/ }, ({ path: p }) => {
      const k = Object.hasOwn(mocks, p) ? p : p.endsWith('/i18n/lang') ? 'lang' : p.endsWith('/Icons') ? 'icons' : p.endsWith('/analytics') ? 'analytics' : p.endsWith('/AlertDialog') ? 'dialog' : null;
      return k ? { path: k, namespace: 'mock' } : null;
    });
    b.onLoad({ filter: /.*/, namespace: 'mock' }, a => ({ contents: mocks[a.path], loader: 'js' }));
  } }],
});
let serial = 0;
const load = () => import('data:text/javascript;base64,' + Buffer.from(compiled.outputFiles[0].text + '\n//' + serial++).toString('base64'));
const tick = () => new Promise(setImmediate);
async function until(predicate) {
  const deadline = performance.now() + 3000;
  while (performance.now() < deadline) { if (predicate()) return; await new Promise(resolve => setTimeout(resolve, 1)); }
  assert.fail('condition did not settle');
}
const sosBody = { category: 'other', booking_id: 73, note: 'Личный SOS' };
const stuckBody = { lat: null, lng: null, note: 'Нужна помощь' };
const pendingKey = 'yuldash.sos.pending';
const realNow = Date.now;
const ok = data => new Response(JSON.stringify(data), { status: 200 });
const nodes = n => Array.isArray(n) ? n.flatMap(nodes) : !n || typeof n !== 'object' ? [] : [n, ...nodes(n.props?.children)];
function render(api, target = { kind: 'booking', id: 73 }) { h.i = 0; h.effects = []; const tree = api.Roadside({ target }); for (const fn of h.effects) fn(); return tree; }
function confirm(api, target) { nodes(render(api, target)).find(n => n.type === 'button').props.onClick(); return nodes(render(api, target)).find(n => n.type === 'AlertDialog').props.confirm.onClick; }
function cleanup() { for (const fn of h.cleanups ?? []) fn?.(); }
async function environment() {
  cleanup(); Object.assign(h, { values: [], cleanups: [], i: 0, effects: [], ba: false, now: 1800000000000 }); Date.now = () => h.now;
  const store = new Map(); globalThis.localStorage = { getItem: k => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)), removeItem: k => store.delete(k) };
  globalThis.indexedDB = new IDBFactory(); globalThis.window = { addEventListener() {}, removeEventListener() {}, setTimeout, clearTimeout };
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: {} });
  Object.defineProperty(globalThis, 'crypto', { configurable: true, value: webcrypto });
  const api = await load(); await api.setSession('access-A', 'refresh-A');
  return { api, store, owner: api.getSessionGeneration(), load };
}
let passed = 0, failed = 0;
async function check(name, fn) { try { await fn(await environment()); passed++; console.log('PASS ' + name); } catch (e) { failed++; console.error('FAIL ' + name + ': ' + e.message); } finally { cleanup(); Date.now = realNow; Object.defineProperty(globalThis, 'crypto', { configurable: true, value: webcrypto }); } }

await check('durable slots preserve SOS and roadside across recreation; ACK of SOS preserves roadside', async ({ api, owner, load }) => {
  const sos = await api.prepareSosIntent(sosBody, owner);
  const roadside = await api.prepareSosIntent(stuckBody, owner, 'roadside-booking-73');
  const restarted = await load();
  const sosRetry = await restarted.prepareSosIntent(sosBody, owner);
  assert.equal(sosRetry.key, sos.key, 'roadside displaced the unconfirmed SOS');
  const roadsideRetry = await restarted.prepareSosIntent(stuckBody, owner, 'roadside-booking-73');
  assert.equal(roadsideRetry.key, roadside.key);
  restarted.acknowledgeSosIntent(sosRetry);
  assert.equal((await (await load()).prepareSosIntent(stuckBody, owner, 'roadside-booking-73')).key, roadside.key);
});

await check('SOS lost response then roadside lost response: recreated explicit retries commit one event each', async ({ api, load }) => {
  const events = new Map(), attempts = new Map(), calls = [];
  globalThis.fetch = async (url, init) => {
    const path = new URL(url).pathname, key = init.headers['Idempotency-Key']; calls.push({ path, key });
    const count = (attempts.get(path) ?? 0) + 1; attempts.set(path, count);
    const identity = path + ':' + (key ?? 'unkeyed-' + count);
    if (!events.has(identity)) events.set(identity, events.size + 1);
    if (count === 1) throw Error('response lost after simulated commit');
    return ok({ id: events.get(identity), delivery_unconfirmed: true, hint_ru: 'Сигнал сохранён. Доставка не подтверждена.' });
  };
  await assert.rejects(api.sendSos(sosBody)); await assert.rejects(api.roadsideHelpBooking(73, stuckBody));
  const restarted = await load(); await restarted.sendSos(sosBody); await restarted.roadsideHelpBooking(73, stuckBody);
  assert.equal(events.size, 2, 'a retry created another saved event');
  assert.equal(calls[0].key, calls[2].key); assert.equal(calls[1].key, calls[3].key);
  assert.match(calls[1].key, /^[A-Za-z0-9_-]{16,128}$/);
});

for (const ba of [false, true]) await check(`${ba ? 'BA' : 'RU'} actual roadside replay shows delivery uncertainty without invented counters`, async ({ api }) => {
  h.ba = ba;
  globalThis.fetch = async () => ok({ delivery_unconfirmed: true, hint_ru: 'Сигнал сохранён. Доставка не подтверждена.', hint_ba: 'Сигнал һаҡланды. Еткереү раҫланманы.' });
  confirm(api)();
  await until(() => nodes(render(api)).some(n => n.props.role === 'status'));
  const text = JSON.stringify(render(api));
  assert.ok(text.includes(ba ? 'Сигнал һаҡланды. Еткереү раҫланманы.' : 'Сигнал сохранён. Доставка не подтверждена.'));
  assert.ok(!text.includes('undefined') && !text.includes('Близких у тебя') && !text.includes('Близким отправлено'));
});

await check('held roadside location callback cannot POST after A logs into B', async ({ api }) => {
  let geolocation; const calls = [];
  navigator.geolocation = { getCurrentPosition: ok => { geolocation = ok; } };
  globalThis.fetch = async (_u, init) => { calls.push(init); return ok({ contacts_notified: 0, contacts_total: 0 }); };
  confirm(api)(); await api.setSession('access-B', 'refresh-B'); render(api);
  geolocation({ coords: { latitude: 54, longitude: 55 }, timestamp: Date.now() });
  await tick(); await tick(); assert.equal(calls.length, 0, 'old A callback sent using B token');
});

await check('two held roadside confirmation callbacks start one POST', async ({ api }) => {
  const calls = []; globalThis.fetch = (_u, init) => new Promise(resolve => calls.push({ init, resolve }));
  const held = confirm(api); held(); held();
  try { await until(() => calls.length >= 1); await tick(); assert.equal(calls.length, 1); }
  finally { for (const call of calls) call.resolve(ok({ contacts_notified: 0, contacts_total: 0 })); await tick(); await tick(); }
});

await check('32 durable roadside slots protect SOS; overflow still sends and retains its memory key with an explicit restart limit', async ({ api, owner, store, load }) => {
  const sos = await api.prepareSosIntent(sosBody, owner), originals = new Map();
  for (let id = 1; id <= 32; id++) originals.set(id, await api.prepareSosIntent(stuckBody, owner, 'roadside-booking-' + id));
  const calls = []; globalThis.fetch = async (_u, init) => { calls.push(init); throw Error('unknown result'); };
  await assert.rejects(api.roadsideHelpBooking(33, stuckBody)); await assert.rejects(api.roadsideHelpBooking(33, stuckBody));
  assert.equal(calls[0].headers['Idempotency-Key'], calls[1].headers['Idempotency-Key']);
  const slots = JSON.parse(store.get(pendingKey)); assert.equal(Object.keys(slots).length, 33);
  assert.equal(slots.sos.key, sos.key); assert.equal(slots['roadside-booking-33'], undefined);
  for (const [id, original] of originals) assert.equal(slots['roadside-booking-' + id].key, original.key);
  const restarted = await load(); await tick(); assert.equal(calls.length, 2, 'loading module must not send overflow');
  await assert.rejects(restarted.roadsideHelpBooking(33, stuckBody));
  assert.notEqual(calls[0].headers['Idempotency-Key'], calls[2].headers['Idempotency-Key'], 'capacity refusal cannot promise restart persistence');
  assert.equal((await restarted.prepareSosIntent(sosBody, owner)).key, sos.key);
});

await check('memory overflow is bounded to32 additional scopes while durable32 and SOS remain protected', async ({ api, owner, store }) => {
  const sos = await api.prepareSosIntent(sosBody, owner);
  for (let id = 1; id <= 32; id++) await api.prepareSosIntent(stuckBody, owner, 'roadside-booking-' + id);
  const firstOverflow = await api.prepareSosIntent(stuckBody, owner, 'roadside-booking-33');
  for (let id = 34; id <= 65; id++) await api.prepareSosIntent(stuckBody, owner, 'roadside-booking-' + id);
  const retriedOverflow = await api.prepareSosIntent(stuckBody, owner, 'roadside-booking-33');
  assert.notEqual(retriedOverflow.key, firstOverflow.key, 'unbounded extra memory scopes retain overflow beyond the agreed32');
  const slots = JSON.parse(store.get(pendingKey)); assert.equal(Object.keys(slots).length, 33); assert.equal(slots.sos.key, sos.key);
  for (let id = 1; id <= 32; id++) assert.ok(slots['roadside-booking-' + id]);
});

await check('expired and future slots are purged without displacing an active SOS', async ({ api, owner, store }) => {
  const sos = await api.prepareSosIntent(sosBody, owner), sample = await api.prepareSosIntent(stuckBody, owner, 'roadside-booking-1');
  store.set(pendingKey, JSON.stringify({ sos, 'roadside-booking-1': { ...sample, createdAt: h.now - 900_001 }, 'roadside-booking-2': { ...sample, createdAt: h.now + 1 } }));
  await api.prepareSosIntent(stuckBody, owner, 'roadside-booking-3');
  const slots = JSON.parse(store.get(pendingKey)); assert.deepEqual(Object.keys(slots).sort(), ['roadside-booking-3', 'sos']); assert.equal(slots.sos.key, sos.key);
});

await check('roadside429 preserves its scope through recreation and ACK leaves pending SOS intact', async ({ api, owner, store, load }) => {
  const sos = await api.prepareSosIntent(sosBody, owner), calls = [];
  globalThis.fetch = async (_u, init) => { calls.push(init); return calls.length === 1 ? new Response('{}', { status: 429 }) : ok({ delivery_unconfirmed: true }); };
  await assert.rejects(api.roadsideHelpBooking(73, stuckBody), e => e.status === 429);
  h.now += 60_000; await (await load()).roadsideHelpBooking(73, stuckBody);
  assert.equal(calls[0].headers['Idempotency-Key'], calls[1].headers['Idempotency-Key']);
  const slots = JSON.parse(store.get(pendingKey)); assert.deepEqual(Object.keys(slots), ['sos']); assert.equal(slots.sos.key, sos.key);
});

await check('fresh coordinates or another original booking create distinct roadside intent keys', async ({ api }) => {
  const calls = []; globalThis.fetch = async (_u, init) => { calls.push(init); throw Error('unknown result'); };
  await assert.rejects(api.roadsideHelpBooking(73, { lat: 54, lng: 55 }));
  await assert.rejects(api.roadsideHelpBooking(73, { lat: 54.1, lng: 55 }));
  await assert.rejects(api.roadsideHelpBooking(74, { lat: 54.1, lng: 55 }));
  assert.equal(new Set(calls.map(c => c.headers['Idempotency-Key'])).size, 3);
});

await check('captured roadside owner is checked before hashing/POST after a new login', async ({ api, owner }) => {
  await api.setSession('access-B', 'refresh-B'); const calls = [];
  globalThis.fetch = async (_u, init) => { calls.push(init); return ok({ contacts_notified: 0, contacts_total: 0 }); };
  await assert.rejects(api.roadsideHelpBooking(73, stuckBody, { _generation: owner }), e => e.status === 409);
  assert.equal(calls.length, 0);
});

await check('optional metadata quota failure does not block either help request or mix their memory keys', async ({ api, owner }) => {
  api.activateStorageFence(); const set = localStorage.setItem;
  localStorage.setItem = (key, value) => { if (key === pendingKey) throw Error('metadata quota'); return set(key, value); };
  const calls = []; globalThis.fetch = async (url, init) => { calls.push({ path: new URL(url).pathname, key: init.headers['Idempotency-Key'] }); throw Error('response lost'); };
  await assert.rejects(api.sendSos(sosBody)); await assert.rejects(api.roadsideHelpBooking(73, stuckBody));
  await assert.rejects(api.sendSos(sosBody)); await assert.rejects(api.roadsideHelpBooking(73, stuckBody));
  assert.equal(calls[0].key, calls[2].key); assert.equal(calls[1].key, calls[3].key); assert.notEqual(calls[0].key, calls[1].key);
  assert.equal(api.storageFenced(), false); assert.equal(api.getSessionGeneration(), owner);
});

await check('order and parcel adapters retain their original unkeyed body and do not touch pending SOS', async ({ api, owner, store }) => {
  await api.prepareSosIntent(sosBody, owner); const original = store.get(pendingKey), calls = [];
  globalThis.fetch = async (url, init) => { calls.push({ path: new URL(url).pathname, init }); return ok({ contacts_notified: 2, contacts_total: 3 }); };
  const where = { lat: 54, lng: 55, note: 'Прежний контракт' };
  await api.roadsideHelpOrder(8, where); await api.roadsideHelpParcel(9, where);
  assert.deepEqual(calls.map(c => c.path), ['/instant/orders/8/stuck', '/parcels/9/stuck']);
  for (const { init } of calls) { assert.equal(init.headers['Idempotency-Key'], undefined); assert.deepEqual(JSON.parse(init.body), { note: where.note, lat: 54, lng: 55 }); }
  assert.equal(store.get(pendingKey), original);
});

for (const ba of [false, true]) for (const [counts, phrase] of [
  [{ contacts_notified: 0, contacts_total: 0 }, ['Близких у тебя', 'Ҡәҙерлеләрең өҫтәлмәгән']],
  [{ contacts_notified: 0, contacts_total: 3 }, ['позвони им сам', 'уларға үҙең шылтырат']],
  [{ contacts_notified: 2, contacts_total: 3 }, ['Сообщений близким подготовлено: 2', 'Яҡындарға әҙерләнгән хәбәрҙәр: 2']],
]) await check(`${ba ? 'BA' : 'RU'} normal first-response counters remain truthful: ${counts.contacts_notified}/${counts.contacts_total}`, async ({ api }) => {
  h.ba = ba; globalThis.fetch = async () => ok(counts); confirm(api)();
  await until(() => nodes(render(api)).some(n => n.props.role === 'status'));
  assert.ok(JSON.stringify(render(api)).includes(phrase[ba ? 1 : 0]));
});

await check('held confirmation cannot adopt target A again after A→B→A', async ({ api }) => {
  const held = confirm(api), calls = [];
  render(api, { kind: 'booking', id: 74 }); render(api, { kind: 'booking', id: 73 });
  globalThis.fetch = async (_u, init) => { calls.push(init); return ok({ contacts_notified: 0, contacts_total: 0 }); };
  held(); await tick(); await tick(); assert.equal(calls.length, 0);
});

function holdFirstDigest() {
  let release, entered = false; const gate = new Promise(resolve => { release = resolve; });
  Object.defineProperty(globalThis, 'crypto', { configurable: true, value: {
    randomUUID: webcrypto.randomUUID.bind(webcrypto), getRandomValues: webcrypto.getRandomValues.bind(webcrypto),
    subtle: { digest: async (...args) => { if (!entered) { entered = true; await gate; } return webcrypto.subtle.digest(...args); } },
  } }); return { release, entered: () => entered };
}

await check('actual roadside rechecks geolocation freshness after hash and never reuses expired coordinates', async ({ api }) => {
  let position; navigator.geolocation = { getCurrentPosition: ok => { position = ok; } };
  const digest = holdFirstDigest(), calls = []; globalThis.fetch = async (_u, init) => { calls.push(JSON.parse(init.body)); return ok({ contacts_notified: 0, contacts_total: 0 }); };
  confirm(api)(); position({ coords: { latitude: 54, longitude: 55 }, timestamp: h.now });
  await until(digest.entered); h.now += 60_000; digest.release(); await until(() => calls.length === 1);
  assert.equal(calls[0].lat, null); assert.equal(calls[0].lng, null);
});

await check('unmount while roadside hash is held prevents POST', async ({ api }) => {
  const digest = holdFirstDigest(), calls = [];
  globalThis.fetch = async (_u, init) => { calls.push(init); return ok({ contacts_notified: 0, contacts_total: 0 }); };
  confirm(api)(); await until(digest.entered); cleanup(); digest.release(); await tick(); await tick(); assert.equal(calls.length, 0);
});

for (const ba of [false, true]) await check(`${ba ? 'BA' : 'RU'} first lost ACK after saved booking-roadside says outcome is unconfirmed before retry`, async ({ api }) => {
  h.ba = ba; const saved = new Map(); let attempts = 0;
  globalThis.fetch = async (_url, init) => {
    attempts++; saved.set(init.headers['Idempotency-Key'], JSON.parse(init.body));
    throw new TypeError('response lost after simulated server commit');
  };
  confirm(api)(); await until(() => nodes(render(api)).some(n => n.props.role === 'status'));
  assert.equal(attempts, 1); assert.equal(saved.size, 1, 'saved event must exist before the lost ACK is shown');
  const text = JSON.stringify(render(api));
  assert.ok(text.includes(ba
    ? 'Ебәреүҙе раҫлап булманы. Бәйләнеште тикшер, сигналды ҡабатла йәки, мөмкин булһа, 112-гә һәм яҡындарыңа шылтырат.'
    : 'Не удалось подтвердить отправку. Проверь связь, повтори сигнал или позвони 112 и близким, если можешь.'), 'unknown ACK must not be reported as guaranteed non-delivery');
  assert.ok(!text.includes(ba ? 'Сигнал китмәне' : 'Сигнал не ушёл'));
});

for (const ba of [false, true]) await check(`${ba ? 'BA' : 'RU'} known booking-roadside429 retains its previous refusal copy`, async ({ api }) => {
  h.ba = ba; globalThis.fetch = async () => new Response('{}', { status: 429 });
  confirm(api)(); await until(() => nodes(render(api)).some(n => n.props.role === 'status'));
  const text = JSON.stringify(render(api));
  assert.ok(text.includes(ba ? 'Сигнал китмәне — бәйләнеш юҡ. Ҡәҙерлеләреңә йәки 112-гә шылтырат.' : 'Сигнал не ушёл — нет связи. Позвони близким или 112.'));
  assert.ok(!text.includes(ba ? 'Ебәреүҙе раҫлап булманы.' : 'Не удалось подтвердить отправку.'));
});

for (const ba of [false, true]) for (const [scheduled, total] of [[0, 0], [0, 2], [2, 2]]) await check(`${ba ? 'BA' : 'RU'} null-location roadside saved ${scheduled}/${total} never promises coordinates or delivery`, async ({ api }) => {
  h.ba = ba; let sent;
  globalThis.fetch = async (_url, init) => { sent = JSON.parse(init.body); return ok({ contacts_notified: scheduled, contacts_total: total }); };
  nodes(render(api)).find(n => n.type === 'button').props.onClick();
  const dialog = nodes(render(api)).find(n => n.type === 'AlertDialog');
  assert.equal(dialog.props.text, ba
    ? 'Юлдашҡа сигнал ебәрәбеҙ. Урын билдәле булһа, уны ла өҫтәйбеҙ. Хәүеф янаһа — 112-гә шылтырат.'
    : 'Отправим сигнал в Юлдаш. Если место доступно, приложим его. Если угрожает опасность — звони 112.');
  dialog.props.confirm.onClick();
  await until(() => nodes(render(api)).some(n => n.props.role === 'status'));
  const text = JSON.stringify(render(api));
  assert.ok(text.includes(ba ? 'Сигнал Юлдашта һаҡланды.' : 'Сигнал сохранён в Юлдаш.'));
  assert.ok(!text.includes('координат') && !text.includes('получил') && !text.includes('координаталарыңды алды'));
  assert.ok(sent.lat == null && sent.lng == null);
});

console.log(`Roadside network retry: ${passed} passed, ${failed} failed`);
if (failed) process.exitCode = 1;
