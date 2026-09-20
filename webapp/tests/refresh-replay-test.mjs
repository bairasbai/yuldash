import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { IDBFactory, IDBObjectStore } from 'fake-indexeddb';
import { fileURLToPath } from 'node:url';
const { outputFiles } = await build({ stdin: { contents: "export * from './src/api/client'; export { refreshSession } from './src/api/auth';", resolveDir: fileURLToPath(new URL('../', import.meta.url)), loader: 'ts' }, bundle: true, write: false, format: 'esm', platform: 'node', define: { 'import.meta.env': '{}' } });
let serial = 0, passed = 0, failed = 0;
const response = (status, body = {}) => new Response(JSON.stringify(body), { status });
const pair = { access_token: 'A2', refresh_token: 'R2' };
const instance = () => import('data:text/javascript;base64,' + Buffer.from(outputFiles[0].text + '\n//' + serial++).toString('base64'));
async function intents() {
  const db = await new Promise((resolve, reject) => { const r = indexedDB.open('yuldash-refresh-intents', 1); r.onsuccess = () => resolve(r.result); r.onerror = () => reject(r.error); });
  return new Promise((resolve, reject) => { const tx = db.transaction('intents'), r = tx.objectStore('intents').getAll(); tx.oncomplete = () => { db.close(); resolve(r.result); }; tx.onabort = () => { db.close(); reject(tx.error); }; });
}
async function check(name, run) {
  globalThis.indexedDB = new IDBFactory();
  const storage = new Map();
  globalThis.localStorage = { getItem: k => storage.get(k) ?? null, setItem: (k, v) => storage.set(k, String(v)), removeItem: k => storage.delete(k) };
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: {} });
  const api = await instance(); api.setSession('A', 'R');
  try { await run(api); passed++; console.log('PASS ' + name); } catch (e) { failed++; console.log('FAIL ' + name + ': ' + e.message); }
}
for (const failure of ['network', '503', 'malformed']) await check('reload retains committed nonce after ' + failure, async api => {
  let first;
  globalThis.fetch = async (_, init) => { first = JSON.parse(init.body); if (failure === 'network') throw new TypeError('offline'); return failure === '503' ? response(503) : response(200, {}); };
  await assert.rejects(api.refreshSession()); assert.match(first.rotation_id ?? '', /^[a-f0-9]{64}$/);
  const reloaded = await instance();
  globalThis.fetch = async (_, init) => { assert.deepEqual(JSON.parse(init.body), first); return response(200, pair); };
  assert.equal(await reloaded.refreshSession(), true); assert.equal(api.getRefreshToken(), 'R2');
});
await check('two tabs without Web Locks use the same persisted nonce', async api => {
  const other = await instance(), bodies = []; let release;
  const barrier = new Promise(r => release = r);
  globalThis.fetch = async (_, init) => { bodies.push(JSON.parse(init.body)); if (bodies.length === 2) release(); await barrier; return response(200, pair); };
  await Promise.all([api.refreshSession(), other.refreshSession()]);
  assert.equal(bodies.length, 2); assert.match(bodies[0].rotation_id ?? '', /^[a-f0-9]{64}$/); assert.deepEqual(bodies[0], bodies[1]);
});
await check('next parent refresh receives a new nonce', async api => {
  const bodies = [];
  globalThis.fetch = async (_, init) => { bodies.push(JSON.parse(init.body)); return response(200, { access_token: 'A' + bodies.length, refresh_token: 'R' + bodies.length }); };
  await api.refreshSession(); await api.refreshSession();
  assert.match(bodies[0].rotation_id ?? '', /^[a-f0-9]{64}$/); assert.notEqual(bodies[0].rotation_id, bodies[1].rotation_id);
});
await check('late previous account success cannot change replacement session or its intent', async api => {
  let release, start;
  const held = new Promise(r => release = r), ready = new Promise(r => start = r);
  globalThis.fetch = async () => { start(); await held; return response(200, pair); };
  const pending = api.refreshSession(); await ready; api.setSession('B', 'RB');
  let bodyB;
  globalThis.fetch = async (_, init) => { bodyB = JSON.parse(init.body); throw new TypeError('offline'); };
  await assert.rejects(api.refreshSession()); release(); assert.equal(await pending, false);
  assert.equal(api.getRefreshToken(), 'RB');
  globalThis.fetch = async (_, init) => { assert.deepEqual(JSON.parse(init.body), bodyB); return response(200, { access_token: 'B2', refresh_token: 'RB2' }); };
  assert.equal(await api.refreshSession(), true);
});
await check('unavailable durable storage prevents unsafe refresh request', async api => {
  globalThis.indexedDB = undefined; let requests = 0;
  globalThis.fetch = async () => { requests++; return response(200, pair); };
  await assert.rejects(api.refreshSession()); assert.equal(requests, 0); assert.equal(api.getRefreshToken(), 'R');
});
await check('an aborted intent commit sends no request and keeps the session', async api => {
  const original = IDBObjectStore.prototype.put; let requests = 0;
  IDBObjectStore.prototype.put = function (...args) { const request = original.apply(this, args); request.addEventListener('success', () => this.transaction.abort()); return request; };
  globalThis.fetch = async () => { requests++; return response(200, pair); };
  try { await assert.rejects(api.refreshSession()); } finally { IDBObjectStore.prototype.put = original; }
  assert.equal(requests, 0); assert.equal(api.getRefreshToken(), 'R'); assert.deepEqual(await intents(), []);
});
await check('confirmed success and definitive rejection discard the old intent', async api => {
  globalThis.fetch = async () => response(200, pair);
  assert.equal(await api.refreshSession(), true); assert.deepEqual(await intents(), []);
  globalThis.fetch = async () => response(401);
  assert.equal(await api.refreshSession(), false); assert.deepEqual(await intents(), []);
});
await check('logout clears a failed rotation intent', async api => {
  globalThis.fetch = async () => { throw new TypeError('offline'); };
  await assert.rejects(api.refreshSession()); assert.equal((await intents()).length, 1);
  api.setSession(null, null);
  for (let i = 0; i < 20 && (await intents()).length; i++) await new Promise(setImmediate);
  assert.deepEqual(await intents(), []);
});
await check('late R1 response cannot roll back a newer R2 to R3 rotation', async api => {
  const other = await instance(); let release, start;
  const held = new Promise(r => release = r), ready = new Promise(r => start = r); let calls = 0;
  globalThis.fetch = async (_, init) => {
    const body = JSON.parse(init.body); calls++;
    if (calls === 1) { start(); await held; return response(200, pair); }
    return response(200, body.refresh_token === 'R' ? pair : { access_token: 'A3', refresh_token: 'R3' });
  };
  const pending = api.refreshSession(); await ready;
  assert.equal(await other.refreshSession(), true); assert.equal(await other.refreshSession(), true);
  release(); assert.equal(await pending, true); assert.equal(api.getRefreshToken(), 'R3'); assert.equal(api.getToken(), 'A3');
});
console.log(`Refresh replay: ${passed} passed, ${failed} failed`); if (failed) process.exitCode = 1;
