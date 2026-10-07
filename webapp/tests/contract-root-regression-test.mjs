// Запускаем настоящий contract-test в отдельной копии структуры репозитория.
// Проверяем выполненную сверку, отказ без моделей и обнаружение неверного типа.
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const HERE = dirname(fileURLToPath(import.meta.url));
const tempPrefix = resolve(tmpdir(), "yuldash-contract-root-");
const fixtureBase = mkdtempSync(tempPrefix);
const repo = join(fixtureBase, "nested", "workspace", "project");
const tests = join(repo, "webapp", "tests");
const api = join(repo, "webapp", "src", "api");
const models = join(repo, "backend", "app", "models.py");
mkdirSync(tests, { recursive: true });
mkdirSync(api, { recursive: true });
mkdirSync(dirname(models), { recursive: true });
writeFileSync(join(tests, "contract-test.mjs"), readFileSync(join(HERE, "contract-test.mjs")));
writeFileSync(models, Array.from({ length: 21 }, (_, i) =>
  `class Fixture${i}(SQLModel):\n    value: Optional[str] = None\n`
).join("\n"));
const interfaces = Array.from({ length: 7 }, (_, i) =>
  `export interface Fixture${i} {\n  value: string | null;\n}\n`
).join("\n");
writeFileSync(join(api, "fixture.ts"), interfaces);
// Эта часть production-проверки читает код, не выполняет fetch или импорт клиента.
writeFileSync(join(api, "client.ts"), 'typeof detail === "object";\nArray.isArray(detail);\n');

function run(cwd = repo) {
  const result = spawnSync(process.execPath, [join(tests, "contract-test.mjs")], {
    cwd, encoding: "utf8", timeout: 15_000,
  });
  assert.ifError(result.error);
  assert.notEqual(result.status, null, `child interrupted: ${result.signal}`);
  return { status: result.status, output: result.stdout + result.stderr };
}

try {
  const valid = run();
  assert.equal(valid.status, 0, valid.output);
  assert.match(valid.output, /модели сервера прочитаны\s+классов: 21/);
  assert.match(valid.output, /описания сверены с моделями по имени\s+совпало имён: 7/);
  assert.doesNotMatch(valid.output, /пропущена/);
  console.log("✓ настоящая структура repo/webapp/tests находит соседний backend и сверяет модели");

  const unrelatedCwd = run(fixtureBase);
  assert.equal(unrelatedCwd.status, 0, unrelatedCwd.output);
  assert.match(unrelatedCwd.output, /совпало имён: 7/);
  console.log("✓ сверка не зависит от текущей папки запуска");

  writeFileSync(join(api, "fixture.ts"), interfaces.replace("value: string | null;", "value: string;"));
  const mismatch = run();
  assert.equal(mismatch.status, 1, mismatch.output);
  assert.match(mismatch.output, /Fixture0\.value: string/);
  assert.match(mismatch.output, /ПРОВАЛЕНО: 1/);
  console.log("✓ неверный обязательный тип при nullable-модели действительно отклоняется");

  rmSync(models);
  const missing = run();
  assert.equal(missing.status, 1, missing.output);
  assert.match(missing.output, /Не найден ожидаемый файл моделей сервера/);
  assert.doesNotMatch(missing.output, /ВСЁ СОШЛОСЬ|сверка контракта пропущена/);
  console.log("✓ отсутствие ожидаемого backend завершает проверку отказом без ложного успеха");
} finally {
  const target = realpathSync(fixtureBase);
  assert.ok(target.startsWith(tempPrefix) && target !== tempPrefix, "unexpected cleanup target");
  rmSync(target, { recursive: true, force: true });
}
