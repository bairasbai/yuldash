import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { IDBFactory } from 'fake-indexeddb';
import { fileURLToPath } from 'node:url';
import { storedActions } from './outbox-helpers.mjs';
const {outputFiles}=await build({stdin:{contents:"export * from './src/api/client';export * from './src/utils/outbox';",resolveDir:fileURLToPath(new URL('../',import.meta.url)),loader:'ts'},bundle:true,write:false,format:'esm',platform:'node',define:{'import.meta.env':'{}'}});
let serial=0,passed=0;
async function check(name,fn){
 globalThis.indexedDB=new IDBFactory();const store=new Map();globalThis.localStorage={getItem:k=>store.get(k)??null,setItem:(k,v)=>store.set(k,String(v)),removeItem:k=>store.delete(k)};
 const load=()=>import('data:text/javascript;base64,'+Buffer.from(outputFiles[0].text+'\n//'+serial++).toString('base64'));
 const api=await load();api.setSession('A','refresh-A');
 const base={id:1234,session:api.getSessionGeneration(),kind:'message',createdAt:1234,bookingId:42};
 const first={...base,payload:'first'},second={...base,payload:'second'};
 const save=rows=>store.set('yuldash.outbox',JSON.stringify(rows));
 const calls=[];globalThis.fetch=async(u,i)=>{calls.push({key:i.headers['Idempotency-Key'],body:JSON.parse(i.body)});return new Response('{}');};
 await fn({api,load,save,first,second,calls});console.log('✓ '+name);passed++;
}
await check('distinct messages in different bookings survive the same numeric id',async({api,save,first,second})=>{
 save([first,{...second,bookingId:43}]);await api.refreshOutbox();assert.equal((await storedActions()).length,2);
});
await check('same-booking collisions have distinct stable delivery keys; acknowledgement prevents re-import',async({api,load,save,first,second,calls})=>{
 save([first,second,first]);await api.refreshOutbox();const before=await storedActions();assert.equal(before.length,2);assert.equal(new Set(before.map(a=>String(a.id))).size,2);
 const reloaded=await load();await reloaded.refreshOutbox();assert.deepEqual((await storedActions()).map(a=>a.id),before.map(a=>a.id));
 await reloaded.flushOutbox();await api.refreshOutbox();await api.flushOutbox();assert.equal(calls.length,2);assert.equal(new Set(calls.map(c=>c.key)).size,2);assert.equal((await storedActions()).length,0);
});
await check('new collision arriving after an acknowledgement is imported once',async({api,load,save,first,second,calls})=>{
 save([first]);await api.flushOutbox();save([second,first]);await (await load()).flushOutbox();await api.flushOutbox();assert.equal(calls.length,2);assert.equal(new Set(calls.map(c=>c.key)).size,2);assert.equal((await storedActions()).length,0);
});
await check('two independent importers retain both messages exactly once',async({api,load,save,first,second})=>{
 save([first,second]);const other=await load();await Promise.all([api.refreshOutbox(),other.refreshOutbox()]);assert.equal((await storedActions()).length,2);
});
await check('ambiguous old boolean receipt never resurrects previously acknowledged actions',async({api,save,first,second,calls})=>{
 await api.refreshOutbox();const db=await new Promise((resolve,reject)=>{const r=indexedDB.open('yuldash-outbox-v1',1);r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error);});
 await new Promise((resolve,reject)=>{const tx=db.transaction('imported','readwrite');tx.objectStore('imported').put(true,JSON.stringify([first.session,first.id]));tx.oncomplete=resolve;tx.onabort=()=>reject(tx.error);});db.close();
 save([first,second]);await api.flushOutbox();assert.equal(calls.length,0);assert.equal((await storedActions()).length,0);
});
await check('lost delivery response reuses the migrated key',async({api,save,first,second})=>{
 save([first,second]);const accepted=new Set(),keys=[];let failed=false;
 globalThis.fetch=async(u,i)=>{const key=i.headers['Idempotency-Key'];keys.push(key);accepted.add(key);if(!failed){failed=true;throw new TypeError('lost response');}return new Response('{}');};
 await api.flushOutbox();await api.flushOutbox();assert.equal(accepted.size,2);assert.equal(keys.length,3);assert.equal(keys[0],keys[1]);assert.equal((await storedActions()).length,0);
});

await check('interleaved colliding records retain their original delivery order',async({api,save,first,second,calls})=>{
 save([first,{...first,id:4321,payload:'middle'},second]);await api.flushOutbox();assert.deepEqual(calls.map(c=>c.body.text),['first','middle','second']);
});

console.log(`Legacy migration: ${passed} passed`);
