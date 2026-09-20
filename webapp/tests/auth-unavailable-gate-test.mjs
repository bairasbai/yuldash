import assert from 'node:assert/strict';
import {build} from 'esbuild';
import {fileURLToPath} from 'node:url';
const h=globalThis.__authGate={status:'unavailable',retried:0};
const mocks={
 'react/jsx-runtime':`export const Fragment='fragment';export const jsx=(type,props)=>({type,props}),jsxs=jsx;`,
 'react-router-dom':`export const Navigate='navigate',useLocation=()=>({pathname:'/profile'});`,
 auth:`export const useAuth=()=>({status:globalThis.__authGate.status,retrySession:()=>globalThis.__authGate.retried++});`,
 lang:`export const useLang=()=>({appText:(ru,ba)=>globalThis.__authGate.ba?ba:ru});`
};
const b=await build({entryPoints:[fileURLToPath(new URL('../src/components/RequireAuth.tsx',import.meta.url))],bundle:true,write:false,format:'esm',platform:'node',jsx:'automatic',plugins:[{name:'boundary',setup(b){b.onResolve({filter:/.*/},a=>{const k=a.path.endsWith('/auth/AuthProvider')?'auth':a.path.endsWith('/i18n/lang')?'lang':a.path;return k in mocks?{path:k,namespace:'mock'}:null;});b.onLoad({filter:/.*/,namespace:'mock'},a=>({contents:mocks[a.path]}));}}]});
const {default:Gate}=await import('data:text/javascript;base64,'+Buffer.from(b.outputFiles[0].text).toString('base64'));
for(const ba of [false,true]){h.ba=ba;const tree=Gate({children:'PRIVATE'});assert.equal(tree.type,'div');assert.ok(!JSON.stringify(tree).includes('PRIVATE'));const button=tree.props.children.find(c=>c.type==='button');assert.equal(button.props.children,ba?'Ҡабатлау':'Повторить');button.props.onClick();}assert.equal(h.retried,2);
h.status='guest';assert.equal(Gate({children:'PRIVATE'}).type,'navigate');h.status='authed';assert.equal(Gate({children:'PRIVATE'}).props.children,'PRIVATE');
console.log('Auth gate: 4 passed (RU/BA unavailable, guest, authed)');
