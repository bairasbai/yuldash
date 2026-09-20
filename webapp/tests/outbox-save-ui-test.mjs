// Actual TripChat function exported only in an in-memory bundle. No browser/network/IndexedDB.
// Hooks and external dependencies are deterministic fixtures; enqueue settlement is controlled.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const source = await readFile(new URL('../src/screens/ActiveTripScreen.tsx', import.meta.url), 'utf8');
const mocks = {};
// Stub unrelated screen imports while retaining the full, unchanged production source.
for (const match of source.matchAll(/import\s+([\s\S]*?)\s+from\s+"([^"]+)";/g)) {
  const [, clause, path] = match;
  const names = (clause.match(/\{([\s\S]*?)\}/)?.[1] ?? '').split(',')
    .map(s => s.trim()).filter(s => s && !s.startsWith('type ')).map(s => s.split(/\s+as\s+/)[0]);
  mocks[path] = `export default function Stub(){return null;}\n` + names.map(name => `export const ${name}=()=>null;`).join('\n');
}
mocks.react = `const h=()=>globalThis.__outboxUI;
  export function useState(initial){const s=h(),i=s.cursor++;if(!(i in s.slots))s.slots[i]=typeof initial==='function'?initial():initial;
    return[s.slots[i],v=>{s.slots[i]=typeof v==='function'?v(s.slots[i]):v;s.dirty=true;}];}
  export function useRef(initial){const s=h(),i=s.cursor++;return s.slots[i]??={current:initial};}
  export function useCallback(fn){h().cursor++;return fn;}
  export function useEffect(){h().cursor++;}
`;
mocks['react/jsx-runtime'] = `export const Fragment='fragment';export const jsx=(type,props)=>({type,props});export const jsxs=jsx;`;
mocks['../i18n/lang'] = `export const useLang=()=>({appText:ru=>ru});`;
mocks['../api/client'] = `export class ApiError extends Error{constructor(status,message){super(message);this.status=status;}}export const getSessionGeneration=()=>globalThis.__outboxUI.generation;`;
mocks['../api/chat'] = `import {ApiError} from '../api/client';
  export const sendMessageRest=async(id,text,voice,key)=>{globalThis.__outboxUI.sends.push([id,text,key]);throw new ApiError(0,'offline');};
  export const fetchMessages=async()=>[];export const openBookingChat=()=>({send:()=>false,close:()=>{}});
  export const openTripLocation=()=>null;export const editMessage=()=>null;export const deleteMessage=()=>null;`;
mocks['../utils/outbox'] = `export const createOutboxId=()=>'intent-42';export const outboxCount=()=>globalThis.__outboxUI.saved;
  export const enqueue=(...args)=>{const h=globalThis.__outboxUI;h.enqueues.push(args);return h.pending.promise.then(()=>{h.saved++;});};
  export const watchOutbox=()=>()=>{};export const subscribeOutbox=()=>()=>{};`;
const bundle = await build({
  stdin: { contents: source + '\nexport { TripChat as TestChat };', loader: 'tsx',
    resolveDir: fileURLToPath(new URL('../src/screens/', import.meta.url)) },
  bundle: true, write: false, platform: 'node', format: 'esm', jsx: 'automatic',
  plugins: [{ name: 'outbox-ui-fixtures', setup(b) {
    b.onResolve({ filter: /.*/ }, args => args.path in mocks ? { path: args.path, namespace: 'fixture' } : undefined);
    b.onLoad({ filter: /.*/, namespace: 'fixture' }, args => ({ contents: mocks[args.path], loader: 'js' }));
  } }],
});
const { TestChat } = await import('data:text/javascript;base64,' + Buffer.from(bundle.outputFiles[0].text).toString('base64'));
function deferred() { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b; }); return {promise,resolve,reject}; }
const nodes = node => !node || typeof node !== 'object' ? [] : [node, ...[node.props?.children].flat(Infinity).flatMap(nodes)];
const input = h => nodes(h.tree).find(n => n.type === 'input' && n.props['aria-label'] === 'Сообщение');
const send = h => nodes(h.tree).find(n => n.type === 'button' && n.props['aria-label'] === 'Отправить');
const text = node => typeof node === 'string' ? node : [node?.props?.children].flat(Infinity).map(child => child && typeof child === 'object' ? text(child) : typeof child === 'string' ? child : '').join(' ');
function render(h) { h.cursor=0; h.dirty=false; h.tree=TestChat({bookingId:42,myId:1}); }
async function settle(h) { for(let i=0;i<12;i++){if(h.dirty)render(h);await Promise.resolve();}assert.equal(h.dirty,false); }
async function type(h,value) { input(h).props.onChange({target:{value}}); await settle(h); }
async function start(h) { await settle(h); await type(h,'Исходный текст'); h.completion=send(h).props.onClick(); await settle(h); assert.deepEqual(h.enqueues,[[42,'message','Исходный текст','intent-42']]); assert.deepEqual(h.sends,[[42,'Исходный текст','intent-42']]); }
let cases=0,failed=0;
async function check(name,fn){
  const h=globalThis.__outboxUI={slots:[],cursor:0,dirty:true,generation:'A',saved:0,enqueues:[],sends:[],pending:deferred()};cases++;
  try{await fn(h);console.log(`PASS ${name}`);}catch(e){failed++;console.error(`FAIL ${name}\n${e.stack}`);}
}
await check('pending durable save retains chat text and does not announce queued success',async h=>{
  await start(h);assert.equal(input(h).props.value,'Исходный текст');assert.equal(send(h).props.disabled,true);assert.doesNotMatch(text(h.tree),/отправим сами/);
  h.pending.resolve();await h.completion;await settle(h);
});
await check('rejected durable save retains chat text and shows an error without success',async h=>{
  await start(h);h.pending.reject(new Error('IndexedDB write failed'));await h.completion;await settle(h);
  assert.equal(input(h).props.value,'Исходный текст');assert.match(text(h.tree),/Не получилось отправить/);assert.doesNotMatch(text(h.tree),/отправим сами/);assert.equal(send(h).props.disabled,false);
});
await check('completed durable save clears original text and announces queued success',async h=>{
  await start(h);h.pending.resolve();await h.completion;await settle(h);assert.equal(input(h).props.value,'');assert.match(text(h.tree),/отправим сами/);
});
await check('typing while durable save is pending preserves the new draft',async h=>{
  await start(h);await type(h,'Следующее сообщение');h.pending.resolve();await h.completion;await settle(h);assert.equal(input(h).props.value,'Следующее сообщение');assert.match(text(h.tree),/отправим сами/);
});
await check('late durable save after account change does not clear text or announce success',async h=>{
  await start(h);h.generation='B';await type(h,'Текст нового аккаунта');h.pending.resolve();await h.completion;await settle(h);assert.equal(input(h).props.value,'Текст нового аккаунта');assert.doesNotMatch(text(h.tree),/отправим сами/);
});
console.log(`Outbox save UI: ${cases-failed}/${cases} passed`);
if(failed)process.exitCode=1;
