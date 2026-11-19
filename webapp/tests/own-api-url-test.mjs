import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { IDBFactory } from 'fake-indexeddb';
import { fileURLToPath } from 'node:url';

// Real consumer functions, synthetic session, no network requests.
const { outputFiles } = await build({
  stdin: { contents: "export * from './src/api/client';export {fetchSecureDoc} from './src/api/admin';export {imageUrlOf} from './src/api/chat';",
    resolveDir: fileURLToPath(new URL('../', import.meta.url)), loader: 'ts' },
  bundle: true, write: false, format: 'esm', platform: 'node',
  define: { 'import.meta.env': JSON.stringify({ VITE_API_BASE: 'https://api.example.test' }) },
});
globalThis.location = { href: 'https://api.example.test/' };
globalThis.indexedDB = new IDBFactory();
const storage = new Map();
globalThis.localStorage = { getItem: k => storage.get(k) ?? null, setItem: (k,v) => storage.set(k,String(v)), removeItem: k => storage.delete(k) };
const api = await import('data:text/javascript;base64,' + Buffer.from(outputFiles[0].text).toString('base64'));
await api.setSession('synthetic-admin-token', 'synthetic-refresh');
let calls = [];
globalThis.fetch = async (url, init) => { calls.push({url, init}); return new Response('synthetic-image'); };
let passed = 0, failed = 0;
async function check(name, fn) { try { await fn(); passed++; console.log('✓ ' + name); } catch (e) { failed++; console.error('FAIL ' + name + ': ' + e.message); } }
const malicious = [
  '/\\attacker.example/image', '/\t/attacker.example/image', '/\n/attacker.example/image', '/\r/attacker.example/image',
  '//attacker.example/image', 'https://attacker.example/image', 'https://api.example.test@attacker.example/image',
  'https://api.example.test.attacker.example/image', 'http://api.example.test/image',
  'https://api.example.test:444/image', 'https://user:pass@api.example.test/image',
];
for (const url of malicious) {
  await check('reject URL ' + JSON.stringify(url), () => assert.equal(api.isOwnApiUrl(url), false));
  await check('chat does not embed ' + JSON.stringify(url), () => assert.equal(api.imageUrlOf('[img]' + url), null));
  await check('secure document sends no request ' + JSON.stringify(url), async () => {
    calls = [];
    await assert.rejects(api.fetchSecureDoc(url), /doc host not allowed/);
    assert.equal(calls.length, 0);
  });
}
for (const url of ['/chat/photo.png', 'https://api.example.test/chat/photo.png', 'https://API.EXAMPLE.TEST:443/chat/photo.png']) {
  await check('allow legitimate image ' + url, () => { assert.equal(api.isOwnApiUrl(url), true); assert.equal(api.imageUrlOf('[img]' + url), url); });
}
await check('different PWA origin cannot receive the API token via a relative URL', async () => {
  globalThis.location.href = 'https://pwa.example.test/'; calls = [];
  assert.equal(api.isOwnApiUrl('/secure/docs/test.jpg'), false);
  await assert.rejects(api.fetchSecureDoc('/secure/docs/test.jpg'), /doc host not allowed/);
  assert.equal(calls.length, 0);
  globalThis.location.href = 'https://api.example.test/';
});
await check('document base cannot redirect a relative resource to another origin', () => {
  globalThis.document = {baseURI: 'https://other.example.test/'};
  assert.equal(api.isOwnApiUrl('/chat/photo.png'), false);
  delete globalThis.document;
});
await check('secure document still loads from API with authorization', async () => {
  calls = []; const url = 'https://api.example.test/secure/docs/test.jpg';
  const blobUrl = await api.fetchSecureDoc(url);
  assert.equal(calls.length, 1); assert.equal(calls[0].url, url);
  assert.equal(calls[0].init.headers.Authorization, 'Bearer synthetic-admin-token');
  URL.revokeObjectURL(blobUrl);
});
console.log(`Own API URL: ${passed} passed, ${failed} failed`);
if (failed) process.exitCode = 1;
