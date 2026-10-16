// Actual production components; only React hooks, leaf widgets and network are controlled.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { build, transform } from 'esbuild';

const h = globalThis.__rideshareLocal = { components: {} };
const previousLocalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
const passValues = new Map();
Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: {
  getItem: key => passValues.get(key) ?? null,
  setItem: (key, value) => { if (h.failPassWrites) throw Error('storage unavailable'); passValues.set(key, value); },
  removeItem: key => passValues.delete(key),
} });
const passModule = await build({ entryPoints: [new URL('../src/utils/tripPass.ts', import.meta.url).pathname.replace(/^\/(\w:)/, '$1')], bundle: true, write: false, format: 'esm', platform: 'node' });
h.passStorage = await import('data:text/javascript;base64,' + Buffer.from(passModule.outputFiles[0].text).toString('base64'));
const ownedPassModule = await build({ entryPoints: [new URL('../src/utils/offlineTripPass.ts', import.meta.url).pathname.replace(/^\/(\w:)/, '$1')], bundle: true, write: false, format: 'esm', platform: 'node', plugins: [{ name: 'session-boundary', setup(b) {
  b.onResolve({ filter: /api\/client$/ }, () => ({ path: 'client', namespace: 'session' }));
  b.onLoad({ filter: /.*/, namespace: 'session' }, () => ({ contents: `export const getToken=()=>'fixture',getSessionGeneration=()=>globalThis.__rideshareLocal.generation;` }));
} }] });
h.ownedPassStorage = await import('data:text/javascript;base64,' + Buffer.from(ownedPassModule.outputFiles[0].text).toString('base64'));
h.detailsSync = await import('../src/utils/tripDetailsSync.js');
class ApiError extends Error { constructor(status, message = '') { super(message); this.status = status; } }
h.ApiError = ApiError;
const definitions = {
  useState: `v=>{const i=h.current.i++;const c=h.current;if(!(i in c.values))c.values[i]=typeof v==='function'?v():v;return[c.values[i],x=>{h.stateCalls.push({component:c.type.name,index:i,mounted:c.live});if(!c.live)return;c.values[i]=typeof x==='function'?x(c.values[i]):x;h.dirty=true}];}`,
  useRef: `v=>{const c=h.current,i=c.i++;return c.values[i]??={current:v}}`,
  useEffect: `(fn,deps)=>{const c=h.current,i=c.i++,old=c.values[i];if(!old||!deps||deps.some((v,j)=>!Object.is(v,old.deps?.[j]))){c.values[i]={deps,cleanup:old?.cleanup};h.effects.push(()=>{old?.cleanup?.();c.values[i].cleanup=fn()})}}`,
  useCallback: `(fn,deps)=>{const c=h.current,i=c.i++,old=c.values[i];if(!old||deps.some((v,j)=>!Object.is(v,old.deps[j])))c.values[i]={fn,deps};return c.values[i].fn}`,
  useLang: `()=>({lang:h.lang,appText:(ru,ba)=>{if(!ba)throw Error('missing Bashkir: '+ru);return h.lang==='ba'?ba:ru}})`,
  useParams: `()=>({id:String(h.bookingId)})`, useNavigate: `()=>p=>h.navigation.push(p)`, useAuth: `()=>({user:{id:h.userId}})`,
  getSessionGeneration: `()=>h.generation`, ApiError: `h.ApiError`,
  fetchBookingDetails: `async id=>{h.requests.push('details');if(h.detailsError)throw new h.ApiError(0);return {...(h.detailsById.get(id)??h.details)}}`,
  fetchTripState: `async id=>{h.requests.push('state');if(h.tripError)throw new h.ApiError(0);const held=h.tripHolds.shift();if(held){h.tripHeldRequests.push(id);return held.promise}return {...(h.tripById.get(id)??h.trip)}}`,
  fetchBoardingCode: `id=>{h.requests.push('code');h.codeRequests.push(id);return h.codeHolds.get(id)?.promise??Promise.resolve({code:'4931'})}`,
  fetchBookingShares: `async()=>{h.requests.push('shares');if(h.sharesError)throw new h.ApiError(h.sharesError);return h.shares}`,
  fetchTrustedContacts: `async()=>h.contacts`, shareBooking: `async()=>{h.requests.push('create-share');return {}}`,
  setTripStatus: `async(id,status)=>{h.sent.push([id,status]);if(h.sendError!==null)throw new h.ApiError(h.sendError);if(status==='done')h.trip.status='done';else if(h.allowOnboard)h.trip.status='onboard';h.details.status=h.trip.status;return h.shares??[]}`,
  enqueue: `async(...v)=>h.queued.push(v)`,
  fetchReceipt: `async()=>({...h.receipt})`, fetchBookingTip: `async()=>{h.requests.push('tip');return {already_thanked:false,money:{sbp:'+79990000000',name:'Test'}}}`,
  thankBooking: `async id=>h.thanks.push(id)`,
  loadTripPass: `id=>h.passStorage.loadTripPass(id)`, saveVerifiedTripPass: `(p,owner,generation)=>{const snapshot=structuredClone(p);h.passWrites.push(snapshot);h.ownedPassStorage.saveVerifiedTripPass(snapshot,owner,generation);h.passes.set(p.bookingId,{...snapshot,savedAt:Date.now()})}`, dropTripPass: `id=>{h.passStorage.dropTripPass(id);h.passes.delete(id)}`,
  clearTripPassBoardingCode: `id=>h.passStorage.clearTripPassBoardingCode(id)`,
  tripPassStage: `(...args)=>h.passStorage.tripPassStage(...args)`,
  loadOfflineTripPass: `id=>h.ownedPassStorage.loadOfflineTripPass(id)`,
  isTerminalBookingStatus: `s=>s==='done'||s==='cancelled'`,
  shouldApplyBookingDetails: `(...args)=>h.detailsSync.shouldApplyBookingDetails(...args)`, shouldRefreshBookingDetails: `(...args)=>h.detailsSync.shouldRefreshBookingDetails(...args)`,
  reconcilePaymentDraft: `(draft)=>draft`, useRouteWeather: `()=>null`,
  useVisibleInterval: `(ms,fn)=>{h.interval=fn}`, openTripLocation: `()=>({close(){}})`,
  winterCheckDelay: `()=>Infinity`, wasWinterCheckAsked: `()=>false`, winterCheckNeedsAnswer: `()=>false`, serverMs: `()=>NaN`,
  formatWhen: `()=>''`, priceLabel: `n=>String(n)`, payMethodLabel: `()=>''`,
  SubHeader: `'SubHeader'`, Link: `'a'`, PayTripCard: `'PayTripCard'`, SbpPay: `'SbpPay'`,
};
async function compile(path, exported = 'default') {
  const source = readFileSync(new URL('../src/' + path, import.meta.url), 'utf8');
  const names = [];
  const body = source.replace(/import\s+([\s\S]*?)\s+from\s+"[^"]+";/g, (_, bindings) => {
    const first = bindings.split('{')[0].trim().replace(/,$/, '').trim();
    if (first && !first.startsWith('type ')) names.push(first);
    const named = bindings.match(/\{([\s\S]*)\}/)?.[1];
    for (const entry of named?.split(',') ?? []) { const n = entry.trim(); if (n && !n.startsWith('type ')) names.push(n); }
    return '';
  });
  const prelude = `const h=globalThis.__rideshareLocal;const __jsx=(type,props,...children)=>({type,props:{...props,children}}),__fragment='fragment';\n` + names.map(n => `const ${n}=${definitions[n] ?? (['ShareTripCard', 'StatusPill', 'OfflineTripPass'].includes(n) ? `(...args)=>h.components.${n}(...args)` : '()=>null')};`).join('\n');
  const result = await transform(prelude + body, { loader: 'tsx', format: 'esm', jsx: 'transform', jsxFactory: '__jsx', jsxFragment: '__fragment' });
  return (await import('data:text/javascript;base64,' + Buffer.from(result.code).toString('base64')))[exported];
}
h.components.ShareTripCard = await compile('components/ShareTripCard.tsx');
h.components.StatusPill = await compile('components/StatusPill.tsx', 'StatusPill');
h.components.OfflineTripPass = await compile('components/OfflineTripPass.tsx');
const Active = await compile('screens/ActiveTripScreen.tsx');
const Receipt = await compile('screens/TripReceiptScreen.tsx');
const all = n => n && typeof n === 'object' ? [n, ...[n.props?.children].flat(Infinity).flatMap(all)] : [];
const text = n => n == null || n === false ? '' : typeof n !== 'object' ? String(n) : [n.props?.children].flat(Infinity).map(text).join('');
const button = label => all(h.tree).find(n => n.type === 'button' && text(n) === label);
function dispose(instance) {
  if (!instance.live) return;
  instance.live = false;
  for (const value of instance.values) value?.cleanup?.();
}
function reset({ role = 'passenger', status = 'confirmed', lang = 'ru', shares = [], sharesError = 0 } = {}) {
  for (const instance of h.instances?.values() ?? []) dispose(instance);
  passValues.clear();
  Object.assign(h, { userId: 7, bookingId: 42, detailsError: false, tripError: false, failPassWrites: false, tripHolds: [], tripHeldRequests: [], detailsById: new Map(), tripById: new Map(), codeHolds: new Map(), codeRequests: [], passes: new Map(), passWrites: [], stateCalls: [], instances: new Map(), effects: [], dirty: false, lang, generation: 'A', requests: [], sent: [], queued: [], thanks: [], navigation: [], shares, sharesError, contacts: [], allowOnboard: true, sendError: null,
    details: { booking_id: 42, role, status, from_city: 'Уфа', to_city: 'Сибай', depart_at: '2026-10-15T10:00:00Z', contact_unlocked: true, driver_name: 'QA Driver', driver_car: 'QA Car', driver_plate: 'QA001', driver_phone: '+79990000000', pickup: 'QA Pickup', price: 300, pay_method: 'cash', seats: 1 },
    trip: { role, status, driver_phase: 'arriving', arrival_verified: true },
    receipt: { role, booking_id: 42, from_city: 'Уфа', to_city: 'Сибай', amount: 300, paid: false, pay_method: 'cash' },
  });
}
function render(Component, props = {}) {
  h.effects = []; h.dirty = false;
  const seen = new Set();
  function visit(n, path) {
    if (Array.isArray(n)) return n.map((v, i) => visit(v, path + '.' + i));
    if (!n || typeof n !== 'object') return n;
    if (typeof n.type === 'function') {
      seen.add(path); let c = h.instances.get(path);
      if (!c || c.type !== n.type) { if (c) dispose(c); c = { type: n.type, values: [], i: 0, live: true }; h.instances.set(path, c); }
      h.current = c; c.i = 0;
      return visit(n.type(n.props), path + '.child');
    }
    return { ...n, props: { ...n.props, children: visit(n.props?.children, path + '.children') } };
  }
  h.tree = visit({ type: Component, props }, 'root');
  for (const [path, c] of h.instances) if (!seen.has(path)) { dispose(c); h.instances.delete(path); }
  for (const effect of h.effects) effect();
  return h.tree;
}
async function settle(Component, props = {}) { for (let i = 0; i < 10; i++) { render(Component, props); await new Promise(setImmediate); if (!h.dirty) return h.tree; } throw Error('render did not settle'); }
let failed = 0, count = 0;
async function test(name, fn) { count++; try { await fn(); console.log('✓ ' + name); } catch (e) { failed++; console.error('✗ ' + name + ': ' + e.message); } }
for (const lang of ['ru', 'ba']) {
  const sat = lang === 'ru' ? 'Села в машину' : 'Машинаға ултырҙым';
  const done = lang === 'ru' ? 'Поездка завершена' : 'Сәфәр тамамланды';
  const hint = lang === 'ru' ? 'Назови его водителю при посадке' : 'Ултырғанда йөрөтөүсегә әйт';
  const inTransit = lang === 'ru' ? 'В пути' : 'Юлда';
  await test(`${lang}: confirmed passenger can board without contacts or a shared link`, async () => {
    reset({ lang }); await settle(Active); assert.ok(button(sat)); assert.ok(text(h.tree).includes(hint));
    await button(sat).props.onClick(); await settle(Active); assert.deepEqual(h.sent, [[42, 'sat']]); assert.ok(!h.requests.includes('create-share'));
    assert.ok(!text(h.tree).includes(hint)); assert.ok(!text(h.tree).includes('4931')); assert.ok(text(h.tree).includes(inTransit)); assert.ok(button(done));
  });
  await test(`${lang}: boarding independent of missing family-sharing endpoint`, async () => {
    reset({ lang, sharesError: 404 }); await settle(Active); assert.ok(button(sat));
    await button(sat).props.onClick(); await settle(Active); assert.deepEqual(h.sent, [[42, 'sat']]);
  });
  await test(`${lang}: reloaded onboard passenger has no boarding code, arriving/GPS badge or sat action`, async () => {
    reset({ lang, status: 'onboard' }); await settle(Active); assert.ok(!text(h.tree).includes(hint)); assert.ok(!text(h.tree).includes('4931'));
    assert.ok(text(h.tree).includes(inTransit)); assert.ok(!text(h.tree).includes('GPS')); assert.ok(!button(sat)); assert.ok(button(done)); assert.ok(!h.requests.includes('code'));
  });
  await test(`${lang}: confirmed driver has neither passenger code nor own-phone call`, async () => {
    reset({ lang, role: 'driver' }); await settle(Active); assert.ok(!text(h.tree).includes(hint)); assert.ok(!text(h.tree).includes('4931'));
    assert.ok(!all(h.tree).some(n => n.type === 'a' && n.props.href === 'tel:+79990000000')); assert.ok(!h.requests.includes('code')); assert.ok(!button(sat));
  });
  await test(`${lang}: onboard takes precedence over stale arriving phase`, async () => {
    reset({ lang }); await settle(h.components.StatusPill, { status: 'onboard', phase: 'arriving', arrivalVerified: true }); assert.equal(text(h.tree), inTransit);
  });
  await test(`${lang}: confirmed approaching badge and verified GPS remain available`, async () => {
    reset({ lang }); await settle(h.components.StatusPill, { status: 'confirmed', phase: 'arriving', arrivalVerified: true }); assert.ok(text(h.tree).includes('GPS')); assert.notEqual(text(h.tree), inTransit);
  });
  await test(`${lang}: driver receipt excludes passenger payment, thanks, tip request`, async () => {
    reset({ lang, role: 'driver', status: 'done' }); await settle(Receipt);
    assert.ok(!all(h.tree).some(n => ['PayTripCard', 'SbpPay'].includes(n.type))); assert.ok(!button(lang === 'ru' ? 'Сказать рәхмәт' : 'Рәхмәт әйтеү')); assert.ok(!h.requests.includes('tip'));
    assert.ok(text(h.tree).includes('Уфа')); assert.ok(text(h.tree).includes('300'));
  });
  await test(`${lang}: unpaid passenger retains payment, thanks and enabled tips`, async () => {
    reset({ lang, status: 'done' }); await settle(Receipt);
    assert.ok(all(h.tree).some(n => n.type === 'PayTripCard')); assert.ok(all(h.tree).some(n => n.type === 'SbpPay'));
    const thanks = button(lang === 'ru' ? 'Сказать рәхмәт' : 'Рәхмәт әйтеү'); assert.ok(thanks); await thanks.props.onClick(); await settle(Receipt); assert.deepEqual(h.thanks, [42]);
  });
}
await test('server rejection does not invent boarding or enqueue a server-side denial', async () => {
  reset(); h.shares = [{ id: 1, contact_id: 1 }]; h.sendError = 409; await settle(Active);
  await button('Села в машину').props.onClick(); await settle(Active); assert.equal(h.trip.status, 'confirmed'); assert.ok(button('Села в машину')); assert.deepEqual(h.queued, []);
});
await test('transport failure preserves explicit offline queue without inventing onboard', async () => {
  reset(); h.shares = [{ id: 1, contact_id: 1 }]; h.sendError = 0; await settle(Active);
  await button('Села в машину').props.onClick(); await settle(Active); assert.equal(h.trip.status, 'confirmed'); assert.deepEqual(h.queued, [[42, 'trip_status', 'sat']]);
});
await test('successful early sat remains confirmed when the server does not transition', async () => {
  reset(); h.shares = [{ id: 1, contact_id: 1 }]; h.allowOnboard = false; await settle(Active);
  await button('Села в машину').props.onClick(); await settle(Active); assert.ok(button('Села в машину')); assert.ok(text(h.tree).includes('4931')); assert.equal(h.trip.status, 'confirmed');
});
await test('done without a shared link reaches the completed booking UI', async () => {
  reset({ status: 'onboard' }); await settle(Active); assert.ok(button('Поездка завершена'));
  await button('Поездка завершена').props.onClick(); await settle(Active); assert.deepEqual(h.sent, [[42, 'done']]); assert.equal(h.trip.status, 'done'); assert.ok(!button('Поездка завершена'));
});
await test('paid passenger receipt does not offer paying again', async () => {
  reset({ status: 'done' }); h.receipt.paid = true; await settle(Receipt); assert.ok(!all(h.tree).some(n => n.type === 'PayTripCard'));
});

// Held network responses exercise the original component effect/cleanup. The pass
// boundary stores exactly the snapshot/bookingId sent by production saveTripPass.
function holdBoardingCode(bookingId) {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  const pending = { promise, resolve, bookingId };
  h.codeHolds.set(bookingId, pending);
  return pending;
}
async function replyBoardingCode(pending, code) {
  assert.ok(h.codeRequests.includes(pending.bookingId), 'The held request must actually have started');
  pending.resolve({ code });
  await new Promise(setImmediate);
  await new Promise(setImmediate);
}
const Empty = () => null;
await test('held boarding reply after confirmed→onboard cannot update current UI or save a stale pass code', async () => {
  reset(); const pending = holdBoardingCode(42); await settle(Active);
  assert.deepEqual(h.codeRequests, [42]); assert.equal(h.passes.get(42)?.boardingCode, '');
  await button('Села в машину').props.onClick(); await settle(Active);
  assert.equal(h.trip.status, 'onboard'); assert.ok(text(h.tree).includes('В пути'));
  assert.ok(!text(h.tree).includes('Назови его водителю при посадке'));
  const writesBeforeReply = h.passWrites.length, stateCallsBeforeReply = h.stateCalls.length;
  await replyBoardingCode(pending, '428428'); await settle(Active);
  assert.ok(!text(h.tree).includes('428428'));
  assert.equal(h.passWrites.length, writesBeforeReply, 'A stale reply must not call the pass storage boundary');
  assert.equal(h.stateCalls.length, stateCallsBeforeReply, 'Effect cleanup must ignore the stale reply before setState');
  assert.equal(h.passes.get(42)?.boardingCode, '', 'No boarding code existed before this held response');
});
await test('held boarding reply after unmount cannot call old state setters or pass storage', async () => {
  reset(); const pending = holdBoardingCode(42); await settle(Active);
  assert.deepEqual(h.codeRequests, [42]); assert.ok(h.passes.has(42));
  await settle(Empty); assert.equal(h.tree, null);
  const writesBeforeReply = h.passWrites.length, stateCallsBeforeReply = h.stateCalls.length;
  await replyBoardingCode(pending, '428428'); await settle(Empty);
  assert.equal(h.tree, null);
  assert.equal(h.passWrites.length, writesBeforeReply);
  assert.equal(h.stateCalls.length, stateCallsBeforeReply, 'Disposed code effect must not even attempt a setter');
  assert.equal(h.passes.get(42)?.boardingCode, '');
});
for (const oldReplyFirst of [true, false]) await test(`booking42→43: old reply ${oldReplyFirst ? 'before' : 'after'} current reply preserves UI and each pass owner`, async () => {
  reset(); const oldReply = holdBoardingCode(42); await settle(Active);
  assert.deepEqual(h.codeRequests, [42]); const previousPass = structuredClone(h.passes.get(42));
  assert.equal(previousPass.bookingId, 42); assert.equal(previousPass.boardingCode, '');
  const currentReply = holdBoardingCode(43);
  h.bookingId = 43;
  h.detailsById.set(43, { ...h.details, booking_id: 43, from_city: 'Баймак', to_city: 'Уфа' });
  h.tripById.set(43, { ...h.trip, status: 'confirmed' });
  await settle(Active);
  assert.deepEqual(h.codeRequests, [42, 43]);
  assert.equal(all(h.tree).find(n => n.type === 'SubHeader')?.props.title, 'Баймак → Уфа'); assert.equal(h.passes.get(43)?.bookingId, 43);
  async function rejectOldReply() {
    const writesBeforeReply = h.passWrites.length, stateCallsBeforeReply = h.stateCalls.length;
    await replyBoardingCode(oldReply, '428428'); await settle(Active);
    assert.ok(!text(h.tree).includes('428428'));
    assert.equal(h.passWrites.length, writesBeforeReply);
    assert.equal(h.stateCalls.length, stateCallsBeforeReply);
    assert.deepEqual(h.passes.get(42), previousPass, 'Old response must not rewrite the previous booking pass');
  }
  if (oldReplyFirst) await rejectOldReply();
  await replyBoardingCode(currentReply, '435435'); await settle(Active);
  assert.ok(text(h.tree).includes('435435'), 'The current booking code must remain visible');
  assert.equal(h.passes.get(43)?.boardingCode, '435435', 'The current successful reply belongs in booking43 pass');
  assert.ok(h.passWrites.some(pass => pass.bookingId === 43 && pass.boardingCode === '435435'));
  assert.ok(!h.passWrites.some(pass => pass.bookingId === 42 && pass.boardingCode === '435435'));
  if (!oldReplyFirst) await rejectOldReply();
  assert.ok(text(h.tree).includes('435435'));
  assert.equal(h.passes.get(43)?.boardingCode, '435435');
  assert.ok(!h.passWrites.some(pass => pass.boardingCode === '428428'));
});

// Actual storage/parser and OfflineTripPass, rather than a hand-written copy of
// the code-clearing rule. Only HTTP and localStorage availability are controlled.
const storedPass = id => h.passStorage.loadTripPass(id);
const codeCards = () => all(h.tree).filter(n => n.props?.className === 'code-card');
async function reloadWithoutBookingApi() {
  await settle(Empty); h.detailsError = true; await settle(Active);
  assert.ok(text(h.tree).includes(h.lang === 'ba' ? 'Бәйләнеш юҡ — һаҡланғанды күрһәтәбеҙ' : 'Нет связи — показываем сохранённое'));
}
for (const lang of ['ru', 'ba']) {
  await test(`${lang}: confirmed code and driver details survive an offline reload before boarding`, async () => {
    reset({ lang }); await settle(Active); const before = structuredClone(storedPass(42));
    assert.equal(before.boardingCode, '4931');
    await reloadWithoutBookingApi();
    assert.equal(codeCards().length, 1); assert.ok(text(h.tree).includes('4931'));
    assert.deepEqual(storedPass(42), before);
  });
  await test(`${lang}: already received code is absent from persisted pass and offline UI after boarding`, async () => {
    reset({ lang }); await settle(Active); const before = storedPass(42);
    assert.equal(before.boardingCode, '4931');
    await button(lang === 'ba' ? 'Машинаға ултырҙым' : 'Села в машину').props.onClick(); await settle(Active);
    const after = storedPass(42); assert.equal(after.boardingCode, '');
    for (const key of ['driverName', 'driverPhone', 'fromCity', 'toCity', 'price', 'pickup']) assert.equal(after[key], before[key]);
    await reloadWithoutBookingApi(); assert.equal(codeCards().length, 0); assert.ok(!text(h.tree).includes('4931'));
    assert.ok(text(h.tree).includes(before.driverName));
  });
  await test(`${lang}: onboard role clears used code even when the follow-up details request fails`, async () => {
    reset({ lang }); await settle(Active); assert.equal(storedPass(42).boardingCode, '4931');
    h.trip.status = 'onboard'; h.detailsError = true;
    await h.interval(); await settle(Active);
    assert.equal(storedPass(42).boardingCode, '');
    await reloadWithoutBookingApi(); assert.equal(codeCards().length, 0);
  });
  await test(`${lang}: fresh onboard details sanitize a legacy cached code even if role is unavailable`, async () => {
    reset({ lang }); await settle(Active); assert.equal(storedPass(42).boardingCode, '4931');
    await settle(Empty); h.details.status = 'onboard'; h.tripError = true; await settle(Active);
    assert.equal(storedPass(42).boardingCode, '');
    await reloadWithoutBookingApi(); assert.equal(codeCards().length, 0);
  });
}
await test('onboard received before effect cleanup rejects a held code reply before storage or setters', async () => {
  reset(); const held = holdBoardingCode(42); await settle(Active);
  h.trip.status = 'onboard'; h.detailsError = true;
  await h.interval(); // deliberately do not render: the old code effect is still alive
  const writes = h.passWrites.length, setters = h.stateCalls.length;
  await replyBoardingCode(held, '428428');
  assert.equal(h.passWrites.length, writes); assert.equal(h.stateCalls.length, setters);
  assert.equal(storedPass(42).boardingCode, '');
  await settle(Active); await reloadWithoutBookingApi(); assert.equal(codeCards().length, 0);
});
for (const status of ['done', 'cancelled']) await test(`${status} still removes the entire offline pass`, async () => {
  reset(); await settle(Active); assert.ok(storedPass(42));
  h.trip.status = status; await h.interval(); await settle(Active);
  assert.equal(storedPass(42), null);
  await settle(Empty); h.detailsError = true; await settle(Active); assert.equal(codeCards().length, 0);
});
await test('boarding one booking does not change the other booking passport', async () => {
  reset(); await settle(Active);
  h.passStorage.saveTripPass({ ...storedPass(42), bookingId: 43, boardingCode: '435435' });
  const other = structuredClone(storedPass(43));
  await button('Села в машину').props.onClick(); await settle(Active);
  assert.deepEqual(storedPass(43), other);
});
await test('unwritable storage removes only the used-code snapshot instead of leaving a reusable code', async () => {
  reset(); await settle(Active);
  h.passStorage.saveTripPass({ ...storedPass(42), bookingId: 43, boardingCode: '435435' });
  const other = structuredClone(storedPass(43)); h.failPassWrites = true; h.detailsError = true;
  h.trip.status = 'onboard'; await h.interval(); await settle(Active);
  assert.equal(storedPass(42), null); assert.deepEqual(storedPass(43), other);
});

for (const observed of [
  { status: 'onboard', lang: 'ru' }, { status: 'onboard', lang: 'ba' },
  { status: 'done', lang: 'ru' }, { status: 'cancelled', lang: 'ru' },
]) await test(`${observed.lang}: late confirmed role cannot undo observed ${observed.status} while details fails`, async () => {
  reset({ lang: observed.lang }); await settle(Active);
  const confirmedReply = { ...h.trip }; let release;
  h.tripHolds.push({ promise: new Promise(resolve => { release = resolve; }) });
  const staleTick = h.interval(); assert.deepEqual(h.tripHeldRequests, [42]);
  h.trip.status = observed.status; h.detailsError = true;
  await h.interval(); await settle(Active);
  const before = structuredClone(storedPass(42)), codes = h.codeRequests.length;
  if (observed.status === 'onboard') assert.equal(before.boardingCode, '');
  else assert.equal(before, null);
  release(confirmedReply); await staleTick; await settle(Active);
  assert.equal(h.codeRequests.length, codes, 'A stale role must not start another boarding-code request');
  assert.deepEqual(storedPass(42), before, 'The observed state must survive the late role response');
  assert.equal(codeCards().length, 0);
  assert.ok(!button(observed.lang === 'ba' ? 'Машинаға ултырҙым' : 'Села в машину'));
});

await test('verified B cannot see A cached pass after details fails', async () => {
  reset(); await settle(Active); assert.equal(storedPass(42).ownerId,7); await settle(Empty);
  h.generation='B';h.userId=8;h.detailsError=true;h.tripError=true;await settle(Active);
  assert.ok(!text(h.tree).includes('QA Driver'));assert.equal(codeCards().length,0);
});
await test('verified session cannot promote a legacy unbound cached pass on details failure', async () => {
  reset();await settle(Active);await settle(Empty);const legacy=storedPass(42);delete legacy.ownerId;delete legacy.sessionGeneration;delete legacy.ownershipId;passValues.set('yuldash.tripPass.42',JSON.stringify(legacy));
  h.detailsError=true;h.tripError=true;await settle(Active);assert.ok(!text(h.tree).includes('QA Driver'));assert.equal(codeCards().length,0);
});
console.log(`rideshare local regression: ${count - failed}/${count} passed`);
delete globalThis.__rideshareLocal;
if (previousLocalStorage) Object.defineProperty(globalThis, 'localStorage', previousLocalStorage);
else delete globalThis.localStorage;
if (failed) process.exitCode = 1;
