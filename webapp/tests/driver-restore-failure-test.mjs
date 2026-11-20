// Exact production load callback execution with controlled effects, not a React mount.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { test as nodeTest, after } from 'node:test';
let passed = 0;
function test(name, callback) {
 return nodeTest(name, async (...args) => { await callback(...args); passed += 1; });
}
after(() => console.log(`TF05 recovery: ${passed} passed`));
import { canRestoreDriverOrder, clearStoredDriverOrder } from '../src/utils/driverActiveOrder.js';
const path = process.env.TF05_SCREEN || new URL('../src/screens/InstantDriverTripScreen.tsx', import.meta.url);
const source = readFileSync(path, 'utf8');
const begin = source.indexOf('  const load = useCallback(');
const end = source.indexOf('\n\n  useEffect(() => {', begin);
assert.ok(begin >= 0 && end > begin);
const callback = source.slice(begin, end).replace('(signal?: AbortSignal)', '(signal)');
function deferred() { let resolve, reject; const promise = new Promise((a,b) => {resolve=a;reject=b;}); return {promise,resolve,reject}; }
async function flush() { for(let i=0;i<20;i++) await Promise.resolve(); }
class ApiError extends Error { constructor(status,code) {super('synthetic');this.status=status;this.code=code;} }
function harness() {
 const order=deferred(), values=new Map([['active','17']]);
 const h={order,values,generation:'A',boot:[],active:[],driver:[],calls:[]};
 const ctx={ApiError, ACTIVE_KEY:'active', loadEpochRef:{current:0},activeIdRef:{current:null},
  getSessionGeneration:()=>h.generation,useCallback:fn=>fn,
  setBoot:x=>h.boot.push(x),setDriver:x=>h.driver.push(x),setCurrentActive:x=>h.active.push(x),
  fetchTaxiApplication:async()=>({status:'approved'}),fetchDriverStatus:async()=>({online:true}),
  fetchInstantOrder:(id,signal)=>{h.calls.push({id,signal});return h.order.promise;},
  localStorage:{getItem:k=>values.get(k)??null,removeItem:k=>values.delete(k)},
  canRestoreDriverOrder,clearStoredDriverOrder};
 h.ctx=ctx;h.load=runInNewContext(callback+'\nload;',ctx);return h;
}
for(const error of [new TypeError('offline'),new Error('timeout'),... [0,401,403,404,408,409,429,500,502,503].map(s=>new ApiError(s))]) {
 test(`failed read retains recovery id (${error.status??error.message})`,async()=>{
  const h=harness();h.load();await flush();h.order.reject(error);await flush();
  assert.equal(h.values.get('active'),'17');assert.equal(h.boot.at(-1),'error');assert.equal(h.active.length,0);
 });
}
for(const status of ['accepted','arriving','onboard']) test(`restores ${status}`,async()=>{
 const h=harness();h.load();await flush();h.order.resolve({id:17,role:'driver',status});await flush();
 assert.equal(h.active[0]?.status,status);assert.equal(h.values.get('active'),'17');assert.equal(h.boot.at(-1),'ready');
});
for(const status of ['done','cancelled','expired','searching','offered','scheduled','created']) test(`confirmed nonactive ${status} clears only saved id`,async()=>{
 const h=harness();h.load();await flush();h.order.resolve({id:17,role:'driver',status});await flush();
 assert.equal(h.values.has('active'),false);assert.equal(h.active.length,0);assert.equal(h.boot.at(-1),'ready');
});
for(const value of [null,{id:18,role:'driver',status:'accepted'},{id:17,role:'passenger',status:'accepted'},{id:17,role:'driver',status:'future-status'}]) test(`unconfirmed payload retained: ${JSON.stringify(value)}`,async()=>{
 const h=harness();h.load();await flush();h.order.resolve(value);await flush();
 assert.equal(h.values.get('active'),'17');assert.equal(h.active.length,0);assert.equal(h.boot.at(-1),'error');
});
for(const action of ['abort','account','unmount','new-id']) test(`late response cannot publish after ${action}`,async()=>{
 const h=harness(),ac=new AbortController();h.load(ac.signal);await flush();
 const count=h.boot.length;
 if(action==='abort')ac.abort();
 if(action==='account')h.generation='B';
 if(action==='unmount')h.ctx.loadEpochRef.current++;
 if(action==='new-id')h.values.set('active','18');
 h.order.resolve({id:17,role:'driver',status:'accepted'});
 await flush();assert.equal(h.active.length,0);
 if(action==='new-id')assert.equal(h.boot.at(-1),'error');
 else assert.equal(h.boot.length,count);
});
test('old load result loses to newer load',async()=>{
 const h=harness(),first=h.order;h.load();await flush();h.order=deferred();h.load();await flush();
 first.resolve({id:17,role:'driver',status:'accepted'});await flush();assert.equal(h.active.length,0);
 h.order.resolve({id:17,role:'driver',status:'onboard'});await flush();assert.equal(h.active[0]?.status,'onboard');
});
test('retry performs GET and recovers retained trip',async()=>{
 const h=harness();h.load();await flush();h.order.reject(new TypeError('offline'));await flush();
 h.order=deferred();h.load();await flush();h.order.resolve({id:17,role:'driver',status:'onboard'});await flush();
 assert.equal(h.calls.length,2);assert.equal(h.active[0]?.status,'onboard');assert.equal(h.values.get('active'),'17');
});
test('restore stays loading and passes abort signal; offers require ready',async()=>{
 const h=harness(),ac=new AbortController();h.load(ac.signal);await flush();
 assert.equal(h.boot.at(-1),'loading');assert.equal(h.calls[0].signal,ac.signal);
 const effect=source.slice(source.indexOf('// ---------------- Опрос оффера'),source.indexOf('// ---------------- Спрос рядом'));
 assert.match(effect,/if \(boot !== "ready"\) return;/);assert.match(effect,/\[boot, online, active\]/);
 assert.match(source,/\+\+loadEpochRef.current;\s*ac.abort\(\)/);
});

test('live replacement ID can retry a fresh authoritative read',async()=>{
 const h=harness();h.load();await flush();h.values.set('active','18');
 h.order.resolve({id:17,role:'driver',status:'accepted'});await flush();
 assert.equal(h.boot.at(-1),'error');assert.equal(h.values.get('active'),'18');assert.equal(h.active.length,0);
 h.order=deferred();h.load();await flush();assert.equal(h.calls.at(-1).id,18);
 h.order.resolve({id:18,role:'driver',status:'onboard'});await flush();
 assert.equal(h.active[0]?.id,18);assert.equal(h.boot.at(-1),'ready');
});

for (const [status,code] of [[404,'instant_order_not_found'],[403,'instant_order_access_denied']]) {
 test(`authoritative GET ${code} releases only captured recovery`,async()=>{
  const h=harness();h.load();await flush();h.order.reject(new ApiError(status,code));await flush();
  assert.equal(h.values.has('active'),false);assert.equal(h.boot.at(-1),'ready');assert.equal(h.active.length,0);
 });
 for(const action of ['abort','account','unmount','new-id','active-id']) test(`authoritative ${code} cannot clear after ${action}`,async()=>{
  const h=harness(),ac=new AbortController();h.load(ac.signal);await flush();
  if(action==='abort')ac.abort();if(action==='account')h.generation='B';if(action==='unmount')h.ctx.loadEpochRef.current++;
  if(action==='new-id')h.values.set('active','18');if(action==='active-id')h.ctx.activeIdRef.current=18;
  h.order.reject(new ApiError(status,code));await flush();
  assert.equal(h.values.get('active'),action==='new-id'?'18':'17');assert.equal(h.active.length,0);assert.notEqual(h.boot.at(-1),'ready');
 });
}
for(const [status,code] of [[403,'instant_order_not_found'],[404,'instant_order_access_denied'],[500,'instant_order_not_found'],[404,'other']]) test(`nonmatching code/status retained ${status}/${code}`,async()=>{
 const h=harness();h.load();await flush();h.order.reject(new ApiError(status,code));await flush();
 assert.equal(h.values.get('active'),'17');assert.equal(h.boot.at(-1),'error');
});
