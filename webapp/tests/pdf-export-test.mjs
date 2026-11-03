import nodeAssert from 'node:assert/strict';
import { build } from 'esbuild';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
let checks = 0;
const assert = new Proxy(nodeAssert, { get(target, key) {
  const value = target[key];
  return typeof value === 'function' ? (...args) => { checks++; return value.apply(target, args); } : value;
} });
const out = join(root, 'tests/.bundles');
mkdirSync(out, { recursive: true });
const bundle = async (entry, name, plugins = []) => {
  const file = join(out, name + '.mjs');
  await build({ entryPoints: [join(root, entry)], outfile: file, bundle: true, platform: 'node', format: 'esm', packages: 'external',
    define: { 'import.meta.env.BASE_URL': JSON.stringify('/') }, plugins, logLevel: 'silent' });
  return import(pathToFileURL(file).href);
};
const receipt = await bundle('src/utils/receiptPdf.ts', 'receipt-pdf');
const r = { booking_id: 17, ride_id: 18, role: 'passenger', from_city: 'Уфа <script>alert(1)</script>', to_city: 'Стәрлетамаҡ',
  depart_at: '2026-11-03T12:00:00Z', seats: 2, amount: 12345, pay_method: 'cash', paid: false,
  driver_name: 'PRIVATE_DRIVER', driver_verified: true, pickup_stop: 'PRIVATE_STOP', phone: 'PRIVATE_PHONE', token: 'PRIVATE_TOKEN' };
for (const ru of [true, false]) {
  const content = receipt.receiptPdfContent(r, ru);
  assert.ok(content.body.includes(r.from_city), 'untrusted route stays literal text');
  for (const secret of ['PRIVATE_DRIVER', 'PRIVATE_STOP', 'PRIVATE_PHONE', 'PRIVATE_TOKEN']) assert.ok(!JSON.stringify(content).includes(secret), 'private fields excluded');
  assert.match(content.body, /12[\u00a0\u202f ]345 ₽/, 'server amount displayed without changing units');
  assert.match(content.body, /2026.*UTC/, 'download keeps absolute date, year and timezone');
}

let render;
let renderCount = 0;
globalThis.__pdfGenerate = args => { renderCount++; return render(args); };
const pdf = await bundle('src/utils/localPdf.ts', 'pdf-download-mock', [{ name: 'controlled-renderer', setup(b) {
  b.onResolve({ filter: /^@pdfme\/generator$/ }, () => ({ path: 'renderer', namespace: 'controlled' }));
  b.onLoad({ filter: /.*/, namespace: 'controlled' }, () => ({ contents: 'export const generate = args => globalThis.__pdfGenerate(args)', loader: 'js' }));
} }]);
const saved = { fetch: globalThis.fetch, document: globalThis.document, window: globalThis.window,
  create: URL.createObjectURL, revoke: URL.revokeObjectURL };
let current = false, fetches = 0, clicks = 0, removals = 0, urls = 0, revoked = 0;
let resolveFont;
const timers = [];
globalThis.fetch = () => { fetches++; return new Promise(resolve => { resolveFont = resolve; }); };
globalThis.window = { setTimeout: fn => { timers.push(fn); } };
globalThis.document = { body: { appendChild() {} }, createElement: () => ({ click() { clicks++; }, remove() { removals++; } }) };
URL.createObjectURL = () => { urls++; return 'blob:local-pdf'; };
URL.revokeObjectURL = () => { revoked++; };
const options = { title: 'Тест', body: 'Текст', filename: 'test.pdf', isCurrent: () => current };
try {
  assert.equal(await pdf.downloadTextPdf(options), false);
  assert.equal(fetches, 0, 'stale operation does not load anything');
  current = true;
  const duringFont = pdf.downloadTextPdf(options);
  current = false;
  resolveFont({ ok: true, arrayBuffer: async () => new ArrayBuffer(4) });
  assert.equal(await duringFont, false);
  assert.equal(renderCount, 0, 'logout during font load blocks rendering');
  current = true;
  let finish;
  render = () => new Promise(resolve => { finish = resolve; });
  const duringRender = pdf.downloadTextPdf(options);
  await new Promise(resolve => setImmediate(resolve));
  current = false;
  finish(new Uint8Array([1, 2, 3]));
  assert.equal(await duringRender, false);
  assert.equal(urls, 0, 'logout during generation creates no download URL');
  current = true;
  render = async () => { throw new Error('renderer failed'); };
  await assert.rejects(pdf.downloadTextPdf(options), /renderer failed/);
  assert.equal(clicks, 0);
  render = async args => { assert.equal(args.inputs[0].body, 'Текст'); return new Uint8Array([1, 2, 3]); };
  assert.equal(await pdf.downloadTextPdf(options), true, 'retry after renderer error succeeds');
  assert.equal(clicks, 1);
  assert.equal(removals, 1);
  timers.splice(0).forEach(fn => fn());
  assert.equal(revoked, 1, 'temporary URL released');
} finally {
  globalThis.fetch = saved.fetch; globalThis.document = saved.document; globalThis.window = saved.window;
  URL.createObjectURL = saved.create; URL.revokeObjectURL = saved.revoke;
  delete globalThis.__pdfGenerate;
}

// Real library, real font, real PDFs. The runner additionally verifies page text/layout.
const real = await bundle('src/utils/localPdf.ts', 'pdf-real');
const font = new Uint8Array(readFileSync(join(root, 'public/fonts/pdf/ProjectSans.ttf')));
for (const [name, ru] of [['receipt-ru', true], ['receipt-ba', false]]) {
  const content = receipt.receiptPdfContent(r, ru);
  const bytes = await real.createTextPdf(content.title, content.body + '\nӘә Ғғ Ҡҡ Ңң Өө Ҫҫ Үү Һһ Ҙҙ ₽', font);
  assert.equal(new TextDecoder().decode(bytes.slice(0, 4)), '%PDF');
  writeFileSync(join(out, name + '.pdf'), bytes);
}
const long = Array.from({ length: 180 }, (_, i) => `${i + 1}. Өфө - Стәрлетамаҡ · Әә Ғғ Ҡҡ Ңң Өө Ҫҫ Үү Һһ Ҙҙ`).join('\n');
writeFileSync(join(out, 'multipage.pdf'), await real.createTextPdf('Длинный список', long, font));
console.log(`${checks} passed`);
