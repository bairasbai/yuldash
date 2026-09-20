import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { IDBFactory, IDBObjectStore } from 'fake-indexeddb';
import { fileURLToPath } from 'node:url';
import { storedActions, until } from './outbox-helpers.mjs';
const {outputFiles}=await build({stdin:{contents:"export * from './src/api/client'; export * from './src/utils/outbox';",resolveDir:fileURLToPath(new URL('../',import.meta.url)),loader:'ts'},bundle:true,write:false,format:'esm',platform:'node',define:{'import.meta.env':'{}'}});
let serial=0,failed=0,passed=0;
async function environment(){
 globalThis.indexedDB=new IDBFactory();const storage=new Map();
 globalThis.localStorage={getItem:k=>storage.get(k)??null,setItem:(k,v)=>storage.set(k,String(v)),removeItem:k=>storage.delete(k)};
 globalThis.window={addEventListener(){},removeEventListener(){}};
 const load=()=>import('data:text/javascript;base64,'+Buffer.from(outputFiles[0].text+'\n//'+serial++).toString('base64'));
 const api=await load(),calls=[];globalThis.fetch=async(u,i)=>{calls.push(JSON.parse(i.body));return new Response('{}');};api.setSession('A','refresh-A');
 return {api,load,storage,calls};
}
async function check(name,fn){try{await fn(await environment());passed++;console.log('✓ '+name);}catch(e){failed++;console.error('FAIL '+name+': '+e.message);}}
await check('100 concurrent transaction commits persist 100 distinct actions',async({api,load})=>{
 const second=await load();await Promise.all(Array.from({length:100},(_,i)=>(i%2?api:second).enqueue(42,'message',String(i))));
 assert.equal((await storedActions()).length,100);assert.equal(new Set((await storedActions()).map(a=>a.id)).size,100);
 await second.refreshOutbox();assert.equal(second.outboxCount(42),100);
});
await check('legacy migration is idempotent even after acknowledgement and re-import',async({api,load,storage,calls})=>{
 const old={id:42,session:api.getSessionGeneration(),bookingId:7,kind:'message',payload:'legacy',createdAt:123};
 storage.set('yuldash.outbox',JSON.stringify([old,old]));
 await api.refreshOutbox();const second=await load();await second.refreshOutbox();assert.equal((await storedActions()).length,1);
 await api.flushOutbox();await second.flushOutbox();assert.equal(calls.length,1);assert.equal((await storedActions()).length,0);
});
await check('malformed and ownerless legacy records are not imported',async({api,storage})=>{
 storage.set('yuldash.outbox',JSON.stringify([{id:1,bookingId:1,kind:'message',payload:'unknown',createdAt:1},{id:2,session:api.getSessionGeneration(),bookingId:1,kind:'message',createdAt:1}]));
 await api.refreshOutbox();assert.equal((await storedActions()).length,0);
});
await check('quota exception rejects saving and does not claim a persisted message',async({api})=>{
 const add=IDBObjectStore.prototype.add;IDBObjectStore.prototype.add=function(){throw new DOMException('quota fixture','QuotaExceededError');};
 try{await assert.rejects(api.enqueue(42,'message','not saved'));}finally{IDBObjectStore.prototype.add=add;}
 assert.equal((await storedActions()).length,0);assert.equal(api.hasPending(),false);
 await api.enqueue(42,'message','retry');assert.equal((await storedActions()).length,1);
});
await check('transaction abort after add rejects the save and rolls back the action',async({api})=>{
 const add=IDBObjectStore.prototype.add;IDBObjectStore.prototype.add=function(...args){const request=add.apply(this,args);this.transaction.abort();return request;};
 try{await assert.rejects(api.enqueue(42,'message','aborted'));}finally{IDBObjectStore.prototype.add=add;}
 assert.equal((await storedActions()).length,0);
});
await check('account change before opening the database prevents stale insertion',async({api})=>{
 const pending=api.enqueue(42,'message','old A');api.setSession('B','refresh-B');await assert.rejects(pending);
 await api.enqueue(99,'message','own B');assert.deepEqual((await storedActions()).map(a=>a.payload),['own B']);
});
await check('delayed cleanup cannot remove the new account actions',async({api})=>{
 await api.enqueue(42,'message','old A');const cleanup=api.clearOutbox();api.setSession('B','refresh-B');
 await api.enqueue(99,'message','own B');await cleanup;
 assert.deepEqual((await storedActions()).map(a=>a.payload),['own B']);
});
await check('startup watcher drains persistent data without waiting for UI snapshot',async({api,load,calls})=>{
 await api.enqueue(42,'message','saved');const reloaded=await load();assert.equal(reloaded.hasPending(),false);
 let notified=false;const stop=reloaded.watchOutbox(()=>{notified=true;});await until(()=>notified);stop();assert.equal(calls.length,1);assert.equal((await storedActions()).length,0);
});
await check('failed cross-tab signal cannot turn a committed save into a retry',async({api})=>{
 const old=globalThis.BroadcastChannel;
 globalThis.window.BroadcastChannel=class {};
 globalThis.BroadcastChannel=class {constructor(){throw new Error('channel blocked');}};
 try {await api.enqueue(42,'message','committed');assert.equal((await storedActions()).length,1);}
 finally {globalThis.BroadcastChannel=old;}
});await check('postMessage failure preserves successful commit',async({api})=>{
 const old=globalThis.BroadcastChannel;globalThis.window.BroadcastChannel=class {};
 globalThis.BroadcastChannel=class {postMessage(){throw new Error('signal failed');}};
 try {await api.enqueue(42,'message','committed');assert.equal((await storedActions()).length,1);}
 finally {globalThis.BroadcastChannel=old;}
});console.log(`IndexedDB queue cases: ${passed} passed, ${failed} failed`);if(failed)process.exitCode=1;
