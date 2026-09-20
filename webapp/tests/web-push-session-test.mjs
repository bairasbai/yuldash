// Actual Web Push orchestration and HTTP client, with browser/network boundaries controlled.
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

const bundle = await build({
  stdin: { contents: "export * from './src/api/client'; export * from './src/push/webPush';",
    resolveDir: fileURLToPath(new URL('../', import.meta.url)), loader: 'ts' },
  bundle: true, write: false, platform: 'node', format: 'esm',
  define: { 'import.meta.env': '{"VITE_VAPID_PUBLIC_KEY":"AQID"}' },
});
const deferred = () => { let resolve, reject; const promise = new Promise((r, j) => { resolve = r; reject = j; }); return { promise, resolve, reject }; };
const ok = () => new Response('{}', { status: 200 });
let serial = 0;
let failed = 0;
async function environment() {
  const store = new Map();
  globalThis.localStorage = { getItem: k => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)), removeItem: k => store.delete(k) };
  const env = { calls: [], browserUnsubscribes: 0 };
  const sub = { endpoint: 'https://push.invalid/local-only', toJSON: () => ({ keys: { p256dh: 'synthetic', auth: 'synthetic' } }),
    unsubscribe: async () => { env.browserUnsubscribes++; return true; } };
  const registration = { pushManager: { getSubscription: async () => sub, subscribe: async () => sub } };
  globalThis.Notification = { permission: 'granted', requestPermission: async () => 'granted' };
  globalThis.PushManager = { supportedContentEncodings: ['aes128gcm'] };
  const nav = { userAgent: 'local-test', platform: 'test', maxTouchPoints: 0,
    serviceWorker: { ready: Promise.resolve(registration), getRegistration: async () => registration } };
  Object.defineProperty(globalThis, 'navigator', { value: nav, configurable: true });
  globalThis.window = { Notification, PushManager, navigator: nav, matchMedia: () => ({ matches: true }) };
  env.registration = registration;
  env.subscription = sub;
  env.reply = async () => ok();
  globalThis.fetch = (url, init) => {
    const call = { path: new URL(url).pathname, auth: init.headers.Authorization, body: init.body };
    env.calls.push(call);
    return env.reply(call);
  };
  const source = bundle.outputFiles[0].text + `\n// case ${serial++}`;
  env.api = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
  return env;
}
function changeToB(api) { api.setSession(null, null); api.setSession('B', 'refresh-B'); }
async function check(name, run) {
  try { await run(await environment()); console.log(`PASS ${name}`); }
  catch (error) { failed++; console.error(`FAIL ${name}\n${error.stack}`); }
}

await check('permission completion from A cannot subscribe account B', async env => {
  const permission = deferred();
  Notification.requestPermission = () => permission.promise;
  env.api.setSession('A', 'refresh-A');
  const enabling = env.api.enableWebPush();
  changeToB(env.api);
  permission.resolve('granted');
  assert.equal(await enabling, 'error');
  assert.deepEqual(env.calls, []);
  assert.equal(env.api.isWebPushEnabled(), false);
});

await check('old registration lookup cannot unsubscribe account B', async env => {
  const lookup = deferred();
  navigator.serviceWorker.getRegistration = () => lookup.promise;
  env.api.setSession('A', 'refresh-A');
  const disabling = env.api.disableWebPush();
  changeToB(env.api);
  lookup.resolve(env.registration);
  await disabling;
  assert.deepEqual(env.calls, []);
  assert.equal(env.browserUnsubscribes, 0);
});

await check('old enable result cannot erase the new accounts enabled flag', async env => {
  const oldResponse = deferred();
  const oldStarted = deferred();
  env.reply = call => {
    if (call.auth === 'Bearer A') { oldStarted.resolve(); return oldResponse.promise; }
    return Promise.resolve(ok());
  };
  env.api.setSession('A', 'refresh-A');
  const enablingA = env.api.enableWebPush();
  await oldStarted.promise;
  changeToB(env.api);
  assert.equal(await env.api.enableWebPush(), 'ok');
  assert.equal(env.api.isWebPushEnabled(), true);
  oldResponse.resolve(ok());
  assert.equal(await enablingA, 'error');
  assert.equal(env.api.isWebPushEnabled(), true);
});

await check('old disable response cannot cancel the subscription adopted by B', async env => {
  const oldResponse = deferred();
  const oldStarted = deferred();
  env.reply = call => {
    if (call.auth === 'Bearer A') { oldStarted.resolve(); return oldResponse.promise; }
    return Promise.resolve(ok());
  };
  env.api.setSession('A', 'refresh-A');
  const disablingA = env.api.disableWebPush();
  await oldStarted.promise;
  changeToB(env.api);
  assert.equal(await env.api.enableWebPush(), 'ok');
  oldResponse.resolve(ok());
  await disablingA;
  assert.equal(env.browserUnsubscribes, 0);
  assert.equal(env.api.isWebPushEnabled(), true);
});

await check('normal enable registers the current subscription and enabled flag', async env => {
  env.api.setSession('A', 'refresh-A');
  assert.equal(await env.api.enableWebPush(), 'ok');
  assert.equal(env.api.isWebPushEnabled(), true);
  assert.deepEqual(env.calls.map(c => [c.path, c.auth]), [['/push/web/subscribe', 'Bearer A']]);
  assert.equal(JSON.parse(env.calls[0].body).endpoint, 'https://push.invalid/local-only');
});

await check('normal disable revokes server and browser subscription', async env => {
  env.api.setSession('A', 'refresh-A');
  assert.equal(await env.api.enableWebPush(), 'ok');
  await env.api.disableWebPush();
  assert.equal(env.api.isWebPushEnabled(), false);
  assert.equal(env.browserUnsubscribes, 1);
  assert.deepEqual(env.calls.map(c => [c.path, c.auth]), [
    ['/push/web/subscribe', 'Bearer A'], ['/push/web/unsubscribe', 'Bearer A'],
  ]);
});

for (const stage of ['ready', 'getSubscription', 'subscribe']) {
  await check(`enable session guard after ${stage}`, async env => {
    const pending = deferred();
    const started = deferred();
    if (stage === 'ready') {
      Object.defineProperty(navigator.serviceWorker, 'ready', {
        configurable: true, get: () => { started.resolve(); return pending.promise; },
      });
    } else if (stage === 'getSubscription') {
      env.registration.pushManager.getSubscription = () => { started.resolve(); return pending.promise; };
    } else {
      env.registration.pushManager.getSubscription = async () => null;
      env.registration.pushManager.subscribe = () => { started.resolve(); return pending.promise; };
    }
    env.api.setSession('A', 'refresh-A');
    const enabling = env.api.enableWebPush();
    await started.promise;
    changeToB(env.api);
    pending.resolve(stage === 'ready' ? env.registration : env.subscription);
    assert.equal(await enabling, 'error');
    assert.deepEqual(env.calls, []);
    assert.equal(env.browserUnsubscribes, 0, 'Do not remove a subscription that a newer account may already use');
  });
}

await check('disable session guard after subscription lookup', async env => {
  const pending = deferred();
  const started = deferred();
  env.registration.pushManager.getSubscription = () => { started.resolve(); return pending.promise; };
  env.api.setSession('A', 'refresh-A');
  const disabling = env.api.disableWebPush();
  await started.promise;
  changeToB(env.api);
  pending.resolve(env.subscription);
  await disabling;
  assert.deepEqual(env.calls, []);
  assert.equal(env.browserUnsubscribes, 0);
});

await check('rejected old permission request preserves the new enabled flag', async env => {
  const pending = deferred();
  Notification.requestPermission = () => pending.promise;
  env.api.setSession('A', 'refresh-A');
  const enablingA = env.api.enableWebPush();
  changeToB(env.api);
  Notification.requestPermission = async () => 'granted';
  assert.equal(await env.api.enableWebPush(), 'ok');
  pending.reject(new Error('Old browser request rejected'));
  assert.equal(await enablingA, 'error');
  assert.equal(env.api.isWebPushEnabled(), true);
});

await check('rejected old network request preserves the new enabled flag', async env => {
  const pending = deferred();
  const started = deferred();
  env.reply = call => {
    if (call.auth === 'Bearer A') { started.resolve(); return pending.promise; }
    return Promise.resolve(ok());
  };
  env.api.setSession('A', 'refresh-A');
  const enablingA = env.api.enableWebPush();
  await started.promise;
  changeToB(env.api);
  assert.equal(await env.api.enableWebPush(), 'ok');
  pending.reject(new Error('Old network request rejected'));
  assert.equal(await enablingA, 'error');
  assert.equal(env.api.isWebPushEnabled(), true);
});

console.log(`Web Push session cases: ${serial - failed} passed, ${failed} failed`);
if (failed) process.exitCode = 1;
