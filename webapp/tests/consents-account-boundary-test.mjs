// Actual consent screen; deterministic hooks, account, storage and HTTP boundaries.
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';

const mocks = {
  react: `const h=()=>globalThis.__consentHarness.active;
    const equal=(a,b)=>a&&b&&a.length===b.length&&a.every((v,i)=>Object.is(v,b[i]));
    export function useState(initial){const s=h(),i=s.cursor++;if(!(i in s.slots))s.slots[i]=typeof initial==='function'?initial():initial;
      return[s.slots[i],value=>{if(!s.mounted)return;const prev=s.slots[i];s.slots[i]=typeof value==='function'?value(prev):value;if(!Object.is(prev,s.slots[i]))globalThis.__consentHarness.dirty=true}];}
    export function useRef(initial){const s=h(),i=s.cursor++;return s.slots[i]??={current:initial};}
    export function useMemo(fn,deps){const s=h(),i=s.cursor++;if(!equal(s.deps[i],deps)){s.slots[i]=fn();s.deps[i]=deps;}return s.slots[i];}
    export const useCallback=(fn,deps)=>useMemo(()=>fn,deps);
    export function useEffect(fn,deps){const s=h(),i=s.cursor++;if(!equal(s.deps[i],deps)){s.deps[i]=deps;s.effects.push(()=>{s.cleanups[i]?.();s.cleanups[i]=fn();});}}`,
  'react/jsx-runtime': `export const Fragment='fragment';export const jsx=(type,props,key)=>({type,props,key});export const jsxs=jsx;`,
  'react-router-dom': `export const useNavigate=()=>()=>{};`,
  '../i18n/lang': `export const useLang=()=>({lang:'ru',appText:ru=>ru});`,
  '../auth/AuthProvider': `export const useAuth=()=>globalThis.__consentHarness.auth;`,
  '../api/client': `export const getSessionGeneration=()=>globalThis.__consentHarness.generation;export const getToken=()=>String(globalThis.__consentHarness.auth.user?.id??'');`,
  '../flags': `export const flags={consents:()=>({...globalThis.__consentHarness.local}),setConsent:(kind,value)=>{const h=globalThis.__consentHarness;h.writes.push([h.auth.user?.id,kind,value]);h.local={...h.local,[kind]:value};return {...h.local};}};`,
  '../api/trust': `export const fetchMyConsents=signal=>{const h=globalThis.__consentHarness;const id=h.auth.user?.id;h.fetches.push(id);return h.fetch(id,signal);};
    export const grantConsent=kind=>{const h=globalThis.__consentHarness;h.grants.push([h.auth.user?.id,kind]);return h.grant(h.auth.user?.id,kind);};`,
  '../components/Icons': `export const IconChevron='icon';`,
  '../utils/format': `export const formatWhen=value=>value;`,
};
const bundle = await build({
  stdin: { contents: "export {default as Screen} from './src/screens/ConsentsScreen.tsx';",
    resolveDir: fileURLToPath(new URL('../', import.meta.url)), loader: 'tsx' },
  bundle: true, write: false, platform: 'node', format: 'esm', jsx: 'automatic',
  plugins: [{ name: 'consent-boundaries', setup(b) {
    b.onResolve({ filter: /.*/ }, args => args.path in mocks ? { path: args.path, namespace: 'fixture' } : undefined);
    b.onLoad({ filter: /.*/, namespace: 'fixture' }, args => ({ contents: mocks[args.path], loader: 'js' }));
  } }],
});
const { Screen } = await import('data:text/javascript;base64,' + Buffer.from(bundle.outputFiles[0].text).toString('base64'));
const empty = () => ({ offer: false, privacy: false, geo: false });
const all = node => !node || typeof node !== 'object' ? [] : [node, ...[node.props?.children].flat(Infinity).flatMap(all)];
const offer = h => all(h.tree).find(node => node.type === 'input' && node.props['aria-label'] === 'Оферта (условия сервиса)');
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; };
const granted = { kind: 'offer', granted_at: '2026-09-12T10:00:00Z' };
function fresh() {
  return globalThis.__consentHarness = { fibers: new Map(), active: null,
    dirty: true, auth: { user: { id: 1 }, isAuthed: true, status: 'authed' }, generation: 1,
    local: empty(), writes: [], fetches: [], grants: [], fetch: async () => [], grant: async () => granted };
}
function render(h) {
  h.dirty = false;
  const visited = new Set();
  const effects = [];
  function walk(node, path) {
    if (Array.isArray(node)) return node.map((child, index) => walk(child, path + '/' + index));
    if (!node || typeof node !== 'object') return node;
    if (typeof node.type === 'function') {
      const id = path + ':' + node.type.name + ':' + String(node.key ?? '');
      let fiber = h.fibers.get(id);
      if (!fiber) {
        fiber = { slots: [], deps: [], cleanups: [], effects: [], cursor: 0, mounted: true };
        h.fibers.set(id, fiber);
      }
      visited.add(id); fiber.cursor = 0; fiber.effects = []; h.active = fiber;
      const rendered = node.type(node.props ?? {});
      effects.push(...fiber.effects);
      return walk(rendered, id);
    }
    return { ...node, props: { ...node.props, children: walk(node.props?.children, path + '/children') } };
  }
  h.tree = walk({ type: Screen, props: {} }, 'root');
  for (const [id, fiber] of h.fibers) {
    if (!visited.has(id)) { fiber.mounted = false; fiber.cleanups.forEach(cleanup => cleanup?.()); h.fibers.delete(id); }
  }
  effects.forEach(effect => effect());
}
async function settle(h) {
  // Let promise callbacks and the resulting render/effect updates finish deterministically.
  for (let i = 0; i < 12; i++) { if (h.dirty) render(h); await Promise.resolve(); }
  assert.equal(h.dirty, false, 'Hooks did not settle');
}
function switchToB(h, local = empty()) {
  h.auth = { user: { id: 2 }, isAuthed: true, status: 'authed' }; h.generation++;
  h.local = local; h.dirty = true;
}
let failed = 0, cases = 0;
async function check(name, test) {
  const h = fresh(); cases++;
  try { await test(h); console.log(`PASS ${name}`); }
  catch (error) { failed++; console.error(`FAIL ${name}\n${error.stack}`); }
  finally { for (const fiber of h.fibers.values()) { fiber.mounted = false; fiber.cleanups.forEach(cleanup => cleanup?.()); } }
}

await check('mounted consent screen reloads B and removes A server grant', async h => {
  h.fetch = async id => id === 1 ? [granted] : [];
  await settle(h);
  assert.equal(offer(h).props.disabled, true);
  switchToB(h);
  await settle(h);
  assert.ok(h.fetches.includes(2), 'Second account was not fetched on the same mounted screen');
  assert.equal(offer(h).props.checked, false);
  assert.equal(Boolean(offer(h).props.disabled), false);
});

await check('late A fetch cannot grant consent or write B local flags', async h => {
  const pending = deferred();
  h.fetch = id => id === 1 ? pending.promise : Promise.resolve([]);
  await settle(h);
  switchToB(h);
  await settle(h);
  pending.resolve([granted]);
  await settle(h);
  assert.equal(h.local.offer, false, 'Old server result wrote a consent into B local storage');
  assert.equal(Boolean(offer(h).props.disabled), false);
  assert.equal(offer(h).props.checked, false);
});

await check('late A grant cannot become a recorded server grant for B', async h => {
  const pending = deferred();
  h.grant = () => pending.promise;
  await settle(h);
  offer(h).props.onChange();
  await settle(h);
  assert.deepEqual(h.grants, [[1, 'offer']]);
  switchToB(h);
  await settle(h);
  const writesBefore = h.writes.length;
  pending.resolve(granted);
  await settle(h);
  assert.equal(h.local.offer, false);
  assert.equal(Boolean(offer(h).props.disabled), false, 'Old grant still locks the checkbox for B');
  assert.equal(offer(h).props.checked, false);
  assert.equal(h.writes.length, writesBefore, 'Old completion writes new-account flags');
});

await check('local mirror alone is not a server-recorded consent', async h => {
  h.auth = { user: { id: 2 }, isAuthed: true, status: 'authed' };
  h.local.offer = true;
  await settle(h);
  assert.deepEqual(h.fetches, [2]);
  assert.equal(Boolean(offer(h).props.disabled), false, 'Local mirror must not disable a missing server grant');
  // A locally checked mirror is allowed; it must still be possible to record consent.
  offer(h).props.onChange();
  await settle(h);
  assert.deepEqual(h.grants, [[2, 'offer']]);
});

await check('recording a locally mirrored consent keeps its checkbox and mirror enabled', async h => {
  h.local.offer = true;
  await settle(h);
  assert.equal(Boolean(offer(h).props.disabled), false);
  offer(h).props.onChange();
  await settle(h);
  assert.deepEqual(h.grants, [[1, 'offer']]);
  assert.equal(Boolean(offer(h).props.disabled), true, 'The successful server grant must be recorded');
  assert.equal(offer(h).props.checked, true, 'Recording a grant must not toggle its local checkbox off');
  assert.equal(h.local.offer, true, 'The local mirror must agree with the successful server grant');
});

await check('failed final consent does not report all done and a new explicit attempt can succeed', async h => {
  h.fetch = async () => ['privacy', 'geo'].map(kind => ({ kind, granted_at: granted.granted_at }));
  h.grant = async () => { throw new Error('Local fixture 503'); };
  await settle(h);
  offer(h).props.onChange();
  await settle(h);
  const text = node => {
    if (Array.isArray(node)) return node.map(text).join(' ');
    if (node && typeof node === 'object') return text(node.props?.children);
    return typeof node === 'string' ? node : '';
  };
  assert.equal(h.grants.length, 1, 'Failure must not silently retry without a new user action');
  assert.equal(Boolean(offer(h).props.disabled), false, 'Failed consent must remain actionable');
  assert.ok(!text(h.tree).includes('Спасибо! Все согласия отмечены.'), 'A failed final grant must not report success');
  h.grant = async () => granted;
  offer(h).props.onChange();
  await settle(h);
  assert.equal(h.grants.length, 2);
  assert.equal(Boolean(offer(h).props.disabled), true);
  assert.equal(offer(h).props.checked, true);
  assert.equal(h.local.offer, true);
  assert.ok(text(h.tree).includes('Спасибо! Все согласия отмечены.'));
});

console.log(`Consent account cases: ${cases - failed} passed, ${failed} failed`);
if (failed) process.exitCode = 1;
