// Production privacy helper; in-memory stores model one tab and shared local storage.
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

const bundle = await build({ entryPoints: [fileURLToPath(new URL('../src/utils/privacy.ts', import.meta.url))],
  bundle: true, write: false, platform: 'node', format: 'esm' });
let imports = 0, cases = 0, failed = 0;
async function privacy() {
  const source = bundle.outputFiles[0].text + `\n// tab module ${imports++}`;
  return import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
}
function storage(map) {
  return { get length() { return map.size; }, key: i => [...map.keys()][i] ?? null,
    getItem: k => map.get(k) ?? null, setItem: (k, v) => map.set(k, String(v)), removeItem: k => map.delete(k) };
}
const privateEntries = {
  'yuldash.winterAsk.42': '1',
  'yuldash.winterAskOrder.51': '1',
  'yuldash.tracked.booking42': '1',
  'yuldash.scroll.rides': '450',
  'yuldash.tripPass.42': '{"boardingCode":"synthetic"}',
};
function seedPrivate(map) { for (const [key, value] of Object.entries(privateEntries)) map.set(key, value); }
// Before implementation this is intentionally a no-op: RED proves missing cleanup,
// not a missing export/import or compilation error.
function sync(module, generation) { module.syncPersonalSession?.(generation); }
async function check(name, test) {
  cases++;
  const session = new Map();
  const local = new Map();
  globalThis.sessionStorage = storage(session);
  globalThis.localStorage = storage(local);
  try { await test({ session, local, module: await privacy() }); console.log(`PASS ${name}`); }
  catch (error) { failed++; console.error(`FAIL ${name}\n${error.stack}`); }
}

await check('account change clears tab-private prefixes but preserves chunk reload state', ({ session, module }) => {
  sync(module, 'epoch-A');
  seedPrivate(session);
  session.set('yuldash.chunkReload', '1');
  sync(module, 'epoch-B');
  for (const key of Object.keys(privateEntries)) assert.equal(session.has(key), false, `Old tab data survived: ${key}`);
  assert.equal(session.get('yuldash.chunkReload'), '1');
});

await check('same owner survives module reload without losing tab state', async ({ session, module }) => {
  sync(module, 'epoch-A');
  seedPrivate(session);
  const reloaded = await privacy();
  sync(reloaded, 'epoch-A');
  for (const [key, value] of Object.entries(privateEntries)) assert.equal(session.get(key), value);
});

await check('legacy ownerless tab state is not adopted by the current account', ({ session, module }) => {
  seedPrivate(session);
  session.set('yuldash.chunkReload', '1');
  sync(module, 'epoch-B');
  for (const key of Object.keys(privateEntries)) assert.equal(session.has(key), false, `Ownerless data was adopted: ${key}`);
  assert.equal(session.get('yuldash.chunkReload'), '1');
});

await check('tab synchronization cannot delete shared local storage of the new account', ({ session, local, module }) => {
  sync(module, 'epoch-A');
  seedPrivate(session);
  local.set('yuldash.token', 'account-B');
  local.set('yuldash.consents', '{"owner":"B","offer":true}');
  local.set('yuldash.tripPass.99', 'new-account-data');
  local.set('yuldash.lang', 'ba');
  const before = [...local.entries()];
  sync(module, 'epoch-B');
  assert.deepEqual([...local.entries()], before, 'One tab must not erase the current shared account data');
});

await check('blocked session storage does not crash account synchronization', ({ module }) => {
  globalThis.sessionStorage = {
    get length() { throw new Error('Denied'); }, key() { throw new Error('Denied'); },
    getItem() { throw new Error('Denied'); }, setItem() { throw new Error('Denied'); }, removeItem() { throw new Error('Denied'); },
  };
  assert.doesNotThrow(() => sync(module, 'epoch-B'));
});

console.log(`Tab personal storage cases: ${cases - failed} passed, ${failed} failed`);
if (failed) process.exitCode = 1;
