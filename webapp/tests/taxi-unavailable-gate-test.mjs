import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';

// Execute the unchanged production gate body; replace only hook/view boundaries.
const source=readFileSync(new URL('../src/App.tsx',import.meta.url),'utf8').replace(/\r\n/g,'\n');
const gate=source.match(/function TaxiGuestGate[\s\S]*?\n}\n/)?.[0];
assert.ok(gate,'production TaxiGuestGate must be located');
const FEATURES_OFF={rides:true,taxi:false,courier:false,parcels:false,ads:false,business:false};
const h=globalThis.__taxiUnavailable={status:'unavailable',features:{...FEATURES_OFF,ready:true},ba:false};
const boundary=`
import {isTaxiOpen} from './src/featureRules.ts';
const useFeatures=()=>globalThis.__taxiUnavailable.features;
const useAuth=()=>({status:globalThis.__taxiUnavailable.status});
const useLang=()=>({lang:globalThis.__taxiUnavailable.ba?'ba':'ru',appText:(ru,ba)=>globalThis.__taxiUnavailable.ba?ba:ru});
const useNavigate=()=>()=>{};
const LoadingList='loading';
const taxiGateView=({ru})=>({type:'public-soon',props:{ru}});
${gate}
export default TaxiGuestGate;`;
const result=await build({stdin:{contents:boundary,resolveDir:fileURLToPath(new URL('../',import.meta.url)),loader:'tsx'},bundle:true,write:false,platform:'node',format:'esm',jsx:'automatic',plugins:[{name:'jsx-boundary',setup(b){b.onResolve({filter:/^react\/jsx-runtime$/},()=>({path:'jsx',namespace:'mock'}));b.onLoad({filter:/.*/,namespace:'mock'},()=>({contents:`export const Fragment='fragment';export const jsx=(type,props)=>({type,props}),jsxs=jsx;`}));}}]});
const {default:Gate}=await import('data:text/javascript;base64,'+Buffer.from(result.outputFiles[0].text).toString('base64'));
let count=0,failures=0;
function check(label,fn){count++;try{fn();}catch(e){failures++;console.error(label+': '+e.message);}}
for(const ba of [false,true]){
  h.ba=ba;
  for(const status of ['unavailable','guest']){
    h.status=status;h.features={...FEATURES_OFF,ready:true};
    check(`${ba}/${status}: closed taxi is public Soon`,()=>{const value=Gate({children:'AUTH-GUARDED-ORDER'});assert.equal(value.type,'public-soon');assert.equal(value.props.ru,!ba);});
    h.features={...FEATURES_OFF,ready:false};
    check(`${ba}/${status}: unresolved flags wait`,()=>assert.equal(Gate({children:'AUTH-GUARDED-ORDER'}).type,'loading'));
  }
  for(const status of ['authed','loading','unavailable','guest']){
    h.status=status;h.features={...FEATURES_OFF,taxi:true,ready:true};
    check(`${ba}/${status}: ON retains original auth/order gate`,()=>assert.equal(Gate({children:'AUTH-GUARDED-ORDER'}).props.children,'AUTH-GUARDED-ORDER'));
  }
  h.status='authed';h.features={...FEATURES_OFF,ready:true};
  check(`${ba}: verified owner with OFF can restore old order`,()=>assert.equal(Gate({children:'AUTH-GUARDED-ORDER'}).props.children,'AUTH-GUARDED-ORDER'));
}
console.log(`${count} cases, ${failures} failures`);process.exitCode=failures?1:0;
