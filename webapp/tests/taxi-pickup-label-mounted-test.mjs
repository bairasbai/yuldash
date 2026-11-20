// Actual mounted screen/providers, not hook stubs or visual/browser acceptance.
// Explicit installed jsdom and workspace TMPDIR are prerequisites; no installs.
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, realpathSync } from 'node:fs';
import { join, resolve, isAbsolute, relative, sep } from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { build } from 'esbuild';
import { IDBFactory } from 'fake-indexeddb';

const require = createRequire(import.meta.url);
if (!process.env.TEST_JSDOM_MODULE_PATH || !process.env.TMPDIR || !process.env.TEST_WORKSPACE_ROOT) {
  console.error('NOT_RUN: explicit TEST_JSDOM_MODULE_PATH, TEST_WORKSPACE_ROOT and workspace TMPDIR required');
  process.exitCode = 2;
} else {
  await run();
}

async function run() {
  assert.equal(process.argv.length, 2, 'No positional arguments supported');
  assert.ok(isAbsolute(process.env.TEST_WORKSPACE_ROOT), 'Absolute TEST_WORKSPACE_ROOT required');
  assert.ok(isAbsolute(process.env.TMPDIR), 'Absolute TMPDIR required');
  const workspace = realpathSync(process.env.TEST_WORKSPACE_ROOT);
  const tempRoot = realpathSync(process.env.TMPDIR);
  const isInside = (root, path) => {
    const suffix = relative(root, path);
    return suffix !== '' && suffix !== '..' && !suffix.startsWith(`..${sep}`) && !isAbsolute(suffix);
  };
  const systemTmp = realpathSync('/tmp');
  assert.ok(workspace !== systemTmp && !isInside(systemTmp, workspace), 'Workspace must not be /tmp or its descendant');
  assert.ok(tempRoot !== systemTmp && !isInside(systemTmp, tempRoot), 'TMPDIR must not resolve into /tmp');
  assert.ok(isInside(workspace, tempRoot), 'TMPDIR must be a dedicated descendant of supplied workspace');
  const jsdomEntry = require.resolve(resolve(process.env.TEST_JSDOM_MODULE_PATH));
  const { JSDOM, requestInterceptor } = require(jsdomEntry);
  assert.equal(typeof requestInterceptor, 'function', 'Installed jsdom request interceptor required');
  console.log(`React=${require('react/package.json').version}; jsdom=${createRequire(jsdomEntry)('../package.json').version}`);
  // Installed jsdom31 resource guard proof, isolated from scenario counters.
  // No real dispatcher is reachable even if the interceptor is broken.
  let probeDenied = 0, probeBaseDispatch = 0, probeTimeout;
  const probeError = new Error('Synthetic resource-deny probe');
  const probe = JSDOM.fromURL('http://127.0.0.1:19117/resource-deny-probe', {
    resources: {
      dispatcher: { dispatch(options, handler) {
        probeBaseDispatch += 1;
        handler.onResponseError(null, new Error('Unexpected synthetic base dispatch'));
        return true;
      } },
      interceptors: [requestInterceptor(() => { probeDenied += 1; throw probeError; })],
    },
  });
  try {
    await assert.rejects(Promise.race([
      probe,
      new Promise((_, reject) => { probeTimeout = setTimeout(() => reject(new Error('Resource probe timeout')), 1000); }),
    ]), error => error === probeError);
    assert.equal(probeDenied, 1);
    assert.equal(probeBaseDispatch, 0);
    console.log('Resource guard control: denied=1, underlying dispatch=0');
  } finally { clearTimeout(probeTimeout); }
  const forbidden = [];
  const deny = target => {
    forbidden.push(String(target));
    throw new Error(`Forbidden test transport: ${target}`);
  };
  const dom = new JSDOM('<div id="root"></div>', {
    url: 'http://127.0.0.1:19118/taxi', pretendToBeVisual: true,
    // Never return undefined: that delegates to the underlying dispatcher.
    // Its deny-only fallback also cannot open a real connection.
    resources: {
      dispatcher: { dispatch(options, handler) {
        forbidden.push('Unexpected resource base dispatch');
        handler.onResponseError(null, new Error('Resource base dispatch denied'));
        return true;
      } },
      interceptors: [requestInterceptor(request => deny(`resource ${request.url}`))],
    },
  });
  for (const key of ['window', 'document', 'navigator', 'location', 'localStorage', 'sessionStorage', 'HTMLElement', 'Element', 'Node', 'Event', 'MouseEvent']) {
    Object.defineProperty(globalThis, key, { value: dom.window[key], configurable: true });
  }
  // All transport entrypoints are fenced before importing the application bundle.
  class DenySocket { constructor(url) { deny(`socket ${url}`); } }
  class DenyXHR { open(method, url) { deny(`xhr ${method} ${url}`); } }
  for (const object of [globalThis, window]) {
    object.WebSocket = DenySocket;
    object.EventSource = DenySocket;
    object.XMLHttpRequest = DenyXHR;
  }
  Object.defineProperty(navigator, 'sendBeacon', { value: url => deny(`beacon ${url}`), configurable: true });
  globalThis.indexedDB = new IDBFactory();
  globalThis.IS_REACT_ACT_ENVIRONMENT = true;
  // jsdom lacks browser BroadcastChannel: no cross-tab lifecycle claim.
  globalThis.BroadcastChannel = undefined;
  const NativeMessageChannel = globalThis.MessageChannel;
  const channels = [];
  globalThis.MessageChannel = class extends NativeMessageChannel {
    constructor() { super(); channels.push(this); }
  };
  window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} });
  const originalSetTimeout = globalThis.setTimeout;
  const originalClearTimeout = globalThis.clearTimeout;
  const timers = new Map();
  globalThis.setTimeout = (callback, ms, ...args) => {
    let handle;
    handle = originalSetTimeout(() => { timers.delete(handle); callback(...args); }, ms);
    timers.set(handle, ms);
    return handle;
  };
  globalThis.clearTimeout = handle => { timers.delete(handle); originalClearTimeout(handle); };
  const intervals = new Map();
  let intervalId = 0;
  window.setInterval = callback => { const id = ++intervalId; intervals.set(id, callback); return id; };
  window.clearInterval = id => intervals.delete(id);
  const response = body => new Response(JSON.stringify(body), {
    status: 200, headers: { 'Content-Type': 'application/json' },
  });
  const base = {
    id: 18, status: 'accepted', wait_until: null, price: 10000,
    distance_km: 80, eta_min: 137, offer_pickup_eta_min: 3,
    category: 'standard', driver_name: 'Fixture driver', driver_car: '',
    driver_rating: 0, driver_phone: '', passenger_name: '', passenger_phone: '',
    passenger_price_kop: 10000, promo_discount_kop: 0, created_at: null,
    from_text: 'Synthetic pickup', to_text: 'Synthetic destination',
  };
  let payload = { ...base };
  let language = 'ru';
  const calls = [];
  const languageUpdates = [];
  globalThis.fetch = async (url, init = {}) => {
    const parsed = new URL(url);
    const method = init.method || 'GET';
    if (parsed.origin !== 'http://127.0.0.1:19119') deny(`${method} ${parsed.origin}${parsed.pathname}`);
    calls.push({ method, path: parsed.pathname });
    if (method === 'POST' && parsed.pathname === '/me/update') {
      let body;
      try { body = JSON.parse(init.body); }
      catch { return deny('Malformed language-update body'); }
      if (!['ru', 'ba'].includes(language) || body === null || typeof body !== 'object'
          || Array.isArray(body) || Object.keys(body).length !== 1 || body.language !== language) {
        return deny('Unexpected language-update body');
      }
      languageUpdates.push({ language: body.language });
      return response({ ok: true, name: 'Synthetic', avatar_url: '', city: '', language, gender: '' });
    }
    if (method !== 'GET') deny(`${method} ${parsed.pathname}`);
    switch (parsed.pathname) {
      case '/me': return response({ id: 1, name: 'Synthetic', phone: '', language });
      case '/config/features': return response({ taxi: true, rides: true });
      case '/instant/orders/mine': return response([{ ...payload }]);
      case '/instant/orders/18': return response({ ...payload });
      // Full-sheet ShareTripCard mount reads only; no sharing writes allowed.
      case '/trusted-contacts': return response([]);
      case '/instant/orders/18/shares': return response([]);
      case '/instant/availability': return response({ enabled: true });
      default: return deny(`${method} ${parsed.pathname}`);
    }
  };
  window.fetch = globalThis.fetch;
  const directory = mkdtempSync(join(tempRoot, 'pickup-label-mounted-'));
  let app, root;
  const failures = [];
  let groups = 0;
  const check = (label, callback) => {
    groups += 1;
    try { callback(); console.log(`PASS ${label}`); }
    catch (error) { failures.push(new Error(`${label}: ${error.message}`, { cause: error })); console.error(`FAIL ${label}: ${error.message}`); }
  };
  try {
    const webapp = resolve(new URL('..', import.meta.url).pathname);
    await build({
      absWorkingDir: webapp,
      stdin: { contents: `
        import React from 'react';
        import {act} from 'react';
        import {createRoot} from 'react-dom/client';
        import {MemoryRouter, Routes, Route} from 'react-router-dom';
        import {AuthProvider} from './src/auth/AuthProvider';
        import {LangProvider} from './src/i18n/lang';
        import RequireAuth from './src/components/RequireAuth';
        import InstantOrderScreen from './src/screens/InstantOrderScreen';
        export {setSession} from './src/api/client';
        export {saveLanguage} from './src/i18n/languagePreference';
        export {act, createRoot};
        export const tree = () => <MemoryRouter initialEntries={['/taxi']}>
          <AuthProvider><LangProvider><Routes>
            <Route path='/taxi' element={<RequireAuth><InstantOrderScreen /></RequireAuth>} />
            <Route path='/login' element={<p>Fixture login</p>} />
          </Routes></LangProvider></AuthProvider></MemoryRouter>;
      `, resolveDir: webapp, loader: 'tsx' },
      bundle: true, platform: 'node', format: 'esm', jsx: 'automatic',
      define: {
        'import.meta.env': JSON.stringify({ VITE_API_BASE: 'http://127.0.0.1:19119', VITE_YANDEX_MAPS_JS_KEY: '', DEV: false, MODE: 'test' }),
        'process.env.NODE_ENV': '"test"',
      },
      loader: { '.css': 'empty', '.svg': 'dataurl', '.png': 'dataurl' },
      outfile: join(directory, 'mounted.mjs'), logLevel: 'silent',
    });
    app = await import(pathToFileURL(join(directory, 'mounted.mjs')));
    const settle = async () => {
      for (let i = 0; i < 12; i++) await app.act(async () => { await new Promise(done => setTimeout(done, 2)); });
    };
    const header = () => {
      const value = document.querySelector('.taxi-trip-head');
      assert.ok(value, `Actual tracking header mounted: ${document.body.textContent}`);
      return value;
    };
    const verify = (status, lang, label) => check(label, () => {
      const head = header();
      const title = head.querySelector('.taxi-trip-head__title');
      const subtitle = head.querySelector('.taxi-trip-head__sub');
      const progress = head.querySelector('[role="progressbar"]');
      assert.ok(progress, 'Actual progress rail retained');
      assert.equal(progress.getAttribute('aria-valuenow'), String({ accepted: 0, arriving: 1, onboard: 2 }[status]));
      if (status === 'onboard') {
        assert.equal(title.textContent, lang === 'ru' ? 'В пути' : 'Юлда');
        assert.equal(subtitle?.textContent, lang === 'ru' ? 'Хорошей дороги 🌿' : 'Юлың уң булһын 🌿');
      } else {
        assert.equal(title.textContent, status === 'arriving'
          ? (lang === 'ru' ? 'Машина на месте' : 'Машина урынында')
          : (lang === 'ru' ? 'Водитель едет к тебе' : 'Йөрөтөүсе һиңә килә'));
        assert.equal(subtitle?.textContent?.trim() || '', '', 'No invented numeric pickup subtitle (neither trip137 nor offer3)');
        assert.doesNotMatch(head.textContent, /(?:137|211|3|NaN)\s*мин/);
      }
    });
    for (const lang of ['ru', 'ba']) {
      for (const status of ['accepted', 'arriving', 'onboard']) {
        if (root) { await app.act(async () => root.unmount()); root = null; }
        language = lang;
        payload = { ...base, status };
        await app.setSession(`synthetic-${lang}-${status}`, 'synthetic-refresh');
        await app.saveLanguage(lang);
        await app.act(async () => { root = app.createRoot(document.getElementById('root')); root.render(app.tree()); });
        await settle();
        verify(status, lang, `${lang}/${status}/trip137-offer3`);
        if (status === 'onboard') continue;
        for (const missing of [false, true]) {
          payload = { ...base, status, eta_min: 211 };
          if (missing) { delete payload.eta_min; delete payload.offer_pickup_eta_min; }
          const before = calls.filter(call => call.path === '/instant/orders/18').length;
          await app.act(async () => { for (const callback of [...intervals.values()]) callback(); });
          await settle();
          assert.ok(calls.filter(call => call.path === '/instant/orders/18').length > before, 'Actual polling GET ran');
          verify(status, lang, `${lang}/${status}/${missing ? 'missing-ETA' : 'polled-trip211'}`);
        }
      }
    }
    // One live mounted screen per language: polling changes phases, not root.render.
    for (const lang of ['ru', 'ba']) {
      if (root) { await app.act(async () => root.unmount()); root = null; }
      language = lang;
      payload = { ...base, status: 'accepted', waiting_started_at: null, wait_free_min: 5 };
      await app.setSession(`synthetic-${lang}-phase-sequence`, 'synthetic-refresh');
      await app.saveLanguage(lang);
      await app.act(async () => { root = app.createRoot(document.getElementById('root')); root.render(app.tree()); });
      await settle();
      const initialHeader = header();
      const poll = async () => {
        const before = calls.filter(call => call.path === '/instant/orders/18').length;
        await app.act(async () => { for (const callback of [...intervals.values()]) callback(); });
        await settle();
        assert.ok(calls.filter(call => call.path === '/instant/orders/18').length > before);
        assert.equal(header(), initialHeader, 'Same mounted tracking header through polling');
      };
      verify('accepted', lang, `${lang}/sequence/accepted`);
      await app.act(async () => document.querySelector('.taxi-sheet__grip').click());
      await settle();
      assert.equal(document.querySelector('.taxi-sheet-scaffold').dataset.stop, 'full');
      assert.ok(document.querySelector('.trip-actions'), 'Actual full-sheet children mounted');
      assert.equal(document.querySelector('.wait-counter'), null, 'No waiting counter without a server start');
      payload = { ...payload, status: 'arriving' };
      await poll();
      verify('arriving', lang, `${lang}/sequence/arriving-no-wait`);
      assert.equal(document.querySelector('.wait-counter'), null);
      // Current synthetic server timestamp, not historical/fixed countdown seconds.
      payload = { ...payload, waiting_started_at: new Date().toISOString() };
      await poll();
      verify('arriving', lang, `${lang}/sequence/arriving-running-wait`);
      check(`${lang}/full-sheet/free-window`, () => {
        const counter = document.querySelector('.wait-counter');
        assert.ok(counter);
        assert.equal(counter.classList.contains('wait-counter--paid'), false);
        assert.ok(counter.textContent.startsWith(lang === 'ru' ? 'Бесплатное ожидание ' : 'Бушлай көтөү '));
        assert.equal(header().querySelector('.wait-counter'), null, 'Waiting is not pickup ETA');
      });
      payload = { ...payload, status: 'onboard', waiting_started_at: null };
      await poll();
      verify('onboard', lang, `${lang}/sequence/onboard`);
      assert.equal(document.querySelector('.wait-counter'), null);
    }
    assert.deepEqual(forbidden, [], 'No swallowed forbidden transport');
    assert.ok(calls.filter(call => call.path.startsWith('/instant/')).every(call => call.method === 'GET'), 'No order mutations');
    assert.equal(calls.filter(call => call.method !== 'GET').length, languageUpdates.length, 'Only exact language-profile writes allowed');
    assert.ok(languageUpdates.some(update => update.language === 'ru') && languageUpdates.some(update => update.language === 'ba'), 'Actual LangProvider synchronized both languages');
    console.log(`Language profile updates=${languageUpdates.length}; order mutations=0`);
    console.log(`Groups=${groups}, failures=${failures.length}`);
    if (failures.length) throw new AggregateError(failures, 'Mounted pickup-label regression failures');
    console.log('MOUNTED PASS; not visual browser QA, not Android or road-ranking proof');
  } finally {
    let remainingTimers, remainingIntervals;
    try {
      if (root && app) await app.act(async () => root.unmount());
      dom.window.close();
      remainingTimers = timers.size;
      remainingIntervals = intervals.size;
    } finally {
      for (const handle of timers.keys()) originalClearTimeout(handle);
      for (const channel of channels) { channel.port1.close(); channel.port2.close(); }
      globalThis.MessageChannel = NativeMessageChannel;
      globalThis.setTimeout = originalSetTimeout;
      globalThis.clearTimeout = originalClearTimeout;
      rmSync(directory, { recursive: true, force: false });
    }
    assert.equal(remainingTimers, 0, 'No own application timers after unmount');
    assert.equal(remainingIntervals, 0, 'No own polling intervals after unmount');
    assert.deepEqual(forbidden, [], 'Forbidden attempts fail even if product swallowed errors');
    console.log('Cleanup: own bundle removed, MessagePorts closed, no live application timers');
  }
}
