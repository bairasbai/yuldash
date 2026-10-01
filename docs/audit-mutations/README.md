# Нарочные поломки (мутации)

Тест чего-то стоит, только если он падает, когда код ломается. Поэтому каждая отметка 🟩 опирается
на записанную «нарочную поломку»: берём точный кусок исходника, портим его и проверяем, что тест
это заметил. Файл описания — один на лист: `docs/audit-mutations/<лист>.json`.

```json
{
  "schema": 1,
  "leaf": "leaf-1.1",
  "mutations": [
    {
      "id": "M1",
      "file": "backend/app/ledger.py",
      "rule": "R1",
      "description": "проводка комиссии пишется с обратным знаком",
      "find": "amount=fee",
      "replace": "amount=-fee",
      "runner": "pytest",
      "cwd": "backend",
      "args": ["tests/walk/l1_1/test_l1_1_ledger.py::test_fee_entry_sign"],
      "db": "sqlite"
    },
    {
      "id": "M2",
      "file": "android/app/src/main/java/com/yuldash/app/WalletScreen.kt",
      "rule": "R2",
      "description": "кнопка повтора не вызывает загрузку",
      "find": "onRetry = { reload() }",
      "replace": "onRetry = { }",
      "runner": "gradle",
      "tests": ["com.yuldash.app.walk.l1_4.WalletScreenStatesTest"]
    }
  ]
}
```

Правила:

- `find` должен встречаться в файле **ровно один раз** (переводы строк CRLF/LF учитываются сами).
- Поломка засчитывается, только если тест **упал как тест**. Ошибка компиляции, синтаксиса или сбора
  тестов поймой не считается — это поломка, которую ловит любой компилятор.
- До поломки тот же тест обязан проходить, иначе доказательства нет.
- `db: "postgres"` — тест гоняется в свежей базе на изолированном кластере (`AUDIT_PG_BASE`).

Проверка: `python tools/audit_mutation.py validate --spec docs/audit-mutations/leaf-1.1.json`.
Прогон: `python tools/audit_mutation.py replay --spec docs/audit-mutations/leaf-1.1.json`
(выборочно — `--sample 3`, конкретные — `--only M1,M2`). Исходник всегда восстанавливается побайтно.
