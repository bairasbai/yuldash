// Production voice button / chat parent / HTTP adapters; controlled hooks, microphone and fetch.
// Commit/lost ACK below is a fetch fixture, not a claim about SQL or a real browser microphone.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const ROOT = fileURLToPath(new URL('../', import.meta.url));
const h = globalThis.__voiceBoundary = {};
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b; }); return { promise, resolve, reject }; };
const hooks = `const h=globalThis.__voiceBoundary;
const same=(a,b)=>a&&b&&a.length===b.length&&a.every((v,i)=>Object.is(v,b[i]));
export function useState(initial){const x=h.current,i=x.cursor++;if(!(i in x.slots))x.slots[i]=typeof initial==='function'?initial():initial;return[x.slots[i],value=>{if(!x.live)return;x.slots[i]=typeof value==='function'?value(x.slots[i]):value;h.dirty=true;}];}
export function useRef(initial){const x=h.current,i=x.cursor++;return x.slots[i]??={current:initial};}
export function useCallback(fn,deps){const x=h.current,i=x.cursor++;if(!same(x.slots[i]?.deps,deps))x.slots[i]={fn,deps};return x.slots[i].fn;}
export function useEffect(fn,deps){const x=h.current,i=x.cursor++,old=x.slots[i];if(!same(old?.deps,deps)){x.slots[i]={deps};h.effects.push(()=>{x.cleanup[i]?.();x.cleanup[i]=fn();});}}
export const useLayoutEffect=useEffect;export const useMemo=(fn)=>fn();`;
const jsx = `export const Fragment='fragment';export const jsx=(type,props,key)=>({type,props,key}),jsxs=jsx;`;
const lang = `export const useLang=()=>({appText:(ru,ba)=>globalThis.__voiceBoundary.lang==='ba'?ba:ru});`;
const iconNames = ['IconMic', 'IconCheck', 'IconArrow', 'IconCar', 'IconChevron', 'IconWarn'];
const iconModule = iconNames.map(name => `export function ${name}(){return null;}`).join('\n');
async function bundle(contents, mocks, resolveDir = ROOT + 'src') {
  const result = await build({ stdin: { contents, resolveDir, loader: 'tsx' }, bundle: true,
    write: false, format: 'esm', platform: 'node', jsx: 'automatic', define: { 'import.meta.env': JSON.stringify({ VITE_API_BASE: 'https://voice.synthetic.invalid' }) },
    plugins: [{ name: 'voice-fixtures', setup(b) {
      b.onResolve({ filter: /.*/ }, a => Object.hasOwn(mocks, a.path) ? { path: a.path, namespace: 'fixture' } : undefined);
      b.onLoad({ filter: /.*/, namespace: 'fixture' }, a => ({ contents: mocks[a.path], loader: 'js' }));
    } }] });
  return import('data:text/javascript;base64,' + Buffer.from(result.outputFiles[0].text).toString('base64'));
}
const basic = { react: hooks, 'react/jsx-runtime': jsx, '../i18n/lang': lang, './Icons': iconModule };
const buttonModule = await bundle(`export{ChatVoiceButton}from'./components/ChatVoice';export{getSessionGeneration,apiUpload}from'./api/client';export{uploadVoice}from'./api/voice';`, basic);
const screenSource = await readFile(new URL('../src/screens/TripChatScreen.tsx', import.meta.url), 'utf8');
const parentMocks = { ...basic, '../components/Icons': iconModule };
for (const [, clause, path] of screenSource.matchAll(/import\s+([\s\S]*?)\s+from\s+"([^"]+)";/g)) {
  if (path in parentMocks || ['../api/client', '../api/chat'].includes(path)) continue;
  const names = (clause.match(/\{([\s\S]*?)\}/)?.[1] ?? '').split(',').map(s => s.trim()).filter(s => s && !s.startsWith('type ')).map(s => s.split(/\s+as\s+/)[0]);
  parentMocks[path] = 'export default function Stub(){return null;}\n' + names.map(name => `export function ${name}(){return null;}`).join('\n');
}
parentMocks['../utils/outbox'] = `const h=globalThis.__voiceBoundary;export const createOutboxId=()=> 'voice-intent-'+(++h.keys);
export const outboxCount=()=>0,watchOutbox=()=>()=>{},subscribeOutbox=()=>()=>{};
export const acknowledgeMessage=async()=>{},enqueue=async()=>{h.voiceQueueAttempts++;},isPermanentRejection=()=>false;`;
parentMocks['../utils/tripChat.js'] = `export const NO_MARK=0,getSeen=()=>0,setSeen=()=>0,isNearBottom=()=>true,newestId=()=>0,unreadCount=()=>0,viewportBox=()=>({});`;
parentMocks['../components/ChatVoice'] = `export function ChatVoiceButton(){return null;}export function VoiceBubble(){return null;}`;
const parentModule = await bundle(screenSource + '\nexport {TripChat as TestChat};export{setRefreshHandler,rotateSession}from"../api/client";', parentMocks, ROOT + 'src/screens');

class Store {
  map = new Map(); get length() { return this.map.size; } key(index) { return [...this.map.keys()][index] ?? null; }
  getItem(key) { return this.map.get(key) ?? null; } setItem(key, value) { this.map.set(key, String(value)); } removeItem(key) { this.map.delete(key); } clear() { this.map.clear(); }
}
function unmount() {
  if (!h.instance) return;
  h.instance.live = false;
  for (const cleanup of h.instance.cleanup) cleanup?.();
  h.instance = null;
}
function reset({ lang = 'ru' } = {}) {
  unmount();
  Object.assign(h, { instance: null, current: null, effects: [], dirty: true, lang, bookingId: 42, myId: 7,
    keys: 0, voiceQueueAttempts: 0, requests: [], posts: [], rows: [], receipts: new Map(), streams: [], recorders: [], mics: [], sent: [], problems: [], timers: new Map(), timerId: 0, dropAck: false, failHistory: false, historyRows: null,
    onSend: async url => { h.sent.push(url); }, postHold: null, uploadHold: null, emitOnTrackStop: false, callbacksAtTrackStop: 0, reject401: 0, recorderMime: 'audio/webm' });
  parentModule.setRefreshHandler(null);
  globalThis.localStorage = new Store(); globalThis.sessionStorage = new Store();
  localStorage.setItem('yuldash.session', 'A'); localStorage.setItem('yuldash.token', 'synthetic-A');
  globalThis.location = { href: 'https://voice.synthetic.invalid/trip/42/chat' };
  globalThis.document = { body: { style: { overflow: '' } }, visibilityState: 'visible', addEventListener() {}, removeEventListener() {} };
  globalThis.window = { setInterval: fn => { const id = ++h.timerId; h.timers.set(id, fn); return id; }, clearInterval: id => h.timers.delete(id),
    setTimeout: fn => { const id = ++h.timerId; h.timers.set(id, fn); return id; }, clearTimeout: id => h.timers.delete(id), addEventListener() {}, removeEventListener() {} };
  globalThis.WebSocket = class { static OPEN = 1; readyState = 0; send() {} close() { this.readyState = 3; } };
  class Recorder {
    state = 'inactive'; mimeType = h.recorderMime; ondataavailable = null; onstop = null;
    constructor(stream) { this.stream = stream; h.recorders.push(this); }
    start() { this.state = 'recording'; }
    stop() { if (this.state === 'inactive') throw Error('Recorder already stopped'); this.state = 'inactive'; }
  }
  globalThis.MediaRecorder = window.MediaRecorder = Recorder;
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { mediaDevices: { getUserMedia() { const request = deferred(); h.mics.push(request); return request.promise; } } } });
  globalThis.fetch = async (url, init = {}) => {
    const path = new URL(url).pathname, method = init.method ?? 'GET', headers = new Headers(init.headers);
    h.requests.push({ path, method, headers, body: init.body });
    if (path === '/voice') return h.uploadHold ? h.uploadHold.promise : reply({ url: '/voice/synthetic.webm' });
    if (/\/bookings\/\d+\/messages$/.test(path) && method === 'GET') {
      if (h.failHistory) throw TypeError('Controlled history disconnect');
      return reply(h.historyRows ?? h.rows);
    }
    if (/\/bookings\/\d+\/messages$/.test(path) && method === 'POST') {
      const body = JSON.parse(init.body), key = headers.get('Idempotency-Key');
      h.posts.push({ path, body, key, owner: headers.get('Authorization') });
      if (h.reject401 > 0) { h.reject401--; return reply({ detail: 'Controlled expired credential' }, 401); }
      if (h.postHold) return h.postHold.promise;
      let row = key ? h.receipts.get(key) : null;
      if (!row) { row = { id: h.rows.length + 10, sender_id: h.myId, text: body.text, voice_url: body.voice_url }; h.rows.push(row); if (key) h.receipts.set(key, row); }
      if (h.dropAck) { h.dropAck = false; throw TypeError('Controlled committed voice acknowledgement lost'); }
      return reply(row);
    }
    throw Error('Unexpected controlled fetch ' + method + ' ' + path);
  };
}
const reply = (body, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
function changeOwner(owner = 'B') { localStorage.setItem('yuldash.session', owner); localStorage.setItem('yuldash.token', 'synthetic-' + owner); }
function render(kind = h.kind ?? 'button') {
  h.kind = kind;
  if (!h.instance) h.instance = { slots: [], cleanup: [], cursor: 0, live: true };
  h.instance.cursor = 0; h.current = h.instance; h.effects = []; h.dirty = false;
  h.tree = kind === 'button' ? buttonModule.ChatVoiceButton({ bookingId: h.bookingId, onSend: h.onSend, onProblem: message => h.problems.push(message) }) : parentModule.TestChat({ bookingId: h.bookingId, myId: h.myId });
  for (const effect of h.effects.splice(0)) effect();
  return h.tree;
}
async function settle() { for (let i = 0; i < 35; i++) { await Promise.resolve(); if (h.dirty && h.instance?.live) render(); } }
const nodes = tree => !tree || typeof tree !== 'object' ? [] : [tree, ...[tree.props?.children].flat(Infinity).flatMap(nodes)];
const text = tree => typeof tree === 'string' ? tree : [tree?.props?.children].flat(Infinity).map(child => typeof child === 'object' ? text(child) : typeof child === 'string' ? child : '').join(' ');
const voiceParent = () => nodes(h.tree).find(node => node.type?.name === 'ChatVoiceButton');
const retry = () => nodes(h.tree).find(node => node.type === 'button' && /Повторить отправку|Ҡабат ебәреү/.test(text(node)));
function stream() {
  const value = { stopped: 0, getTracks: () => [{ stop() { value.stopped++; if (h.emitOnTrackStop) for (const mr of h.recorders) if (mr.stream === value && mr.onstop) { h.callbacksAtTrackStop++; void mr.onstop(); } } }] };
  h.streams.push(value); return value;
}
async function record() {
  render('button').props.onClick();
  const value = stream(); h.mics.at(-1).resolve(value); await settle();
  const mr = h.recorders.at(-1); mr.ondataavailable({ data: new Blob(['x'.repeat(1800)], { type: 'audio/webm' }) });
  return { mr, stream: value };
}
let passed = 0, failed = 0;
async function check(name, run, options) {
  reset(options);
  try { await run(); passed++; console.log('✓ ' + name); }
  catch (error) { failed++; console.error('FAIL ' + name + ': ' + error.stack); }
  finally { unmount(); }
}

await check('held microphone permission cannot start a recorder or upload under replacement owner', async () => {
  render().props.onClick(); changeOwner(); const value = stream(); h.mics[0].resolve(value); await settle();
  assert.equal(h.recorders.length, 0); assert.equal(value.stopped, 1); assert.equal(h.requests.length, 0);
});
await check('held microphone permission after unmount stops the newly returned stream', async () => {
  render().props.onClick(); unmount(); const value = stream(); h.mics[0].resolve(value); await settle();
  assert.equal(h.recorders.length, 0); assert.equal(value.stopped, 1); assert.equal(h.requests.length, 0);
});
await check('synchronous double click while permission is pending asks for microphone once', async () => {
  const click = render().props.onClick; click(); click(); assert.equal(h.mics.length, 1);
});
await check('retained recorder callback cannot upload A recording with B credentials', async () => {
  const { mr } = await record(); const callback = mr.onstop; changeOwner(); await callback(); await settle();
  assert.equal(h.requests.filter(request => request.path === '/voice').length, 0); assert.equal(h.sent.length, 0);
});
await check('unmount detaches recorder callbacks before stopping tracks', async () => {
  const { stream: value } = await record(); h.emitOnTrackStop = true; unmount(); await settle();
  assert.equal(value.stopped, 1); assert.equal(h.callbacksAtTrackStop, 0); assert.equal(h.requests.length, 0);
});
await check('upload completion after owner change cannot send or report a problem into another owner', async () => {
  const { mr } = await record(); h.uploadHold = deferred(); const pending = mr.onstop(); await settle();
  changeOwner(); h.uploadHold.resolve(reply({ url: '/voice/private-A.webm' })); await pending; await settle();
  assert.equal(h.sent.length, 0); assert.equal(h.problems.filter(Boolean).length, 0);
});
await check('button remains busy until parent message POST has finished', async () => {
  const completion = deferred(); h.onSend = url => { h.sent.push(url); return completion.promise; };
  const { mr } = await record(); const pending = mr.onstop(); await settle();
  assert.equal(render().props.disabled, true); assert.deepEqual(h.sent, ['/voice/synthetic.webm']);
  completion.resolve(); await pending; await settle(); assert.equal(render().props.disabled, false);
});
await check('held microphone callback cannot adopt another booking target', async () => {
  render().props.onClick(); h.bookingId = 43; render(); const value = stream(); h.mics[0].resolve(value); await settle();
  assert.equal(h.recorders.length, 0); assert.equal(value.stopped, 1); assert.equal(h.requests.length, 0);
});
await check('retained start button from another booking does not open a microphone permission request', async () => {
  const oldClick = render().props.onClick; h.bookingId = 43; render(); oldClick();
  assert.equal(h.mics.length, 0);
});
await check('ordinary owned recording uploads once and awaits its owned parent', async () => {
  const { mr, stream: value } = await record(); await mr.onstop(); await settle();
  assert.deepEqual(h.sent, ['/voice/synthetic.webm']); assert.equal(value.stopped, 1);
  assert.equal(h.requests.length, 1); assert.equal(h.requests[0].headers.get('Authorization'), 'Bearer synthetic-A');
  assert.equal(render().props.disabled, false);
});
for (const [mime, filename] of [['audio/webm;codecs=opus', 'voice.webm'], ['audio/mp4;codecs=mp4a.40.2', 'voice.m4a'],
  ['audio/ogg;codecs=opus', 'voice.ogg'], ['audio/wav', 'voice.wav'], ['audio/mpeg', 'voice.mp3']]) {
  await check(`actual recorded Blob MIME chooses matching multipart filename (${mime})`, async () => {
    h.recorderMime = mime; const { mr } = await record(); await mr.onstop(); await settle();
    assert.equal(h.requests.length, 1); const file = h.requests[0].body.get('file');
    assert.equal(file.name, filename); assert.equal(file.type, mime);
  });
}
await check('voice upload adapter rejects an explicitly stale owner before any HTTP', async () => {
  changeOwner();
  await assert.rejects(buttonModule.uploadVoice(new Blob(['x'.repeat(1800)]), 'voice.webm', undefined, 'A'));
  assert.equal(h.requests.length, 0);
});
await check('old upload finally cannot reset a new booking recording', async () => {
  const { mr } = await record(); h.uploadHold = deferred(); const oldUpload = h.uploadHold;
  const old = mr.onstop(); await settle(); h.bookingId = 43; render(); await settle();
  h.uploadHold = null; await record();
  oldUpload.resolve(reply({ url: '/voice/old-target.webm' })); await old; await settle();
  assert.equal(render().props['aria-label'], 'Остановить и отправить'); assert.equal(h.sent.length, 0);
});

await check('ordinary current owner voice POST is confirmed once without history fallback or offline queue', async () => {
  render('parent'); await settle(); const initialGets = h.requests.filter(request => request.method === 'GET').length;
  await voiceParent().props.onSend('/voice/current-owner.webm'); await settle();
  assert.equal(h.posts.length, 1); assert.ok(h.posts[0].key); assert.equal(h.rows.length, 1);
  assert.equal(h.requests.filter(request => request.method === 'GET').length, initialGets);
  assert.equal(voiceParent().props.disabled, false); assert.equal(h.voiceQueueAttempts, 0);
});
await check('actual chat parent uses an owned stable request key and reconciles committed lost ACK from own history', async () => {
  render('parent'); await settle(); h.dropAck = true;
  await voiceParent().props.onSend('/voice/committed.webm'); await settle();
  assert.equal(h.posts.length, 1); assert.ok(h.posts[0].key, 'Actual PWA voice POST must include an intent key');
  assert.equal(h.posts[0].owner, 'Bearer synthetic-A'); assert.equal(h.rows.length, 1);
  assert.ok(h.requests.filter(request => request.method === 'GET').length >= 2, 'Ambiguous ACK must fetch authoritative history');
  assert.doesNotMatch(text(h.tree), /Голосовое не ушло|Тауыш китмәне/); assert.equal(retry(), undefined);
});
for (const lang of ['ru', 'ba']) await check(`unknown ACK and failed GET retain manual same-key same-asset retry (${lang})`, async () => {
  render('parent'); await settle(); h.failHistory = true; h.dropAck = true;
  await voiceParent().props.onSend('/voice/manual-retry.webm'); await settle();
  assert.equal(h.posts.length, 1); const first = h.posts[0]; assert.ok(first.key);
  assert.doesNotMatch(text(h.tree), /Голосовое не ушло|Тауыш китмәне/); assert.ok(retry(), 'An unknown delivery needs an explicit retry action');
  const click = retry().props.onClick; click(); click(); await settle();
  assert.equal(h.posts.length, 2); assert.equal(h.posts[1].key, first.key); assert.equal(h.posts[1].body.voice_url, first.body.voice_url);
  assert.equal(h.rows.length, 1); assert.equal(retry(), undefined); assert.equal(h.voiceQueueAttempts, 0);
}, { lang });
await check('foreign sender with exact asset URL cannot acknowledge a lost outgoing voice', async () => {
  render('parent'); await settle(); h.dropAck = true;
  h.historyRows = [{ id: 90, sender_id: 999, text: '', voice_url: '/voice/own.webm' }];
  await voiceParent().props.onSend('/voice/own.webm'); await settle(); assert.ok(retry());
});
await check('own sender with another asset URL cannot acknowledge a lost outgoing voice', async () => {
  render('parent'); await settle(); h.dropAck = true;
  h.historyRows = [{ id: 90, sender_id: 7, text: '', voice_url: '/voice/another.webm' }];
  await voiceParent().props.onSend('/voice/own.webm'); await settle(); assert.ok(retry());
});
await check('unknown voice disables a new recording and cannot be overwritten by another asset', async () => {
  render('parent'); await settle(); h.dropAck = true; h.failHistory = true;
  const send = voiceParent().props.onSend; await send('/voice/pending.webm'); await settle();
  const first = h.posts[0]; assert.equal(voiceParent().props.disabled, true);
  await send('/voice/replacement.webm'); await settle(); assert.equal(h.posts.length, 1);
  retry().props.onClick(); await settle();
  assert.equal(h.posts[1].key, first.key); assert.equal(h.posts[1].body.voice_url, '/voice/pending.webm');
});
await check('real HTTP401 refresh retains the original voice key, target, asset and owner generation', async () => {
  render('parent'); await settle(); h.reject401 = 1; let refreshes = 0;
  localStorage.setItem('yuldash.refresh', 'synthetic-refresh-A');
  parentModule.setRefreshHandler(async () => {
    refreshes++; assert.equal(await parentModule.rotateSession('synthetic-A-renewed', 'synthetic-refresh-renewed', 'A'), true); return true;
  });
  await voiceParent().props.onSend('/voice/refresh.webm'); await settle();
  assert.equal(refreshes, 1); assert.equal(h.posts.length, 2); assert.equal(h.rows.length, 1);
  assert.ok(h.posts[0].key); assert.equal(h.posts[0].key, h.posts[1].key);
  assert.deepEqual(h.posts[0].body, h.posts[1].body); assert.equal(h.posts[0].path, h.posts[1].path);
  assert.equal(h.posts[1].owner, 'Bearer synthetic-A-renewed'); assert.equal(localStorage.getItem('yuldash.session'), 'A');
});
await check('retained parent callback after account change sends no voice under B', async () => {
  render('parent'); await settle(); const callback = voiceParent().props.onSend; changeOwner();
  await callback('/voice/private-A.webm'); await settle(); assert.equal(h.posts.length, 0);
});
await check('retained parent callback after unmount sends no message', async () => {
  render('parent'); await settle(); const callback = voiceParent().props.onSend; unmount();
  await callback('/voice/private-A.webm'); await settle(); assert.equal(h.posts.length, 0);
});
await check('retained parent callback after booking target change cannot send to the old target', async () => {
  render('parent'); await settle(); const callback = voiceParent().props.onSend; h.bookingId = 43; render('parent'); await settle();
  await callback('/voice/private-old-target.webm'); await settle(); assert.equal(h.posts.length, 0);
});
await check('old POST finally cannot unlock a different booking voice still waiting for its response', async () => {
  render('parent'); await settle(); const oldHold = deferred(); h.postHold = oldHold;
  const old = voiceParent().props.onSend('/voice/old-pending.webm'); await settle();
  h.bookingId = 43; render('parent'); await settle(); const newHold = deferred(); h.postHold = newHold;
  const next = voiceParent().props.onSend('/voice/new-pending.webm'); await settle();
  assert.equal(h.posts.length, 2); oldHold.resolve(reply({ id: 80, sender_id: 7, text: '', voice_url: '/voice/old-pending.webm' }));
  await old; await settle();
  await voiceParent().props.onSend('/voice/new-pending.webm'); await settle(); assert.equal(h.posts.length, 2);
  newHold.resolve(reply({ id: 81, sender_id: 7, text: '', voice_url: '/voice/new-pending.webm' }));
  await next; await settle(); assert.equal(voiceParent().props.disabled, false);
});
await check('navigation discards only the in-screen retry intent and never automatically sends it on remount', async () => {
  render('parent'); await settle(); h.dropAck = true; h.failHistory = true;
  await voiceParent().props.onSend('/voice/not-restored.webm'); await settle(); assert.ok(retry());
  unmount(); h.failHistory = false; render('parent'); await settle();
  assert.equal(h.posts.length, 1); assert.equal(retry(), undefined); assert.equal(voiceParent().props.disabled, false);
});
console.log(`Voice network boundary: ${passed} passed, ${failed} failed`);
if (failed) process.exitCode = 1;
