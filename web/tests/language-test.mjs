import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { test } from "node:test";
import vm from "node:vm";
import ts from "typescript";

const require = createRequire(import.meta.url);

// Execute the actual provider with controlled React hooks and a minimal document.
// No browser, network, analytics provider or additional test dependency is needed.
function providerHarness(initial) {
  let state = initial;
  let effects = [];
  const events = [];
  const document = { documentElement: { lang: "ru" } };
  const compiledModule = { exports: {} };
  const source = readFileSync(new URL("../components/lang.tsx", import.meta.url), "utf8");
  const compiled = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  }).outputText;
  vm.runInNewContext(compiled, {
    module: compiledModule,
    exports: compiledModule.exports,
    document,
    require: (name) => {
      if (name === "react") return {
        ...require("react"),
        useState: () => [state, (next) => { state = next; }],
        useEffect: (effect) => { effects.push(effect); },
      };
      if (name === "./analytics") return { track: (event) => events.push(event) };
      return require(name);
    },
  });
  return {
    document,
    events,
    render() {
      effects = [];
      const tree = compiledModule.exports.LangProvider({ initial, children: null });
      for (const effect of effects) effect();
      return tree.props.value;
    },
  };
}

test("BA initial route updates document language after hydration", () => {
  const app = providerHarness("ba");
  assert.equal(app.render().lang, "ba");
  assert.equal(app.document.documentElement.lang, "ba");
});

test("RU/BA toggles keep the document language in sync without changing analytics", () => {
  const app = providerHarness("ru");
  app.render().setLang("ba");
  assert.equal(app.render().lang, "ba");
  assert.equal(app.document.documentElement.lang, "ba");
  app.render().setLang("ru");
  assert.equal(app.render().lang, "ru");
  assert.equal(app.document.documentElement.lang, "ru");
  assert.deepEqual(app.events, ["lang_ba", "lang_ru"]);
});
