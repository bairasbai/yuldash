// Actual SosScreen + safety/client adapters; controlled fetch and browser storage.
// Map below models a committed server event. This is not a live API/DB proof.
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { IDBFactory } from 'fake-indexeddb';
import { fileURLToPath } from 'node:url';
import { createHash, webcrypto } from 'node:crypto';

const h = globalThis.__sosNetwork = {};
const root = fileURLToPath(new URL('../', import.meta.url));
const mocks = {
  react: `const h=globalThis.__sosNetwork;
    export function useState(v){const x=h.current,i=x.i++;if(!(i in x.values))x.values[i]=typeof v==='function'?v():v;return[x.values[i],v=>{if(x.live)x.values[i]=typeof v==='function'?v(x.values[i]):v}];}
    export function useRef(v){const x=h.current,i=x.i++;return x.values[i]??={current:v};}
    export function useCallback(fn,deps){const x=h.current,i=x.i++,p=x.values[i];if(!p||deps.some((v,j)=>v!==p.deps[j]))x.values[i]={fn,deps};return x.values[i].fn;}
    export function useEffect(fn,deps){const x=h.current,i=x.i++,p=x.values[i];if(!p||!deps||deps.some((v,j)=>v!==p.deps[j])){x.values[i]={deps};h.effects.push(()=>{x.cleanups[i]?.();x.cleanups[i]=fn()});}}`,
  'react/jsx-runtime': `export const Fragment='fragment';export const jsx=(type,props,key)=>({type,props:props??{},key}),jsxs=jsx;`,
  'react-router-dom': `export const useNavigate=()=> (...a)=>globalThis.__sosNetwork.nav.push(a);export const useSearchParams=()=>[new URLSearchParams('?booking=73')];`,
  lang: `export const useLang=()=>({appText:(ru,ba)=>globalThis.__sosNetwork.ba?ba:ru});`,
  auth: `export const useAuth=()=>({isAuthed:true,status:'authed'});`,
  header: `export const SubHeader=()=>null;`,
  icons: `export const IconPhone=()=>null,IconShield=()=>null,IconCheck=()=>null,IconWarn=()=>null,IconHospital=()=>null,IconHeart=()=>null,IconCar=()=>null,IconPin=()=>null,IconCopy=()=>null;`,
  analytics: `export const track=()=>{};`,
};
const compiled = await build({
  stdin: { contents: "export * from './src/api/client';export * from './src/api/safety';export * from './src/utils/sosIntent';export * from './src/utils/privacy';export * from './src/utils/storageHealth';export {default as Screen} from './src/screens/SosScreen';", resolveDir: root, loader: 'ts' },
  bundle: true, write: false, format: 'esm', platform: 'node', jsx: 'automatic', define: { 'import.meta.env': '{}' },
  plugins: [{ name: 'sos-network', setup(b) {
    b.onResolve({ filter: /.*/ }, ({ path: p }) => {
      const k = Object.hasOwn(mocks, p) ? p : p.endsWith('/i18n/lang') ? 'lang' : p.endsWith('/auth/AuthProvider') ? 'auth' : p.endsWith('/ConsentsScreen') ? 'header' : p.endsWith('/components/Icons') ? 'icons' : p.endsWith('/analytics') ? 'analytics' : null;
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
const ok = (id = 1) => new Response(JSON.stringify({ id, user_id: 101, category: 'other', status: 'open' }), { status: 200 });
const body = { category: 'other', booking_id: 73, note: 'Личный текст без места' };
const pendingKey = 'yuldash.sos.pending';
const savedSos = store => { const value = JSON.parse(store.get(pendingKey)); return value?.sos ?? value; };
const realNow = Date.now;
function unmount() { for (const x of h.instances?.values() ?? []) { x.live = false; x.cleanups.forEach(fn => fn?.()); } h.instances = new Map(); }
function render(Screen) {
  const seen = new Set(); h.effects = [];
  function visit(n, path) {
    if (Array.isArray(n)) return n.map((v, i) => visit(v, path + '.' + i));
    if (!n || typeof n !== 'object') return n;
    if (typeof n.type === 'function') {
      const id = path + ':' + (n.key ?? ''); seen.add(id);
      let x = h.instances.get(id);
      if (!x || x.type !== n.type) { x = { type: n.type, values: [], cleanups: [], live: true }; h.instances.set(id, x); }
      x.i = 0; h.current = x; return visit(n.type(n.props), id + '.child');
    }
    return { ...n, props: { ...n.props, children: visit(n.props?.children, path + '.children') } };
  }
  const tree = visit({ type: Screen, props: {} }, 'root');
  for (const [id, x] of h.instances) if (!seen.has(id)) { x.live = false; x.cleanups.forEach(fn => fn?.()); h.instances.delete(id); }
  for (const fn of h.effects) fn(); return tree;
}
const nodes = n => Array.isArray(n) ? n.flatMap(nodes) : !n || typeof n !== 'object' ? [] : [n, ...nodes(n.props?.children)];
const fire = tree => nodes(tree).find(n => n.type === 'button' && n.props.className?.split(' ').includes('btn-danger'));
async function environment() {
  unmount(); Object.assign(h, { instances: new Map(), nav: [], effects: [], ba: false, now: 1800000000000 });
  Date.now = () => h.now;
  const store = new Map();
  const storage = values => ({ getItem: k => values.get(k) ?? null, setItem: (k, v) => values.set(k, String(v)), removeItem: k => values.delete(k), get length() { return values.size; }, key: i => [...values.keys()][i] ?? null });
  globalThis.localStorage = storage(store);
  globalThis.sessionStorage = storage(new Map());
  globalThis.indexedDB = new IDBFactory();
  globalThis.window = { addEventListener() {}, removeEventListener() {}, setTimeout, clearTimeout };
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: {} });
  Object.defineProperty(globalThis, 'crypto', { configurable: true, value: webcrypto });
  const api = await load(); await api.setSession('access-A', 'refresh-A');
  return { api, store, owner: api.getSessionGeneration(), load };
}
let passed = 0, failed = 0;
async function check(name, fn) { try { await fn(await environment()); passed++; console.log('PASS ' + name); } catch (e) { failed++; console.error('FAIL ' + name + ': ' + e.message); } finally { unmount(); Date.now = realNow; Object.defineProperty(globalThis, 'crypto', { configurable: true, value: webcrypto }); } }

await check('saved SOS response lost: explicit same-body retry after module recreation uses one identity', async ({ api, load }) => {
  const events = new Map(), calls = [];
  globalThis.fetch = async (_url, init) => {
    const key = init.headers['Idempotency-Key']; calls.push(key);
    const identity = key ?? 'unkeyed-' + calls.length;
    if (!events.has(identity)) events.set(identity, events.size + 1);
    if (calls.length === 1) throw new TypeError('response lost after simulated commit');
    return ok(events.get(identity));
  };
  await assert.rejects(api.sendSos(body), e => e.status === 0);
  assert.equal(events.size, 1, 'first request must have committed before its response is lost');
  const recreated = await load(); await recreated.sendSos(body);
  assert.equal(calls.length, 2);
  assert.equal(events.size, 1, 'retry created a second SOS');
  assert.match(calls[0], /^[A-Za-z0-9_-]{16,128}$/); assert.equal(calls[0], calls[1]);
});

await check('captured owner A cannot send its SOS using a new login B', async ({ api, owner }) => {
  await api.setSession('access-B', 'refresh-B'); const calls = [];
  globalThis.fetch = async (_u, init) => { calls.push(init); return ok(); };
  await assert.rejects(api.sendSos(body, { _generation: owner }), e => e.status === 409);
  assert.equal(calls.length, 0);
});

await check('401 refresh replays the exact SOS body and identity without changing its owner', async ({ api, owner }) => {
  const calls = [];
  api.setRefreshHandler(async () => api.rotateSession('access-A-new', 'refresh-A-new', owner));
  globalThis.fetch = async (_u, init) => { calls.push(init); return calls.length === 1 ? new Response('{}', { status: 401 }) : ok(); };
  await api.sendSos(body, { _generation: owner });
  assert.equal(calls.length, 2); assert.equal(calls[0].body, calls[1].body);
  assert.match(calls[0].headers['Idempotency-Key'], /^[A-Za-z0-9_-]{16,128}$/);
  assert.equal(calls[0].headers['Idempotency-Key'], calls[1].headers['Idempotency-Key']);
  assert.equal(api.getSessionGeneration(), owner);
  assert.equal(calls[1].headers.Authorization, 'Bearer access-A-new');
});

await check('actual SOS button: two held clicks before render start one POST', async ({ api }) => {
  const requests = [];
  globalThis.fetch = (_u, init) => new Promise(resolve => requests.push({ init, resolve }));
  const held = fire(render(api.Screen)).props.onClick;
  const first = held(), second = held();
  try { await until(() => requests.length >= 1); assert.equal(requests.length, 1, 'double click started two SOS writes'); }
  finally { for (const r of requests) r.resolve(ok()); await Promise.allSettled([first, second]); }
});

await check('pending contains only owner/key/SHA/time and hashes the exact canonical outgoing body', async ({ api, store, owner }) => {
  const input = { category: 'medical', note: 'Очень личная заметка', lat: 54.12345, lng: 55.67891, booking_id: 73 };
  let wire;
  globalThis.fetch = async (_u, init) => { wire = init.body; throw Error('unknown result'); };
  await assert.rejects(api.sendSos(input));
  const raw = store.get(pendingKey), pending = savedSos(store);
  assert.deepEqual(Object.keys(pending).sort(), ['createdAt', 'fingerprint', 'key', 'owner']);
  assert.equal(pending.owner, owner);
  assert.equal(pending.fingerprint, createHash('sha256').update(wire).digest('hex'));
  assert.deepEqual(JSON.parse(wire), { booking_id: 73, category: 'medical', lat: 54.12345, lng: 55.67891, note: input.note, order_id: null });
  for (const secret of [input.note, '54.12345', '55.67891', 'access-A', 'refresh-A']) assert.ok(!raw.includes(secret));
});

await check('undefined and explicit null optional fields share the exact normalized retry body', async ({ api, load }) => {
  const calls = [];
  globalThis.fetch = async (_u, init) => { calls.push(init); if (calls.length === 1) throw Error('lost'); return ok(); };
  await assert.rejects(api.sendSos({ category: 'other' }));
  await (await load()).sendSos({ category: 'other', booking_id: null, order_id: null, note: '', lat: null, lng: null });
  assert.equal(calls[0].body, calls[1].body);
  assert.equal(calls[0].headers['Idempotency-Key'], calls[1].headers['Idempotency-Key']);
});

await check('429 retains pending identity for explicit delayed retry; module loading sends nothing', async ({ api, store, load }) => {
  const calls = [];
  globalThis.fetch = async (_u, init) => { calls.push(init); return calls.length === 1 ? new Response('{}', { status: 429, headers: { 'Retry-After': '60' } }) : ok(); };
  await assert.rejects(api.sendSos(body), e => e.status === 429);
  const saved = store.get(pendingKey), restarted = await load(); await tick(); await tick();
  assert.equal(calls.length, 1, 'restart must not auto-send SOS'); assert.equal(store.get(pendingKey), saved);
  h.now += 60_000; await restarted.sendSos(body);
  assert.equal(calls[0].headers['Idempotency-Key'], calls[1].headers['Idempotency-Key']);
  assert.equal(store.has(pendingKey), false);
});

for (const [name, change] of [['note', { note: 'Другой сигнал' }], ['category', { category: 'medical' }], ['booking', { booking_id: 74 }], ['fresh coordinates', { lat: 54, lng: 55 }]]) {
  await check(`changed ${name} creates a new signal identity`, async ({ api }) => {
    const calls = []; globalThis.fetch = async (_u, init) => { calls.push(init); throw Error('unknown result'); };
    await assert.rejects(api.sendSos(body)); await assert.rejects(api.sendSos({ ...body, ...change }));
    assert.equal(calls.length, 2); assert.notEqual(calls[0].headers['Idempotency-Key'], calls[1].headers['Idempotency-Key']);
  });
}

for (const malformed of ['invalid JSON', 'missing fingerprint', 'invalid key', 'future time', 'expired time', 'other owner', 'extra raw note']) {
  await check(`${malformed} pending cannot block a new SOS or supply its key`, async ({ api, store, owner }) => {
    const canonical = JSON.stringify({ booking_id: 73, category: 'other', lat: null, lng: null, note: body.note, order_id: null });
    const poison = { owner, key: 'old-pending-key-12345', fingerprint: createHash('sha256').update(canonical).digest('hex'), createdAt: h.now };
    if (malformed === 'missing fingerprint') delete poison.fingerprint;
    if (malformed === 'invalid key') poison.key = '!';
    if (malformed === 'future time') poison.createdAt++;
    if (malformed === 'expired time') poison.createdAt -= 900_001;
    if (malformed === 'other owner') poison.owner = 'other-login';
    if (malformed === 'extra raw note') poison.note = 'must not be restored';
    store.set(pendingKey, malformed === 'invalid JSON' ? '{broken' : JSON.stringify(poison));
    const calls = []; globalThis.fetch = async (_u, init) => { calls.push(init); return ok(); };
    await api.sendSos(body); assert.equal(calls.length, 1);
    assert.match(calls[0].headers['Idempotency-Key'], /^[A-Za-z0-9_-]{16,128}$/);
    assert.notEqual(calls[0].headers['Idempotency-Key'], poison.key);
  });
}

await check('retry at fifteen minutes retains original timestamp; later retry gets a new identity', async ({ api, owner, store }) => {
  const first = await api.prepareSosIntent(body, owner); h.now += 900_000;
  globalThis.fetch = async () => { throw Error('unknown'); };
  await assert.rejects(api.sendSos(body));
  assert.deepEqual(savedSos(store), first);
  h.now++; await assert.rejects(api.sendSos(body));
  assert.notEqual(savedSos(store).key, first.key);
});

await check('new login by the same user cannot reuse the previous login identity', async ({ api, owner, store }) => {
  const calls = []; globalThis.fetch = async (_u, init) => { calls.push(init); throw Error('unknown'); };
  await assert.rejects(api.sendSos(body)); await api.setSession('access-A', 'refresh-A');
  assert.notEqual(api.getSessionGeneration(), owner); await assert.rejects(api.sendSos(body));
  assert.notEqual(calls[0].headers['Idempotency-Key'], calls[1].headers['Idempotency-Key']);
  assert.equal(savedSos(store).owner, api.getSessionGeneration());
});

await check('logout personal cleanup removes pending metadata and a later login starts fresh', async ({ api, store }) => {
  const calls = []; globalThis.fetch = async (_u, init) => { calls.push(init); throw Error('unknown'); };
  await assert.rejects(api.sendSos(body)); assert.ok(store.has(pendingKey));
  api.clearPersonalLocal(); assert.equal(store.has(pendingKey), false);
  await api.setSession(null, null); await api.setSession('access-A', 'refresh-A');
  await assert.rejects(api.sendSos(body));
  assert.notEqual(calls[0].headers['Idempotency-Key'], calls[1].headers['Idempotency-Key']);
});

await check('late success acknowledges only its own intent, preserving a newer unknown result', async ({ api, store }) => {
  const calls = []; let release;
  globalThis.fetch = (_u, init) => {
    calls.push(init);
    if (calls.length === 1) return new Promise(resolve => { release = resolve; });
    if (calls.length === 2) return Promise.reject(Error('second response lost'));
    return Promise.resolve(ok(2));
  };
  const first = api.sendSos(body); await until(() => calls.length === 1);
  const nextBody = { ...body, note: 'Новое намерение' };
  await assert.rejects(api.sendSos(nextBody)); const newer = store.get(pendingKey);
  release(ok()); await first; assert.equal(store.get(pendingKey), newer);
  await api.sendSos(nextBody); assert.equal(calls[1].headers['Idempotency-Key'], calls[2].headers['Idempotency-Key']);
  assert.equal(store.has(pendingKey), false);
});

await check('pending write failure uses memory without quarantining valid auth', async ({ api, owner, store }) => {
  api.activateStorageFence(); const set = localStorage.setItem;
  localStorage.setItem = (k, v) => { if (k === pendingKey) throw Error('metadata quota'); return set(k, v); };
  const calls = []; globalThis.fetch = async (_u, init) => { calls.push(init); if (calls.length === 1) throw Error('lost'); return ok(); };
  await assert.rejects(api.sendSos(body)); assert.equal(store.has(pendingKey), false);
  await api.sendSos(body);
  assert.equal(calls[0].headers['Idempotency-Key'], calls[1].headers['Idempotency-Key']);
  assert.equal(api.storageFenced(), false); assert.equal(api.getSessionGeneration(), owner); assert.equal(api.getToken(), 'access-A');
});

await check('pending read failure does not quarantine auth or prevent SOS', async ({ api, owner }) => {
  api.activateStorageFence(); const get = localStorage.getItem;
  localStorage.getItem = k => { if (k === pendingKey) throw Error('metadata unreadable'); return get(k); };
  let calls = 0; globalThis.fetch = async () => { calls++; return ok(); };
  await api.sendSos(body); assert.equal(calls, 1); assert.equal(api.storageFenced(), false); assert.equal(api.getSessionGeneration(), owner);
});

await check('ack removal failure preserves confirmed success; a later intentional signal gets a fresh key', async ({ api, owner }) => {
  api.activateStorageFence(); const remove = localStorage.removeItem;
  localStorage.removeItem = k => { if (k === pendingKey) throw Error('metadata cleanup failed'); return remove(k); };
  const calls = []; globalThis.fetch = async (_u, init) => { calls.push(init); return ok(calls.length); };
  assert.equal((await api.sendSos(body)).id, 1); assert.equal((await api.sendSos(body)).id, 2);
  assert.notEqual(calls[0].headers['Idempotency-Key'], calls[1].headers['Idempotency-Key']);
  assert.equal(api.storageFenced(), false); assert.equal(api.getSessionGeneration(), owner);
});

await check('failed persistence has an explicit restart limit: no saved key is invented after recreation', async ({ api, load }) => {
  const set = localStorage.setItem;
  localStorage.setItem = (k, v) => { if (k === pendingKey) throw Error('metadata quota'); return set(k, v); };
  const calls = []; globalThis.fetch = async (_u, init) => { calls.push(init); throw Error('unknown'); };
  await assert.rejects(api.sendSos(body)); await assert.rejects((await load()).sendSos(body));
  assert.notEqual(calls[0].headers['Idempotency-Key'], calls[1].headers['Idempotency-Key'], 'disk failure cannot promise restart deduplication');
});

function holdFirstDigest() {
  let release, entered = false;
  const gate = new Promise(resolve => { release = resolve; });
  Object.defineProperty(globalThis, 'crypto', { configurable: true, value: {
    randomUUID: webcrypto.randomUUID.bind(webcrypto), getRandomValues: webcrypto.getRandomValues.bind(webcrypto),
    subtle: { digest: async (...args) => { if (!entered) { entered = true; await gate; } return webcrypto.subtle.digest(...args); } },
  } });
  return { release, entered: () => entered };
}

await check('owner changes during fingerprint await: no metadata write and no POST under the new login', async ({ api, owner, store }) => {
  const digest = holdFirstDigest(), calls = [];
  globalThis.fetch = async (_u, init) => { calls.push(init); return ok(); };
  const pending = api.sendSos(body, { _generation: owner });
  const refused = assert.rejects(pending, e => e.status === 409);
  await until(digest.entered); await api.setSession('access-B', 'refresh-B'); digest.release(); await refused;
  assert.equal(calls.length, 0); assert.equal(store.has(pendingKey), false);
});

await check('actual component rechecks location age after fingerprint await before sending', async ({ api }) => {
  let position;
  navigator.geolocation = { getCurrentPosition: ok => { position = ok; } };
  render(api.Screen); position({ coords: { latitude: 54, longitude: 55 }, timestamp: h.now });
  const tree = render(api.Screen), digest = holdFirstDigest(), calls = [];
  globalThis.fetch = async (_u, init) => { calls.push(JSON.parse(init.body)); return ok(); };
  const pending = fire(tree).props.onClick(); await until(digest.entered);
  h.now += 60_000; digest.release(); await pending;
  assert.equal(calls.length, 1); assert.equal(calls[0].lat, null); assert.equal(calls[0].lng, null);
});

await check('actual component unmount during fingerprint await cannot start its old POST', async ({ api }) => {
  const digest = holdFirstDigest(), calls = [];
  globalThis.fetch = async (_u, init) => { calls.push(init); return ok(); };
  const pending = fire(render(api.Screen)).props.onClick(); await until(digest.entered);
  unmount(); digest.release(); await pending; assert.equal(calls.length, 0);
});

for (const ba of [false, true]) await check(`${ba ? 'BA' : 'RU'} first lost ACK after saved SOS says outcome is unconfirmed before any retry`, async ({ api }) => {
  h.ba = ba; const saved = new Map(); let attempts = 0;
  globalThis.fetch = async (_url, init) => {
    attempts++; saved.set(init.headers['Idempotency-Key'], JSON.parse(init.body));
    throw new TypeError('response lost after simulated server commit');
  };
  await fire(render(api.Screen)).props.onClick();
  assert.equal(attempts, 1); assert.equal(saved.size, 1, 'first request must be saved before the transport loses its ACK');
  const tree = render(api.Screen), text = JSON.stringify(tree);
  assert.ok(text.includes(ba
    ? 'Ебәреүҙе раҫлап булманы. Бәйләнеште тикшер, сигналды ҡабатла йәки, мөмкин булһа, 112-гә һәм яҡындарыңа шылтырат.'
    : 'Не удалось подтвердить отправку. Проверь связь, повтори сигнал или позвони 112 и близким, если можешь.'), 'unknown ACK must not claim that the signal was not saved');
  assert.ok(!text.includes(ba ? 'Ебәреп булманы.' : 'Не удалось отправить.'));
  assert.ok(nodes(tree).some(n => n.type === 'strong' && n.props.children === (ba ? 'Ебәреүҙе раҫлап булманы' : 'Не удалось подтвердить отправку')), 'unknown ACK heading must not claim a failed save');
  assert.ok(!text.includes(ba ? 'Сигнал ебәрелмәне' : 'Сигнал не отправлен'));
  assert.ok(nodes(tree).some(n => n.props.href === 'tel:112'));
});

for (const ba of [false, true]) await check(`${ba ? 'BA' : 'RU'} known SOS429 retains its previous refusal copy`, async ({ api }) => {
  h.ba = ba; globalThis.fetch = async () => new Response('{}', { status: 429 });
  await fire(render(api.Screen)).props.onClick();
  const tree = render(api.Screen), text = JSON.stringify(tree);
  assert.ok(nodes(tree).some(n => n.type === 'strong' && n.props.children === (ba ? 'Сигнал ебәрелмәне' : 'Сигнал не отправлен')), 'known429 heading remains unchanged');
  assert.ok(text.includes(ba ? 'Ебәреп булманы. Юғарыла хеҙмәткә шылтырат — тиҙерәк.' : 'Не удалось отправить. Позвони в службу выше — это быстрее.'));
  assert.ok(!text.includes(ba ? 'Ебәреүҙе раҫлап булманы.' : 'Не удалось подтвердить отправку.'));
});

console.log(`SOS network retry: ${passed} passed, ${failed} failed`);
if (failed) process.exitCode = 1;
