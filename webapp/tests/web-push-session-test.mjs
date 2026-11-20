import { IDBFactory } from 'fake-indexeddb';
// Actual Web Push orchestration and HTTP client, with browser/network boundaries controlled.
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { fileURLToPath } from 'node:url';

const bundle = await build({
  stdin: { contents: "export * from './src/api/client'; export * from './src/push/webPush'; export * from './src/utils/sessionRecord'; export * from './src/push/rateDraft'; export * from './src/api/notificationRating';",
    resolveDir: fileURLToPath(new URL('../', import.meta.url)), loader: 'ts' },
  bundle: true, write: false, platform: 'node', format: 'esm',
  define: { 'import.meta.env': '{"VITE_VAPID_PUBLIC_KEY":"AQID"}' },
});
const deferred = () => { let resolve, reject; const promise = new Promise((r, j) => { resolve = r; reject = j; }); return { promise, resolve, reject }; };
const ok = () => new Response('{}', { status: 200 });
let serial = 0;
let failed = 0;
async function environment() {
  globalThis.indexedDB = new IDBFactory();
  const store = new Map();
  globalThis.localStorage = { getItem: k => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)), removeItem: k => store.delete(k) };
  const env = { calls: [], browserUnsubscribes: 0 };
  const sub = { endpoint: 'https://push.invalid/local-only', toJSON: () => ({ keys: { p256dh: 'synthetic', auth: 'synthetic' } }),
    unsubscribe: async () => { env.browserUnsubscribes++; return true; } };
  const registration = {
    active: { postMessage: (message, ports) => { if (message.type === "supports-session-push") ports[0].postMessage(true); } },
    pushManager: { getSubscription: async () => sub, subscribe: async () => sub },
  };
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
async function changeToB(api) { await api.setSession(null, null); await api.setSession('B', 'refresh-B'); }
async function check(name, run) {
  try { await run(await environment()); console.log(`PASS ${name}`); }
  catch (error) { failed++; console.error(`FAIL ${name}\n${error.stack}`); }
}

await check('permission completion from A cannot subscribe account B', async env => {
  const permission = deferred();
  Notification.requestPermission = () => permission.promise;
  await env.api.setSession('A', 'refresh-A');
  const enabling = env.api.enableWebPush();
  await changeToB(env.api);
  permission.resolve('granted');
  assert.equal(await enabling, 'error');
  assert.deepEqual(env.calls, []);
  assert.equal(env.api.isWebPushEnabled(), false);
});

await check('old registration lookup cannot unsubscribe account B', async env => {
  const lookup = deferred();
  navigator.serviceWorker.getRegistration = () => lookup.promise;
  await env.api.setSession('A', 'refresh-A');
  const disabling = env.api.disableWebPush();
  await changeToB(env.api);
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
  await env.api.setSession('A', 'refresh-A');
  const enablingA = env.api.enableWebPush();
  await oldStarted.promise;
  await changeToB(env.api);
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
  await env.api.setSession('A', 'refresh-A');
  const disablingA = env.api.disableWebPush();
  await oldStarted.promise;
  await changeToB(env.api);
  assert.equal(await env.api.enableWebPush(), 'ok');
  oldResponse.resolve(ok());
  await disablingA;
  assert.equal(env.browserUnsubscribes, 0);
  assert.equal(env.api.isWebPushEnabled(), true);
});

await check('normal enable registers the current subscription and enabled flag', async env => {
  await env.api.setSession('A', 'refresh-A');
  assert.equal(await env.api.enableWebPush(), 'ok');
  assert.equal(env.api.isWebPushEnabled(), true);
  assert.deepEqual(env.calls.map(c => [c.path, c.auth]), [['/push/web/subscribe', 'Bearer A']]);
  assert.equal(JSON.parse(env.calls[0].body).endpoint, 'https://push.invalid/local-only');
});

await check('normal disable revokes server and browser subscription', async env => {
  await env.api.setSession('A', 'refresh-A');
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
    await env.api.setSession('A', 'refresh-A');
    const enabling = env.api.enableWebPush();
    await started.promise;
    await changeToB(env.api);
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
  await env.api.setSession('A', 'refresh-A');
  const disabling = env.api.disableWebPush();
  await started.promise;
  await changeToB(env.api);
  pending.resolve(env.subscription);
  await disabling;
  assert.deepEqual(env.calls, []);
  assert.equal(env.browserUnsubscribes, 0);
});

await check('rejected old permission request preserves the new enabled flag', async env => {
  const pending = deferred();
  Notification.requestPermission = () => pending.promise;
  await env.api.setSession('A', 'refresh-A');
  const enablingA = env.api.enableWebPush();
  await changeToB(env.api);
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
  await env.api.setSession('A', 'refresh-A');
  const enablingA = env.api.enableWebPush();
  await started.promise;
  await changeToB(env.api);
  assert.equal(await env.api.enableWebPush(), 'ok');
  pending.reject(new Error('Old network request rejected'));
  assert.equal(await enablingA, 'error');
  assert.equal(env.api.isWebPushEnabled(), true);
});

const scopedAccess = sid => "fixture." + Buffer.from(JSON.stringify({ sub: "42", sid })).toString("base64url") + ".fixture";
const idA = "a".repeat(64), idB = "b".repeat(64);
const bindA = "1".repeat(32), bindB = "2".repeat(32);
const bindingReply = id => Response.json({ ok: true, binding_id: id });

function worker(locks = { request: async (_name, fn) => fn() }, maxActions = 2) {
  const handlers = new Map(), notifications = [], targets = [];
  const self = {
    navigator: { locks },
    location: { origin: "https://pwa.invalid" },
    addEventListener: (type, fn) => handlers.set(type, fn),
    registration: {
      async showNotification(title, options) {
        notifications.push({ title, ...options, closed: false, close() { this.closed = true; } });
      },
      async getNotifications(options) {
        return notifications.filter(n => !n.closed && (!options || options.tag === n.tag));
      },
    },
    clients: { matchAll: async () => [], openWindow: async url => { targets.push(url); } },
  };
  runInNewContext(readFileSync(new URL("../public/push-sw.js", import.meta.url), "utf8"), {
    self, indexedDB, URL, atob, Notification: { maxActions },
  });
  return {
    notifications, targets,
    async dispatch(type, detail = {}) {
      let done;
      handlers.get(type)({ ...detail, waitUntil: promise => { done = promise; } });
      await done;
    },
  };
}

await check("current signed-in browser displays its notification and opens only its own URL", async env => {
  const sw = worker();
  await env.api.setSession(scopedAccess(idA), "refresh-A");
  env.reply = async () => bindingReply(bindA);
  assert.equal(await env.api.enableWebPush(), "ok");
  const saved = await env.api.readWebPushBinding();
  assert.equal(saved.sessionId, idA);
  assert.equal(saved.bindingId, bindA);
  assert.equal(JSON.parse(env.calls[0].body).session_bound, true);
  for (const target of ["/rides/17", "https://outside.invalid/rides/17", "//outside.invalid/rides/17"]) {
    await sw.dispatch("push", { data: { json: () => ({
      title: "Booking", body: "Details", url: target, tag: "trip", session_id: idA, binding_id: bindA,
    }) } });
    const shown = sw.notifications.at(-1);
    assert.equal(shown.body, "Details");
    await sw.dispatch("notificationclick", { notification: shown });
  }
  assert.deepEqual(sw.targets, ["https://pwa.invalid/rides/17", "/", "/"]);
});

await check("after account replacement, queued and already shown old pushes hide trip details", async env => {
  const sw = worker();
  const dataA = { data: { json: () => ({
    title: "Account A", body: "Old trip", url: "/rides/17", session_id: idA, binding_id: bindA,
  }) } };
  await env.api.setSession(scopedAccess(idA), "refresh-A");
  env.reply = async () => bindingReply(bindA);
  assert.equal(await env.api.enableWebPush(), "ok");
  await sw.dispatch("push", dataA);
  const old = sw.notifications[0];
  assert.equal(old.body, "Old trip");
  await env.api.setSession(null, null);
  await env.api.setSession(scopedAccess(idB), "refresh-B");
  await sw.dispatch("message", { data: { type: "auth-scope-changed" } });
  assert.equal(old.closed, true);
  await sw.dispatch("notificationclick", { notification: old });
  assert.equal(sw.targets.at(-1), "/");
  await sw.dispatch("push", dataA);
  assert.equal(sw.notifications.at(-1).body, undefined);
  assert.equal(sw.notifications.at(-1).data.url, "/");
  assert.equal(sw.notifications.at(-1).data.binding_id, undefined);

  env.reply = async () => bindingReply(bindB);
  assert.equal(await env.api.enableWebPush(), "ok");
  assert.equal(JSON.parse(env.calls.at(-1).body).expected_binding_id, bindA);
  await sw.dispatch("push", { data: { json: () => ({
    title: "Account B", body: "New trip", url: "/rides/29", session_id: idB, binding_id: bindB,
  }) } });
  assert.equal(sw.notifications.at(-1).body, "New trip");
  await sw.dispatch("push", dataA);
  assert.equal(sw.notifications.at(-1).body, undefined);
});

await check("completed old logout can remove a binding before the browser reuses its endpoint", async env => {
  await env.api.setSession(scopedAccess(idA), "refresh-A");
  env.reply = async () => bindingReply(bindA);
  assert.equal(await env.api.enableWebPush(), "ok");
  await env.api.setSession(null, null);
  await env.api.setSession(scopedAccess(idB), "refresh-B");
  env.reply = async call => JSON.parse(call.body).expected_binding_id
    ? new Response("old binding removed", { status: 409 })
    : bindingReply(bindB);
  assert.equal(await env.api.enableWebPush(), "ok");
  assert.deepEqual(env.calls.slice(-2).map(call => JSON.parse(call.body).expected_binding_id), [bindA, undefined]);
  await env.api.disableWebPush();
  assert.equal(JSON.parse(env.calls.at(-1).body).expected_binding_id, bindB);
  assert.equal(await env.api.readWebPushBinding(), undefined);
  assert.equal(env.browserUnsubscribes, 1);
});

await check("notification click waits for a pending logout under the same browser lock", async env => {
  await env.api.setSession(scopedAccess(idA), "refresh-A");
  env.reply = async () => bindingReply(bindA);
  assert.equal(await env.api.enableWebPush(), "ok");
  const signOut = deferred();
  const lockEntered = deferred();
  const sw = worker({ request: async (name, run) => {
    assert.equal(name, "yuldash-session-write");
    lockEntered.resolve();
    await signOut.promise;
    return run();
  } });
  const notification = { data: { session_id: idA, binding_id: bindA, url: "/rides/17" }, close() {} };
  const clicked = sw.dispatch("notificationclick", { notification });
  await lockEntered.promise;
  assert.deepEqual(sw.targets, []);
  await env.api.setSession(null, null);
  signOut.resolve();
  await clicked;
  assert.deepEqual(sw.targets, ["/"]);
});

await check("old active worker cannot register a scoped subscription", async env => {
  await env.api.setSession(scopedAccess(idA), "refresh-A");
  env.registration.active = null;
  assert.equal(await env.api.enableWebPush(), "error");
  assert.equal(env.api.isWebPushEnabled(), false);
  assert.deepEqual(env.calls, []);
});

await check("worker without browser locks shows only the app name and opens its start page", async env => {
  const sw = worker(null);
  await env.api.setSession(scopedAccess(idA), "refresh-A");
  env.reply = async () => bindingReply(bindA);
  assert.equal(await env.api.enableWebPush(), "ok");
  await sw.dispatch("push", { data: { json: () => ({
    body: "Private", url: "/rides/17", session_id: idA, binding_id: bindA,
  }) } });
  assert.equal(sw.notifications.at(-1).body, undefined);
  await sw.dispatch("notificationclick", { notification: {
    data: { session_id: idA, binding_id: bindA, url: "/rides/17" }, close() {},
  } });
  assert.deepEqual(sw.targets, ["/"]);
});

await check("notification with missing storage or old payload exposes no private body", async env => {
  const sw = worker();
  await sw.dispatch("push", { data: { json: () => ({ title: "Private", body: "Private", url: "/rides/5" }) } });
  assert.equal(sw.notifications.at(-1).body, undefined);
  assert.equal(sw.notifications.at(-1).data.url, "/");
  await env.api.setSession(scopedAccess(idA), "refresh-A");
  env.reply = async () => bindingReply(bindA);
  assert.equal(await env.api.enableWebPush(), "ok", "worker must not create an empty database before the app");
  await sw.dispatch("push", { data: { json: () => { throw Error("invalid JSON"); } } });
  assert.equal(sw.notifications.at(-1).body, undefined);
});

async function ratingNotification(env, overrides = {}, actionLimit = 2) {
  navigator.locks = { request: async (_name, run) => run() };
  await env.api.setSession(scopedAccess(idA), "refresh-A");
  env.reply = async () => bindingReply(bindA);
  assert.equal(await env.api.enableWebPush(), "ok");
  const sw = worker(navigator.locks, actionLimit);
  await sw.dispatch("push", { data: { json: () => ({
    url: "/trip/17", session_id: idA, binding_id: bindA,
    rate: { booking_id: 17, recipient_user_id: 42, until: Math.ceil(Date.now() / 1000) + 300 },
    actions: [{ action: "rate_five", title: "5 ★" }, { action: "rate_details", title: "Иҫкәрмәләр бар" }],
    ...overrides,
  }) } });
  return { sw, notification: sw.notifications.at(-1) };
}

await check("quick rating click creates one draft for its own booking, with no HTTP write", async env => {
  const { sw, notification } = await ratingNotification(env);
  assert.equal(JSON.stringify(notification.actions), JSON.stringify([
    { action: "rate_five", title: "5 ★" }, { action: "rate_details", title: "Иҫкәрмәләр бар" },
  ]));
  assert.equal(await env.api.takeRatingDraft(17, 42, env.api.getSessionGeneration()), undefined);
  await sw.dispatch("notificationclick", { notification, action: "rate_five" });
  assert.deepEqual(sw.targets, ["https://pwa.invalid/trip/17"]);
  assert.equal(await env.api.takeRatingDraft(18, 42, env.api.getSessionGeneration()), undefined);
  assert.equal(await env.api.takeRatingDraft(17, 43, env.api.getSessionGeneration()), undefined);
  const reads = await Promise.all([env.api.takeRatingDraft(17, 42, env.api.getSessionGeneration()), env.api.takeRatingDraft(17, 42, env.api.getSessionGeneration())]);
  assert.equal(reads.filter(Boolean).length, 1);
  assert.match(reads.find(Boolean).bindingId, /^1{32}$/);
  assert.deepEqual(env.calls.map(call => call.path), ["/push/web/subscribe"]);
});

await check("the regular link, details action, and browsers without buttons create no draft", async env => {
  const { sw, notification } = await ratingNotification(env, { url: "/trip/17?rate=5" });
  assert.equal(notification.actions, undefined);
  await sw.dispatch("notificationclick", { notification, action: "rate_five" });
  assert.equal(await env.api.takeRatingDraft(17, 42, env.api.getSessionGeneration()), undefined);
  const plain = await ratingNotification(env);
  for (const action of ["", "rate_details"]) {
    await plain.sw.dispatch("notificationclick", { notification: plain.notification, action });
  }
  assert.equal(await env.api.takeRatingDraft(17, 42, env.api.getSessionGeneration()), undefined);
  const unsupported = await ratingNotification(env, {}, 0);
  assert.equal(unsupported.notification.actions, undefined);
  await unsupported.sw.dispatch("notificationclick", { notification: unsupported.notification });
  assert.deepEqual(unsupported.sw.targets, ["https://pwa.invalid/trip/17"]);
  const omitted = await ratingNotification(env, { actions: [] });
  assert.equal(omitted.notification.actions, undefined);
  await omitted.sw.dispatch("notificationclick", { notification: omitted.notification, action: "rate_five" });
  assert.equal(await env.api.takeRatingDraft(17, 42, env.api.getSessionGeneration()), undefined);
});

await check("a wrong recipient, expired notification, and changed binding cannot leave a draft", async env => {
  for (const rate of [
    { booking_id: 17, recipient_user_id: 43, until: Math.ceil(Date.now() / 1000) + 300 },
    { booking_id: 17, recipient_user_id: 42, until: 1 },
    { booking_id: 18, recipient_user_id: 42, until: Math.ceil(Date.now() / 1000) + 300 },
  ]) {
    const { sw, notification } = await ratingNotification(env, { rate });
    assert.equal(notification.actions, undefined);
    await sw.dispatch("notificationclick", { notification, action: "rate_five" });
    assert.equal(await env.api.takeRatingDraft(17, 42, env.api.getSessionGeneration()), undefined);
  }
  const { sw, notification } = await ratingNotification(env);
  await sw.dispatch("notificationclick", { notification, action: "rate_five" });
  const draft = await env.api.takeRatingDraft(17, 42, env.api.getSessionGeneration());
  assert.equal(await env.api.isCurrentRatingDraft(draft), true);
  assert.equal(await env.api.isCurrentRatingDraft({ ...draft, expiresAt: Date.now() - 1 }), false);
  await sw.dispatch("notificationclick", { notification, action: "rate_five" });
  await env.api.setSession(null, null);
  await env.api.pruneRatingDraft();
  await env.api.setSession(scopedAccess(idB), "refresh-B");
  await sw.dispatch("notificationclick", { notification, action: "rate_five" });
  assert.equal(sw.targets.at(-1), "/");
  assert.equal(await env.api.isCurrentRatingDraft(draft), false);
  assert.equal(await env.api.takeRatingDraft(17, 42, env.api.getSessionGeneration()), undefined);
});

await check("manual retry reports the existing stars and a malformed receipt cannot imply success", async env => {
  const { sw, notification } = await ratingNotification(env);
  await sw.dispatch("notificationclick", { notification, action: "rate_five" });
  const draft = await env.api.takeRatingDraft(17, 42, env.api.getSessionGeneration());
  const signal = new AbortController().signal;
  env.reply = async call => {
    if (call.path.endsWith("/details")) return Response.json({
      booking_id: 17, can_rate: true, status: "done", role: "driver", my_stars: 3,
    });
    throw new TypeError("response lost");
  };
  assert.deepEqual(await env.api.fetchNotificationRatingState(17, draft, signal), { allowed: true, stars: 3 });
  await assert.rejects(() => env.api.rateBookingFromNotification(draft, signal));
  env.reply = async () => Response.json({ kept: true, my_stars: 3 });
  assert.deepEqual(await env.api.rateBookingFromNotification(draft, signal), { kept: true, stars: 3 });
  env.reply = async () => Response.json({ kept: false, my_stars: 3 });
  assert.equal(await env.api.rateBookingFromNotification(draft, signal), undefined);
  env.reply = async () => new Response("", { status: 200 });
  assert.equal(await env.api.rateBookingFromNotification(draft, signal), undefined);
  const posts = env.calls.filter(call => call.path === "/bookings/17/rate");
  assert.deepEqual(posts.map(call => JSON.parse(call.body)), Array.from({ length: 4 }, () => ({
    stars: 5, from_notification: true,
  })));
});

await check("replacing the browser binding during auth refresh blocks the second rating POST", async env => {
  const { sw, notification } = await ratingNotification(env);
  await sw.dispatch("notificationclick", { notification, action: "rate_five" });
  const draft = await env.api.takeRatingDraft(17, 42, env.api.getSessionGeneration());
  const endpoint = (await env.api.readWebPushBinding()).endpoint;
  env.api.setRefreshHandler(async () => {
    const access = scopedAccess(idA).replace(".fixture", ".refreshed");
    await env.api.writeWebPushBinding(draft.generation, {
      generation: draft.generation, sessionId: idA, bindingId: bindB, endpoint,
    });
    return { access, refresh: "refreshed" };
  });
  env.reply = async () => new Response(null, { status: 401 });
  await assert.rejects(() => env.api.rateBookingFromNotification(draft, new AbortController().signal),
    { code: "command_scope_changed" });
  assert.equal(env.calls.filter(call => call.path === "/bookings/17/rate").length, 1);
});

console.log(`Web Push session cases: ${serial - failed} passed, ${failed} failed`);
if (failed) process.exitCode = 1;
