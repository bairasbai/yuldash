// Execute all three actual deferred setPass updater bodies from the production
// screen with its captured owner and real TripPass storage, not a React renderer.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { build } from 'esbuild';
const root = fileURLToPath(new URL('../', import.meta.url));
const source = readFileSync(root + 'src/screens/ActiveTripScreen.tsx', 'utf8');
assert.match(source, /const sessionOwner = useRef\(getSessionGeneration\(\)\)\.current/);
const tree = ts.createSourceFile('screen.tsx', source, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
const bodies = [];
function visit(node) {
  if (ts.isCallExpression(node) && node.expression.getText(tree) === 'setPass' &&
      node.arguments[0] && ts.isArrowFunction(node.arguments[0]) && node.arguments[0].getText(tree).includes('saveTripPass')) bodies.push(node.arguments[0].getText(tree));
  ts.forEachChild(node, visit);
}
visit(tree);assert.equal(bodies.length, 3);
let serial = 0;
for (const body of bodies) {
  const store = new Map();globalThis.localStorage={getItem:k=>store.get(k)??null,setItem:(k,v)=>store.set(k,String(v)),removeItem:k=>store.delete(k)};
  const apiBuild = await build({ stdin:{contents:`export * from './src/api/client';export * from './src/utils/tripPass';`,resolveDir:root,loader:'ts'},bundle:true,write:false,format:'esm',platform:'node',define:{'import.meta.env':'{}'} });
  const api = await import('data:text/javascript;base64,' + Buffer.from(apiBuild.outputFiles[0].text + `\n//fixture ${serial++}`).toString('base64'));
  api.setSession('A','refresh-A');const ownerA=api.getSessionGeneration();
  globalThis.__passFixture={save:api.saveTripPass,owner:ownerA};
  const fixture = `const {save:saveTripPass,owner:sessionOwner}=globalThis.__passFixture;
    const bookingId=42,d={driver_phone:'A-phone',status:'confirmed'},r={code:'A-code'},agreement={pay_amount:100};
    export const update=${body};`;
  const compiled=await build({stdin:{contents:fixture,loader:'ts'},write:false,format:'esm',platform:'node'});
  const {update}=await import('data:text/javascript;base64,'+Buffer.from(compiled.outputFiles[0].text + `\n//updater ${serial}`).toString('base64'));
  api.setSession('B','refresh-B');api.saveTripPass({bookingId:42,driverPhone:'B-phone',boardingCode:'B-code'});
  const before=[...store.entries()];
  update({bookingId:42,driverPhone:'A-phone',boardingCode:'A-code'});
  assert.equal(api.loadTripPass(42).driverPhone,'B-phone');assert.equal(api.loadTripPass(42).boardingCode,'B-code');
  assert.deepEqual([...store.entries()],before,'Deferred A updater wrote after B login');
}
console.log('ActiveTrip captured-owner updaters: 3 passed, 0 failed');
