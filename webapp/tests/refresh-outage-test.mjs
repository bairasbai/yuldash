import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { IDBFactory } from 'fake-indexeddb';
import { fileURLToPath } from 'node:url';
import { storedActions } from './outbox-helpers.mjs';
const {outputFiles}=await build({stdin:{contents:"export * from './src/api/client';export {refreshSession} from './src/api/auth';export * from './src/utils/outbox';",resolveDir:fileURLToPath(new URL('../',import.meta.url)),loader:'ts'},bundle:true,write:false,format:'esm',platform:'node',define:{'import.meta.env':'{}'}});
let serial=0,passed=0,failed=0;
const response=(status,body={})=>new Response(JSON.stringify(body),{status});
async function check(name,fn){
 globalThis.indexedDB=new IDBFactory();const storage=new Map();globalThis.localStorage={getItem:k=>storage.get(k)??null,setItem:(k,v)=>storage.set(k,String(v)),removeItem:k=>storage.delete(k)};
 const api=await import('data:text/javascript;base64,'+Buffer.from(outputFiles[0].text+'\n//'+serial++).toString('base64'));api.setSession('A','refresh-A');api.setRefreshHandler(api.refreshSession);
 let cleared=0;const cleanup=[];api.setUnauthorizedHandler(()=>{cleared++;cleanup.push(api.clearOutbox());});
 try{await fn({api,cleared:()=>cleared,cleanup});passed++;console.log('✓ '+name);}catch(e){failed++;console.log('FAIL '+name+': '+e.message);}
}
for(const failure of [0,400,408,422,429,500,503,'broken','empty'])await check('refresh '+failure+' preserves session and queued message until recovery',async({api,cleared})=>{
 await api.enqueue(42,'message','saved');const generation=api.getSessionGeneration(),id=(await storedActions())[0].id;
 globalThis.fetch=async url=>{if(!String(url).includes('/auth/refresh'))return response(401);if(failure===0)throw new TypeError('offline');if(failure==='broken')return new Response('{');if(failure==='empty')return response(200);return response(failure);};
 assert.equal(await api.flushOutbox(),false);assert.equal(api.getToken(),'A');assert.equal(api.getRefreshToken(),'refresh-A');assert.equal(api.getSessionGeneration(),generation);assert.equal(cleared(),0);assert.equal((await storedActions())[0].id,id);
 const keys=[];globalThis.fetch=async(url,init)=>{if(String(url).includes('/auth/refresh'))return response(200,{access_token:'A-new',refresh_token:'refresh-A-new'});keys.push(init.headers['Idempotency-Key']);return response(init.headers.Authorization==='Bearer A-new'?200:401);};
 await api.flushOutbox();assert.equal(api.getToken(),'A-new');assert.equal(api.getSessionGeneration(),generation);assert.deepEqual(keys,[id,id]);assert.equal((await storedActions()).length,0);
});
await check('definitive refresh rejection still logs out and clears private queue',async({api,cleared,cleanup})=>{
 await api.enqueue(42,'message','private');globalThis.fetch=async()=>response(401);await api.flushOutbox();await Promise.all(cleanup);assert.equal(api.getToken(),null);assert.equal(cleared(),1);assert.equal((await storedActions()).length,0);
});
await check('parallel 401 requests share failed refresh and allow later recovery',async({api,cleared})=>{
 let release;const held=new Promise(r=>release=r);let refreshCalls=0;
 globalThis.fetch=async url=>{if(!String(url).includes('/auth/refresh'))return response(401);refreshCalls++;await held;return response(503);};
 const pending=Promise.allSettled([api.apiGet('/one'),api.apiGet('/two')]);
 for(let i=0;i<30&&refreshCalls===0;i++)await new Promise(setImmediate);
 assert.equal(refreshCalls,1);release();const results=await pending;assert.ok(results.every(r=>r.status==='rejected'&&r.reason.status===0));assert.equal(refreshCalls,1);assert.equal(cleared(),0);
 globalThis.fetch=async(url,init)=>String(url).includes('/auth/refresh')?(refreshCalls++,response(200,{access_token:'A-new',refresh_token:'refresh-A-new'})):response(init.headers.Authorization==='Bearer A-new'?200:401);
 await api.apiGet('/one');assert.equal(refreshCalls,2);assert.equal(api.getToken(),'A-new');
});
await check('late refresh failure cannot alter replacement account',async({api,cleared})=>{
 let release,started;const held=new Promise(r=>release=r),ready=new Promise(r=>started=r);
 globalThis.fetch=async url=>{if(!String(url).includes('/auth/refresh'))return response(401);started();await held;return response(503);};
 const pending=api.apiGet('/private').catch(e=>e);await ready;api.setSession('B','refresh-B');release();await pending;
 assert.equal(api.getToken(),'B');assert.equal(api.getRefreshToken(),'refresh-B');assert.equal(cleared(),0);
});
console.log(`Refresh outage: ${passed} passed, ${failed} failed`);if(failed)process.exitCode=1;
