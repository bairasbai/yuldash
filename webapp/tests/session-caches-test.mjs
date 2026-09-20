import assert from 'node:assert/strict';
import { build } from 'esbuild';
const {outputFiles}=await build({entryPoints:[new URL('../src/utils/sessionCaches.ts',import.meta.url).pathname.replace(/^\/(\w:)/,'$1')],bundle:true,write:false,format:'esm',define:{'import.meta.env.BASE_URL':'"/"'}});
const {clearSessionCaches}=await import('data:text/javascript;base64,'+Buffer.from(outputFiles[0].text).toString('base64'));
globalThis.window={location:{origin:'https://qa.test'}};
const name='workbox-precache-v2-https://qa.test/';
const req=p=>new Request(new URL(p,'https://qa.test'));
let count=0;
async function test(label,fn){await fn();count++;console.log('PASS '+label);}
function setup(entries){const deleted=[];const map=new Map(entries.map(r=>[r.url,r]));globalThis.caches={keys:async()=>[name,'legacy-private'],delete:async k=>{deleted.push(k);return true;},open:async()=>({keys:async()=>[...map.values()],delete:async r=>map.delete(r.url)})};return {map,deleted};}
await test('retains offline HTML and build chunks; removes personal runtime cache',async()=>{const s=setup([req('/index.html?__WB_REVISION__=abc'),req('/assets/index-abc.js'),req('/assets/index-abc.css')]);await clearSessionCaches(()=>true);assert.equal(s.map.size,3);assert.deepEqual(s.deleted,['legacy-private']);});
await test('private responses inside precache are removed',async()=>{const s=setup([req('/api/me'),req('/profile'),req('/rides'),req('/feed')]);await clearSessionCaches(()=>true);assert.equal(s.map.size,0);});
await test('foreign origin, unknown query and authenticated static entries are removed',async()=>{const s=setup([req('https://other.test/index.html'),req('/index.html?token=A'),new Request('https://qa.test/assets/a.js',{headers:{Authorization:'Bearer A'}})]);await clearSessionCaches(()=>true);assert.equal(s.map.size,0);});
await test('changed session stops cleanup after enumeration',async()=>{const s=setup([req('/api/me')]);await clearSessionCaches(()=>false);assert.equal(s.map.size,1);assert.equal(s.deleted.length,0);});
await test('changed session during precache enumeration stops deletion',async()=>{let current=true;const s=setup([req('/api/me')]);caches.open=async()=>({keys:async()=>{current=false;return [req('/api/me')];},delete:async()=>assert.fail('late delete')});await clearSessionCaches(()=>current);assert.equal(s.deleted.length,0);});
console.log(`Session caches: ${count} passed`);
