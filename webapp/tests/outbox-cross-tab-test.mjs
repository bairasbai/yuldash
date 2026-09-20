import { until } from './outbox-helpers.mjs';
import { IDBFactory } from 'fake-indexeddb';
globalThis.indexedDB = new IDBFactory();
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
const {outputFiles}=await build({stdin:{contents:"export * from './src/api/client'; export * from './src/utils/outbox';",resolveDir:fileURLToPath(new URL('../',import.meta.url)),loader:'ts'},bundle:true,write:false,format:'esm',platform:'node',define:{'import.meta.env':'{}'}});
const store=new Map();globalThis.localStorage={getItem:k=>store.get(k)??null,setItem:(k,v)=>store.set(k,String(v)),removeItem:k=>store.delete(k)};
const locks=new Map();
Object.defineProperty(globalThis,'navigator',{configurable:true,value:{locks:{request(name,callback){const previous=locks.get(name)??Promise.resolve();const next=previous.then(callback);locks.set(name,next.catch(()=>{}));return next;}}}});
const load=tag=>import('data:text/javascript;base64,'+Buffer.from(outputFiles[0].text+'\n//'+tag).toString('base64'));
const a=await load('tab-a'),b=await load('tab-b');
let release;const gate=new Promise(r=>release=r),calls=[];
globalThis.fetch=async(url,init)=>{calls.push({auth:init.headers.Authorization,body:JSON.parse(init.body)});await gate;return new Response('{}');};
a.setSession('A','refresh-A');await a.enqueue(42,'message','one message');
const first=a.flushOutbox(),second=b.flushOutbox();
await until(() => calls.length === 1);
try { assert.equal(calls.length,1,'Tabs sent the same action concurrently'); }
finally { release();await Promise.all([first,second]); }
assert.equal(calls.length,1);assert.equal(a.hasPending(),false);
console.log('✓ two independent modules share one send lock and deliver one queued action once');
