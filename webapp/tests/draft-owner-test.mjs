// Production draft helpers and session owner; hook effects can be deferred like a stale mount.
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

const react = `const h=()=>globalThis.__draftHooks;
  const eq=(a,b)=>a&&b&&a.length===b.length&&a.every((v,i)=>Object.is(v,b[i]));
  export function useState(v){const s=h(),i=s.cursor++;if(!(i in s.slots))s.slots[i]=typeof v==='function'?v():v;return[s.slots[i],n=>{s.slots[i]=typeof n==='function'?n(s.slots[i]):n}];}
  export function useRef(v){const s=h(),i=s.cursor++;return s.slots[i]??={current:v};}
  export function useEffect(fn,deps){const s=h(),i=s.cursor++;if(!eq(s.deps[i],deps)){s.deps[i]=deps;s.effects.push(fn);}}
  export function useMemo(fn,deps){const s=h(),i=s.cursor++;if(!eq(s.deps[i],deps)){s.deps[i]=deps;s.slots[i]=fn();}return s.slots[i];}
  export const useCallback=(fn,deps)=>useMemo(()=>fn,deps);`;
const bundle = await build({
  stdin: { contents: "export * from './src/api/client'; export * from './src/utils/formDraft';",
    resolveDir: fileURLToPath(new URL('../', import.meta.url)), loader: 'ts' },
  bundle: true, write: false, platform: 'node', format: 'esm', define: { 'import.meta.env': '{}' },
  plugins: [{ name: 'draft-hook-boundary', setup(b) {
    b.onResolve({ filter: /^react$/ }, () => ({ path: 'react', namespace: 'fixture' }));
    b.onLoad({ filter: /.*/, namespace: 'fixture' }, () => ({ contents: react, loader: 'js' }));
  } }],
});
let serial = 0, failed = 0;
async function environment() {
  const store = new Map();
  globalThis.localStorage = { get length() { return store.size; }, key: i => [...store.keys()][i] ?? null,
    getItem: k => store.get(k) ?? null, setItem: (k, v) => store.set(k, String(v)), removeItem: k => store.delete(k) };
  globalThis.__draftHooks = { cursor: 0, slots: [], deps: [], effects: [] };
  const source = bundle.outputFiles[0].text + `\n// draft owner case ${serial++}`;
  const api = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
  return { api, store };
}
function render(fn) { const h = globalThis.__draftHooks; h.cursor = 0; h.effects = []; return fn(); }
function flushEffects() { const h = globalThis.__draftHooks; const effects = h.effects; h.effects = []; effects.forEach(fn => fn()); }
async function check(name, test) {
  try { await test(await environment()); console.log(`PASS ${name}`); }
  catch (error) { failed++; console.error(`FAIL ${name}\n${error.stack}`); }
}
const key = 'taxi-application';
const draftA = { permit: 'synthetic-A' }, draftB = { permit: 'synthetic-B' };

await check('direct account change cannot read a previous accounts draft', ({ api }) => {
  api.setSession('A', 'refresh-A');
  api.writeDraft(key, draftA);
  assert.deepEqual(api.readDraft(key), draftA);
  api.setSession('B', 'refresh-B');
  assert.equal(api.readDraft(key), null);
});

await check('ownerless legacy draft is not assigned to the current account', ({ api, store }) => {
  api.setSession('B', 'refresh-B');
  store.set('yuldash.draft.' + key, JSON.stringify({ at: Date.now(), data: draftA }));
  assert.equal(api.readDraft(key), null);
});

await check('ordinary refresh preserves the same sessions draft', ({ api }) => {
  api.setSession('A', 'refresh-A');
  api.writeDraft(key, draftA);
  assert.equal(api.rotateSession('A-new', 'refresh-A-new', api.getSessionGeneration()), true);
  assert.deepEqual(api.readDraft(key), draftA);
});

await check('old useDraftSync effect cannot overwrite B draft', ({ api }) => {
  api.setSession('A', 'refresh-A');
  render(() => api.useDraftSync(key, { permit: '' }, () => {}));
  flushEffects();
  render(() => api.useDraftSync(key, draftA, () => {}));
  api.setSession('B', 'refresh-B');
  api.writeDraft(key, draftB);
  flushEffects();
  assert.deepEqual(api.readDraft(key), draftB);
});

await check('old useFormDraft save effect cannot overwrite B draft', ({ api }) => {
  api.setSession('A', 'refresh-A');
  render(() => api.useFormDraft(key, draftA));
  api.setSession('B', 'refresh-B');
  api.writeDraft(key, draftB);
  flushEffects();
  assert.deepEqual(api.readDraft(key), draftB);
});

await check('old form forget callback cannot delete B draft', ({ api }) => {
  api.setSession('A', 'refresh-A');
  const [, , forgetA] = render(() => api.useFormDraft(key, draftA));
  flushEffects();
  api.setSession('B', 'refresh-B');
  api.writeDraft(key, draftB);
  forgetA();
  assert.deepEqual(api.readDraft(key), draftB);
});

console.log(`Draft owner cases: ${serial - failed} passed, ${failed} failed`);
if (failed) process.exitCode = 1;
