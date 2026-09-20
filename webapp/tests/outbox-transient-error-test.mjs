import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { IDBFactory } from 'fake-indexeddb';
import { fileURLToPath } from 'node:url';
import { storedActions } from './outbox-helpers.mjs';
const {outputFiles}=await build({stdin:{contents:"export * from './src/api/client';export * from './src/utils/outbox';",resolveDir:fileURLToPath(new URL('../',import.meta.url)),loader:'ts'},bundle:true,write:false,format:'esm',platform:'node',define:{'import.meta.env':'{}'}});
let serial=0,passed=0,failed=0;
async function check(name,fn){
 globalThis.indexedDB=new IDBFactory();const storage=new Map();globalThis.localStorage={getItem:k=>storage.get(k)??null,setItem:(k,v)=>storage.set(k,String(v)),removeItem:k=>storage.delete(k)};
 const api=await import('data:text/javascript;base64,'+Buffer.from(outputFiles[0].text+'\n//'+serial++).toString('base64'));api.setSession('A','refresh-A');
 try{await fn(api);passed++;console.log('✓ '+name);}catch(e){failed++;console.log('FAIL '+name+': '+e.message);}
}
for(const status of [408,425,429,500,502,503,504])await check('HTTP '+status+' keeps queue and retries in order with same key',async api=>{
 await api.enqueue(42,'message','first');await api.enqueue(42,'message','second');const ids=(await storedActions()).map(a=>a.id),calls=[];
 globalThis.fetch=async(u,i)=>{calls.push({key:i.headers['Idempotency-Key'],text:JSON.parse(i.body).text});return new Response('{}',{status});};
 assert.equal(await api.flushOutbox(),false);assert.deepEqual((await storedActions()).map(a=>a.id),ids);assert.equal(calls.length,1);
 globalThis.fetch=async(u,i)=>{calls.push({key:i.headers['Idempotency-Key'],text:JSON.parse(i.body).text});return new Response('{}');};
 assert.equal(await api.flushOutbox(),true);assert.deepEqual(calls.map(c=>c.text),['first','first','second']);assert.equal(calls[0].key,calls[1].key);assert.equal((await storedActions()).length,0);
});
await check('malformed 200 response retains action and retry identity',async api=>{
 await api.enqueue(42,'message','accepted but response broken');const keys=[];
 globalThis.fetch=async(u,i)=>{keys.push(i.headers['Idempotency-Key']);return new Response('{broken');};
 assert.equal(await api.flushOutbox(),false);assert.equal((await storedActions()).length,1);
 globalThis.fetch=async(u,i)=>{keys.push(i.headers['Idempotency-Key']);return new Response('{}');};await api.flushOutbox();assert.equal(keys.length,2);assert.equal(keys[0],keys[1]);assert.equal((await storedActions()).length,0);
});
for(const status of [400,403,404,409,410,422])await check('confirmed permanent HTTP '+status+' does not block following action',async api=>{
 await api.enqueue(42,'message','rejected');await api.enqueue(42,'message','valid');let calls=0;
 globalThis.fetch=async()=>new Response('{}',{status:++calls===1?status:200});await api.flushOutbox();assert.equal(calls,2);assert.equal((await storedActions()).length,0);
});
console.log(`Retry failures: ${passed} passed, ${failed} failed`);if(failed)process.exitCode=1;
