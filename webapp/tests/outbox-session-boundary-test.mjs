import { until, storedActions } from './outbox-helpers.mjs';
import { IDBFactory } from 'fake-indexeddb';
globalThis.indexedDB = new IDBFactory();
// Production outbox, API wrappers and session guards; only fetch/storage are replaced.
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

const bundle = await build({
  stdin: { contents: "export * from './src/api/client'; export * from './src/utils/outbox';",
    resolveDir: fileURLToPath(new URL('../', import.meta.url)), loader: 'ts' },
  bundle: true, write: false, platform: 'node', format: 'esm', define: { 'import.meta.env': '{}' },
});
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const ok = () => new Response('{}', { status: 200 });
let serial = 0, failed = 0;
async function environment() {
  globalThis.indexedDB = new IDBFactory();
  const store = new Map();
  globalThis.localStorage = { getItem: k => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)), removeItem: k => store.delete(k) };
  const source = bundle.outputFiles[0].text + `\n// outbox case ${serial++}`;
  const api = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
  const env = { api, store, calls: [], reply: async () => ok() };
  globalThis.fetch = (url, init) => {
    const call = { path: new URL(url).pathname, auth: init.headers.Authorization, body: JSON.parse(init.body ?? '{}') };
    env.calls.push(call);
    return env.reply(call);
  };
  return env;
}
async function check(name, test) {
  try { await test(await environment()); console.log(`PASS ${name}`); }
  catch (error) { failed++; console.error(`FAIL ${name}\n${error.message}`); }
}

await check('late flush of A cannot overwrite B queue or send A second action as B', async ({ api, store, calls }) => {
  // The actual API client turns a late A response into ApiError409 after setSession B.
  const pending = deferred();
  globalThis.fetch = (url, init) => {
    calls.push({ path: new URL(url).pathname, auth: init.headers.Authorization, body: JSON.parse(init.body ?? '{}') });
    return calls.length === 1 ? pending.promise : Promise.resolve(ok());
  };
  api.setSession('A', 'refresh-A');
  await api.enqueue(42, 'message', 'A1');
  await api.enqueue(42, 'message', 'A2');
  const flushing = api.flushOutbox();
  await until(() => calls.length === 1);
  assert.equal(calls.length, 1);
  await api.clearOutbox();
  api.setSession('B', 'refresh-B');
  await api.enqueue(99, 'message', 'B1');
  pending.resolve(ok());
  await flushing;
  assert.deepEqual(calls.map(c => [c.auth, c.body.text]), [['Bearer A', 'A1']], 'Old action escaped to the new session');
  assert.equal(api.outboxCount(99), 1, 'New account queue was overwritten by old snapshot');
  assert.deepEqual((await storedActions()).map(action => action.payload), ['B1']);
});

await check('enqueue during a pending flush is preserved instead of overwritten by its snapshot', async env => {
  const { api } = env;
  const pending = deferred();
  env.reply = () => env.calls.length === 1 ? pending.promise : Promise.resolve(ok());
  api.setSession('A', 'refresh-A');
  await api.enqueue(42, 'message', 'A1');
  await api.enqueue(42, 'message', 'A2');
  const flushing = api.flushOutbox();
  await until(() => env.calls.length === 1);
  assert.equal(env.calls.length, 1);
  await api.enqueue(42, 'message', 'A3');
  pending.resolve(ok());
  await flushing;
  const sent = env.calls.map(call => call.body.text);
  const remaining = (await storedActions()).map(action => action.payload);
  assert.deepEqual([...sent, ...remaining], ['A1', 'A2', 'A3'], 'New action was lost while acknowledging the older one');
});

await check('offline failure leaves every queued action available for retry', async env => {
  env.reply = async () => { throw new Error('Offline fixture'); };
  env.api.setSession('A', 'refresh-A');
  await env.api.enqueue(42, 'message', 'A1');
  await env.api.enqueue(42, 'message', 'A2');
  assert.equal(await env.api.flushOutbox(), false);
  assert.equal(env.api.outboxCount(42), 2);
  assert.equal(env.calls.length, 1);
});

await check('ordinary successful flush sends actions in order and empties queue', async env => {
  env.api.setSession('A', 'refresh-A');
  await env.api.enqueue(42, 'message', 'A1');
  await env.api.enqueue(42, 'message', 'A2');
  assert.equal(await env.api.flushOutbox(), true);
  assert.deepEqual(env.calls.map(call => [call.auth, call.body.text]), [['Bearer A', 'A1'], ['Bearer A', 'A2']]);
  assert.equal(env.api.hasPending(), false);
});

await check('old online listener cannot start work for the new account', async env => {
  const handlers = new Set();
  globalThis.window = {
    addEventListener: (event, fn) => { if (event === 'online') handlers.add(fn); },
    removeEventListener: (event, fn) => { if (event === 'online') handlers.delete(fn); },
  };
  env.api.setSession('A', 'refresh-A');
  let notifications = 0;
  const stop = env.api.watchOutbox(() => notifications++);
  env.api.setSession('B', 'refresh-B');
  await env.api.enqueue(99, 'message', 'B1');
  for (const handler of handlers) handler();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(env.calls.length, 0);
  assert.equal(env.api.outboxCount(99), 1);
  assert.equal(notifications, 0);
  stop();
  assert.equal(handlers.size, 0);
});

await check('token refresh preserves the current flush and remaining actions', async env => {
  const pending = deferred();
  env.reply = () => env.calls.length === 1 ? pending.promise : Promise.resolve(ok());
  env.api.setSession('A', 'refresh-A');
  await env.api.enqueue(42, 'message', 'A1');
  await env.api.enqueue(42, 'message', 'A2');
  const flushing = env.api.flushOutbox();
  await until(() => env.calls.length === 1);
  assert.equal(env.api.rotateSession('A-new', 'refresh-A-new', env.api.getSessionGeneration()), true);
  pending.resolve(ok());
  assert.equal(await flushing, true);
  assert.deepEqual(env.calls.map(call => [call.auth, call.body.text]), [['Bearer A', 'A1'], ['Bearer A-new', 'A2']]);
  assert.equal(env.api.hasPending(), false);
});

await check('new account can flush while the previous account request is still pending', async env => {
  const pending = deferred();
  env.reply = () => env.calls.length === 1 ? pending.promise : Promise.resolve(ok());
  env.api.setSession('A', 'refresh-A');
  await env.api.enqueue(42, 'message', 'A1');
  const oldFlush = env.api.flushOutbox();
  await until(() => env.calls.length === 1);
  await env.api.clearOutbox();
  env.api.setSession('B', 'refresh-B');
  await env.api.enqueue(99, 'message', 'B1');
  try {
    assert.equal(await env.api.flushOutbox(), true, 'Old pending request blocked the new account queue');
    assert.deepEqual(env.calls.map(call => [call.auth, call.body.text]), [['Bearer A', 'A1'], ['Bearer B', 'B1']]);
  } finally {
    pending.resolve(ok());
    await oldFlush;
  }
  assert.equal(env.api.hasPending(), false);
});

await check('same account cannot send the pending action twice', async env => {
  const pending = deferred();
  env.reply = () => pending.promise;
  env.api.setSession('A', 'refresh-A');
  await env.api.enqueue(42, 'message', 'A1');
  const first = env.api.flushOutbox();
  await until(() => env.calls.length === 1);
  try {
    assert.equal(await env.api.flushOutbox(), false);
    assert.equal(env.calls.length, 1);
  } finally {
    pending.resolve(ok());
    await first;
  }
  assert.equal(env.api.hasPending(), false);
});

await check('saved actions cannot be inherited by a new account without explicit cleanup', async env => {
  env.api.setSession('A', 'refresh-A');
  await env.api.enqueue(42, 'message', 'private A');
  env.api.setSession('B', 'refresh-B');
  assert.equal(env.api.hasPending(), false);
  assert.equal(env.api.outboxCount(42), 0);
  await env.api.flushOutbox();
  assert.equal(env.calls.length, 0);
  await env.api.enqueue(99, 'message', 'own B');
  await env.api.flushOutbox();
  assert.deepEqual(env.calls.map(call => [call.auth, call.body.text]), [['Bearer B', 'own B']]);
});

await check('legacy ownerless actions are never sent under the current token', async env => {
  env.api.setSession('B', 'refresh-B');
  env.store.set('yuldash.outbox', JSON.stringify([{id:1,bookingId:42,kind:'message',payload:'owner unknown',createdAt:Date.now()}]));
  assert.equal(env.api.hasPending(), false);
  await env.api.flushOutbox();
  assert.equal(env.calls.length, 0);
});

console.log(`Outbox session cases: ${serial - failed} passed, ${failed} failed`);
if (failed) process.exitCode = 1;
