// ================================================================
//  Крошечный React для тестов экранов без браузера (как в ride-booking-path-test, но общий):
//  компонент рисуется на один уровень, а состояние, мемо и эффекты — настоящие. Вложенный
//  компонент рисуют отдельно: mount(элемент.type, элемент.props). Плюс подмена модулей по пути
//  импорта (остальное собирает настоящий esbuild) и поддельные window/document для шторок.
//
//  Не набор: run.mjs его не запускает — им пользуются nearby-feed-tails-test и sheet-exit-test.
// ================================================================
import assert from "node:assert/strict";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";

export const root = fileURLToPath(new URL("../", import.meta.url));

// ---------------------------------------------------------------- заглушка React

export const rt = (globalThis.__rt = { cur: null });

export const REACT = `
const R = globalThis.__rt;
const same = (a, b) => !!a && !!b && a.length === b.length && a.every((x, i) => Object.is(x, b[i]));
const slot = () => { const c = R.cur; const i = c.i++; return (c.hooks[i] ??= {}); };
export function useState(init) {
  const c = R.cur, s = slot();
  if (!("v" in s)) s.v = typeof init === "function" ? init() : init;
  s.set ??= (v) => { const nv = typeof v === "function" ? v(s.v) : v; if (!Object.is(nv, s.v)) { s.v = nv; c.dirty = true; } };
  return [s.v, s.set];
}
export function useRef(v) { const s = slot(); if (!("current" in s)) s.current = v; return s; }
export function useMemo(fn, deps) { const s = slot(); if (!("v" in s) || !same(s.deps, deps)) { s.v = fn(); s.deps = deps; } return s.v; }
export function useCallback(fn, deps) { return useMemo(() => fn, deps); }
export function useEffect(fn, deps) {
  const c = R.cur, s = slot();
  if (s.ran && deps && same(s.deps, deps)) return;
  c.pending.push(() => { s.cleanup?.(); s.cleanup = fn(); s.deps = deps; s.ran = true; });
}
export const useLayoutEffect = useEffect;
let ids = 0;
export function useId() { const s = slot(); return (s.id ??= ":r" + ids++ + ":"); }
export default { useState, useRef, useMemo, useCallback, useEffect, useLayoutEffect, useId };
`;
export const JSX = `export const Fragment = "Fragment"; export function jsx(type, props, key) { return { type, props: props ?? {}, key }; } export const jsxs = jsx;`;
export const LANG = `export const useLang = () => ({ lang: "ru", appText: (ru) => ru, t: (k) => k });`;
export const PORTAL = `export function createPortal(node) { return node; }`;

/** Подмена модулей по пути импорта; остальное собирается настоящим. */
export function stubs(list) {
  return {
    name: "stubs",
    setup(b) {
      b.onResolve({ filter: /.*/ }, (a) => {
        const i = list.findIndex(([re]) => re.test(a.path));
        return i >= 0 ? { path: String(i), namespace: "stub" } : null;
      });
      b.onLoad({ filter: /.*/, namespace: "stub" }, (a) => ({ contents: list[Number(a.path)][1], loader: "js" }));
    },
  };
}

export async function load(entry, list) {
  const out = await build({
    ...(entry.endsWith(".tsx") ? { entryPoints: [root + entry] } : { stdin: { contents: entry, resolveDir: root, loader: "ts" } }),
    bundle: true,
    write: false,
    format: "esm",
    platform: "node",
    jsx: "automatic",
    define: { "import.meta.env": "{}" },
    plugins: [stubs(list)],
  });
  return import("data:text/javascript;base64," + Buffer.from(out.outputFiles[0].text).toString("base64"));
}

/** Один компонент со своими хуками: рисуем, пока состояние не перестанет меняться, потом эффекты. */
export function mount(fn, props) {
  const c = { hooks: [], i: 0, pending: [], dirty: false, props, tree: null };
  c.render = (p) => {
    if (p !== undefined) c.props = p;
    for (let n = 0; n < 40; n++) {
      rt.cur = c;
      c.i = 0;
      c.pending = [];
      c.dirty = false;
      try {
        c.tree = fn(c.props);
      } finally {
        rt.cur = null;
      }
      for (const run of c.pending) run();
      if (!c.dirty) return c.tree;
    }
    throw new Error("перерисовка не останавливается");
  };
  c.unmount = () => c.hooks.forEach((s) => s?.cleanup?.());
  c.render();
  return c;
}

/** Дождаться ответов «сети» и перерисовать всё, что поменялось. */
export async function settle(...parts) {
  for (let k = 0; k < 12; k++) {
    await new Promise((r) => setImmediate(r));
    for (const c of parts) if (c.dirty) c.render();
  }
}

export const flat = (node) =>
  node == null || typeof node !== "object" ? [] : Array.isArray(node) ? node.flatMap(flat) : [node, ...flat(node.props?.children)];
export const named = (type, name) => type === name || (typeof type === "function" && new RegExp(`^${name}\\d*$`).test(type.name));
export const all = (tree, name) => flat(tree).filter((n) => named(n.type, name));
export const one = (tree, name) => {
  const found = all(tree, name);
  assert.equal(found.length, 1, `${name}: ждали один, нашли ${found.length}`);
  return found[0];
};
export const text = (node) =>
  typeof node === "string" || typeof node === "number"
    ? String(node)
    : Array.isArray(node)
      ? node.map(text).join("")
      : node && typeof node === "object"
        ? text(node.props?.children)
        : "";

let bad = 0;
/** Одна проверка: ✓ или ✗ с причиной; итог — finish(). */
export async function test(name, run) {
  try {
    await run();
    console.log("✓ " + name);
  } catch (e) {
    bad++;
    console.log("✗ " + name + "\n    " + (e?.message ?? e));
  }
}

// ---------------------------------------------------------------- браузер

export class FakeEl {
  constructor(name) {
    this.name = name;
    this.focused = 0;
  }
  focus() {
    this.focused++;
    globalThis.document.activeElement = this;
  }
}
/** Поддельный браузер: `reduced` — «Уменьшить движение», `timers` — отложенные window.setTimeout. */
export const browser = { reduced: false, timers: [] };
globalThis.HTMLElement = FakeEl;
globalThis.document = { activeElement: null, body: "body" };
globalThis.window = {
  matchMedia: (q) => ({ matches: browser.reduced && q.includes("prefers-reduced-motion: reduce") }),
  setTimeout: (fn, ms) => browser.timers.push({ fn, ms, on: true }) - 1,
  clearTimeout: (id) => {
    if (browser.timers[id]) browser.timers[id].on = false;
  },
  getComputedStyle: () => ({ animationDuration: "0.26s" }),
};
globalThis.localStorage = { getItem: () => null, setItem() {}, removeItem() {} };

/** Итог набора для run.mjs: ВСЁ СОШЛОСЬ / ПРОВАЛЕНО и код выхода. */
export function finish() {
  console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
  process.exit(bad === 0 ? 0 : 1);
}
