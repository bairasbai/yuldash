// Transport adapter contract, not React lifecycle evidence (separate mounted gate).
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { readFileSync } from 'node:fs';
const calls = globalThis.__waitAdapterCalls = [];
const { outputFiles } = await build({
  entryPoints: [new URL('../src/api/instant.ts', import.meta.url).pathname],
  bundle: true, write: false, platform: 'node', format: 'esm',
  plugins: [{ name: 'transport-recording-boundary', setup(plugin) {
    plugin.onResolve({ filter: /^\.\/client$/ }, () => ({ path: 'recording-client', namespace: 'test' }));
    plugin.onLoad({ filter: /.*/, namespace: 'test' }, () => ({ contents: `
      export const apiGet = (...args) => { globalThis.__waitAdapterCalls.push(['GET', ...args]); return Promise.resolve({id:18}); };
      export const apiPost = (...args) => { globalThis.__waitAdapterCalls.push(['POST', ...args]); return Promise.resolve({ok:true}); };
    ` }));
  } }],
});
try {
  const api = await import('data:text/javascript;base64,' + Buffer.from(outputFiles[0].text).toString('base64'));
  const controller = new AbortController();
  await api.waitForDriver(18);
  assert.deepEqual(calls.pop(), ['POST', '/instant/orders/18/wait', undefined, undefined]);
  console.log('✓ legacy wait remains bodyless and backward compatible');
  await api.waitForDriver(18, { signal: controller.signal, _generation: 'captured-owner' });
  assert.deepEqual(calls.pop(), ['POST', '/instant/orders/18/wait', undefined, { signal: controller.signal, _generation: 'captured-owner' }]);
  console.log('✓ wait forwards captured auth generation and its own abort signal');
  await api.fetchInstantOrder(18, controller.signal, 'captured-owner');
  assert.deepEqual(calls.pop(), ['GET', '/instant/orders/18', { signal: controller.signal, _generation: 'captured-owner', cache: 'no-store' }]);
  console.log('✓ same-order authoritative read forwards owner and no-store');
  await api.fetchInstantOrder(18);
  assert.deepEqual(calls.pop(), ['GET', '/instant/orders/18', { signal: undefined, _generation: undefined, cache: 'no-store' }]);
  console.log('✓ existing order read callers remain supported');
  await api.fetchMyOrders(5, controller.signal, 'display-owner');
  assert.deepEqual(calls.pop(), ['GET', '/instant/orders/mine?limit=5', { signal: controller.signal, _generation: 'display-owner' }]);
  console.log('✓ boot receives the captured display owner, not a later account');
  const screen = readFileSync(new URL('../src/screens/InstantOrderScreen.tsx', import.meta.url), 'utf8');
  const createCompletion = screen.match(/const o = await createInstantOrder\([\s\S]*?onOrdered\(o\);/)?.[0];
  assert.ok(createCompletion, 'Actual create awaits response then calls captured onOrdered prop');
  assert.ok(!createCompletion.includes('getSessionGeneration'), 'No new owner adopted after create await');
  console.log('✓ create callback source linkage retains captured owner (not create-flow acceptance)');
} finally { delete globalThis.__waitAdapterCalls; }
