// «Выехал» без сети уходит из очереди позже. Если сервер тогда отказал насовсем («Рано», «Поездка
// отменена»), очередь раньше молча снимала отметку — водитель думал, что пассажир предупреждён.
// Теперь отказ запоминается (и его показывает плашка в оболочке); сообщения чата, временные сбои
// (5xx, 429, обрыв) и чужой вход его не создают.
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { IDBFactory } from 'fake-indexeddb';
import { fileURLToPath } from 'node:url';
import { storedActions } from './outbox-helpers.mjs';
const { outputFiles } = await build({ stdin: { contents: "export * from './src/api/client';export * from './src/utils/outbox';", resolveDir: fileURLToPath(new URL('../', import.meta.url)), loader: 'ts' }, bundle: true, write: false, format: 'esm', platform: 'node', define: { 'import.meta.env': '{}' } });
let serial = 0, passed = 0, failed = 0;
async function check(name, fn) {
  globalThis.indexedDB = new IDBFactory(); const storage = new Map();
  globalThis.localStorage = { getItem: k => storage.get(k) ?? null, setItem: (k, v) => storage.set(k, String(v)), removeItem: k => storage.delete(k) };
  const api = await import('data:text/javascript;base64,' + Buffer.from(outputFiles[0].text + '\n//' + serial++).toString('base64')); api.setSession('A', 'refresh-A');
  try { await fn(api); passed++; console.log('✓ ' + name); } catch (e) { failed++; console.log('FAIL ' + name + ': ' + e.message); }
}
const TOO_EARLY = { ru: 'Рано: «Я выехал» можно нажать за час до выезда — иначе пассажир получит «Водитель выехал» раньше времени.', ba: 'Иртә: «Сыҡтым»ға сығыуға бер сәғәт ҡалғас ҡына баҫып була.', code: 'departed_too_early' };
const refuse = (status, detail) => async () => new Response(JSON.stringify({ detail }), { status, headers: { 'content-type': 'application/json' } });

await check('«Выехал» из очереди, сервер ответил 409 departed_too_early: действие снято, отказ — слова сервера — запомнен', async api => {
  await api.enqueue(42, 'driver_status', 'departed');
  globalThis.fetch = refuse(409, TOO_EARLY);
  await api.flushOutbox();
  assert.equal((await storedActions()).length, 0, 'повторять отказ насовсем бессмысленно — очередь свободна');
  const seen = api.getOutboxRefusals();
  assert.equal(seen.length, 1);
  assert.equal(seen[0].bookingId, 42); assert.equal(seen[0].kind, 'driver_status'); assert.equal(seen[0].payload, 'departed');
  assert.equal(seen[0].message, TOO_EARLY.ru, 'человек читает слова сервера, не общую фразу');
});

for (const status of [400, 403, 404, 409, 410, 422]) await check('отказ насовсем HTTP ' + status + ' по отметке запоминается, а следующая отметка идёт дальше', async api => {
  await api.enqueue(42, 'driver_status', 'departed'); await api.enqueue(42, 'driver_status', 'arriving');
  let calls = 0; globalThis.fetch = async () => ++calls === 1 ? refuse(status, { ru: 'Отказ ' + status, ba: 'Баш тартыу ' + status })() : new Response('{}');
  await api.flushOutbox();
  assert.equal(calls, 2); assert.equal((await storedActions()).length, 0);
  assert.deepEqual(api.getOutboxRefusals().map(r => [r.payload, r.message]), [['departed', 'Отказ ' + status]]);
});

await check('«Доехала» (trip_status) тоже: отказ запомнен', async api => {
  await api.enqueue(7, 'trip_status', 'arrived');
  globalThis.fetch = refuse(409, { ru: 'Доехал — после посадки', ba: 'Барып етеү — ултырғандан һуң' });
  await api.flushOutbox();
  const [r] = api.getOutboxRefusals(); assert.equal(r.kind, 'trip_status'); assert.equal(r.payload, 'arrived'); assert.equal(r.bookingId, 7);
});

await check('сообщение чата с отказом насовсем отказом-плашкой не становится (у чата свой путь)', async api => {
  await api.enqueue(42, 'message', 'привет');
  globalThis.fetch = refuse(403, { ru: 'Чат закрыт', ba: 'Чат ябыҡ' });
  await api.flushOutbox();
  assert.equal((await storedActions()).length, 0); assert.deepEqual(api.getOutboxRefusals(), []);
});

for (const status of [408, 425, 429, 500, 502, 503, 504]) await check('временный сбой HTTP ' + status + ': отметка остаётся в очереди, отказа нет', async api => {
  await api.enqueue(42, 'driver_status', 'departed');
  globalThis.fetch = refuse(status, 'сбой');
  await api.flushOutbox();
  assert.equal((await storedActions()).length, 1); assert.deepEqual(api.getOutboxRefusals(), []);
});

await check('обрыв сети (fetch упал): отметка остаётся, отказа нет', async api => {
  await api.enqueue(42, 'driver_status', 'departed');
  globalThis.fetch = async () => { throw new TypeError('offline'); };
  await api.flushOutbox();
  assert.equal((await storedActions()).length, 1); assert.deepEqual(api.getOutboxRefusals(), []);
});

await check('«Понятно»: отказ снимается и подписчики узнают; два отказа — по очереди', async api => {
  await api.enqueue(42, 'driver_status', 'departed'); await api.enqueue(42, 'driver_status', 'arriving');
  let notified = 0; const stop = api.subscribeOutboxRefusals(() => notified++);
  globalThis.fetch = refuse(409, { ru: 'Нельзя', ba: 'Ярамай' });
  await api.flushOutbox();
  const [first, second] = api.getOutboxRefusals(); assert.equal(first.payload, 'departed'); assert.equal(second.payload, 'arriving'); assert.ok(notified >= 2);
  api.dismissOutboxRefusal(first.key); assert.deepEqual(api.getOutboxRefusals().map(r => r.payload), ['arriving']);
  api.dismissOutboxRefusal(first.key); assert.equal(api.getOutboxRefusals().length, 1, 'повторное закрытие ничего не ломает');
  stop();
});

await check('отказ принадлежит входу: после выхода и входа другого его не видно; «очистить очередь» его снимает', async api => {
  await api.enqueue(42, 'driver_status', 'departed');
  globalThis.fetch = refuse(409, { ru: 'Нельзя', ba: 'Ярамай' });
  await api.flushOutbox(); assert.equal(api.getOutboxRefusals().length, 1);
  api.setSession('B', 'refresh-B');
  assert.deepEqual(api.getOutboxRefusals(), [], 'чужой отказ новому человеку не показываем');
  api.setSession('A', 'refresh-A'); // другая сессия того же человека — всё равно новое поколение
  assert.deepEqual(api.getOutboxRefusals(), []);
  await api.enqueue(43, 'driver_status', 'departed'); await api.flushOutbox();
  assert.equal(api.getOutboxRefusals().length, 1);
  await api.clearOutbox(); assert.deepEqual(api.getOutboxRefusals(), []);
});

console.log(`Outbox refusals: ${passed} passed, ${failed} failed`); if (failed) process.exitCode = 1;
