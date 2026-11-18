// Separate mounted gate: TEST_JSDOM_MODULE_PATH must identify an available jsdom
// entry/module directory. No dependency installation and no browser-visual claim.
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { build } from 'esbuild';
import { IDBFactory } from 'fake-indexeddb';
const require = createRequire(import.meta.url);
if (!process.env.TEST_JSDOM_MODULE_PATH) {
  console.error('NOT_RUN: mounted gate requires explicit TEST_JSDOM_MODULE_PATH');
  process.exit(2);
}
const jsdomEntry = require.resolve(resolve(process.env.TEST_JSDOM_MODULE_PATH));
const { JSDOM } = require(jsdomEntry);
console.log(`Mounted dependencies: React ${require("react/package.json").version}, jsdom ${createRequire(jsdomEntry)("../package.json").version}`);
const dom = new JSDOM('<div id="root"></div>', { url: 'http://127.0.0.1:19118/taxi', pretendToBeVisual: true });
for (const key of ['window', 'document', 'navigator', 'location', 'localStorage', 'sessionStorage', 'HTMLElement', 'Node', 'Event', 'MouseEvent']) {
  Object.defineProperty(globalThis, key, { value: dom.window[key], configurable: true });
}
// jsdom has no browser BroadcastChannel; do not accidentally use Node's live handle.
globalThis.BroadcastChannel = undefined; // Capability fixture: cross-tab delivery is not tested.
// Bundled React 18 act uses its browser MessageChannel fallback; Node ports
// otherwise outlive the jsdom window. Track only this test's channels for teardown.
const NativeMessageChannel = globalThis.MessageChannel;
const channels = [];
globalThis.MessageChannel = class extends NativeMessageChannel {
  constructor() { super(); channels.push(this); }
};
globalThis.indexedDB = new IDBFactory();
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} });
const timers = new Map();
const originalSetTimeout = globalThis.setTimeout;
const originalClearTimeout = globalThis.clearTimeout;
globalThis.setTimeout = (fn, ms, ...args) => {
  let handle;
  handle = originalSetTimeout(() => { timers.delete(handle); fn(...args); }, ms);
  timers.set(handle, { ms, stack: new Error().stack.split('\n').slice(2, 5).join('\n') });
  return handle;
};
globalThis.clearTimeout = handle => { timers.delete(handle); originalClearTimeout(handle); };
const intervals = new Map(); let intervalId = 0;
window.setInterval = fn => { const id = ++intervalId; intervals.set(id, fn); return id; };
window.clearInterval = id => intervals.delete(id);
const dir = mkdtempSync(join(tmpdir(), 'yuldash-wait-mounted-'));
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const response = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
const conflict = () => response({ detail: { code: 'wait_snapshot_conflict', ru: 'Заказ изменился', ba: 'Заказ үҙгәрҙе' } }, 409);
const forbidden = [];
let calls = [], post, get, mine;
let userId = 1;
const base = { id: 18, status: 'expired', wait_until: null, price: 10000, distance_km: 1, eta_min: 5, category: 'standard', driver_name: '', driver_car: '', driver_rating: 0, driver_phone: '', passenger_name: '', passenger_phone: '', passenger_price_kop: 10000, promo_discount_kop: 0, created_at: null, from_text: 'synthetic-private-address', to_text: 'synthetic-destination' };
globalThis.fetch = async (url, init = {}) => {
  const parsed = new URL(url);
  if (parsed.origin !== 'http://127.0.0.1:19119') { forbidden.push(`${parsed.origin}${parsed.pathname}`); throw new Error('Forbidden transport origin'); }
  const path = parsed.pathname, method = init.method || 'GET';
  calls.push({ path, method, authorization: new Headers(init.headers).get('Authorization'), signal: init.signal });
  if (path === '/me' || path === '/me/update') return response({ id: userId, name: 'Synthetic', phone: '', language: 'ru' });
  if (path === '/config/features') return response({ taxi: true, rides: true });
  if (path === '/instant/orders/mine') return mine();
  if (path === '/instant/orders/18/wait') return post();
  if (path === '/instant/orders/18') return get();
  if (method === 'GET' && (path === '/places/saved' || path === '/places/recent')) return response([]);
  if (path === '/instant/availability') return response({ enabled: true });
  if (path === '/auth/refresh') return response({ detail: 'expired synthetic refresh' }, 401);
  forbidden.push(`${method} ${path}`);
  throw new Error(`Unexpected transport: ${method} ${path}`);
};
let root, app;
try {
  await build({
    absWorkingDir: resolve(new URL('..', import.meta.url).pathname),
    stdin: { contents: `
      import React from 'react';
      import {createRoot} from 'react-dom/client';
      import {act} from 'react';
      import {MemoryRouter,Routes,Route} from 'react-router-dom';
      import {AuthProvider,useAuth} from './src/auth/AuthProvider';
      import {LangProvider} from './src/i18n/lang';
      import RequireAuth from './src/components/RequireAuth';
      import InstantOrderScreen from './src/screens/InstantOrderScreen';
      export {setSession,getSessionGeneration} from './src/api/client';
      export {saveLanguage} from './src/i18n/languagePreference';
      export {act,createRoot};
      function AuthControl() { const auth = useAuth(); globalThis.__waitAuth = auth; React.useLayoutEffect(() => { globalThis.__waitAuthCommits?.push([auth.status,auth.user?.id]); }); return null; }
      const content = () => <MemoryRouter initialEntries={["/taxi"]}><AuthProvider><AuthControl /><LangProvider><Routes><Route path="/taxi" element={<RequireAuth><InstantOrderScreen /></RequireAuth>} /><Route path="/login" element={<p>Login fixture destination</p>} /></Routes></LangProvider></AuthProvider></MemoryRouter>;
      export const tree = (strict=false) => strict ? <React.StrictMode>{content()}</React.StrictMode> : content();
    `, resolveDir: resolve(new URL('..', import.meta.url).pathname), loader: 'tsx' },
    bundle: true, platform: 'node', format: 'esm', jsx: 'automatic',
    define: { 'import.meta.env': JSON.stringify({ VITE_API_BASE: 'http://127.0.0.1:19119', DEV: false, MODE: 'test' }), 'process.env.NODE_ENV': '"test"' },
    plugins: [{ name: 'capture-actual-completion-callback', setup(plugin) {
      plugin.onLoad({ filter: /InstantOrderScreen\.tsx$/ }, args => {
        const source = readFileSync(args.path, 'utf8').replace('const waitRead = useWaitConflictReconcile(order?.id, generation, setOrder);', 'const waitRead = useWaitConflictReconcile(order?.id, generation, setOrder); globalThis.__waitRenderedOrderId = order?.id;');
        const start = source.indexOf('function ComposeView(');
        const body = source.indexOf('  const { appText, lang } = useLang();', start);
        assert.ok(start > 0 && body > start);
        return { contents: source.slice(0, body) + '  globalThis.__waitCompletedCallback = onOrdered;\n' + source.slice(body), loader: 'tsx' };
      });
    } }],
    loader: { '.css' : 'empty', '.svg': 'dataurl', '.png': 'dataurl' },
    outfile: join(dir, 'mounted.mjs'), logLevel: 'silent',
  });
  app = await import(pathToFileURL(join(dir, 'mounted.mjs')));
  const settle = async () => { for (let i = 0; i < 12; i++) await app.act(async () => { await new Promise(r => setTimeout(r, 2)); }); };
  const text = () => document.body.textContent;
  const button = label => [...document.querySelectorAll('button')].find(b => b.textContent.includes(label));
  const click = async label => { const b = button(label); assert.ok(b, `Button ${label}: ${text()}`); await app.act(async () => b.click()); await settle(); };
  const only = (method, path) => calls.filter(c => c.method === method && c.path === path);
  async function mount(strict = false) {
    if (root) { await app.act(async () => root.unmount()); root = null; }
    userId = 1;
    await app.setSession('synthetic-access-A', 'synthetic-refresh-A');
    mine = async () => response([{ ...base, wait_until: new Date(Date.now() + 60000).toISOString() }]);
    get = async () => response(base);
    post = async () => conflict();
    await app.act(async () => { root = app.createRoot(document.getElementById('root')); root.render(app.tree(strict)); });
    await settle();
    // Actual tracking poll receives an expired/no-wait state after active restore.
    await app.act(async () => { for (const fn of intervals.values()) fn(); });
    await settle();
    assert.ok(button('Подождать машину'), text());
    calls = [];
  }
  await mount();
  await click('Подождать машину');
  assert.equal(only('POST', '/instant/orders/18/wait').length, 1);
  assert.equal(only('GET', '/instant/orders/18').length, 1);
  assert.ok(button('Подождать машину'), text());
  assert.ok(!text().includes('Ждём машину ещё'));
  console.log('✓ actual providers/card/screen: exact conflict POST1 GET1, no false success');

  await mount();
  get = async () => response({ detail: 'unavailable' }, 503);
  await click('Подождать машину');
  assert.ok(text().includes('не подтверждено'));
  assert.ok(!button('Подождать машину'));
  const count = calls.length;
  await app.act(async () => { for (const fn of intervals.values()) fn(); }); await settle();
  assert.equal(calls.length, count, 'Unconfirmed blocks background reads');
  get = async () => response(base);
  await click('Проверить заказ');
  assert.equal(only('POST', '/instant/orders/18/wait').length, 1, 'Manual retry is read-only');
  assert.equal(only('GET', '/instant/orders/18').length, 2);
  console.log('✓ failed reconciliation remains unconfirmed; explicit GET retry only');

  for (const status of [403, 404]) {
    await mount(); get = async () => response({ detail: 'denied' }, status);
    await click('Подождать машину');
    assert.ok(text().includes('Старые данные скрыты'));
    assert.ok(!text().includes(base.from_text));
    assert.ok(!button('Попробовать снова'));
  }
  console.log('✓ denied GET403/404 hides tracking/private payload and mutation actions');

  await mount();
  const held = deferred(); post = () => held.promise;
  const b = button('Подождать машину');
  await app.act(async () => { b.click(); b.click(); }); await settle();
  assert.equal(only('POST', '/instant/orders/18/wait').length, 1);
  await app.act(async () => { userId = 2; await globalThis.__waitAuth.login('synthetic-access-B', 'synthetic-refresh-B', {id:userId,name:'Synthetic',phone:'',language:'ru'}, app.getSessionGeneration()); }); await settle();
  const mark = calls.length;
  await app.act(async () => held.resolve(conflict())); await settle();
  assert.equal(calls.slice(mark).filter(c => c.path === '/instant/orders/18').length, 0, 'Old POST cannot reconcile under B');
  assert.ok(!text().includes('Ждём машину ещё'));
  console.log('✓ actual auth account swap invalidates old POST and synchronous double tap');

  await mount();
  const successDeadline = new Date(Date.now() + 180).toISOString();
  post = async () => response({ wait_until: successDeadline, wait_minutes: 20 });
  get = async () => response({ ...base, wait_until: successDeadline });
  await click('Подождать машину');
  assert.ok(text().includes('Ждём машину ещё'));
  await app.act(async () => { await new Promise(r => setTimeout(r, 220)); });
  assert.ok(button('Подождать машину'), text());
  assert.ok(!text().includes('Ждём машину ещё'), 'Expired success must not retain a waiting claim');
  await mount();
  const successFuture = new Date(Date.now() + 60000).toISOString();
  post = async () => response({ wait_until: successFuture, wait_minutes: 20 });
  get = async () => response({ ...base, wait_until: successFuture });
  await click('Подождать машину');
  assert.ok(text().includes('Ждём машину ещё'));
  get = async () => response(base);
  await app.act(async () => { for (const fn of intervals.values()) fn(); }); await settle();
  assert.ok(button('Подождать машину'), text());
  assert.ok(!text().includes('Ждём машину ещё'), 'Poll NULL removes the stale waiting claim');
  get = async () => response({...base,wait_until:new Date(Date.now()+90000).toISOString()});
  await app.act(async () => { for (const fn of intervals.values()) fn(); }); await settle();
  assert.ok(button('Не ждать машину'));
  assert.ok(!text().includes('Ждём машину ещё'), 'Old success marker cannot reappear for a new deadline');
  console.log('✓ successful POST note expires with deadline and authoritative poll NULL');

  // Same user, new authentication generation is also a new owner.
  await mount();
  const sameUser = deferred(); post = () => sameUser.promise;
  await app.act(async () => button('Подождать машину').click()); await settle();
  const oldGeneration = app.getSessionGeneration();
  await app.act(async () => { await globalThis.__waitAuth.login('synthetic-access-A2', 'synthetic-refresh-A2', {id:userId,name:'Synthetic',phone:'',language:'ru'}, app.getSessionGeneration()); }); await settle();
  assert.notEqual(app.getSessionGeneration(), oldGeneration);
  const sameMark = calls.length;
  await app.act(async () => sameUser.resolve(response({ wait_until: new Date(Date.now() + 60000).toISOString(), wait_minutes: 20 }))); await settle();
  assert.equal(calls.slice(sameMark).filter(c => c.path === '/instant/orders/18').length, 0);
  assert.ok(!text().includes('Ждём машину ещё'));
  console.log('✓ same-user/new-generation rejects old successful POST publication');

  // A poll already in flight cannot overwrite a later authoritative reconcile.
  await mount();
  const oldPoll = deferred(); get = () => oldPoll.promise;
  await app.act(async () => { for (const fn of intervals.values()) fn(); }); await settle();
  get = async () => response({ ...base, wait_until: new Date(Date.now() + 60000).toISOString() });
  await click('Подождать машину');
  assert.ok(button('Не ждать машину'));
  await app.act(async () => oldPoll.resolve(response(base))); await settle();
  assert.ok(button('Не ждать машину'), 'Late poll cannot erase reconciled wait');
  assert.ok(text().includes('Заказ обновлён'));
  assert.ok(!text().includes('Ждём машину ещё'));
  console.log('✓ held old poll blocked after newer reconcile, neutral refreshed notice');

  // Suppression includes reads initiated during a pending reconciliation.
  await mount();
  const pendingRead = deferred(); get = () => pendingRead.promise;
  await app.act(async () => button('Подождать машину').click()); await settle();
  const loadingCount = calls.length;
  await app.act(async () => { for (const fn of intervals.values()) fn(); }); await settle();
  assert.equal(calls.length, loadingCount);
  assert.ok(button('Проверить заказ').disabled);
  await app.act(async () => pendingRead.resolve(response(base))); await settle();
  assert.ok(button('Подождать машину'), text());
  console.log('✓ all tracking poll publication/starts gated while reconcile pending');

  for (const status of [400, 409, 503]) {
    await mount(); post = async () => response({ detail: { code: 'other_conflict' } }, status);
    await click('Подождать машину');
    assert.equal(only('GET', '/instant/orders/18').length, 0);
    assert.ok(text().includes('Не получилось встать'));
  }
  console.log('✓ unrelated POST errors never trigger conflict reconciliation');

  await mount(); get = async () => response({ ...base, id: 999 });
  await click('Подождать машину');
  assert.ok(text().includes('не подтверждено'));
  assert.ok(!button('Подождать машину'));
  console.log('✓ response ID mismatch fails closed, no adoption');

  await mount();
  const unmountedRead = deferred(); get = () => unmountedRead.promise;
  await app.act(async () => button('Подождать машину').click()); await settle();
  const readCall = only('GET', '/instant/orders/18')[0];
  await app.act(async () => root.unmount()); root = null;
  assert.equal(readCall.signal.aborted, true);
  await app.act(async () => unmountedRead.resolve(response(base))); await settle();
  assert.equal(document.getElementById('root').textContent, '');
  console.log('✓ unmount aborts owned GET; late result does not publish');

  await mount();
  get = async () => response({ ...base, wait_until: new Date(Date.now() + 180).toISOString() });
  await click('Подождать машину');
  assert.ok(button('Не ждать машину'));
  await app.act(async () => { await new Promise(r => setTimeout(r, 220)); });
  assert.ok(button('Подождать машину'), 'Confirmed wait deadline expires without polling');
  console.log('✓ local confirmed deadline timer updates waiting without a poll');

  await mount();
  const heldGet = deferred(); get = () => heldGet.promise;
  await app.act(async () => button('Подождать машину').click()); await settle();
  const oldSignal = only('GET', '/instant/orders/18')[0].signal;
  globalThis.__waitAuthCommits = [];
  await app.act(async () => {
    userId = 2;
    await globalThis.__waitAuth.login('synthetic-direct-B', 'synthetic-direct-refresh-B', {id:2,name:'B',phone:'',language:'ru'}, app.getSessionGeneration());
  }); await settle();
  assert.equal(oldSignal.aborted, true);
  assert.ok(globalThis.__waitAuthCommits.some(([status,id]) => status === 'authed' && id === 2));
  assert.ok(!globalThis.__waitAuthCommits.some(([status]) => status === 'loading'), 'Actual batched login swaps authed→authed without loading commit');
  await app.act(async () => heldGet.resolve(response({ ...base, status: 'cancelled' }))); await settle();
  assert.ok(!text().includes('Заказ отменён'));
  console.log('✓ actual login authed→authed invalidates held reconcile GET without relying on RequireAuth loading');

  await mount();
  get = async () => response({}, 401);
  await click('Подождать машину');
  assert.ok(!button('Подождать машину'));
  assert.ok(!text().includes(base.from_text));
  assert.equal(only('POST','/auth/refresh').length, 1);
  console.log('✓ GET401 follows actual refresh/auth teardown, no stale tracking publication');

  for (const status of ['accepted', 'cancelled', 'done']) {
    await mount(); get = async () => response({ ...base, status });
    await click('Подождать машину');
    assert.ok(!button('Подождать машину'));
    assert.ok(!text().includes('Ждём машину ещё'));
    assert.ok(text().includes('Заказ обновлён'));
  }
  console.log('✓ actual terminal payload replaces expired card without waiting success');

  // A language render cannot restart boot and adopt data while unconfirmed.
  await mount(); get = async () => response({}, 503);
  await click('Подождать машину');
  const languageCalls = calls.length;
  await app.act(async () => { await app.saveLanguage('ba'); }); await settle();
  assert.equal(calls.slice(languageCalls).filter(c => c.path.includes('/instant/')).length, 0);
  assert.ok(text().includes('раҫланманы'));
  await app.act(async () => { await app.saveLanguage('ru'); }); await settle();
  console.log('✓ language change keeps unconfirmed read fence, no boot adoption');

  // Hold actual boot transport, then replace its displayed account owner.
  await mount(); await app.act(async () => root.unmount()); root = null;
  const boot = deferred(); mine = () => boot.promise;
  await app.act(async () => { root = app.createRoot(document.getElementById('root')); root.render(app.tree()); }); await settle();
  mine = async () => response([{ ...base, wait_until: new Date(Date.now()+60000).toISOString() }]);
  await app.act(async () => { userId = 2; await globalThis.__waitAuth.login('synthetic-boot-B', 'synthetic-boot-refresh-B', {id:userId,name:'Synthetic',phone:'',language:'ru'}, app.getSessionGeneration()); }); await settle();
  await app.act(async () => boot.resolve(response([{...base,id:999,wait_until:new Date(Date.now()+60000).toISOString()}]))); await settle();
  get = async () => response(base);
  await app.act(async () => { for (const fn of intervals.values()) fn(); }); await settle();
  assert.ok(button('Подождать машину'), text());
  assert.equal(calls.filter(c => c.path === '/instant/orders/999').length, 0);
  console.log('✓ delayed actual boot result cannot publish the previous account order');

  // Capture, do not replace, the actual ComposeView completion callback.
  // This is a callback-publication control, NOT a create-HTTP round-trip claim.
  await mount(); await app.act(async () => root.unmount()); root = null;
  mine = async () => response([]);
  globalThis.__waitCompletedCallback = undefined;
  await app.act(async () => { root = app.createRoot(document.getElementById('root')); root.render(app.tree()); }); await settle();
  const completedA = globalThis.__waitCompletedCallback;
  assert.equal(typeof completedA, 'function');
  mine = async () => response([{...base,wait_until:new Date(Date.now()+60000).toISOString()}]);
  await app.act(async () => { userId=2; await globalThis.__waitAuth.login('synthetic-create-B', 'synthetic-create-refresh-B', {id:userId,name:'Synthetic',phone:'',language:'ru'}, app.getSessionGeneration()); }); await settle();
  await app.act(async () => completedA({...base,id:999})); await settle();
  await app.act(async () => { for (const fn of intervals.values()) fn(); }); await settle();
  assert.equal(calls.filter(c => c.path === '/instant/orders/999').length, 0);
  assert.ok(button('Подождать машину'), text());
  console.log('✓ held real onOrdered callback retains old owner, no adoption');

  await mount(true);
  await click('Подождать машину');
  assert.equal(only('POST', '/instant/orders/18/wait').length, 1);
  assert.equal(only('GET', '/instant/orders/18').length, 1);
  assert.ok(button('Подождать машину'), text());
  console.log('✓ React StrictMode effect replay restores boot and reconciles successfully');

  await mount();
  const leavingPoll = deferred(); get = () => leavingPoll.promise;
  await app.act(async () => { for (const fn of intervals.values()) fn(); }); await settle();
  await app.act(async () => {
    button('Попробовать снова').click();
    leavingPoll.resolve(response({...base,wait_until:new Date(Date.now()+60000).toISOString()}));
    await leavingPoll.promise;
  }); await settle();
  assert.equal(globalThis.__waitRenderedOrderId, undefined, 'Same-batch late read cannot restore cleared order');
  assert.ok(!button('Не ждать машину'));
  console.log('✓ same-batch back-to-compose invalidates held poll before React render');

  await mount();
  get = async () => { throw new TypeError('Synthetic transport rejection, not a real network timeout'); };
  await click('Подождать машину');
  assert.ok(text().includes('не подтверждено'));
  assert.ok(!button('Подождать машину'));
  console.log('✓ network-rejected GET stays unconfirmed (no automatic timeout claim)');

  await mount();
  const oldButton = button('Подождать машину');
  await app.act(async () => {
    userId = 2;
    await globalThis.__waitAuth.login('synthetic-event-B','synthetic-event-refresh-B',{id:2,name:'B',phone:'',language:'ru'},app.getSessionGeneration());
    oldButton.click();
  }); await settle();
  assert.equal(only('POST','/instant/orders/18/wait').length, 0, 'Old rendered event cannot send as the new owner');
  console.log('✓ owner check before transport rejects old DOM event during account swap');

  await mount();
  const finishingOld = deferred(); post = () => finishingOld.promise;
  await app.act(async () => button('Подождать машину').click()); await settle();
  await app.act(async () => {
    userId = 2;
    await globalThis.__waitAuth.login('synthetic-busy-B','synthetic-busy-refresh-B',{id:2,name:'B',phone:'',language:'ru'},app.getSessionGeneration());
  }); await settle();
  get = async () => response(base);
  await app.act(async () => { for (const fn of intervals.values()) fn(); }); await settle();
  const finishingNew = deferred(); post = () => finishingNew.promise;
  await app.act(async () => button('Подождать машину').click()); await settle();
  assert.ok(button('Подождать машину').disabled);
  await app.act(async () => finishingOld.resolve(conflict())); await settle();
  assert.ok(button('Подождать машину').disabled, 'Old finally cannot clear the new owner busy state');
  await app.act(async () => finishingNew.resolve(response({wait_until:new Date(Date.now()+60000).toISOString(),wait_minutes:20}))); await settle();
  assert.ok(button('Подождать машину'), 'Authoritative GET NULL overrides POST local future even when previous prop was NULL');
  assert.ok(!button('Подождать машину').disabled);
  console.log('✓ old finally cannot clear a new owned pending operation');

  assert.deepEqual(forbidden, [], 'No swallowed forbidden/unexpected transport attempts');
  console.log('MOUNTED PASS (not visual browser QA)');
} finally {
  let remainingTimers, remainingIntervals;
  try {
    if (root && app) await app.act(async () => root.unmount());
    dom.window.close();
    remainingTimers = timers.size;
    remainingIntervals = intervals.size;
  } finally {
    // Own handles only, including failure paths. Remaining handles still FAIL below.
    for (const handle of timers.keys()) originalClearTimeout(handle);
    for (const channel of channels) { channel.port1.close(); channel.port2.close(); }
    globalThis.MessageChannel = NativeMessageChannel;
    globalThis.setTimeout = originalSetTimeout;
    globalThis.clearTimeout = originalClearTimeout;
    rmSync(dir, { recursive: true, force: false });
  }
  assert.equal(remainingTimers, 0, 'No application deadline timers after unmount');
  assert.equal(remainingIntervals, 0, 'No tracking intervals after unmount');
  console.log(`Cleanup: ${channels.length} test-owned React MessageChannels closed; no live timers`);
}
