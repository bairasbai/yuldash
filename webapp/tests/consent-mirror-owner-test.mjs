// Production flags + session owner code. Storage is in memory; no browser/network calls.
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

const bundle = await build({
  stdin: { contents: "export * from './src/api/client'; export {flags} from './src/flags';",
    resolveDir: fileURLToPath(new URL('../', import.meta.url)), loader: 'ts' },
  bundle: true, write: false, platform: 'node', format: 'esm', define: { 'import.meta.env': '{}' },
});
let serial = 0, failed = 0;
const none = { offer: false, privacy: false, geo: false };
async function environment() {
  const store = new Map();
  globalThis.localStorage = {
    getItem: key => store.get(key) ?? null,
    setItem: (key, value) => store.set(key, String(value)),
    removeItem: key => store.delete(key),
  };
  const source = bundle.outputFiles[0].text + `\n// mirror owner case ${serial++}`;
  const api = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
  return { api, store };
}
async function check(name, test) {
  try { await test(await environment()); console.log(`PASS ${name}`); }
  catch (error) { failed++; console.error(`FAIL ${name}\n${error.stack}`); }
}

await check('direct account change does not reuse the previous consent mirror', ({ api }) => {
  api.setSession('account-A', 'refresh-A');
  api.flags.setConsent('offer', true);
  assert.equal(api.flags.consents().offer, true);
  api.setSession('account-B', 'refresh-B');
  assert.deepEqual(api.flags.consents(), none, 'Account B must not inherit a checked consent from A');
});

await check('ordinary token rotation preserves the same sessions consent mirror', ({ api }) => {
  api.setSession('account-A', 'refresh-A');
  api.flags.setConsent('offer', true);
  api.flags.setConsent('privacy', true);
  const generation = api.getSessionGeneration();
  assert.equal(api.rotateSession('account-A-new', 'refresh-A-new', generation), true);
  assert.deepEqual(api.flags.consents(), { offer: true, privacy: true, geo: false });
});

await check('legacy ownerless consent flags cannot be attributed to the current account', ({ api, store }) => {
  store.set('yuldash.consents', JSON.stringify({ offer: true, privacy: true, geo: true }));
  api.setSession('account-B', 'refresh-B');
  assert.deepEqual(api.flags.consents(), none, 'Legacy device flags have no verified account owner');
});

await check('new account can record its own mirror after an account switch', ({ api }) => {
  api.setSession('account-A', 'refresh-A');
  api.flags.setConsent('offer', true);
  api.flags.setConsent('geo', true);
  api.setSession('account-B', 'refresh-B');
  // This is the same write made by ConsentsScreen after the B server result.
  api.flags.setConsent('privacy', true);
  assert.deepEqual(api.flags.consents(), { offer: false, privacy: true, geo: false });
});

console.log(`Consent mirror owner cases: ${serial - failed} passed, ${failed} failed`);
if (failed) process.exitCode = 1;
