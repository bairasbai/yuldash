import assert from 'node:assert/strict';
import {build} from 'esbuild';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
const h=globalThis.__offlineMeasure={online:false,back:false,banner:null,state:0,layout:null,observer:null};
Object.defineProperty(globalThis,'navigator',{value:{get onLine(){return h.online;}},configurable:true});
globalThis.ResizeObserver=class {constructor(cb){this.cb=cb;this.disconnected=false;h.observer=this;}observe(el){this.observed=el;}disconnect(){this.disconnected=true;}};
const mocks={
  'react':`export const useEffect=()=>{};export const useLayoutEffect=f=>{globalThis.__offlineMeasure.layout=f;};export const useRef=()=>({current:globalThis.__offlineMeasure.banner});export const useState=f=>{const h=globalThis.__offlineMeasure;return [h.state++===0?(typeof f==='function'?f():f):h.back,()=>{}];};`,
  'react/jsx-runtime':`export const jsx=(type,props)=>({type,props}),jsxs=jsx;`,
  lang:`export const useLang=()=>({appText:(ru)=>ru});`,
};
const result=await build({entryPoints:[fileURLToPath(new URL('../src/components/OfflineBanner.tsx',import.meta.url))],bundle:true,write:false,platform:'node',format:'esm',jsx:'automatic',loader:{'.css':'empty'},plugins:[{name:'hook-boundaries',setup(b){b.onResolve({filter:/.*/},a=>{const key=a.path.endsWith('/i18n/lang')?'lang':a.path;return key in mocks?{path:key,namespace:'fixture'}:null;});b.onLoad({filter:/.*/,namespace:'fixture'},a=>({contents:mocks[a.path]}));b.onLoad({filter:/OfflineBanner\.tsx$/},()=>process.env.BANNER_BASELINE_SOURCE?{contents:readFileSync(process.env.BANNER_BASELINE_SOURCE,'utf8'),loader:'tsx'}:undefined);}}]});
const {default:Banner}=await import('data:text/javascript;base64,'+Buffer.from(result.outputFiles[0].text).toString('base64'));
let count=0,failures=0;
function mount({online=false,back=false,height=33,present=true,parent=true}={}){
  const values=new Map();const shell={style:{setProperty:(k,v)=>values.set(k,v),removeProperty:k=>values.delete(k)}};
  h.online=online;h.back=back;h.state=0;h.layout=null;h.observer=null;
  h.banner=present?{offsetHeight:height,parentElement:parent?shell:null}:null;
  const tree=Banner();const cleanup=h.layout?.();return {values,tree,cleanup};
}
function check(label,fn){count++;try{fn();}catch(e){failures++;console.error(label+': '+e.message);}}
check('offline publishes actual height',()=>assert.equal(mount({height:49}).values.get('--offline-banner-height'),'49px'));
check('observer watches only its own banner',()=>{mount();assert.equal(h.observer?.observed,h.banner);});
check('wrapped translation/font resize updates height',()=>{const v=mount();h.banner.offsetHeight=81;h.observer?.cb();assert.equal(v.values.get('--offline-banner-height'),'81px');});
check('unmount disconnects observer and removes offset',()=>{const v=mount();const observer=h.observer;assert.equal(typeof v.cleanup,'function');v.cleanup();assert.equal(observer.disconnected,true);assert.equal(v.values.size,0);});
check('hidden online banner reserves no space',()=>{const v=mount({online:true,present:false});assert.equal(v.tree,null);assert.equal(v.values.size,0);assert.equal(h.observer,null);});
check('connection confirmation reserves and releases its height',()=>{const v=mount({online:true,back:true,height:35});assert.equal(v.values.get('--offline-banner-height'),'35px');v.cleanup();assert.equal(v.values.size,0);});
check('detached banner creates no observer',()=>{const v=mount({parent:false});assert.equal(v.values.size,0);assert.equal(h.observer,null);});
console.log(`${count} cases, ${failures} failures`);process.exitCode=failures?1:0;
