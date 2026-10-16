import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
const h=globalThis.__updateBanner={states:[],effects:[],si:0,calls:[]};
const mocks={
 react:`const h=globalThis.__updateBanner;export function useState(v){const i=h.si++;if(!(i in h.states))h.states[i]=v;return[h.states[i],v=>h.states[i]=typeof v==='function'?v(h.states[i]):v];}export function useEffect(fn){h.effects.push(fn);}`,
 'react/jsx-runtime':`export const jsx=(type,props)=>({type,props}),jsxs=jsx;`,
 'virtual:pwa-register':`export function registerSW(options){globalThis.__updateBanner.options=options;return reload=>globalThis.__updateBanner.calls.push(reload);}`,
 lang:`export const useLang=()=>({appText:(ru,ba)=>globalThis.__updateBanner.lang==='ba'?ba:ru});`,
 icons:`export const IconCheck=()=>null;`,
 vite:`export const defineConfig=x=>x;`,
 '@vitejs/plugin-react':`export default ()=>({name:'react'});`,
 'vite-plugin-pwa':`export const VitePWA=options=>({name:'pwa',options});`,
};
async function load(entry){const r=await build({entryPoints:[root+entry],bundle:true,write:false,format:'esm',platform:'node',jsx:'automatic',plugins:[{name:'boundary',setup(b){b.onResolve({filter:/.*/},a=>{const k=Object.hasOwn(mocks,a.path)?a.path:a.path.endsWith('/i18n/lang')?'lang':a.path.endsWith('/Icons')?'icons':null;return k?{path:k,namespace:'mock'}:null;});b.onLoad({filter:/.*/,namespace:'mock'},a=>({contents:mocks[a.path],loader:'js'}));}}]});return import('data:text/javascript;base64,'+Buffer.from(r.outputFiles[0].text).toString('base64'));}
const config=(await load('vite.config.ts')).default;
const Banner=(await load('src/components/UpdateBanner.tsx')).default;
const render=()=>{h.si=0;return Banner();};
function mount(lang='ru'){Object.assign(h,{states:[],effects:[],calls:[],lang});const tree=render();for(const fn of h.effects.splice(0))fn();return tree;}
function nodes(tree,predicate){if(!tree||typeof tree!=='object')return [];return [...(predicate(tree)?[tree]:[]),...[tree.props?.children].flat(Infinity).flatMap(n=>nodes(n,predicate))];}
let count=0,failed=0;async function test(name,fn){try{await fn();count++;console.log('PASS '+name);}catch(e){failed++;console.error('FAIL '+name+': '+e.message);}}
await test('downloaded update waits for explicit consent in real VitePWA options',()=>{const o=config.plugins.find(p=>p.name==='pwa').options;assert.equal(o.registerType,'prompt');assert.notEqual(o.workbox.skipWaiting,true);assert.notEqual(o.workbox.clientsClaim,true);});
await test('update cache contains no private runtime routes',()=>{assert.deepEqual(config.plugins.find(p=>p.name==='pwa').options.workbox.runtimeCaching,[]);});
await test('no banner and no apply before worker reports waiting',()=>{assert.equal(mount(),null);assert.equal(render(),null);assert.equal(h.options.immediate,true);assert.deepEqual(h.calls,[]);});
await test('Russian waiting banner exposes both choices without applying',()=>{mount();h.options.onNeedRefresh();const buttons=nodes(render(),n=>n.type==='button');assert.deepEqual(buttons.map(n=>n.props.children),['Позже','Обновить']);assert.deepEqual(h.calls,[]);});
await test('Later hides banner without applying the waiting worker',()=>{mount();h.options.onNeedRefresh();nodes(render(),n=>n.type==='button')[0].props.onClick();assert.equal(render(),null);assert.deepEqual(h.calls,[]);});
await test('Update requests worker activation explicitly',()=>{mount();h.options.onNeedRefresh();nodes(render(),n=>n.type==='button')[1].props.onClick();assert.deepEqual(h.calls,[true]);});
await test('Bashkir waiting banner keeps the same choices',()=>{mount('ba');h.options.onNeedRefresh();assert.deepEqual(nodes(render(),n=>n.type==='button').map(n=>n.props.children),['Һуңыраҡ','Яңыртыу']);assert.deepEqual(h.calls,[]);});
console.log(`Update banner: ${count} passed, ${failed} failed`);if(failed)process.exitCode=1;
