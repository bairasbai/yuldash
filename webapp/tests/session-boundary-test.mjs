// Run the production HTTP and refresh code; no request reaches a real network.
import assert from 'node:assert/strict';
import { IDBFactory } from 'fake-indexeddb';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

const bundle = await build({
  stdin: { contents: "export * from './src/api/client'; export { refreshSession } from './src/api/auth';",
    resolveDir: fileURLToPath(new URL('../', import.meta.url)), loader: 'ts' },
  bundle: true, write: false, platform: 'node', format: 'esm',
  define: { 'import.meta.env': '{}' },
});
let serial = 0;
let failed = 0;
const response = (status, body = {}) => new Response(JSON.stringify(body), { status });
function deferred() {
  let resolve;
  const promise = new Promise(r => { resolve = r; });
  return { promise, resolve };
}
async function environment() {
  globalThis.indexedDB = new IDBFactory();
  const storage = new Map();
  globalThis.localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, String(value)),
    removeItem: key => storage.delete(key),
  };
  // A fresh module per case also isolates the single-flight refresh state.
  const source = bundle.outputFiles[0].text + `\n// case ${serial++}`;
  const api = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
  globalThis.fetch = async () => { throw new Error('Unexpected fetch; no network is allowed'); };
  api.setRefreshHandler(api.refreshSession);
  return api;
}
async function check(name, run) {
  try { await run(await environment()); console.log(`PASS ${name}`); }
  catch (error) { failed++; console.error(`FAIL ${name}\n${error.stack}`); }
}
function switchToB(api) {
  api.setSession(null, null);
  api.setSession('B', 'refresh-B');
}

await check('old unauthorized response cannot clear the new account', async api => {
  const pending = deferred();
  globalThis.fetch = () => pending.promise;
  api.setRefreshHandler(null);
  api.setSession('A', 'refresh-A');
  const result = api.apiGet('/private').then(value => ({ value }), error => ({ error }));
  switchToB(api);
  pending.resolve(response(401));
  await result;
  assert.equal(api.getToken(), 'B');
  assert.equal(api.getRefreshToken(), 'refresh-B');
});

await check('old refresh success cannot replace a new account', async api => {
  const pending = deferred();
  globalThis.fetch = () => pending.promise;
  api.setSession('A', 'refresh-A');
  const result = api.refreshSession();
  switchToB(api);
  pending.resolve(response(200, { access_token: 'A-new', refresh_token: 'refresh-A-new' }));
  assert.equal(await result, false);
  assert.equal(api.getToken(), 'B');
  assert.equal(api.getRefreshToken(), 'refresh-B');
});

await check('old mutation is never replayed with the new accounts credentials', async api => {
  const pending = deferred();
  const calls = [];
  globalThis.fetch = (url, init) => {
    calls.push({ path: new URL(url).pathname, auth: init.headers.Authorization, body: init.body });
    if (calls.length === 1) return pending.promise;
    return Promise.resolve(response(200, url.endsWith('/auth/refresh')
      ? { access_token: 'B-new', refresh_token: 'refresh-B-new' } : { ok: true }));
  };
  api.setSession('A', 'refresh-A');
  const result = api.apiPost('/me/delete', {}).then(value => ({ value }), error => ({ error }));
  switchToB(api);
  pending.resolve(response(401));
  await result;
  assert.deepEqual(calls.map(call => [call.path, call.auth]), [['/me/delete', 'Bearer A']]);
  assert.equal(api.getToken(), 'B');
});

await check('old protected success is rejected after account change', async api => {
  const pending = deferred();
  globalThis.fetch = () => pending.promise;
  api.setSession('A', 'refresh-A');
  const result = api.apiGet('/trusted-contacts').then(value => ({ value }), error => ({ error }));
  switchToB(api);
  pending.resolve(response(200, { private: 'account-A' }));
  assert.ok((await result).error, 'Private result of account A escaped to a new session');
});

await check('session boundary is checked after asynchronous JSON decoding', async api => {
  const readingBody = deferred();
  const body = deferred();
  globalThis.fetch = async () => ({ status: 200, ok: true,
    json: () => { readingBody.resolve(); return body.promise; } });
  api.setSession('A', 'refresh-A');
  const result = api.apiGet('/private').then(value => ({ value }), error => ({ error }));
  await readingBody.promise;
  switchToB(api);
  body.resolve({ private: 'account-A' });
  assert.ok((await result).error, 'Account changed while the old body was being decoded');
});

await check('old failed unauthenticated refresh cannot clear the new session', async api => {
  const pending = deferred();
  globalThis.fetch = () => pending.promise;
  api.setSession('A', 'refresh-A');
  const result = api.refreshSession();
  switchToB(api);
  pending.resolve(response(401));
  assert.equal(await result, false);
  assert.equal(api.getToken(), 'B');
  assert.equal(api.getRefreshToken(), 'refresh-B');
});

await check('auth false failure cannot log out an existing session', async api => {
  globalThis.fetch = async () => response(401);
  api.setSession('B', 'refresh-B');
  await api.apiPost('/auth/verify', { code: 'invalid' }, { auth: false }).catch(() => {});
  assert.equal(api.getToken(), 'B');
  assert.equal(api.getRefreshToken(), 'refresh-B');
});

await check('ordinary refresh still rotates tokens and retries its request', async api => {
  const calls = [];
  globalThis.fetch = async (url, init) => {
    calls.push({ path: new URL(url).pathname, auth: init.headers.Authorization });
    if (url.endsWith('/auth/refresh')) return response(200, { access_token: 'A-new', refresh_token: 'refresh-A-new' });
    return init.headers.Authorization === 'Bearer A' ? response(401) : response(200, { ok: true });
  };
  api.setSession('A', 'refresh-A');
  assert.deepEqual(await api.apiGet('/private'), { ok: true });
  assert.equal(api.getToken(), 'A-new');
  assert.equal(api.getRefreshToken(), 'refresh-A-new');
  assert.deepEqual(calls.map(call => call.path), ['/private', '/auth/refresh', '/private']);
});

await check('parallel unauthorized requests share one refresh', async api => {
  let refreshes = 0;
  let oldRequests = 0;
  const refreshResponse = deferred();
  const refreshStarted = deferred();
  globalThis.fetch = async (url, init) => {
    if (url.endsWith('/auth/refresh')) {
      refreshes++;
      refreshStarted.resolve();
      return refreshResponse.promise;
    }
    if (init.headers.Authorization === 'Bearer A') { oldRequests++; return response(401); }
    return response(200, { ok: true });
  };
  api.setSession('A', 'refresh-A');
  const first = api.apiGet('/first');
  const second = api.apiGet('/second');
  await refreshStarted.promise;
  // Drain promise continuations so both 401 handlers observe the same in-flight refresh.
  await Promise.resolve(); await Promise.resolve();
  refreshResponse.resolve(response(200, { access_token: 'A-new', refresh_token: 'refresh-A-new' }));
  assert.deepEqual(await Promise.all([first, second]), [{ ok: true }, { ok: true }]);
  assert.equal(oldRequests, 2);
  assert.equal(refreshes, 1);
});

await check('late parallel 401 uses the already rotated token without another refresh', async api => {
  const late401 = deferred();
  let refreshes = 0;
  globalThis.fetch = async (url, init) => {
    if (url.endsWith('/auth/refresh')) {
      refreshes++;
      return response(200, { access_token: 'A-new', refresh_token: 'refresh-A-new' });
    }
    if (url.endsWith('/slow') && init.headers.Authorization === 'Bearer A') return late401.promise;
    return init.headers.Authorization === 'Bearer A' ? response(401) : response(200, { ok: true });
  };
  api.setSession('A', 'refresh-A');
  const slow = api.apiGet('/slow');
  assert.deepEqual(await api.apiGet('/fast'), { ok: true });
  late401.resolve(response(401));
  assert.deepEqual(await slow, { ok: true });
  assert.equal(refreshes, 1);
  assert.equal(api.getToken(), 'A-new');
});

await check('old refresh finishing cannot replace or clear a new sessions in-flight refresh', async api => {
  const responseA = deferred();
  const responseB = deferred();
  const startedA = deferred();
  const startedB = deferred();
  const refreshTokens = [];
  globalThis.fetch = async (url, init) => {
    if (url.endsWith('/auth/refresh')) {
      const token = JSON.parse(init.body).refresh_token;
      refreshTokens.push(token);
      if (token === 'refresh-A') { startedA.resolve(); return responseA.promise; }
      if (token === 'refresh-B') { startedB.resolve(); return responseB.promise; }
      throw new Error('Unexpected refresh token');
    }
    return init.headers.Authorization === 'Bearer B-new' ? response(200, { ok: true }) : response(401);
  };
  api.setSession('A', 'refresh-A');
  const old = api.apiGet('/old').then(value => ({ value }), error => ({ error }));
  await startedA.promise;
  switchToB(api);
  const firstB = api.apiGet('/new');
  await startedB.promise;
  responseA.resolve(response(200, { access_token: 'A-new', refresh_token: 'refresh-A-new' }));
  assert.ok((await old).error);
  assert.equal(api.getToken(), 'B');
  // A's finally handler must not erase B's flight: this third request should join it.
  const secondB = api.apiGet('/also-new');
  await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
  responseB.resolve(response(200, { access_token: 'B-new', refresh_token: 'refresh-B-new' }));
  assert.deepEqual(await Promise.all([firstB, secondB]), [{ ok: true }, { ok: true }]);
  assert.deepEqual(refreshTokens, ['refresh-A', 'refresh-B']);
  assert.equal(api.getToken(), 'B-new');
});

await check('direct setToken account changes invalidate an old request even if token is reused', async api => {
  const pending = deferred();
  globalThis.fetch = () => pending.promise;
  api.setSession('A', 'refresh-A');
  const old = api.apiGet('/old').then(value => ({ value }), error => ({ error }));
  api.setToken(null);
  api.setToken('A');
  pending.resolve(response(200, { private: 'old-session' }));
  assert.ok((await old).error, 'Token equality is not sufficient after logout and re-login');
  assert.equal(api.getToken(), 'A');
});

console.log(`Session boundary cases: ${serial - failed} passed, ${failed} failed`);
if (failed) process.exitCode = 1;
