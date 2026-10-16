// Static regression for the actual CSS cascade: duplicate rules, inherited colour,
// alpha compositing and both theme routes. This is NOT browser computed styles.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import postcss from 'postcss';

const read = path => readFileSync(new URL('../src/' + path, import.meta.url), 'utf8');
const index = postcss.parse(read('index.css'));
const ui = postcss.parse(read('ui.css'));
const knownSelectors = new Set(['.code-card', '.code-card__label', '.code-card__hint', '.code-card__value', '.code-card small', '.code-card__row', '.code-card__copy']);
ui.walkRules(rule => {
  if (rule.selector.includes('.code-card')) for (const selector of rule.selectors) {
    assert.ok(knownSelectors.has(selector), `Review new code-card selector before inferring its cascade: ${selector}`);
  }
});

function mediaApplies(rule, theme) {
  for (let node = rule.parent; node; node = node.parent) {
    if (node.type !== 'atrule') continue;
    assert.equal(node.name, 'media', `Review conditional CSS: @${node.name}`);
    const match = node.params.match(/^\(prefers-color-scheme:\s*(dark|light)\)$/);
    assert.ok(match, `Review media query: ${node.params}`);
    if (theme.system !== match[1]) return false;
  }
  return true;
}
function rootSelectorMatches(selector, theme) {
  if (selector === ':root') return true;
  if (selector === ':root:not([data-theme])') return theme.manual == null;
  if (selector === ':root[data-theme="dark"]') return theme.manual === 'dark';
  if (selector === ':root[data-theme="light"]') return theme.manual === 'light';
  return false;
}
// Only the audited simple selectors above participate; unsupported new selectors fail.
const specificity = selector => selector === ':root' ? 10 : selector.startsWith(':root') ? 20 : selector === '.code-card small' ? 11 : 10;
function cascade(sheet, matches, theme) {
  const winner = new Map(); let order = 0;
  sheet.walkRules(rule => {
    const matching = rule.selectors.filter(matches);
    if (!matching.length || !mediaApplies(rule, theme)) return;
    const spec = Math.max(...matching.map(specificity));
    rule.each(decl => {
      if (decl.type !== 'decl') return;
      const rank = [decl.important ? 1 : 0, spec, order++];
      const old = winner.get(decl.prop)?.rank;
      if (!old || rank[0] > old[0] || (rank[0] === old[0] && (rank[1] > old[1] || (rank[1] === old[1] && rank[2] >= old[2])))) {
        winner.set(decl.prop, { value: decl.value, rank });
      }
    });
  });
  return Object.fromEntries([...winner].map(([key, { value }]) => [key, value]));
}
function resolve(value, variables) {
  assert.ok(value, 'Missing CSS colour declaration');
  for (let n = 0; n < 10 && value.includes('var('); n++) {
    value = value.replace(/var\((--[\w-]+)\)/g, (_, key) => {
      assert.ok(variables[key], `Unknown colour token ${key}`); return variables[key];
    });
  }
  assert.ok(!value.includes('var('), 'Unresolved CSS token cycle');
  return value.trim();
}
function rgb(value) {
  let hex = value.replace(/^#/, '');
  if (hex.length === 3) hex = [...hex].map(c => c + c).join('');
  assert.match(hex, /^[0-9a-f]{6}$/i, `Review unsupported colour ${value}`);
  return [0, 2, 4].map(i => parseInt(hex.slice(i, i + 2), 16));
}
const blend = (fg, bg, alpha) => fg.map((c, i) => c * alpha + bg[i] * (1 - alpha));
function luminance(colour) {
  const linear = colour.map(c => c / 255 <= 0.04045 ? c / 255 / 12.92 : ((c / 255 + 0.055) / 1.055) ** 2.4);
  return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722;
}
function contrast(fg, bg) {
  const a = luminance(fg), b = luminance(bg);
  return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
}
function context(theme) {
  const variables = cascade(index, selector => rootSelectorMatches(selector, theme), theme);
  const style = selector => cascade(ui, value => value === selector, theme);
  const card = style('.code-card');
  const bg = rgb(resolve(card.background ?? card['background-color'], variables));
  const inherited = rgb(resolve(card.color ?? variables['--text'], variables));
  function readContrast(selector, ownBackground) {
    const child = style(selector);
    const fg = child.color ? rgb(resolve(child.color, variables)) : inherited;
    const childBg = ownBackground ? rgb(resolve(child.background ?? child['background-color'], variables)) : bg;
    const opacity = Number(child.opacity ?? 1) * Number(card.opacity ?? 1);
    assert.ok(opacity >= 0 && opacity <= 1, 'Invalid CSS opacity');
    return contrast(blend(fg, childBg, opacity), childBg);
  }
  return { readContrast };
}

let failed = 0, total = 0;
function check(name, action) {
  total++;
  try { action(); console.log('✓ ' + name); }
  catch (e) { failed++; console.log('✗ ' + name + ': ' + e.message); }
}
const themes = [
  { name: 'manual light/system light', manual: 'light', system: 'light' },
  { name: 'manual light/system dark', manual: 'light', system: 'dark' },
  { name: 'manual dark/system light', manual: 'dark', system: 'light' },
  { name: 'manual dark/system dark', manual: 'dark', system: 'dark' },
  { name: 'system light', manual: null, system: 'light' },
  { name: 'system dark', manual: null, system: 'dark' },
];
for (const theme of themes) {
  const css = context(theme);
  for (const [selector, label, ownBackground] of [
    ['.code-card__label', 'online/offline boarding label', false],
    ['.code-card__hint', 'online/offline boarding instruction', false],
    ['.code-card__value', 'boarding/delivery digits override', false],
    ['.code-card small', 'parcel delivery caption override', false],
    ['.code-card__copy', 'parcel delivery copy-button override', true],
  ]) check(`${theme.name}: ${label} >=4.5:1`, () => {
    const ratio = css.readContrast(selector, ownBackground);
    assert.ok(ratio >= 4.5, `${ratio.toFixed(4)}:1`);
  });
}
check('online and offline boarding consumers still use the audited label/hint/value classes', () => {
  for (const file of ['screens/ActiveTripScreen.tsx', 'components/OfflineTripPass.tsx']) {
    const source = read(file);
    for (const cls of ['code-card', 'code-card__label', 'code-card__hint', 'code-card__value']) assert.ok(source.includes(`className="${cls}"`), `${file}: ${cls}`);
  }
});
check('parcel delivery consumer still uses its caption/value/copy overrides', () => {
  const source = read('components/parcelForm.tsx');
  assert.ok(source.includes('<small>{label}</small>'));
  for (const cls of ['code-card', 'code-card__value', 'code-card__copy']) assert.ok(source.includes(`className="${cls}"`), cls);
});
console.log(`boarding code contrast: ${total - failed}/${total} passed (static CSS; browser acceptance separate)`);
if (failed) process.exitCode = 1;
