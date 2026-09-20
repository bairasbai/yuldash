// Two independent production module instances share storage, modelling two tabs.
// This is not a real-browser storage-event or Web Locks test. No real network is used.
import assert from 'node:assert/strict';
import { IDBFactory } from 'fake-indexeddb';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

const bundle = await build({
  stdin: { contents: "export * from './src/api/client'; export { refreshSession } from './src/api/auth';",
    resolveDir: fileURLToPath(new URL('../', import.meta.url)), loader: 'ts' },
  bundle: true, write: false, platform: 'node', format: 'esm', define: { 'import.meta.env': '{}' },
});
let serial = 0;
let cases = 0;
let failed = 0;
function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }
const response = (status, body = {}) => new Response(JSON.stringify(body), { status });
async function environment() {
  globalThis.indexedDB = new IDBFactory();
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: {} });
  const storage = new Map();
  globalThis.localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, String(value)),
    removeItem: key => storage.delete(key),
  };
  async function instance() {
    const source = bundle.outputFiles[0].text + `\n// separate tab ${serial++}`;
    const api = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
    api.setRefreshHandler(api.refreshSession);
    return api;
  }
  const a = await instance();
  const b = await instance();
  globalThis.fetch = async () => { throw new Error('Unexpected fetch; real network is prohibited'); };
  return { a, b };
}
function newAccountInOtherTab(b) { b.setSession(null, null); b.setSession('B', 'refresh-B'); }
async function check(name, test) {
  cases++;
  try { await test(await environment()); console.log(`PASS ${name}`); }
  catch (error) { failed++; console.error(`FAIL ${name}\n${error.stack}`); }
}

await check('old 401 in tab A cannot clear the session installed by tab B', async ({ a, b }) => {
  const pending = deferred();
  globalThis.fetch = () => pending.promise;
  a.setRefreshHandler(null);
  a.setSession('A', 'refresh-A');
  const result = a.apiGet('/private').catch(error => error);
  newAccountInOtherTab(b);
  pending.resolve(response(401));
  await result;
  assert.equal(b.getToken(), 'B');
  assert.equal(b.getRefreshToken(), 'refresh-B');
});

await check('old mutation in tab A cannot replay with credentials installed by tab B', async ({ a, b }) => {
  const pending = deferred();
  const calls = [];
  globalThis.fetch = (url, init) => {
    calls.push({ path: new URL(url).pathname, auth: init.headers.Authorization });
    if (calls.length === 1) return pending.promise;
    return Promise.resolve(response(200, url.endsWith('/auth/refresh')
      ? { access_token: 'B-new', refresh_token: 'refresh-B-new' } : { ok: true }));
  };
  a.setSession('A', 'refresh-A');
  const result = a.apiPost('/me/delete', {}).catch(error => error);
  newAccountInOtherTab(b);
  pending.resolve(response(401));
  await result;
  assert.deepEqual(calls, [{ path: '/me/delete', auth: 'Bearer A' }]);
  assert.equal(b.getToken(), 'B');
});

await check('old protected success cannot escape after another tab changes account', async ({ a, b }) => {
  const pending = deferred();
  globalThis.fetch = () => pending.promise;
  a.setSession('A', 'refresh-A');
  const result = a.apiGet('/private').then(value => ({ value }), error => ({ error }));
  newAccountInOtherTab(b);
  pending.resolve(response(200, { private: 'A' }));
  assert.ok((await result).error, 'Old private body was returned after another tab logged in');
});

await check('another tab account change during body decoding rejects the old result', async ({ a, b }) => {
  const body = deferred();
  const reading = deferred();
  globalThis.fetch = async () => ({ status: 200, ok: true, json: () => { reading.resolve(); return body.promise; } });
  a.setSession('A', 'refresh-A');
  const result = a.apiGet('/private').then(value => ({ value }), error => ({ error }));
  await reading.promise;
  newAccountInOtherTab(b);
  body.resolve({ private: 'A' });
  assert.ok((await result).error);
});

await check('old refresh cannot overwrite a new account installed by another tab', async ({ a, b }) => {
  const pending = deferred();
  globalThis.fetch = () => pending.promise;
  a.setSession('A', 'refresh-A');
  const refreshing = a.refreshSession();
  newAccountInOtherTab(b);
  pending.resolve(response(200, { access_token: 'A-new', refresh_token: 'refresh-A-new' }));
  assert.equal(await refreshing, false);
  assert.equal(b.getToken(), 'B');
  assert.equal(b.getRefreshToken(), 'refresh-B');
});

await check('same-account refresh in another tab preserves an earlier successful request', async ({ a, b }) => {
  const pending = deferred();
  globalThis.fetch = (url) => url.endsWith('/auth/refresh')
    ? Promise.resolve(response(200, { access_token: 'A-new', refresh_token: 'refresh-A-new' }))
    : pending.promise;
  a.setSession('A', 'refresh-A');
  const result = a.apiGet('/private');
  assert.equal(await b.refreshSession(), true);
  pending.resolve(response(200, { private: 'A' }));
  assert.deepEqual(await result, { private: 'A' });
  assert.equal(a.getToken(), 'A-new');
});

function installSharedLocks() {
  const tails = new Map();
  const entered = [];
  const bothQueued = deferred();
  const names = [];
  navigator.locks = {
    request(name, callback) {
      names.push(name);
      if (names.length === 2) bothQueued.resolve();
      const previous = tails.get(name) ?? Promise.resolve();
      const task = previous.then(() => { entered.push(name); return callback({ name, mode: 'exclusive' }); });
      tails.set(name, task.catch(() => {}));
      return task;
    },
  };
  return { names, entered, bothQueued };
}

// Earlier RED evidence contains the two duplicate-refresh completion orders. With
// Web Locks, the duplicate request must not be sent at all; no second response exists.
for (const secondStartsLater of [false, true]) {
  await check(`shared Web Lock prevents duplicate refresh (${secondStartsLater ? 'second tab joins pending refresh' : 'simultaneous requests'})`, async ({ a, b }) => {
    const locks = installSharedLocks();
    const refreshResponse = deferred();
    const refreshStarted = deferred();
    let refreshCount = 0;
    globalThis.fetch = (url, init) => {
      if (url.endsWith('/auth/refresh')) {
        refreshCount++;
        refreshStarted.resolve();
        return refreshResponse.promise;
      }
      return Promise.resolve(init.headers.Authorization === 'Bearer A-new'
        ? response(200, { ok: true }) : response(401));
    };
    a.setSession('A', 'refresh-A');
    const first = a.apiGet('/first').then(value => ({ value }), error => ({ error }));
    if (secondStartsLater) await refreshStarted.promise;
    const second = b.apiGet('/second').then(value => ({ value }), error => ({ error }));
    await locks.bothQueued.promise;
    await refreshStarted.promise;
    assert.equal(refreshCount, 1);
    assert.equal(locks.entered.length, 1, 'Second tab must wait outside the critical section');
    refreshResponse.resolve(response(200, { access_token: 'A-new', refresh_token: 'refresh-A-new' }));
    const results = await Promise.all([first, second]);
    assert.equal(a.getToken(), 'A-new');
    assert.equal(a.getRefreshToken(), 'refresh-A-new');
    assert.deepEqual(results.map(result => result.value), [{ ok: true }, { ok: true }]);
    assert.equal(refreshCount, 1);
    assert.equal(locks.names.length, 2);
    assert.equal(locks.names[0], locks.names[1], 'Both tabs of the same session must share a lock');
  });
}

await check('genuine refresh rejection still clears its own session', async ({ a }) => {
  installSharedLocks();
  let refreshCount = 0;
  globalThis.fetch = async url => {
    if (url.endsWith('/auth/refresh')) refreshCount++;
    return response(401);
  };
  a.setSession('A', 'refresh-A');
  await assert.rejects(a.apiGet('/private'));
  assert.equal(refreshCount, 1);
  assert.equal(a.getToken(), null);
  assert.equal(a.getRefreshToken(), null);
});

await check('without Web Locks single-tab parallel refresh retains its fallback', async ({ a }) => {
  assert.equal(navigator.locks, undefined);
  const refreshResponse = deferred();
  const refreshStarted = deferred();
  let refreshCount = 0;
  globalThis.fetch = (url, init) => {
    if (url.endsWith('/auth/refresh')) { refreshCount++; refreshStarted.resolve(); return refreshResponse.promise; }
    return Promise.resolve(init.headers.Authorization === 'Bearer A-new' ? response(200, { ok: true }) : response(401));
  };
  a.setSession('A', 'refresh-A');
  const first = a.apiGet('/first');
  const second = a.apiGet('/second');
  await refreshStarted.promise;
  await Promise.resolve(); await Promise.resolve();
  refreshResponse.resolve(response(200, { access_token: 'A-new', refresh_token: 'refresh-A-new' }));
  assert.deepEqual(await Promise.all([first, second]), [{ ok: true }, { ok: true }]);
  assert.equal(refreshCount, 1);
});

console.log(`Cross-tab session cases: ${cases - failed} passed, ${failed} failed`);
if (failed) process.exitCode = 1;
