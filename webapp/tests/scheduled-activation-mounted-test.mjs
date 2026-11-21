// Real screen/providers/client with synthetic transport; requires separate official jsdom 31.0.1.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { mkdtempSync, rmSync, realpathSync } from 'node:fs';
import { dirname, isAbsolute, join, relative } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { test } from 'node:test';

const webapp = fileURLToPath(new URL('..', import.meta.url));
const workspace = realpathSync(process.env.TEST_WORKSPACE_ROOT);
const temporary = realpathSync(process.env.TMPDIR);
assert.ok(isAbsolute(workspace) && !relative(workspace, temporary).startsWith('..') && workspace !== temporary);
const require = createRequire(join(webapp, 'package.json'));
const { build } = require('esbuild');
const { IDBFactory } = require('fake-indexeddb');
const jsdomEntry = realpathSync(process.env.TEST_JSDOM_MODULE_PATH);
assert.ok(isAbsolute(jsdomEntry) && jsdomEntry.endsWith('/jsdom/lib/api.js'));
assert.equal(require(join(dirname(dirname(jsdomEntry)), 'package.json')).version, '31.0.1');
const { JSDOM, requestInterceptor } = require(jsdomEntry);
const denied = [], calls = [], intervals = new Map(), ports = [], held = [];
const deny = target => { denied.push(target); throw new Error('Unexpected transport: ' + target); };
const dom = new JSDOM('<div id="root"></div>', {
  url: 'http://127.0.0.1:19308/scheduled', pretendToBeVisual: true,
  resources: { dispatcher: { dispatch() { return deny('resource dispatcher'); } },
    interceptors: [requestInterceptor(request => deny('resource ' + request.url))] },
});
for (const key of ['window', 'document', 'navigator', 'location', 'localStorage', 'sessionStorage', 'HTMLElement', 'Element', 'Node', 'Event', 'MouseEvent']) {
  Object.defineProperty(globalThis, key, { value: dom.window[key], configurable: true });
}
for (const object of [globalThis, window]) {
  object.WebSocket = object.EventSource = class { constructor(url) { deny('socket ' + url); } };
  object.XMLHttpRequest = class { open(method, url) { deny('xhr ' + method + ' ' + url); } };
}
Object.defineProperty(navigator, 'sendBeacon', { value: url => deny('beacon ' + url), configurable: true });
globalThis.indexedDB = new IDBFactory();
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
globalThis.BroadcastChannel = undefined;
window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} });
const NativeMessageChannel = globalThis.MessageChannel;
globalThis.MessageChannel = class extends NativeMessageChannel { constructor() { super(); ports.push(this); } };
let intervalId = 0;
globalThis.setInterval = window.setInterval = callback => { const id = ++intervalId; intervals.set(id, callback); return id; };
globalThis.clearInterval = window.clearInterval = id => intervals.delete(id);

let language = 'ru', status = 'scheduled', returnedId = 17, queued = false, hold = false, listing = true, fail = false;
const order = () => ({ id: returnedId, status, role: 'passenger',
  from_text: 'Synthetic A', to_text: 'Synthetic B', category: 'standard',
  price_estimate: 450, price_final: null, passenger_price_kop: 45000, promo_discount_kop: 0,
  scheduled_at: '2030-01-01T10:00:00', wait_until: queued ? '2030-01-01T10:15:00' : null,
  stops: [{ lat: 52.64, lng: 58.41, text: 'Synthetic stop', done: false }], distance_km: 18.5, eta_min: 22 });
const response = body => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
globalThis.fetch = window.fetch = async (url, init = {}) => {
  const parsed = new URL(url), method = init.method || 'GET';
  if (parsed.origin !== 'http://127.0.0.1:19309') return deny(method + ' ' + parsed.origin + parsed.pathname);
  const path = parsed.pathname; calls.push({ method, path });
  if (method === 'GET' && path === '/me') return response({ id: 1, name: 'Synthetic', phone: '', role: 'passenger', language });
  if (method === 'POST' && path === '/me/update') { assert.deepEqual(JSON.parse(init.body), { language }); return response({ ok: true, language }); }
  if (method === 'GET' && path === '/instant/scheduled') return response({ scheduled: listing ? [{ ...order(), id: 17, status: 'scheduled' }] : [], activated: [] });
  if (method === 'GET' && path === '/instant/orders/mine') return response([]);
  if (method === 'POST' && path === '/instant/scheduled/17/activate') {
    if (fail) throw new TypeError('Synthetic offline');
    if (hold) return new Promise(resolve => held.push(() => resolve(response(order()))));
    if (status === 'done' || status === 'cancelled') listing = false;
    return response(order());
  }
  return deny(method + ' ' + path);
};

const directory = mkdtempSync(join(temporary, 'scheduled-mounted-'));
let root, app;
try {
  await build({ absWorkingDir: webapp, stdin: { resolveDir: webapp, loader: 'tsx', contents: `
    import React from 'react';import {act} from 'react';import {createRoot} from 'react-dom/client';
    import {MemoryRouter,Routes,Route} from 'react-router-dom';import {AuthProvider} from './src/auth/AuthProvider';
    import {LangProvider} from './src/i18n/lang';import ScheduledOrdersScreen from './src/screens/ScheduledOrdersScreen';
    export {setSession} from './src/api/client';export {saveLanguage} from './src/i18n/languagePreference';
    export {act,createRoot};export const tree=()=> <MemoryRouter initialEntries={['/scheduled']}><AuthProvider><LangProvider><Routes>
    <Route path='/scheduled' element={<ScheduledOrdersScreen/>}/><Route path='/taxi' element={<p data-destination='taxi'>Synthetic destination</p>}/>
    </Routes></LangProvider></AuthProvider></MemoryRouter>;` }, bundle: true, platform: 'node', format: 'esm', jsx: 'automatic',
    define: { 'import.meta.env': JSON.stringify({ VITE_API_BASE: 'http://127.0.0.1:19309', VITE_YANDEX_MAPS_JS_KEY: '', DEV: false, MODE: 'test' }), 'process.env.NODE_ENV': '"test"' },
    loader: { '.css': 'empty', '.svg': 'dataurl', '.png': 'dataurl', '.webp': 'dataurl' }, outfile: join(directory, 'screen.mjs'), logLevel: 'silent' });
  app = await import(pathToFileURL(join(directory, 'screen.mjs')));
  const settle = async () => { for (let i = 0; i < 15; i++) await app.act(async () => { await new Promise(resolve => setTimeout(resolve, 2)); }); };
  const start = async lang => {
    language = lang; status = 'scheduled'; returnedId = 17; queued = hold = fail = false; listing = true; calls.length = 0;
    await app.setSession('synthetic-scheduled-' + lang + '-' + Date.now(), 'synthetic-refresh'); await app.saveLanguage(lang);
    await app.act(async () => { root = app.createRoot(document.getElementById('root')); root.render(app.tree()); }); await settle();
    assert.ok(document.body.textContent.includes(lang === 'ru' ? 'Мои предзаказы' : 'Минең алдан заказдар'));
  };
  const stop = async () => {
    while (held.length) await app.act(async () => held.shift()());
    await settle();
    if (root) { await app.act(async () => root.unmount()); root = null; await settle(); }
    assert.equal(intervals.size, 0);
  };
  const button = () => [...document.querySelectorAll('button')].find(b => b.textContent === (language === 'ru' ? 'Начать поиск сейчас' : 'Хәҙер эҙләргә'));
  const click = async () => { assert.ok(button()); await app.act(async () => button().click()); await settle(); };
  const navigated = () => !!document.querySelector('[data-destination="taxi"]');
  const posts = () => calls.filter(c => c.method === 'POST' && c.path.endsWith('/activate')).length;
  for (const lang of ['ru', 'ba']) {
    await test('retained scheduled stays visible and retries through release: ' + lang, async () => {
      try {
        await start(lang); const pendingButton = button(); hold = true; await click(); assert.equal(pendingButton.disabled, true);
        pendingButton.click(); await settle(); assert.equal(posts(), 1, 'No second POST while the first is pending');
        hold = false; await app.act(async () => held.shift()()); await settle();
        assert.equal(navigated(), false, 'retained scheduled must remain on scheduled screen');
        assert.ok(document.body.textContent.includes(lang === 'ru' ? 'Поиск ещё не начался' : 'Эҙләү әле башланманы'));
        assert.ok(document.body.textContent.includes('Synthetic A')); assert.equal(button().disabled, false);
        await click(); assert.equal(navigated(), false); assert.equal(posts(), 2);
        status = 'searching'; await click(); assert.equal(navigated(), true); assert.equal(posts(), 3);
      } finally { await stop(); }
    });
    if (process.env.TF08_SCENARIOS === 'deferred') continue;
    for (const [next, queue] of [['created', false], ['searching', false], ['offered', false], ['accepted', false], ['arriving', false], ['onboard', false], ['expired', true], ['expired', false]]) {
      await test(`existing activated navigation ${next}/queue=${queue}: ${lang}`, async () => {
        try { await start(lang); status = next; queued = queue; await click(); assert.equal(navigated(), true); assert.equal(posts(), 1); }
        finally { await stop(); }
      });
    }
    for (const next of ['done', 'cancelled']) await test('terminal notice survives empty reload ' + next + ': ' + lang, async () => {
      try {
        await start(lang); status = next; await click(); assert.equal(navigated(), false);
        assert.ok(document.body.textContent.includes(lang === 'ru' ? 'уже завершён или отменён' : 'тамамланған йәки кире алынған'));
        assert.ok(document.body.textContent.includes(lang === 'ru' ? 'Пока предзаказов нет' : 'Әлегә алдан заказ юҡ'));
      } finally { await stop(); }
    });
    for (const kind of ['unknown', 'wrong-id']) await test('unconfirmed response cannot navigate ' + kind + ': ' + lang, async () => {
      try { await start(lang); status = kind === 'unknown' ? 'future-status' : 'searching'; returnedId = kind === 'wrong-id' ? 18 : 17; await click(); assert.equal(navigated(), false); assert.ok(button() && !button().disabled); }
      finally { await stop(); }
    });
    await test('failed request remains retryable: ' + lang, async () => {
      try { await start(lang); fail = true; await click(); assert.equal(navigated(), false); assert.ok(button() && !button().disabled); fail = false; status = 'searching'; await click(); assert.equal(navigated(), true); }
      finally { await stop(); }
    });
  }
  assert.deepEqual(denied, []); assert.equal(held.length, 0);
  console.log(JSON.stringify({ transport: 'synthetic allowlist loopback URL, no actual HTTP', denied: denied.length, order_create_cancel_payment_requests: 0, lifecycle_runtime_android: 'not tested' }));
} finally {
  if (root) await app.act(async () => root.unmount());
  dom.window.close(); for (const port of ports) { port.port1.close(); port.port2.close(); }
  rmSync(directory, { recursive: true, force: false });
}
