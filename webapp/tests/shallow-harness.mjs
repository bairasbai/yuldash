// ================================================================
//  Общий стенд для наборов паритета (шаг 3–4, 17.10.2026): исполняет НАСТОЯЩИЙ компонент
//  сайта, а всё, что он импортирует, по умолчанию заменяет заглушками.
//
//  • Компонент собирается тем же esbuild, что и сайт. Каждый его импорт перехвачен:
//    имя с большой буквы (компонент) становится строкой — в дереве видно, ЧТО нарисовано
//    и с какими свойствами; имя с маленькой (функция) — шпионом, который запоминает вызовы.
//  • Нужное поведение (ответ сервера, язык, флаги) набор отдаёт сам через `mocks`.
//  • Хуки React — свои, на один корневой компонент: useState / useEffect / useRef / useMemo.
//    Вложенные компоненты не исполняются (поверхностная отрисовка) — их свойства видны в дереве.
//
//  Это не набор: в run.mjs его нет, его импортируют наборы.
// ================================================================
import { readFileSync } from "node:fs";
import { build } from "esbuild";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";

export const ROOT = fileURLToPath(new URL("../", import.meta.url));

const REACT = `const h=()=>globalThis.__sh;
const same=(a,b)=>!!a&&!!b&&a.length===b.length&&a.every((v,i)=>Object.is(v,b[i]));
export function useState(v){const s=h(),i=s.i++;if(!(i in s.values))s.values[i]=typeof v==="function"?v():v;
  return [s.values[i],x=>{if(!s.mounted)return;const old=s.values[i];s.values[i]=typeof x==="function"?x(old):x;if(!Object.is(old,s.values[i]))s.dirty=true;}];}
export function useRef(v){const s=h(),i=s.i++;return s.values[i]??={current:v};}
export function useMemo(fn,deps){const s=h(),i=s.i++;const old=s.values[i];if(!old||!deps||!same(old.deps,deps))s.values[i]={deps,value:fn()};return s.values[i].value;}
export const useCallback=(fn,deps)=>useMemo(()=>fn,deps);
export function useEffect(fn,deps){const s=h(),i=s.i++;const old=s.values[i];if(!old||!deps||!same(old.deps,deps)){const slot={deps,cleanup:old?.cleanup};s.values[i]=slot;s.effects.push(()=>{slot.cleanup?.();const c=fn();slot.cleanup=typeof c==="function"?c:undefined;});}}
export const useLayoutEffect=useEffect;
export const createContext=v=>({value:v});
export const useContext=c=>c.value;
export const Fragment="fragment";
export default {useState,useRef,useMemo,useCallback,useEffect,useLayoutEffect,createContext,useContext,Fragment};`;
const JSX = `export const Fragment="fragment";export const jsx=(type,props,key)=>({type,props:props??{},key});export const jsxs=jsx;`;

/** Импорты файла: [{path, def, named:[...]}] — только значения, без `import type`. */
function importsOf(file) {
  const src = readFileSync(file, "utf8");
  const out = [];
  for (const m of src.matchAll(/^import\s+(?!type\s)([\s\S]*?)\s+from\s+"([^"]+)";/gm)) {
    const [, clause, path] = m;
    const def = clause.split("{")[0].trim().replace(/,$/, "").trim() || null;
    const named = (clause.match(/\{([\s\S]*?)\}/)?.[1] ?? "")
      .split(",")
      .map((s) => s.trim())
      .filter((s) => s && !s.startsWith("type "))
      .map((s) => s.split(/\s+as\s+/)[0].trim());
    out.push({ path, def, named });
  }
  return out;
}

function stubFor({ def, named }) {
  const lines = [];
  const spy = (name) => `(...a)=>{(globalThis.__sh.calls[${JSON.stringify(name)}]??=[]).push(a);return undefined;}`;
  if (def) lines.push(`export default ${/^[A-Z]/.test(def) ? JSON.stringify(def) : spy(def)};`);
  for (const n of named) lines.push(`export const ${n}=${/^[A-Z]/.test(n) ? JSON.stringify(n) : spy(n)};`);
  return lines.join("\n");
}

/**
 * Собрать компонент. `mocks`: { "<конец пути импорта>": "код модуля" } — заменяет заглушку.
 * `real`: концы путей, которые собрать по-настоящему (чистая логика без браузера).
 * `transform(src)` — подмена исходника (контроль «старый код»/мутанты).
 * `define` — подстановки esbuild (например, `{"import.meta.env": "{}"}` для экрана с версией сборки).
 */
export async function bundleComponent(rel, { mocks = {}, real = [], exportName = "default", define } = {}) {
  const entry = resolve(ROOT, rel);
  const own = importsOf(entry);
  const pick = (p) => Object.keys(mocks).find((k) => p === k || p.endsWith(k));
  const result = await build({
    entryPoints: [entry],
    bundle: true,
    write: false,
    format: "esm",
    platform: "node",
    jsx: "automatic",
    logLevel: "silent",
    ...(define ? { define } : {}),
    plugins: [
      {
        name: "shallow",
        setup(b) {
          b.onResolve({ filter: /.*/ }, (a) => {
            if (a.path === "react" || a.path === "react/jsx-runtime") return { path: a.path, namespace: "sh" };
            if (a.importer !== entry) {
              const k = pick(a.path);
              return k ? { path: "mock:" + k, namespace: "sh" } : undefined;
            }
            if (real.some((r) => a.path.endsWith(r))) return undefined;
            const k = pick(a.path);
            if (k) return { path: "mock:" + k, namespace: "sh" };
            return { path: "stub:" + a.path, namespace: "sh" };
          });
          b.onLoad({ filter: /.*/, namespace: "sh" }, (a) => {
            if (a.path === "react") return { contents: REACT, loader: "js" };
            if (a.path === "react/jsx-runtime") return { contents: JSX, loader: "js" };
            if (a.path.startsWith("mock:")) return { contents: mocks[a.path.slice(5)], loader: "js" };
            const p = a.path.slice(5);
            return { contents: stubFor(own.find((x) => x.path === p) ?? { def: null, named: [] }), loader: "js" };
          });
        },
      },
    ],
  });
  const mod = await import("data:text/javascript;base64," + Buffer.from(result.outputFiles[0].text).toString("base64"));
  return mod[exportName];
}

/** Новый стенд для одного корневого компонента. */
export function mount(Component, props = {}) {
  const s = (globalThis.__sh = { values: [], i: 0, effects: [], dirty: true, mounted: true, calls: {}, tree: null });
  const harness = {
    state: s,
    props,
    render() {
      s.i = 0;
      s.dirty = false;
      s.effects = [];
      s.tree = Component(harness.props);
      const fx = s.effects;
      s.effects = [];
      fx.forEach((f) => f());
      return s.tree;
    },
    async settle(rounds = 30) {
      for (let n = 0; n < rounds; n++) {
        if (s.dirty) harness.render();
        await new Promise((r) => setImmediate(r));
      }
      if (s.dirty) harness.render();
      return s.tree;
    },
    unmount() {
      s.mounted = false;
      for (const v of s.values) v?.cleanup?.();
    },
    get tree() {
      return s.tree;
    },
    calls: (name) => s.calls[name] ?? [],
  };
  return harness;
}

/** Все узлы дерева. */
export const all = (n) =>
  !n || typeof n !== "object" ? [] : Array.isArray(n) ? n.flatMap(all) : [n, ...all(n.props?.children)];
/** Видимый текст узла (только дети, без свойств). */
export const text = (n) =>
  n == null || typeof n === "boolean"
    ? ""
    : typeof n !== "object"
      ? String(n)
      : Array.isArray(n)
        ? n.map(text).join("")
        : text(n.props?.children);
/** Кнопка с таким текстом (включение подстроки). */
export const button = (tree, label) => all(tree).find((n) => n.type === "button" && text(n).includes(label));
/** Узлы данного типа (строка-заглушка или тег). */
export const ofType = (tree, type) => all(tree).filter((n) => n.type === type);

/** Язык: appText падает на одноязычной строке — две половины обязательны. */
export const langMock = (lang = "ru") =>
  `export const useLang=()=>({lang:${JSON.stringify(lang)},t:k=>k,appText:(ru,ba)=>{if(!ru||!ba||!String(ba).trim())throw new Error("одноязычная строка: "+ru);return ${JSON.stringify(lang)}==="ba"?ba:ru;}});`;

/** Счётчик проверок в стиле остальных наборов. */
export function checker() {
  let bad = 0;
  return {
    async test(name, fn) {
      try {
        await fn();
        console.log("✓ " + name);
      } catch (e) {
        bad++;
        console.log("✗ " + name + "\n    " + String(e?.message ?? e).split("\n").slice(0, 6).join("\n    "));
      }
    },
    done() {
      console.log(bad === 0 ? "\nВСЁ СОШЛОСЬ" : `\nПРОВАЛЕНО: ${bad}`);
      process.exit(bad === 0 ? 0 : 1);
    },
  };
}
