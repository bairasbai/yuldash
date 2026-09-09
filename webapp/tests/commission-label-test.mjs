import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { transform } from 'esbuild';

const source = await readFile(new URL('../src/utils/commissionLabel.ts', import.meta.url), 'utf8');
const { code } = await transform(source, { loader: 'ts', format: 'esm' });
const { commissionLabel } = await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`);
for (const percent of [0, 3, 8, 15, 17.5]) {
  const label = commissionLabel(percent);
  for (const language of ['ru', 'ba']) assert.ok(label[language].includes(`${percent}%`));
}
for (const percent of [null, undefined, NaN, Infinity, -1]) {
  const label = commissionLabel(percent);
  for (const language of ['ru', 'ba']) assert.ok(!label[language].includes('%'));
}
const screen = await readFile(new URL('../src/screens/InstantDriverTripScreen.tsx', import.meta.url), 'utf8');
assert.match(screen, /commissionLabel\(order\.driver_fee_percent\)\.ru/);
assert.match(screen, /commissionLabel\(order\.driver_fee_percent\)\.ba/);
console.log('PASS: actual order rate in both languages; missing rate does not invent a percentage (22 assertions).');
